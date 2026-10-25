package io.sakurammo.item;

import java.util.Locale;

/**
 * 物品的命中类特殊效果（写在 PDC 里，由 {@link ItemEffectListener} 执行）。
 *
 * @param type            效果类型（lightning / poison / lifesteal / slow / burn）
 * @param chance          触发概率 0~1
 * @param value           效果强度（伤害量 / 吸血比例 / 缓慢等级）
 * @param cooldownSeconds 内置冷却（秒），避免高频触发
 */
public record ItemEffect(Type type, double chance, double value, double cooldownSeconds) {

    public enum Type {
        /** 落雷：额外伤害 + 闪电视觉。 */
        LIGHTNING,
        /** 中毒：目标持续掉血。 */
        POISON,
        /** 吸血：按造成伤害的比例回血。 */
        LIFESTEAL,
        /** 霜冻：目标减速。 */
        SLOW,
        /** 灼烧：目标着火。 */
        BURN;

        public static Type parse(String raw) {
            if (raw == null || raw.isBlank()) {
                return null;
            }
            try {
                return valueOf(raw.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
                return null;
            }
        }
    }

    /** 冷却键（同玩家同效果共用冷却）。 */
    public String cooldownKey() {
        return type.name().toLowerCase(Locale.ROOT);
    }
}
