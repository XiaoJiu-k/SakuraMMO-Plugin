package io.sakurammo.config;

/**
 * 单个资源的定义（classes/&lt;id&gt;.yml 的 resources 段）。
 *
 * @param id             资源 id，如 rage / mana
 * @param displayName    显示名（含颜色码）
 * @param max            上限
 * @param initial        初始值（选职业/切职业时重置到这个值）
 * @param regenPerSecond 每秒自然恢复量
 * @param onDamageTaken  受到伤害时增加量
 * @param onAttack       造成伤害时增加量
 * @param onKill         击杀时增加量
 * @param reserved       P1 预留资源：不自然恢复、不上 HUD，但读写接口可用
 */
public record ResourceDefinition(
        String id,
        String displayName,
        double max,
        double initial,
        double regenPerSecond,
        double onDamageTaken,
        double onAttack,
        double onKill,
        boolean reserved
) {
}
