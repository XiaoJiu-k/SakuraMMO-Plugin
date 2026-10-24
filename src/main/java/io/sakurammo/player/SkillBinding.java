package io.sakurammo.player;

import org.bukkit.Material;

/**
 * 主手物品与技能的绑定关系。按快捷栏槽位（0–8）存。
 */
public record SkillBinding(String skillId, Material material) {
}
