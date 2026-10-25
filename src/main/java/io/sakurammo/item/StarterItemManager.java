package io.sakurammo.item;

import io.sakurammo.SakuraMMOPlugin;
import io.sakurammo.menu.Items;
import io.sakurammo.util.Msg;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 新手指引物品的发放与重领（v4.2.3）。
 * <p>
 * 触发点有两处：选职业时自动发放、玩家主动 {@code /sm starter} 重领（弄丢后）。
 * 防复制只靠一条判据 —— <b>物品是否还在玩家身上（背包 / 盔甲 / 副手 / 末影箱）</b>：
 * 只要还在就拒绝发放，因此无论重复点多少次命令都只会有 1 把。
 * <p>
 * 玩家的 PDC 上另记一份「领取次数」作为审计记录（用于排查刷取行为），
 * 但它<b>不参与</b>发放判定——否则弄丢一次就永远领不回来（见任务书坑表：重领不能只查背包）。
 */
public final class StarterItemManager {

    /** 玩家 PDC：新手指引物品累计领取次数（审计用）。 */
    public static final NamespacedKey KEY_CLAIM_COUNT = new NamespacedKey("sakurammo", "starter_claims");
    /** 玩家 PDC：最近一次领取时间戳（毫秒）。 */
    public static final NamespacedKey KEY_CLAIM_AT = new NamespacedKey("sakurammo", "starter_claim_at");

    private final SakuraMMOPlugin plugin;

    public StarterItemManager(SakuraMMOPlugin plugin) {
        this.plugin = plugin;
    }

    // ==================== 发放 ====================

    /**
     * 玩家选定职业后调用：给该职业的每件新手物品补发（已在身上的跳过）。
     *
     * @return 实际发放的件数（0 表示本来就都有）
     */
    public int grantForClass(Player player, String classId) {
        if (classId == null) {
            return 0;
        }
        int granted = 0;
        for (String itemId : plugin.items().starterItems(classId)) {
            if (claim(player, itemId, "starter.granted-class")) {
                granted++;
            }
        }
        return granted;
    }

    /**
     * 领取一件新手物品（幂等）。
     *
     * @param messageKey 成功时的提示消息键；null = 不提示
     * @return true = 确实发放了（之前身上没有）；false = 已有 / 不符合条件 / 物品 id 无效
     */
    public boolean claim(Player player, String itemId, String messageKey) {
        ItemDefinition def = plugin.items().definition(itemId).orElse(null);
        if (def == null) {
            plugin.getLogger().warning("新手物品 id 不存在：" + itemId);
            return false;
        }
        // 职业校验：限定职业的物品只发给该职业
        String currentClass = currentClassId(player);
        if (def.hasClassLock() && !def.requiredClass().equalsIgnoreCase(currentClass == null ? "" : currentClass)) {
            if (messageKey != null) {
                Msg.send(player, "starter.class-mismatch",
                        "item", def.display(),
                        "class", plugin.items().classDisplay(def.requiredClass()));
            }
            return false;
        }
        // 已有则拒绝（背包 + 盔甲 + 副手 + 末影箱，全查）
        if (hasAnywhere(player, def.id())) {
            if (messageKey != null) {
                Msg.send(player, "starter.already-owned", "item", def.display());
            }
            return false;
        }

        ItemStack item = plugin.items().create(def.id(), 1);
        if (item == null) {
            return false;
        }
        giveToFirstSlot(player, item);

        recordClaim(player);
        if (messageKey != null) {
            String classDisplay = def.hasClassLock()
                    ? plugin.items().classDisplay(def.requiredClass()) : "";
            Msg.send(player, messageKey, "item", def.display(), "class", classDisplay);
            player.sendActionBar(Items.component(Msg.raw("starter.actionbar", "item", def.display())));
            player.playSound(player.getLocation(), org.bukkit.Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.8F, 1.4F);
        }
        plugin.audit().log(player.getName(), "starter-claim", def.id());
        return true;
    }

    /** {@code /sm starter} 入口：重领本职业全部新手物品（已在身上的会逐条提示）。 */
    public int reclaim(Player player) {
        String classId = currentClassId(player);
        if (classId == null) {
            Msg.send(player, "starter.no-class");
            return 0;
        }
        List<String> ids = plugin.items().starterItems(classId);
        if (ids.isEmpty()) {
            Msg.send(player, "starter.none-for-class");
            return 0;
        }
        if (ids.size() == 1) {
            return claim(player, ids.get(0), "starter.reclaimed") ? 1 : 0;
        }
        int granted = 0;
        for (String id : ids) {
            if (claim(player, id, "starter.reclaimed")) {
                granted++;
            }
        }
        return granted;
    }

    // ==================== 查询 ====================

    /**
     * 发放到<b>第一格</b>（快捷栏 1 号位），让玩家一眼看到、直接能拿起来用。
     * <p>
     * 1 号位被占用时依次退让：空快捷栏 → 空背包格 → 直接塞背包 → 实在满了才掉在脚下
     * （绝不覆盖玩家已有物品）。
     */
    private void giveToFirstSlot(Player player, ItemStack item) {
        var inventory = player.getInventory();
        ItemStack first = inventory.getItem(0);
        if (first == null || first.getType().isAir()) {
            inventory.setItem(0, item);
            return;
        }
        for (int slot = 1; slot < 9; slot++) {
            ItemStack current = inventory.getItem(slot);
            if (current == null || current.getType().isAir()) {
                inventory.setItem(slot, item);
                return;
            }
        }
        for (int slot = 9; slot < 36; slot++) {
            ItemStack current = inventory.getItem(slot);
            if (current == null || current.getType().isAir()) {
                inventory.setItem(slot, item);
                return;
            }
        }
        inventory.addItem(item).values()
                .forEach(left -> player.getWorld().dropItemNaturally(player.getLocation(), left));
    }

    /**
     * 物品是否还在玩家身上：背包（含盔甲/副手，1.21 {@code getContents()} 已含 41 格）+ 末影箱。
     * <p>
     * 容器 / 交易 / 丢弃都被 {@code ClassRestrictionListener} 拦住了，所以这两处就是全部可能位置。
     */
    public boolean hasAnywhere(Player player, String itemId) {
        if (itemId == null) {
            return false;
        }
        String target = itemId.toLowerCase(Locale.ROOT);
        for (ItemStack item : player.getInventory().getContents()) {
            if (matches(item, target)) {
                return true;
            }
        }
        for (ItemStack item : player.getEnderChest().getContents()) {
            if (matches(item, target)) {
                return true;
            }
        }
        return false;
    }

    private boolean matches(ItemStack item, String target) {
        String id = plugin.items().idOf(item);
        return id != null && id.equalsIgnoreCase(target);
    }

    public String currentClassId(Player player) {
        var profile = plugin.playerData().getOrLoad(player);
        return profile == null ? null : profile.classId();
    }

    public int claimCount(Player player) {
        Integer count = player.getPersistentDataContainer()
                .get(KEY_CLAIM_COUNT, PersistentDataType.INTEGER);
        return count == null ? 0 : count;
    }

    private void recordClaim(Player player) {
        var pdc = player.getPersistentDataContainer();
        pdc.set(KEY_CLAIM_COUNT, PersistentDataType.INTEGER, claimCount(player) + 1);
        pdc.set(KEY_CLAIM_AT, PersistentDataType.LONG, System.currentTimeMillis());
    }

    /** 玩家身上所有新手物品 id（供调试 / 菜单展示）。 */
    public List<String> ownedStarters(Player player) {
        List<String> out = new ArrayList<>();
        for (ItemDefinition def : plugin.items().starterDefinitions()) {
            if (hasAnywhere(player, def.id())) {
                out.add(def.id());
            }
        }
        return out;
    }
}
