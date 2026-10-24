package io.sakurammo.config;

/**
 * config.yml 的强类型视图。
 */
public record MainConfig(
        String language,
        int saveIntervalSeconds,
        int switchCooldownHours,
        double switchCost,
        boolean requireClassPermission,
        boolean hudEnabled,
        int hudIntervalTicks,
        String hudFormat,
        int resourceTickIntervalTicks,
        boolean menuEnabled,
        boolean menuInterceptSwap,
        boolean menuShiftBypass,
        String menuFiller,
        boolean debug
) {

    public static MainConfig defaults() {
        return new MainConfig("zh_CN", 300, 24, 0.0D, false,
                true, 10, "&b{display} &f{current}&7/&f{max}", 10,
                true, true, true, "GRAY_STAINED_GLASS_PANE", false);
    }

    public long switchCooldownMillis() {
        return Math.max(0, switchCooldownHours) * 3_600_000L;
    }
}
