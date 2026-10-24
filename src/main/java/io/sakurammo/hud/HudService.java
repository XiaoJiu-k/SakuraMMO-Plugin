package io.sakurammo.hud;

import io.sakurammo.SakuraMMOPlugin;
import io.sakurammo.config.ClassDefinition;
import io.sakurammo.config.MainConfig;
import io.sakurammo.config.ResourceDefinition;
import io.sakurammo.player.PlayerProfile;
import io.sakurammo.util.Text;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

/**
 * ActionBar HUD：显示当前职业的主资源。
 * <p>
 * 格式来自 config.yml 的 hud.format，可用占位符：
 * {display} {current} {max} {percent} {class} {class_display} {resource}
 */
public final class HudService {

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacySection();

    private final SakuraMMOPlugin plugin;
    private BukkitTask task;

    public HudService(SakuraMMOPlugin plugin) {
        this.plugin = plugin;
    }

    public void start() {
        stop();
        MainConfig config = plugin.config().main();
        if (!config.hudEnabled()) {
            return;
        }
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick,
                config.hudIntervalTicks(), config.hudIntervalTicks());
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    private void tick() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            PlayerProfile profile = plugin.playerData().get(player.getUniqueId());
            if (profile == null || !profile.hasClass()) {
                continue;
            }
            ClassDefinition definition = plugin.config().classOf(profile.classId());
            if (definition == null) {
                continue;
            }
            ResourceDefinition primary = definition.primaryResource();
            if (primary == null) {
                continue;
            }
            String line = format(player, definition, primary);
            if (line == null || line.isEmpty()) {
                continue;
            }
            player.sendActionBar(LEGACY.deserialize(Text.color(line)));
        }
    }

    public String format(Player player, ClassDefinition definition, ResourceDefinition resource) {
        String pattern = plugin.config().main().hudFormat();
        if (pattern == null || pattern.isBlank()) {
            return null;
        }
        double current = plugin.resources().get(player, resource.id());
        double max = resource.max();
        int percent = max <= 0 ? 0 : (int) Math.round(current / max * 100.0D);
        return pattern
                .replace("{display}", resource.displayName())
                .replace("{current}", Text.number(current))
                .replace("{max}", Text.number(max))
                .replace("{percent}", String.valueOf(percent))
                .replace("{class}", definition.id())
                .replace("{class_display}", definition.displayName())
                .replace("{resource}", resource.id());
    }
}
