package io.sakurammo.listener;

import io.sakurammo.SakuraMMOPlugin;
import io.sakurammo.item.ItemDefinition;
import io.sakurammo.util.Text;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Snowball;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 右键远程基础攻击（v4.2.4）。
 * <p>
 * 物品在 {@code items/*.yml} 里声明 {@code ranged-attack} 段即获得「右键发射」能力
 * （当前使用者：术士新手信物「微光余烬」）。实现要点：
 * <ul>
 *   <li>只在<b>主手</b>生效，且点击可交互方块（箱子/工作台/门…）时让位给原版交互——</li>
 *   <li>命中伤害由本监听器结算（雪球本身 0 伤害），走 {@code damage(值, 玩家)} 所以吃护甲与属性；</li>
 *   <li>弹体带 PDC 伤害标记，别人无法用普通雪球冒充；</li>
 *   <li>飞行沿途打灵魂火粒子，飞出 {@code range} 自动消散。</li>
 * </ul>
 */
public final class RangedAttackListener implements Listener {

    /** 弹体上的伤害标记（PDC）。 */
    public static final NamespacedKey KEY_DAMAGE = new NamespacedKey("sakurammo", "ranged_damage");

    private final SakuraMMOPlugin plugin;
    private final Map<UUID, Long> lastShot = new ConcurrentHashMap<>();

    public RangedAttackListener(SakuraMMOPlugin plugin) {
        this.plugin = plugin;
    }

    // 注意：**不能加 ignoreCancelled = true**。
    // PlayerInteractEvent 在「右键空气」时构造器就把 useClickedBlock 置为 DENY（isCancelled()==true），
    // 带 ignoreCancelled 的监听器会在派发前被过滤掉 —— 表现就是「对着方块能放，对着空气没反应」。
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInteract(PlayerInteractEvent event) {
        Action action = event.getAction();
        if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        if (action == Action.RIGHT_CLICK_BLOCK) {
            Block block = event.getClickedBlock();
            // 点的是箱子/工作台/门这类可交互方块 → 交给原版，不打断正常交互
            if (block != null && block.getType().isInteractable()) {
                return;
            }
        }
        Player player = event.getPlayer();
        ItemStack item = heldItem(event, player);
        if (item == null || item.getType().isAir()) {
            debug("skip: 手上没物品");
            return;
        }
        String id = plugin.items().idOf(item);
        if (id == null) {
            debug("skip: 非本引擎物品 " + item.getType());
            return;
        }
        ItemDefinition def = plugin.items().definition(id).orElse(null);
        if (def == null) {
            debug("skip: 定义缺失 " + id);
            return;
        }
        if (def.rangedAttack() == null) {
            debug("skip: " + id + " 没有 ranged-attack 段");
            return;
        }
        // 职业不符由 ClassRestrictionListener 取消，这里再兜一层（优先级更高，正常不会走到）
        if (def.hasClassLock() && !def.requiredClass().equalsIgnoreCase(classOf(player))) {
            debug("skip: 职业不符 " + classOf(player) + " vs " + def.requiredClass());
            return;
        }
        debug("fire: " + id + " action=" + action);
        event.setCancelled(true);
        fire(player, def);
    }

    private void fire(Player player, ItemDefinition def) {
        ItemDefinition.RangedAttack config = def.rangedAttack();
        long now = System.currentTimeMillis();
        Long previous = lastShot.get(player.getUniqueId());
        if (previous != null && now - previous < config.cooldownMs()) {
            return;   // 冷却中：静默丢弃，避免连点刷伤害
        }
        lastShot.put(player.getUniqueId(), now);

        Vector direction = player.getLocation().getDirection().normalize();
        Location spawnAt = player.getEyeLocation().add(direction.clone().multiply(0.6D));
        Snowball bolt = player.getWorld().spawn(spawnAt, Snowball.class, snowball -> {
            snowball.setShooter(player);
            snowball.setGravity(false);
            snowball.setSilent(true);
            snowball.setVelocity(direction.clone().multiply(config.speed()));
            snowball.getPersistentDataContainer()
                    .set(KEY_DAMAGE, PersistentDataType.DOUBLE, config.damage());
        });

        debug("spawn=" + fmt(spawnAt) + " dir=" + fmt(direction) + " speed=" + config.speed());
        player.getWorld().playSound(player.getLocation(), Sound.ENTITY_BLAZE_SHOOT, 0.6F, 1.6F);
        player.getWorld().spawnParticle(Particle.SOUL_FIRE_FLAME, spawnAt, 8, 0.12D, 0.12D, 0.12D, 0.01D);
        trail(bolt, spawnAt.clone(), config.range());
    }

    /** 飞行粒子 + 射程上限。 */
    private void trail(Snowball bolt, Location origin, double range) {
        new BukkitRunnable() {
            private int ticks;

            @Override
            public void run() {
                if (!bolt.isValid() || bolt.isDead() || ticks++ > 120) {
                    cancel();
                    return;
                }
                Location at = bolt.getLocation();
                at.getWorld().spawnParticle(Particle.SOUL_FIRE_FLAME, at, 2, 0.02D, 0.02D, 0.02D, 0.004D);
                if (at.distance(origin) > range) {
                    debug("range 超出，消散于 " + fmt(at) + "（" + String.format("%.1f", at.distance(origin)) + " 格）");
                    at.getWorld().spawnParticle(Particle.SOUL, at, 6, 0.1D, 0.1D, 0.1D, 0.01D);
                    bolt.remove();
                    cancel();
                }
            }
        }.runTaskTimer(plugin, 0L, 1L);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onHit(ProjectileHitEvent event) {
        if (!(event.getEntity() instanceof Snowball bolt)) {
            return;
        }
        Double damage = bolt.getPersistentDataContainer().get(KEY_DAMAGE, PersistentDataType.DOUBLE);
        if (damage == null) {
            return;
        }
        debug("hit: entity=" + (event.getHitEntity() == null ? "-" : event.getHitEntity().getType())
                + " block=" + (event.getHitBlock() == null ? "-" : event.getHitBlock().getType())
                + " at=" + fmt(bolt.getLocation()));
        Entity hit = event.getHitEntity();
        bolt.remove();
        if (!(hit instanceof LivingEntity target)) {
            return;
        }
        if (!(bolt.getShooter() instanceof Player shooter) || target.equals(shooter)) {
            return;
        }
        target.damage(damage, shooter);
        debug("damage " + damage + " → " + target.getType() + "，剩余血量 " + target.getHealth());
        target.getWorld().spawnParticle(Particle.SOUL_FIRE_FLAME, target.getLocation().add(0, 1, 0),
                10, 0.2D, 0.3D, 0.2D, 0.02D);
        shooter.playSound(shooter.getLocation(), Sound.ENTITY_ARROW_HIT_PLAYER, 0.5F, 1.8F);
        if (plugin.config().main().debug()) {
            plugin.getLogger().info("[debug] 灵魂火弹命中 " + target.getName()
                    + " -" + Text.number(damage) + "（来自 " + shooter.getName() + "）");
        }
    }

    /** 空右键时 getItem() 可能为 null，回落到主手。 */
    private ItemStack heldItem(PlayerInteractEvent event, Player player) {
        ItemStack item = event.getItem();
        if (item != null && !item.getType().isAir()) {
            return item;
        }
        return player.getInventory().getItemInMainHand();
    }

    private static String fmt(Location location) {
        return String.format("(%.1f,%.1f,%.1f)", location.getX(), location.getY(), location.getZ());
    }

    private static String fmt(Vector vector) {
        return String.format("(%.2f,%.2f,%.2f)", vector.getX(), vector.getY(), vector.getZ());
    }

    private void debug(String message) {
        if (plugin.config().main().debug()) {
            plugin.getLogger().info("[debug][远程攻击] " + message);
        }
    }

    private String classOf(Player player) {
        var profile = plugin.playerData().getOrLoad(player);
        return profile == null ? null : profile.classId();
    }
}
