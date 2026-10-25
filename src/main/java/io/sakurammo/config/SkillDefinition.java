package io.sakurammo.config;

import org.bukkit.Material;

/**
 * 技能入口定义（skills.yml 的一条）。
 * <p>
 * v4.1 技能池扩充：
 * <ul>
 *   <li>{@code unlockLevel}：解锁等级（玩家原版等级 ≥ 此值才可施放/绑定，实时判定）</li>
 *   <li>{@code passiveStat}/{@code passiveValue}：PASSIVE 技能的常驻属性加成
 *       （AuraSkills modifier {@code passive_<id>}，解锁后由 PassiveManager 挂载）</li>
 *   <li>{@code passiveSpecial}：特殊被动标记（thorns=受伤反弹 / rage-surge=低血怒气翻倍），
 *       由 CombatListener/ResourceGainEvent 逻辑实现</li>
 * </ul>
 */
public record SkillDefinition(
        String id,
        String displayName,
        String classId,
        String mythicSkill,
        String resource,
        double cost,
        double cooldownSeconds,
        TriggerType trigger,
        String permission,
        Material icon,
        int unlockLevel,
        String passiveStat,
        double passiveValue,
        String passiveSpecial
) {

    public long cooldownMillis() {
        return (long) Math.ceil(Math.max(0.0D, cooldownSeconds) * 1000.0D);
    }

    public boolean hasPermissionGate() {
        return permission != null && !permission.isBlank();
    }

    /** 是否为被动技能（v4.1：PASSIVE 触发 + 有效果定义）。 */
    public boolean isPassive() {
        return trigger == TriggerType.PASSIVE;
    }

    /** 玩家当前等级是否已解锁该技能。 */
    public boolean unlockedAt(int playerLevel) {
        return playerLevel >= unlockLevel;
    }

    /** 菜单图标，null 时由调用方兜底。 */
    public Material iconOrDefault() {
        return icon == null ? Material.PAPER : icon;
    }
}
