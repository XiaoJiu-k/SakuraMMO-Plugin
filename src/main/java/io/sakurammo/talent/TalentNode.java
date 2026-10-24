package io.sakurammo.talent;

import org.bukkit.Material;

/**
 * 天赋节点（talents.yml 的强类型视图）。
 * 一个节点只有一种效果，由 {@link Kind} 决定生效通道：
 * STAT→AuraSkills modifier；COOLDOWN/COST→SkillService；RESOURCE_*→ResourceService；SKILL_DAMAGE→施法窗口。
 */
public record TalentNode(
        String id,
        String name,
        Material icon,
        int max,
        String requiresNode,
        int requiresPoints,
        Kind kind,
        String target,
        double perLevel,
        String desc
) {

    public enum Kind {
        STAT, COOLDOWN, COST, RESOURCE_MAX, RESOURCE_REGEN, SKILL_DAMAGE
    }
}
