package io.sakurammo.listener;

import io.sakurammo.SakuraMMOPlugin;
import io.sakurammo.item.ItemDefinition;
import io.sakurammo.item.ItemManager;
import io.sakurammo.util.Msg;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 职业限制 + 灵魂绑定（v4.2.3）。
 * <p>
 * 两件事：
 * <ul>
 *   <li><b>职业限制</b>：限定职业的物品（{@code required-class}）被其他职业持有时，
 *       攻击与右键全部拦截 —— 拿得起来但用不了。</li>
 *   <li><b>灵魂绑定</b>：{@code soulbound: true} 的物品不可丢弃、死亡不掉（改成复活后归还）。</li>
 *   <li>v4.2.5 起<b>允许存入容器</b>（箱子/末影箱/潜影盒/铁砧）：玩家能收纳，才谈得上好用；
 *       身份仍由 PDC 绑定，其他人拿到也用不了（职业不符）。</li>
 * </ul>
 * 判定只认物品 PDC（{@code sakurammo:item_soulbound} / {@code item_class}），
 * 因此改配置、改 lore、铁砧改名都伪造不出绑定属性。
 */
public final class ClassRestrictionListener implements Listener {

    /** 「职业不符」提示节流：同一玩家 1.5 秒内只提示一次，避免连点刷屏。 */
    private static final long NOTICE_INTERVAL_MS = 1500L;

    private final SakuraMMOPlugin plugin;
    private final Map<UUID, Long> lastNotice = new ConcurrentHashMap<>();
    /** 死亡时被拦下、待复活归还的灵魂绑定物品（同时覆盖「死了没复活就退出」的情况）。 */
    private final Map<UUID, List<ItemStack>> pendingReturn = new ConcurrentHashMap<>();

    public ClassRestrictionListener(SakuraMMOPlugin plugin) {
        this.plugin = plugin;
    }

    // ==================== 职业限制：攻击 ====================

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onAttack(EntityDamageByEntityEvent event) {
        if (event.getCause() != EntityDamageEvent.DamageCause.ENTITY_ATTACK
                && event.getCause() != EntityDamageEvent.DamageCause.ENTITY_SWEEP_ATTACK) {
            return;
        }
        if (!(event.getDamager() instanceof Player player)) {
            return;
        }
        // 只查主手：副手拿着限定物品时仍允许用主手武器正常战斗（否则会误伤）
        ItemStack held = player.getInventory().getItemInMainHand();
        String lockedClass = plugin.items().classLockOf(held);
        if (lockedClass == null) {
            return;
        }
        if (!lockedClass.equalsIgnoreCase(currentClass(player))) {
            event.setCancelled(true);
            noticeClassMismatch(player, held, lockedClass);
        }
    }

    // ==================== 职业限制：右键（放置/使用） ====================

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        // 只处理「空右键」：真正会放置方块的路径交给 BlockPlaceEvent 精确拦截。
        // 早期版本在这里无差别取消 RIGHT_CLICK_BLOCK，结果拿信物时连箱子都打不开（实测踩到）。
        if (event.getAction() != Action.RIGHT_CLICK_AIR) {
            return;
        }
        // 空右键时 getHand() / getItem() 都可能为空：按「事件物品 → 主手 → 副手」回落
        ItemStack item = event.getItem();
        if (item == null || item.getType().isAir()) {
            item = event.getPlayer().getInventory().getItemInMainHand();
        }
        if (item == null || item.getType().isAir()) {
            item = event.getPlayer().getInventory().getItemInOffHand();
        }
        String lockedClass = plugin.items().classLockOf(item);
        if (lockedClass == null) {
            return;
        }
        if (!lockedClass.equalsIgnoreCase(currentClass(event.getPlayer()))) {
            event.setCancelled(true);
            noticeClassMismatch(event.getPlayer(), item, lockedClass);
        }
    }

    /**
     * 放置方块：这里才是「把火把插到地上」的真实入口。
     * <p>
     * 分两种情况——非本职业放置 → 职业不符；本职业放置 → 灵魂绑定不允许摆放
     * （火把放下就被消耗，等于把绑定信物永久销毁）。
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        ItemStack item = event.getItemInHand();
        String lockedClass = plugin.items().classLockOf(item);
        boolean soulbound = plugin.items().isSoulbound(item);
        if (lockedClass == null && !soulbound) {
            return;
        }
        Player player = event.getPlayer();
        if (lockedClass != null && !lockedClass.equalsIgnoreCase(currentClass(player))) {
            event.setCancelled(true);
            noticeClassMismatch(player, item, lockedClass);
            return;
        }
        if (soulbound) {
            event.setCancelled(true);
            notice(player, "soulbind.no-place", "item", displayOf(item));
        }
    }

    // ==================== 灵魂绑定：丢弃 ====================

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        if (plugin.items().isSoulbound(event.getItemDrop().getItemStack())) {
            event.setCancelled(true);
            notice(event.getPlayer(), "soulbind.no-drop",
                    "item", displayOf(event.getItemDrop().getItemStack()));
        }
    }

    // ==================== 灵魂绑定：死亡不掉 ====================

    @EventHandler(priority = EventPriority.HIGH)
    public void onDeath(PlayerDeathEvent event) {
        List<ItemStack> kept = new ArrayList<>();
        event.getDrops().removeIf(item -> {
            if (plugin.items().isSoulbound(item)) {
                kept.add(item);
                return true;
            }
            return false;
        });
        if (!kept.isEmpty()) {
            pendingReturn.put(event.getEntity().getUniqueId(), kept);
            plugin.getLogger().info("灵魂绑定：拦下 " + kept.size() + " 件物品不随 "
                    + event.getEntity().getName() + " 死亡掉落。");
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(PlayerRespawnEvent event) {
        // 延后 1 tick：等服务端把死亡背包清理与位置重置做完
        plugin.getServer().getScheduler().runTask(plugin, () -> restorePending(event.getPlayer()));
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        // 覆盖「死了没复活就退出，再上线」：内存里还留着待归还清单
        plugin.getServer().getScheduler().runTaskLater(plugin,
                () -> restorePending(event.getPlayer()), 20L);
    }

    private void restorePending(Player player) {
        List<ItemStack> kept = pendingReturn.remove(player.getUniqueId());
        if (kept != null) {
            for (ItemStack item : kept) {
                player.getInventory().addItem(item).values()
                        .forEach(left -> player.getWorld().dropItemNaturally(player.getLocation(), left));
            }
            notice(player, "soulbind.returned", "count", String.valueOf(kept.size()));
            return;
        }
        // 兜底：清单因重启丢失时，按职业重新补发新手指引物品（不会复制：身上已有就跳过）
        if (plugin.starter() != null) {
            plugin.starter().grantForClass(player, currentClass(player));
        }
    }

    // ==================== 工具 ====================

    private String currentClass(Player player) {
        var profile = plugin.playerData().getOrLoad(player);
        return profile == null ? null : profile.classId();
    }

    private void noticeClassMismatch(Player player, ItemStack item, String lockedClass) {
        long now = System.currentTimeMillis();
        Long previous = lastNotice.get(player.getUniqueId());
        if (previous != null && now - previous < NOTICE_INTERVAL_MS) {
            return;
        }
        lastNotice.put(player.getUniqueId(), now);
        Msg.send(player, "soulbind.class-mismatch",
                "item", displayOf(item),
                "class", plugin.items().classDisplay(lockedClass));
        player.sendActionBar(io.sakurammo.menu.Items.component(
                Msg.raw("soulbind.class-mismatch-bar", "class", plugin.items().classDisplay(lockedClass))));
    }

    private void notice(Player player, String key, String... kv) {
        long now = System.currentTimeMillis();
        Long previous = lastNotice.get(player.getUniqueId());
        if (previous != null && now - previous < NOTICE_INTERVAL_MS) {
            return;
        }
        lastNotice.put(player.getUniqueId(), now);
        Msg.send(player, key, kv);
    }

    private String displayOf(ItemStack item) {
        String id = plugin.items().idOf(item);
        if (id == null) {
            return "该物品";
        }
        return plugin.items().definition(id).map(ItemDefinition::display).orElse(id);
    }
}
