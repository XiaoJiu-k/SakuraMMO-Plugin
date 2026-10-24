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
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

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

    // ---------------- 绑定 ----------------

    public Result bind(Player player, String skillId) {
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
        ItemStack hand = player.getInventory().getItemInMainHand();
        if (hand == null || hand.getType().isAir()) {
            return Result.fail(Msg.raw("skill.no-binding"));
        }
        int slot = player.getInventory().getHeldItemSlot();
        Material material = hand.getType();
        profile.boundSkills().put(slot, new SkillBinding(definition.id(), material));
        profile.markDirty();
        plugin.playerData().save(profile);
        return Result.ok(Msg.raw("skill.bound",
                "skill", definition.displayName(),
                "material", material.name(),
                "slot", String.valueOf(slot)));
    }

    public Result unbind(Player player) {
        PlayerProfile profile = plugin.playerData().getOrLoad(player);
        int slot = player.getInventory().getHeldItemSlot();
        SkillBinding removed = profile.boundSkills().remove(slot);
        if (removed == null) {
            return Result.fail(Msg.raw("skill.unbound-empty"));
        }
        profile.markDirty();
        plugin.playerData().save(profile);
        return Result.ok(Msg.raw("skill.unbound", "slot", String.valueOf(slot)));
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
            return Result.fail(Msg.raw("class.none-selected"));
        }

        // 1. 职业匹配
        if (!definition.classId().equals(profile.classId())) {
            return Result.fail(Msg.raw("skill.class-mismatch",
                    "skill", definition.displayName(),
                    "owner", plugin.classManager().displayOf(definition.classId()),
                    "player_class", plugin.classManager().displayOf(profile.classId())));
        }

        // 2. 已解锁
        if (definition.hasPermissionGate() && !player.hasPermission(definition.permission())) {
            return Result.fail(Msg.raw("skill.locked", "skill", definition.displayName()));
        }

        // 3. 资源足够
        ClassDefinition classDefinition = plugin.config().classOf(profile.classId());
        ResourceDefinition resource = classDefinition == null ? null : classDefinition.resource(definition.resource());
        if (definition.cost() > 0.0D) {
            if (resource == null) {
                return Result.fail(Msg.raw("skill.cast-failed",
                        "reason", "资源 " + definition.resource() + " 未定义"));
            }
            double current = plugin.resources().get(player, resource.id());
            if (current < definition.cost()) {
                return Result.fail(Msg.raw("skill.no-resource",
                        "resource", Text.stripColor(resource.displayName()),
                        "cost", Text.number(definition.cost()),
                        "current", Text.number(current)));
            }
        }

        // 4. 冷却就绪
        long now = System.currentTimeMillis();
        long until = profile.cooldownUntil(definition.id());
        if (until > now) {
            return Result.fail(Msg.raw("skill.cooldown",
                    "time", TimeFormat.humanize(until - now)));
        }

        // 5. 交给 MythicMobs 执行
        if (!plugin.mythic().available()) {
            return Result.fail(Msg.raw("skill.cast-failed", "reason", "MythicMobs 不可用"));
        }
        boolean cast = plugin.mythic().castSkill(player, definition.mythicSkill());
        if (!cast) {
            return Result.fail(Msg.raw("skill.cast-failed",
                    "reason", "MythicMobs 未找到技能 " + definition.mythicSkill()));
        }

        // 成功后才扣资源 + 进冷却
        if (definition.cost() > 0.0D && resource != null) {
            plugin.resources().consume(player, resource.id(), definition.cost());
        }
        profile.cooldownUntil(definition.id(), now + definition.cooldownMillis());
        profile.markDirty();

        if (plugin.config().main().debug()) {
            plugin.getLogger().info("[debug] " + player.getName() + " 施放 " + definition.id()
                    + " → MythicMobs " + definition.mythicSkill() + " = true");
        }
        return Result.ok(null);
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
