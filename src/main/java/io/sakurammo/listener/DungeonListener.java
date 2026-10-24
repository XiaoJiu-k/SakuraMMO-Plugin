package io.sakurammo.listener;

import io.sakurammo.SakuraMMOPlugin;
import org.bukkit.Location;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerRespawnEvent;

/**
 * 副本事件入口：Boss 死亡链、Boss3 转阶段、双阀/奖励箱交互、副本内复活点。
 * 全部转发给 InstanceManager（本类不持有状态）。
 */
public final class DungeonListener implements Listener {

    private final SakuraMMOPlugin plugin;

    public DungeonListener(SakuraMMOPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onEntityDeath(EntityDeathEvent event) {
        plugin.instances().handleEntityDeath(event.getEntity());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof LivingEntity living) {
            plugin.instances().handleBossDamage(living);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getClickedBlock() == null) {
            return;
        }
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        Player player = event.getPlayer();
        if (plugin.instances().byWorld(player.getWorld().getName()) == null) {
            return;
        }
        if (plugin.config().main().debug()) {
            plugin.getLogger().info("[debug] 副本内交互事件到达：" + player.getName()
                    + " " + event.getAction() + " " + event.getClickedBlock().getType()
                    + " hand=" + event.getHand());
        }
        // 阀门与奖励箱都是插件语义，取消原版交互（拉杆翻转/开空箱）
        event.setCancelled(true);
        plugin.instances().handleInteract(player, event.getClickedBlock());
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onRespawn(PlayerRespawnEvent event) {
        Location respawn = plugin.instances().respawnLocation(event.getPlayer());
        if (respawn != null) {
            event.setRespawnLocation(respawn);
        }
    }
}
