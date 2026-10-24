package io.sakurammo.config;

/**
 * 技能入口定义（skills.yml 的一条）。
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
        String permission
) {

    public long cooldownMillis() {
        return (long) Math.ceil(Math.max(0.0D, cooldownSeconds) * 1000.0D);
    }

    public boolean hasPermissionGate() {
        return permission != null && !permission.isBlank();
    }
}
