package io.sakurammo.resource;

import io.sakurammo.SakuraMMOPlugin;
import io.sakurammo.config.ClassDefinition;
import io.sakurammo.config.ResourceDefinition;
import io.sakurammo.event.ResourceGainEvent;
import io.sakurammo.player.PlayerProfile;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 资源系统唯一入口。任何插件想改资源都必须走 get / set / add / consume。
 * <p>
 * 自然恢复：每 {@code resource.tick-interval-ticks}（默认 10 tick = 0.5s）按
 * {@code regen-per-second × 间隔秒数} 累加。
 * 事件驱动：受击 / 攻击 / 击杀 分别读职业配置里的 on-damage-taken / on-attack / on-kill。
 */
public final class ResourceService {

    private final SakuraMMOPlugin plugin;
    private BukkitTask task;
    private int intervalTicks = 10;

    public ResourceService(SakuraMMOPlugin plugin) {
        this.plugin = plugin;
    }

    public void start() {
        stop();
        intervalTicks = plugin.config().main().resourceTickIntervalTicks();
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, intervalTicks, intervalTicks);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    // ---------------- 读取 ----------------

    public double get(Player player, String resourceId) {
        PlayerProfile profile = plugin.playerData().get(player.getUniqueId());
        if (profile == null || !profile.hasClass()) {
            return 0.0D;
        }
        ClassDefinition definition = plugin.config().classOf(profile.classId());
        ResourceDefinition resource = definition == null ? null : definition.resource(resourceId);
        if (resource == null) {
            return 0.0D;
        }
        return clamp(profile.resource(resourceId), 0.0D, max(player, resourceId));
    }

    /** 有效上限 = 职业配置 + 天赋加成（阶段三）。 */
    public double max(Player player, String resourceId) {
        ResourceDefinition resource = definitionOf(player, resourceId);
        if (resource == null) {
            return 0.0D;
        }
        PlayerProfile profile = plugin.playerData().get(player.getUniqueId());
        double bonus = profile == null ? 0.0D
                : plugin.talents().resourceMaxBonus(profile, resource.id());
        return resource.max() + bonus;
    }

    public ResourceDefinition definitionOf(Player player, String resourceId) {
        PlayerProfile profile = plugin.playerData().get(player.getUniqueId());
        if (profile == null || !profile.hasClass()) {
            return null;
        }
        ClassDefinition definition = plugin.config().classOf(profile.classId());
        return definition == null ? null : definition.resource(resourceId);
    }

    /** 当前职业所有资源（含 reserved）的快照。 */
    public Map<String, Double> snapshot(Player player) {
        Map<String, Double> out = new LinkedHashMap<>();
        PlayerProfile profile = plugin.playerData().get(player.getUniqueId());
        if (profile == null || !profile.hasClass()) {
            return out;
        }
        ClassDefinition definition = plugin.config().classOf(profile.classId());
        if (definition == null) {
            return out;
        }
        for (ResourceDefinition resource : definition.resources().values()) {
            out.put(resource.id(), get(player, resource.id()));
        }
        return out;
    }

    // ---------------- 写入 ----------------

    public void set(Player player, String resourceId, double value) {
        PlayerProfile profile = plugin.playerData().get(player.getUniqueId());
        ResourceDefinition resource = definitionOf(player, resourceId);
        if (profile == null || resource == null) {
            return;
        }
        profile.resource(resourceId, clamp(value, 0.0D, max(player, resourceId)));
    }

    /**
     * 增加资源。已满或事件被取消时返回 false。
     */
    public boolean add(Player player, String resourceId, double amount, ResourceGainEvent.Cause cause) {
        if (amount <= 0.0D) {
            return false;
        }
        ResourceDefinition resource = definitionOf(player, resourceId);
        PlayerProfile profile = plugin.playerData().get(player.getUniqueId());
        if (resource == null || profile == null) {
            return false;
        }
        double effectiveMax = max(player, resourceId);
        double current = get(player, resourceId);
        if (current >= effectiveMax) {
            return false;
        }
        ResourceGainEvent event = new ResourceGainEvent(player, resourceId, amount, cause);
        Bukkit.getPluginManager().callEvent(event);
        if (event.isCancelled() || event.amount() <= 0.0D) {
            return false;
        }
        double next = clamp(current + event.amount(), 0.0D, effectiveMax);
        if (next == current) {
            return false;
        }
        profile.resource(resourceId, next);
        return true;
    }

    /**
     * 消耗资源。不足时返回 false 且不改动。
     */
    public boolean consume(Player player, String resourceId, double amount) {
        if (amount <= 0.0D) {
            return true;
        }
        ResourceDefinition resource = definitionOf(player, resourceId);
        PlayerProfile profile = plugin.playerData().get(player.getUniqueId());
        if (resource == null || profile == null) {
            return false;
        }
        double current = get(player, resourceId);
        if (current < amount) {
            return false;
        }
        profile.resource(resourceId, clamp(current - amount, 0.0D, resource.max()));
        return true;
    }

    /** 重置为职业配置里的 initial 值（选/切职业时调用）。 */
    public void resetToInitial(Player player) {
        PlayerProfile profile = plugin.playerData().get(player.getUniqueId());
        if (profile == null || !profile.hasClass()) {
            return;
        }
        ClassDefinition definition = plugin.config().classOf(profile.classId());
        profile.clearResources();
        if (definition == null) {
            return;
        }
        for (ResourceDefinition resource : definition.resources().values()) {
            profile.resource(resource.id(), clamp(resource.initial(), 0.0D, resource.max()));
        }
    }

    // ---------------- 事件驱动 ----------------

    public void onDamageTaken(Player player) {
        forEachGain(player, "on-damage-taken", ResourceGainEvent.Cause.DAMAGE_TAKEN);
    }

    public void onAttack(Player player) {
        forEachGain(player, "on-attack", ResourceGainEvent.Cause.ATTACK);
    }

    public void onKill(Player player) {
        forEachGain(player, "on-kill", ResourceGainEvent.Cause.KILL);
    }

    private void forEachGain(Player player, String which, ResourceGainEvent.Cause cause) {
        PlayerProfile profile = plugin.playerData().get(player.getUniqueId());
        if (profile == null || !profile.hasClass()) {
            return;
        }
        ClassDefinition definition = plugin.config().classOf(profile.classId());
        if (definition == null) {
            return;
        }
        for (ResourceDefinition resource : definition.resources().values()) {
            double gain = switch (which) {
                case "on-damage-taken" -> resource.onDamageTaken();
                case "on-attack" -> resource.onAttack();
                case "on-kill" -> resource.onKill();
                default -> 0.0D;
            };
            if (gain > 0.0D) {
                add(player, resource.id(), gain, cause);
            }
        }
    }

    // ---------------- 定时 ----------------

    private void tick() {
        double seconds = intervalTicks / 20.0D;
        for (Player player : Bukkit.getOnlinePlayers()) {
            PlayerProfile profile = plugin.playerData().get(player.getUniqueId());
            if (profile == null || !profile.hasClass()) {
                continue;
            }
            ClassDefinition definition = plugin.config().classOf(profile.classId());
            if (definition == null) {
                continue;
            }
            for (ResourceDefinition resource : definition.resources().values()) {
                if (resource.reserved() || resource.regenPerSecond() <= 0.0D) {
                    continue;
                }
                double regen = resource.regenPerSecond()
                        + plugin.talents().resourceRegenBonus(profile, resource.id());
                if (regen <= 0.0D) {
                    continue;
                }
                add(player, resource.id(), regen * seconds, ResourceGainEvent.Cause.REGEN);
            }
        }
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
