package io.sakurammo.config;

/**
 * 技能的触发方式。
 */
public enum TriggerType {

    /** 右键（空气或方块都算） */
    RIGHT_CLICK,
    RIGHT_CLICK_AIR,
    RIGHT_CLICK_BLOCK,
    LEFT_CLICK,
    /** 被动：不由右键触发，P0 不实现被动逻辑 */
    PASSIVE;

    public static TriggerType parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return RIGHT_CLICK;
        }
        String key = raw.trim().toUpperCase(java.util.Locale.ROOT);
        for (TriggerType type : values()) {
            if (type.name().equals(key)) {
                return type;
            }
        }
        return RIGHT_CLICK;
    }

    public boolean matches(boolean rightClick, boolean leftClick, boolean onBlock) {
        return switch (this) {
            case RIGHT_CLICK -> rightClick;
            case RIGHT_CLICK_AIR -> rightClick && !onBlock;
            case RIGHT_CLICK_BLOCK -> rightClick && onBlock;
            case LEFT_CLICK -> leftClick;
            case PASSIVE -> false;
        };
    }

    public boolean bindable() {
        return this != PASSIVE;
    }
}
