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
        boolean keybindShiftHotbarEnabled,
        int partyMaxMembers,
        int partyOfflineKickMinutes,
        int dungeonCleanupDelaySeconds,
        boolean dungeonAutoTemplate,
        double killMoneyMin,
        double killMoneyMax,
        double bossMoneyMin,
        double bossMoneyMax,
        double sellRefundRate,
        int xpPerKill,
        int maxConcurrentInstances,
        int raidMaxMembers,
        int raidClearTokens,
        double adminGiveallMoney,
        boolean infiniteDurability,
        boolean repairOnJoin,
        boolean debug
) {

    public static MainConfig defaults() {
        return new MainConfig("zh_CN", 300, 24, 0.0D, false,
                true, 10, "{display} &f{current}&7/&f{max} &8| &c❤{health}&7/&f{max_health}{skill_cd}", 10,
                true, true, true, "GRAY_STAINED_GLASS_PANE", true,
                5, 5, 5, true,
                2.0D, 6.0D, 60.0D, 150.0D, 0.25D, 15, 3, 20, 5, 100.0D, true, true, false);
    }

    public long switchCooldownMillis() {
        return Math.max(0, switchCooldownHours) * 3_600_000L;
    }
}
