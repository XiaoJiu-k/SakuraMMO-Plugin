package io.sakurammo.listener;

import io.sakurammo.SakuraMMOPlugin;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.projectiles.ProjectileSource;

/**
 * 战斗事件 → 资源增益（受击 / 攻击 / 击杀）。
 */
public final class CombatListener implements Listener {

    private final SakuraMMOPlugin plugin;

    public CombatListener(SakuraMMOPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerDamaged(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player player) {
            plugin.resources().onDamageTaken(player);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerDealtDamage(EntityDamageByEntityEvent event) {
        Player attacker = resolveAttacker(event);
        if (attacker != null) {
            plugin.resources().onAttack(attacker);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onEntityDeath(EntityDeathEvent event) {
        Player killer = event.getEntity().getKiller();
        if (killer != null) {
            plugin.resources().onKill(killer);
        }
    }

    private static Player resolveAttacker(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Player player) {
            return player;
        }
        if (event.getDamager() instanceof Projectile projectile) {
            ProjectileSource source = projectile.getShooter();
            if (source instanceof Player player) {
                return player;
            }
        }
        return null;
    }
}
