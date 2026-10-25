package io.sakurammo.item;

import org.bukkit.Material;

import java.util.List;
import java.util.Map;

/**
 * 一件固定属性物品的定义（items/*.yml 的一条）。
 *
 * @param id         物品 id（PDC 标记，玩家改不了）
 * @param display    显示名（不含品质色，渲染时按 rarity 上色）
 * @param material   原版材质
 * @param rarity     稀有度（决定名字颜色与卖出价系数）
 * @param tier       阶位（1/2/3），装备升级链用
 * @param setId      所属套装 id（null = 不属于套装）
 * @param lore       额外描述行（& 色码）
 * @param attributes 原版属性词条：attribute 名 → 数值
 * @param effects    命中类特殊效果
 * @param value      基础价值（金币），卖出按 {@code economy.sell-refund-rate} 回收
 */
public record ItemDefinition(
        String id,
        String display,
        Material material,
        Rarity rarity,
        int tier,
        String setId,
        List<String> lore,
        Map<String, Double> attributes,
        List<ItemEffect> effects,
        double value
) {

    /** 稀有度：决定名字颜色与价值系数。 */
    public enum Rarity {
        COMMON("&f", "普通", 1.0D),
        UNCOMMON("&a", "精良", 1.6D),
        RARE("&9", "稀有", 2.6D),
        EPIC("&5", "史诗", 4.5D),
        LEGENDARY("&6", "传说", 8.0D),
        MYTHIC("&c", "神话", 14.0D);

        private final String color;
        private final String label;
        private final double valueFactor;

        Rarity(String color, String label, double valueFactor) {
            this.color = color;
            this.label = label;
            this.valueFactor = valueFactor;
        }

        public String color() {
            return color;
        }

        public String label() {
            return label;
        }

        public double valueFactor() {
            return valueFactor;
        }

        public static Rarity parse(String raw) {
            if (raw == null || raw.isBlank()) {
                return COMMON;
            }
            try {
                return valueOf(raw.trim().toUpperCase(java.util.Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
                return COMMON;
            }
        }
    }
}
