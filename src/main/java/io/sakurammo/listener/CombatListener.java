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

import java.util.concurrent.ThreadLocalRandom;

/**
 * 战斗事件：资源增益（受击 / 攻击 / 击杀）、击杀经验与金币（阶段三）、
 * 天赋「技能伤害倍率」的后处理放大（施法窗口内）。
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

    /**
     * 天赋技能伤害倍率（阶段三）：施法窗口内的玩家伤害按倍率补差。
     * <p>
     * 为什么在 MONITOR 直接补差而不是改事件伤害：本服技能用 MythicMobs
     * {@code damage{ignorearmor=true}}（阶段一为数值精确选定），它走原生直扣通道，
     * 事件里 {@code setDamage} 的修改会被无视。这里在事件结算后按
     * {@code finalDamage × (倍率-1)} 直接补一段归因于施法者的伤害（可击杀、可触发死亡掉落）；
     * {@code applying} 护栏防补差伤害再次进入本监听造成递归。
     */
    private final java.util.Set<java.util.UUID> applying = java.util.concurrent.ConcurrentHashMap.newKeySet();

    @EventHandler(priority = EventPriority.MONITOR)
    public void onSkillDamageMultiplier(EntityDamageByEntityEvent event) {
        Player attacker = resolveAttacker(event);
        if (attacker == null) {
            return;
        }
        double multiplier = plugin.talents().activeMultiplier(attacker);
        if (multiplier <= 1.0D) {
            return;
        }
        if (!(event.getEntity() instanceof org.bukkit.entity.LivingEntity living) || living.isDead()) {
            return;
        }
        if (!applying.add(living.getUniqueId())) {
            return; // 补差伤害的嵌套事件，不再放大
        }
        try {
            // MythicMobs ignorearmor 技能会取消事件并按原始伤害直扣（事件值仍是原伤害量），
            // 因此取消与否都按 finalDamage 补差
            double base = event.getFinalDamage();
            if (base <= 0.0D) {
                return;
            }
            double extra = base * (multiplier - 1.0D);
            if (extra <= 0.0D) {
                return;
            }
            if (plugin.config().main().debug()) {
                plugin.getLogger().info("[debug] 技能倍率补差 " + attacker.getName() + " ×" + multiplier
                        + " cancelled=" + event.isCancelled()
                        + " base=" + String.format(java.util.Locale.ROOT, "%.2f", base)
                        + " extra=" + String.format(java.util.Locale.ROOT, "%.2f", extra));
            }
            if (extra >= living.getHealth()) {
                living.damage(extra, attacker); // 致死补差：走伤害通道保证死亡结算与掉落归因
            } else {
                living.setHealth(Math.max(0.0D, living.getHealth() - extra));
            }
        } finally {
            applying.remove(living.getUniqueId());
        }
    }

    /**
     * 召唤物保护（阶段四）：名字带「小鬼」的召唤物免疫玩家方伤害——
     * 术士 AoE 技能（MM 范围目标器无法排除召唤物）不再误伤自己的小鬼。
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onSummonerFriendlyFire(EntityDamageByEntityEvent event) {
        // MM 生成的实体名是 Adventure 组件——legacy getCustomName() 可能为 null，两种通道都查
        String name = null;
        try {
            name = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
                    .serialize(event.getEntity().name());
        } catch (Throwable ignored) {
            // 退化到 legacy
        }
        if (name == null || name.isBlank()) {
            name = event.getEntity().getCustomName();
        }
        if (name == null || !name.contains("小鬼")) {
            return;
        }
        if (resolveAttacker(event) != null) {
            event.setCancelled(true);
        }
    }

    /** 召唤物保护补充：技能击退抛空的摔落伤害一并免疫（FALL）。 */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onSummonerFallDamage(org.bukkit.event.entity.EntityDamageEvent event) {
        String name = null;
        try {
            name = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
                    .serialize(event.getEntity().name());
        } catch (Throwable ignored) {
            name = event.getEntity().getCustomName();
        }
        if (name != null && name.contains("小鬼")
                && event.getCause() == org.bukkit.event.entity.EntityDamageEvent.DamageCause.FALL) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerDealtDamage(EntityDamageByEntityEvent event) {
        Player attacker = resolveAttacker(event);
        if (attacker != null) {
            plugin.resources().onAttack(attacker);
        }
    }

    /**
     * 掉落控制（阶段四用户需求）：副本世界外的生物死亡<b>无掉落物、无经验球</b>；
     * 副本世界内保留掉落，并对 RPGLoot 生成的英文词条物品汉化。玩家死亡不干预。
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onWildDropClear(EntityDeathEvent event) {
        if (event.getEntity() instanceof Player) {
            return; // 玩家死亡掉落保留原版
        }
        boolean inDungeon = plugin.instances().byWorld(event.getEntity().getWorld().getName()) != null;
        if (!inDungeon) {
            event.getDrops().clear();
            event.setDroppedExp(0);
            return;
        }
        for (org.bukkit.inventory.ItemStack drop : event.getDrops()) {
            if (io.sakurammo.loot.RpgLootTranslator.translate(drop)) {
                if (plugin.config().main().debug()) {
                    plugin.getLogger().info("[debug] RPGLoot 物品已汉化：" + drop.getType());
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onEntityDeath(EntityDeathEvent event) {
        Player killer = event.getEntity().getKiller();
        if (killer == null) {
            return;
        }
        plugin.resources().onKill(killer);
        // 阶段四：周常「击杀野怪」（副本世界内的击杀不算野怪，Boss 由 InstanceManager 计）
        if (plugin.instances().byWorld(event.getEntity().getWorld().getName()) == null) {
            plugin.weeklies().progress(killer, "kill_mobs", 1);
        }
        // 阶段三：击杀经验（与副本 Boss 经费在 InstanceManager 里单独发放）
        plugin.talents().addExperience(killer, plugin.config().main().xpPerKill());
        double money = plugin.economy().killMoneyMin()
                + ThreadLocalRandom.current().nextDouble()
                * (plugin.economy().killMoneyMax() - plugin.economy().killMoneyMin());
        if (event.getEntity() instanceof Player) {
            return; // 不给玩家击杀发钱
        }
        plugin.economy().deposit(killer, Math.round(money * 100.0D) / 100.0D, "economy.kill-gain");
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
