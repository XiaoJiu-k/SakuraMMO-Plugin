package io.sakurammo.listener;

import io.sakurammo.SakuraMMOPlugin;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerItemDamageEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;

/**
 * 无限耐久（config.yml → {@code items.infinite-durability}）。
 * <p>
 * 为什么用事件拦截而不是给每件装备打 {@code unbreakable} 标记：
 * <ul>
 *   <li>装备来源有四类（副本掉落 / RPGLoot 随机词条 / 制造 / 升格兑换），逐个打标记会漏；</li>
 *   <li>打标记会改写物品 NBT，可能影响既有的属性/词条组件与堆叠；</li>
 *   <li>取消 {@link PlayerItemDamageEvent} 对武器、盔甲、工具、鞘翅一次性全覆盖。</li>
 * </ul>
 * 已经磨损的物品不会再继续掉耐久，但也不会自动修满——需要修满时用
 * {@code items.repair-on-join: true}（登录时把背包内的磨损清零）。
 */
public final class DurabilityListener implements Listener {

    private final SakuraMMOPlugin plugin;

    public DurabilityListener(SakuraMMOPlugin plugin) {
        this.plugin = plugin;
    }

    /** 取消一切耐久损耗（武器挥砍、盔甲受击、工具挖掘、鞘翅飞行都走这个事件）。 */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onItemDamage(PlayerItemDamageEvent event) {
        if (plugin.config().main().infiniteDurability()) {
            event.setCancelled(true);
        }
    }

    /** 登录时修满背包与盔甲栏（只处理普通可损坏物品；不碰容器内的物品）。 */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        if (!plugin.config().main().repairOnJoin()) {
            return;
        }
        Player player = event.getPlayer();
        int repaired = 0;
        for (ItemStack item : player.getInventory().getContents()) {
            if (repair(item)) {
                repaired++;
            }
        }
        // 1.21 的 getContents() 已含盔甲与副手（41 格），此处不再重复处理
        if (repaired > 0) {
            player.updateInventory();
            if (plugin.config().main().debug()) {
                plugin.getLogger().info("[debug] 登录修满 " + player.getName() + " 的 " + repaired + " 件物品耐久");
            }
        }
    }

    private static boolean repair(ItemStack item) {
        if (item == null || item.getType().isAir()) {
            return false;
        }
        ItemMeta meta = item.getItemMeta();
        if (!(meta instanceof Damageable damageable) || !damageable.hasDamage()) {
            return false;
        }
        damageable.setDamage(0);
        item.setItemMeta(meta);
        return true;
    }
}
