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

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ActionBar HUD（v2，两行单一写入者——消除施法提示与资源条的互相覆盖闪烁）：
 * <pre>
 * 行 1（施法行）：施法成功/失败提示，独立计时淡出（默认 2s），平时为空
 * 行 2（资源行）：法力/怒气 · 生命 · 当前法术冷却（{skill_cd} 占位符）
 * </pre>
 * 行 1 与行 2 由同一个周期任务组装发送（ Adventure 组件内 \n 渲染为换行），
 * castBound 不再抢占整条 ActionBar。
 * <p>
 * 资源行占位符：{display} {current} {max} {percent} {class} {class_display} {resource}
 * {health} {max_health} {skill_cd}（含分隔符：冷却中 " | ⚔盾击 4.2s"，就绪 " | ⚔盾击 就绪"，无记录为空）
 */
public final class HudService {

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacySection();

    private final SakuraMMOPlugin plugin;
    private BukkitTask task;
    /** 施法行：玩家 → 提示内容。 */
    private final Map<UUID, String> castLines = new ConcurrentHashMap<>();
    /** 施法行过期时间。 */
    private final Map<UUID, Long> castExpire = new ConcurrentHashMap<>();
    /** 最近施放的技能（{skill_cd} 显示用）。 */
    private final Map<UUID, String> lastSkillId = new ConcurrentHashMap<>();
    private final Map<UUID, String> lastSkillName = new ConcurrentHashMap<>();

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

    // ---------------- 施法行（行 1） ----------------

    /**
     * 施法提示写入行 1（独立计时淡出，由周期任务统一渲染——不再抢占资源行）。
     */
    public void castMessage(Player player, String legacyText, int seconds) {
        if (legacyText == null || legacyText.isBlank()) {
            return;
        }
        castLines.put(player.getUniqueId(), legacyText);
        castExpire.put(player.getUniqueId(), System.currentTimeMillis() + seconds * 1000L);
        renderOnce(player); // 立即渲染一次，感知零延迟
    }

    /** 记录最近施放的技能（保留接口；{skill_cd} 现按全部绑定槽渲染）。 */
    public void recordSkillCast(Player player, String skillId, String skillDisplay) {
        lastSkillId.put(player.getUniqueId(), skillId);
        lastSkillName.put(player.getUniqueId(), skillDisplay);
    }

    public boolean isOverridden(Player player) {
        Long until = castExpire.get(player.getUniqueId());
        return until != null && until > System.currentTimeMillis();
    }

    /** 玩家退出时清理状态。 */
    public void clearOverride(UUID uuid) {
        castLines.remove(uuid);
        castExpire.remove(uuid);
        lastSkillId.remove(uuid);
        lastSkillName.remove(uuid);
    }

    // ---------------- 渲染 ----------------

    private void tick() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            renderOnce(player);
        }
    }

    /** 组装并发送该玩家的两行 ActionBar（无职业/无资源时不刷新）。 */
    private void renderOnce(Player player) {
        PlayerProfile profile = plugin.playerData().get(player.getUniqueId());
        if (profile == null || !profile.hasClass()) {
            return;
        }
        ClassDefinition definition = plugin.config().classOf(profile.classId());
        if (definition == null) {
            return;
        }
        ResourceDefinition primary = definition.primaryResource();
        if (primary == null) {
            return;
        }
        // 行 1：施法提示（过期清理 + 淡出）
        long now = System.currentTimeMillis();
        Long expire = castExpire.get(player.getUniqueId());
        String castLine = "";
        if (expire != null) {
            if (expire > now) {
                String text = castLines.get(player.getUniqueId());
                if (text != null) {
                    castLine = Text.color(text);
                }
            } else {
                castLines.remove(player.getUniqueId());
                castExpire.remove(player.getUniqueId());
            }
        }
        // 行 2：资源（含 {skill_cd}）
        String resourceLine = format(player, definition, primary);

        String combined = castLine.isBlank() ? resourceLine : castLine + "\n" + resourceLine;
        if (combined == null || combined.isBlank()) {
            return;
        }
        if (plugin.config().main().debug()) {
            plugin.getLogger().info("[debug] HUD " + player.getName() + " → "
                    + combined.replace("\n", " ⏎ "));
        }
        // format() 产出的字符串仍带 & 色码，必须先翻译（施法行已在写入时翻译过，重复翻译无副作用）
        player.sendActionBar(LEGACY.deserialize(Text.color(combined)));
    }

    public String format(Player player, ClassDefinition definition, ResourceDefinition resource) {
        String pattern = plugin.config().main().hudFormat();
        if (pattern == null || pattern.isBlank()) {
            return null;
        }
        double current = plugin.resources().get(player, resource.id());
        double max = plugin.resources().max(player, resource.id());
        int percent = max <= 0 ? 0 : (int) Math.round(current / max * 100.0D);
        return pattern
                .replace("{display}", resource.displayName())
                .replace("{current}", Text.number(current))
                .replace("{max}", Text.number(max))
                .replace("{percent}", String.valueOf(percent))
                .replace("{class}", definition.id())
                .replace("{class_display}", definition.displayName())
                .replace("{resource}", resource.id())
                .replace("{health}", healthText(player, false))
                .replace("{max_health}", healthText(player, true))
                .replace("{skill_cd}", skillCdText(player));
    }

    /**
     * {skill_cd}：Shift+1~4 全部绑定技能的冷却状态（含前置分隔符，无绑定为空）。
     * 冷却中 `⚔名字 X.Xs`（数字红色），就绪 `⚔名字 ✓`（绿色）。
     */
    private String skillCdText(Player player) {
        PlayerProfile profile = plugin.playerData().get(player.getUniqueId());
        if (profile == null || profile.skillBinds().isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder(" &8| ");
        boolean any = false;
        for (String key : io.sakurammo.player.PlayerProfile.SKILL_KEYS) {
            String skillId = profile.skillBind(key);
            if (skillId == null) {
                continue;
            }
            var skill = plugin.config().skill(skillId);
            String name = skill == null ? skillId : skill.displayName();
            long remain = profile.cooldownUntil(skillId) - System.currentTimeMillis();
            if (any) {
                sb.append(' ');
            }
            any = true;
            if (remain > 0) {
                sb.append("&b⚔").append(name).append(" &f")
                        .append(String.format(java.util.Locale.ROOT, "%.1f", remain / 1000.0D)).append("s");
            } else {
                sb.append("&b⚔").append(name).append(" &a✓");
            }
        }
        return any ? sb.toString() : "";
    }

    /** {health} / {max_health}：玩家血量取整显示（供 hud.format 使用）。 */
    private String healthText(Player player, boolean max) {
        try {
            var attribute = player.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH);
            if (max) {
                return String.valueOf((int) Math.ceil(attribute == null ? 20.0D : attribute.getValue()));
            }
            return String.valueOf((int) Math.ceil(player.getHealth()));
        } catch (Throwable t) {
            return "?";
        }
    }
}
