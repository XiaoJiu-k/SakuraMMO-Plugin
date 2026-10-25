package io.sakurammo.talent;

import io.sakurammo.SakuraMMOPlugin;
import io.sakurammo.config.SkillDefinition;
import io.sakurammo.hook.AuraSkillsHook;
import io.sakurammo.player.PlayerProfile;
import io.sakurammo.util.Msg;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

/**
 * 被动技能管理（v4.1 任务书模块：每职业 4 个被动，等级自动解锁生效）。
 * <p>
 * 解锁判定<b>实时按等级计算</b>（unlock-level ≤ 玩家原版等级），无需 unlocked-skills
 * 冗余存储——降级/升级自动同步。
 * <p>
 * 生效通道：
 * <ul>
 *   <li>{@code passive-stat} 类 → AuraSkills modifier {@code passive_<技能id>}（唯一聚合入口）</li>
 *   <li>{@code passive-special} 类 → 事件逻辑（thorns=受伤反弹 / rage-surge=低血怒气翻倍），
 *       在 CombatListener / ResourceGainEvent 处按「已解锁」判定</li>
 * </ul>
 * 挂载时机：登录、原版升级（PlayerLevelChangeEvent）、切职业。
 */
public final class PassiveManager {

    /** AuraSkills 里被动修饰符的命名前缀（与 class_/talent_ 互斥）。 */
    public static final String MODIFIER_PREFIX = "passive_";

    private final SakuraMMOPlugin plugin;

    public PassiveManager(SakuraMMOPlugin plugin) {
        this.plugin = plugin;
    }

    /** 被动是否已解锁。 */
    public boolean unlocked(Player player, SkillDefinition skill) {
        return skill.isPassive() && skill.unlockedAt(player.getLevel());
    }

    /**
     * 重挂该玩家全部 stat 类被动 modifier（先清 passive_ 前缀再加）。
     * 登录 / 升级 / 切职业时调用。
     */
    public void applyPassives(Player player) {
        PlayerProfile profile = plugin.playerData().getOrLoad(player);
        AuraSkillsHook hook = plugin.aura();
        if (!hook.available()) {
            return;
        }
        hook.removeModifiersByPrefix(player, MODIFIER_PREFIX);
        if (!profile.hasClass()) {
            return;
        }
        var definition = plugin.config().classOf(profile.classId());
        if (definition == null) {
            return;
        }
        for (String skillId : definition.skills()) {
            SkillDefinition skill = plugin.config().skill(skillId);
            if (skill == null || !skill.isPassive()
                    || skill.passiveStat().isBlank()
                    || !io.sakurammo.config.ConfigManager.VALID_STATS.contains(skill.passiveStat())) {
                continue;
            }
            if (!skill.unlockedAt(player.getLevel())) {
                continue;
            }
            hook.applyNamedModifier(player, MODIFIER_PREFIX + skill.id(),
                    skill.passiveStat(), skill.passiveValue());
        }
    }

    /** 登录/切职业挂载（延迟等 AuraSkills 就绪）。 */
    public void scheduleApplyOnJoin(Player player) {
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (player.isOnline()) {
                applyPassives(player);
            }
        }, 40L);
    }

    /**
     * 升级后的解锁检查：找出本次等级区间内新解锁的被动/主动技能，
     * 挂载被动并发 Title 提示「解锁了新技能」。
     */
    public void handleLevelUp(Player player, int oldLevel, int newLevel) {
        PlayerProfile profile = plugin.playerData().getOrLoad(player);
        applyPassives(player);
        if (!profile.hasClass()) {
            return;
        }
        var definition = plugin.config().classOf(profile.classId());
        if (definition == null) {
            return;
        }
        List<String> newly = new ArrayList<>();
        for (String skillId : definition.skills()) {
            SkillDefinition skill = plugin.config().skill(skillId);
            if (skill != null && skill.unlockLevel() > oldLevel && skill.unlockLevel() <= newLevel) {
                newly.add(skill.displayName());
            }
        }
        if (newly.isEmpty()) {
            return;
        }
        String names = String.join("、", newly);
        player.sendActionBar(LegacyComponentSerializer.legacySection().deserialize(
                io.sakurammo.util.Text.color(Msg.raw("skill.unlock-title", "skills", names))));
        Msg.send(player, "skill.unlock-chat", "skills", names);
    }

    /** 特殊被动判定：玩家是否已解锁指定 special 标记的被动（thorns / rage-surge）。 */
    public boolean hasSpecial(Player player, String special) {
        PlayerProfile profile = plugin.playerData().get(player.getUniqueId());
        if (profile == null || !profile.hasClass()) {
            return false;
        }
        var definition = plugin.config().classOf(profile.classId());
        if (definition == null) {
            return false;
        }
        for (String skillId : definition.skills()) {
            SkillDefinition skill = plugin.config().skill(skillId);
            if (skill != null && skill.isPassive()
                    && special.equalsIgnoreCase(skill.passiveSpecial())
                    && skill.unlockedAt(player.getLevel())) {
                return true;
            }
        }
        return false;
    }

    /** 特殊被动的数值（passive-value）。 */
    public double specialValue(Player player, String special) {
        PlayerProfile profile = plugin.playerData().get(player.getUniqueId());
        if (profile == null || !profile.hasClass()) {
            return 0.0D;
        }
        var definition = plugin.config().classOf(profile.classId());
        if (definition == null) {
            return 0.0D;
        }
        for (String skillId : definition.skills()) {
            SkillDefinition skill = plugin.config().skill(skillId);
            if (skill != null && skill.isPassive()
                    && special.equalsIgnoreCase(skill.passiveSpecial())
                    && skill.unlockedAt(player.getLevel())) {
                return skill.passiveValue();
            }
        }
        return 0.0D;
    }

    /** 解锁提示的 Title 文本（Legacy & 色码）。 */
    public static String unlockTitleLegacy(String names) {
        return Msg.raw("skill.unlock-title", "skills", names);
    }

    @SuppressWarnings("unused")
    private static LegacyComponentSerializer legacy() {
        return LegacyComponentSerializer.legacySection();
    }
}
