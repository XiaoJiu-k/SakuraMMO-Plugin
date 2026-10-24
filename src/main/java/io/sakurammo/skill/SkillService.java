package io.sakurammo.skill;

import io.sakurammo.SakuraMMOPlugin;
import io.sakurammo.config.ClassDefinition;
import io.sakurammo.config.ResourceDefinition;
import io.sakurammo.config.SkillDefinition;
import io.sakurammo.player.PlayerProfile;
import io.sakurammo.player.SkillBinding;
import io.sakurammo.util.Msg;
import io.sakurammo.util.Text;
import io.sakurammo.util.TimeFormat;
import org.bukkit.entity.Player;

/**
 * 技能入口：绑定 / 解绑 / 五项校验 / 调用 MythicMobs 施放。
 * <p>
 * 插件只管「谁能放、花多少、多久一次」，效果一律由 MythicMobs 执行。
 */
public final class SkillService {

    private final SakuraMMOPlugin plugin;

    public SkillService(SakuraMMOPlugin plugin) {
        this.plugin = plugin;
    }

    public record Result(boolean ok, String message) {
        public static Result ok(String message) {
            return new Result(true, message);
        }

        public static Result fail(String message) {
            return new Result(false, message);
        }
    }

    // ---------------- 绑定（v3：Shift+1~4 按键槽位） ----------------

    /** 校验按键槽位合法性（1/2/3/4）。 */
    public static boolean validKey(String key) {
        return key != null && PlayerProfile.SKILL_KEYS.contains(key.toUpperCase(java.util.Locale.ROOT));
    }

    public static String normalizeKey(String key) {
        return key == null ? null : key.trim().toUpperCase(java.util.Locale.ROOT);
    }

    /** 槽位序号（1-4）→ 按键（1/2/3/4），非法返回 null。 */
    public static String keyOfSlot(int slot) {
        if (slot < 1 || slot > PlayerProfile.SKILL_KEYS.size()) {
            return null;
        }
        return PlayerProfile.SKILL_KEYS.get(slot - 1);
    }

    /** 按键 → 槽位序号（1-4），非法返回 -1。 */
    public static int slotOfKey(String key) {
        int index = PlayerProfile.SKILL_KEYS.indexOf(normalizeKey(key));
        return index < 0 ? -1 : index + 1;
    }

    /** 按键的显示名：槽位 1-4 显示为 Shift+1 ~ Shift+4，其他原样返回。 */
    public static String keyDisplay(String key) {
        return validKey(key) ? "Shift+" + normalizeKey(key) : String.valueOf(key);
    }

    /**
     * 绑定技能到按键槽位（Shift+1~4）。绑定不跟物品走；换绑不清除冷却（冷却按 skillId 存）。
     */
    public Result bind(Player player, String skillId, String key) {
        key = normalizeKey(key);
        if (!validKey(key)) {
            return Result.fail(Msg.raw("skill.bind-key-invalid", "key", String.valueOf(key)));
        }
        SkillDefinition definition = plugin.config().skill(skillId);
        if (definition == null) {
            return Result.fail(Msg.raw("skill.unknown", "id", skillId));
        }
        PlayerProfile profile = plugin.playerData().getOrLoad(player);
        if (!profile.hasClass()) {
            return Result.fail(Msg.raw("class.none-selected"));
        }
        if (!definition.classId().equals(profile.classId())) {
            return Result.fail(Msg.raw("skill.not-owned",
                    "class", plugin.classManager().displayOf(profile.classId()),
                    "skill", definition.displayName()));
        }
        if (!definition.trigger().bindable()) {
            return Result.fail(Msg.raw("skill.passive-not-bindable", "skill", definition.displayName()));
        }
        profile.skillBind(key, definition.id());
        plugin.playerData().save(profile);
        return Result.ok(Msg.raw("skill.bind-success",
                "skill", definition.displayName(), "key", keyDisplay(key)));
    }

    public Result unbind(Player player, String key) {
        key = normalizeKey(key);
        if (!validKey(key)) {
            return Result.fail(Msg.raw("skill.bind-key-invalid", "key", String.valueOf(key)));
        }
        PlayerProfile profile = plugin.playerData().getOrLoad(player);
        String removed = profile.skillBinds().remove(key);
        if (removed == null) {
            return Result.fail(Msg.raw("skill.unbind-empty-key", "key", keyDisplay(key)));
        }
        profile.markDirty();
        plugin.playerData().save(profile);
        return Result.ok(Msg.raw("skill.unbind-success", "key", keyDisplay(key)));
    }

    /** 按键槽位当前绑定的技能 id（null = 未绑定）。 */
    public String bindOf(Player player, String key) {
        PlayerProfile profile = plugin.playerData().get(player.getUniqueId());
        return profile == null ? null : profile.skillBind(normalizeKey(key));
    }

    /** 技能的冷却剩余毫秒（0 = 就绪）。按 skillId 计算，与按键槽位无关。 */
    public long cooldownRemaining(Player player, String skillId) {
        PlayerProfile profile = plugin.playerData().get(player.getUniqueId());
        if (profile == null) {
            return 0L;
        }
        long remain = profile.cooldownUntil(skillId) - System.currentTimeMillis();
        return Math.max(0L, remain);
    }

    public SkillBinding bindingAt(Player player, int slot) {
        PlayerProfile profile = plugin.playerData().get(player.getUniqueId());
        return profile == null ? null : profile.boundSkills().get(slot);
    }

    // ---------------- 施放 ----------------

    /**
     * 校验顺序（任务书 §3 P0-3）：职业匹配 → 已解锁 → 资源足够 → 冷却就绪。
     * 主手绑定匹配由调用方（PlayerListener）先做。
     * 全部通过才扣资源 + 设冷却，任何一步失败都不产生副作用。
     */
    public Result cast(Player player, SkillDefinition definition) {
        PlayerProfile profile = plugin.playerData().get(player.getUniqueId());
        if (profile == null || !profile.hasClass()) {
            return fail(player, definition, "玩家没有职业");
        }

        // 1. 职业匹配
        if (!definition.classId().equals(profile.classId())) {
            return fail(player, definition, "职业不符：技能属于 " + definition.classId()
                    + "，玩家是 " + profile.classId());
        }

        // 2. 已解锁
        if (definition.hasPermissionGate() && !player.hasPermission(definition.permission())) {
            return fail(player, definition, "未解锁：缺少权限 " + definition.permission());
        }

        // 3. 资源足够（天赋「消耗降低」在这里生效）
        ClassDefinition classDefinition = plugin.config().classOf(profile.classId());
        ResourceDefinition resource = classDefinition == null ? null : classDefinition.resource(definition.resource());
        double effectiveCost = definition.cost() * (1.0D - plugin.talents().costReduction(profile));
        if (effectiveCost > 0.0D) {
            if (resource == null) {
                return fail(player, definition, "资源 " + definition.resource() + " 未定义");
            }
            double current = plugin.resources().get(player, resource.id());
            if (current < effectiveCost) {
                return Result.fail(Msg.raw("skill.no-resource",
                        "resource", Text.stripColor(resource.displayName()),
                        "cost", Text.number(effectiveCost),
                        "current", Text.number(current)));
            }
        }

        // 4. 冷却就绪（天赋「冷却缩减」在这里生效）
        long now = System.currentTimeMillis();
        long until = profile.cooldownUntil(definition.id());
        if (until > now) {
            return Result.fail(Msg.raw("skill.cooldown",
                    "time", TimeFormat.humanize(until - now)));
        }

        // 5. 交给 MythicMobs 执行（先记天赋倍率窗口：MythicMobs 的伤害 mechanic 同步结算，
        //    事件在 castSkill 调用内触发，窗口必须先建立）
        if (!plugin.mythic().available()) {
            return fail(player, definition, "MythicMobs 不可用");
        }
        plugin.talents().markCast(player, definition.id());
        boolean cast = plugin.mythic().castSkill(player, definition.mythicSkill());
        if (!cast) {
            return fail(player, definition, "MythicMobs castSkill 返回 false（技能 "
                    + definition.mythicSkill() + "）");
        }

        // 成功后才扣资源（按天赋折减后的消耗）+ 进冷却（按天赋折减后的冷却）
        if (effectiveCost > 0.0D && resource != null) {
            plugin.resources().consume(player, resource.id(), effectiveCost);
        }
        long cooldownMillis = Math.round(definition.cooldownMillis()
                * (1.0D - plugin.talents().cooldownReduction(profile)));
        profile.cooldownUntil(definition.id(), now + cooldownMillis);
        profile.markDirty();

        if (plugin.config().main().debug()) {
            plugin.getLogger().info("[debug] " + player.getName() + " 施放 " + definition.id()
                    + " → MythicMobs " + definition.mythicSkill() + " = true");
        }
        return Result.ok(null);
    }

    /** 失败时统一打 debug 日志 + 返回提示，方便定位「为什么放不出来」。 */
    private Result fail(Player player, SkillDefinition definition, String reason) {
        if (plugin.config().main().debug()) {
            plugin.getLogger().info("[debug] " + player.getName() + " 施放 "
                    + definition.id() + " 失败：" + reason);
        }
        return Result.fail(Msg.raw("skill.cast-failed", "reason", reason));
    }

    // ---------------- 启动校验 ----------------
    /** 校验 skills.yml 里所有 mythic-skill 在 MythicMobs 中存在，缺失只 WARN 不崩溃。 */
    public void validateMythicSkills() {
        if (!plugin.mythic().available()) {
            return;
        }
        for (SkillDefinition definition : plugin.config().skills().values()) {
            if (!plugin.mythic().skillExists(definition.mythicSkill())) {
                plugin.getLogger().warning("MythicMobs 里找不到技能 " + definition.mythicSkill()
                        + "（skills.yml 的 " + definition.id() + "），该技能施放时会失败。");
            }
        }
    }
}
