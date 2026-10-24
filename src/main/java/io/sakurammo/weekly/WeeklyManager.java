package io.sakurammo.weekly;

import io.sakurammo.SakuraMMOPlugin;
import io.sakurammo.player.PlayerProfile;
import io.sakurammo.util.Msg;
import io.sakurammo.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.io.File;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.WeekFields;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 周常任务（阶段四模块 B）。
 * <p>
 * <b>重置策略（任务书坑表 2）</b>：不做「周一 4:00 遍历全部玩家文件」的定时重置，
 * 而是使用 <b>Cycle-ID</b>——周期标识 = 「(当前时间 - 4 小时) 所在的 ISO 周」，
 * 玩家上线/触发事件时对比标识，不同则清零进度。周一 04:00 后的第一批触发即完成
 * 重置，零遍历、零瞬时卡顿。
 * <p>
 * 奖励：金币（SakuraEconomy）+ 兑换代币（profile.tokens）+ 制造底材。
 * 赛季接口：{@link #currentSeasonId()}——赛季结束时对代币做清算（预留）。
 */
public final class WeeklyManager {

    private final SakuraMMOPlugin plugin;
    /** 任务 id → 定义。 */
    private final Map<String, WeeklyTask> tasks = new LinkedHashMap<>();

    public record WeeklyTask(String id, String display, Material icon, int goal,
                             double rewardMoney, int rewardToken, String rewardMaterialId, int rewardMaterialAmount) {
    }

    public WeeklyManager(SakuraMMOPlugin plugin) {
        this.plugin = plugin;
        load();
    }

    public void load() {
        tasks.clear();
        File file = new File(plugin.getDataFolder(), "weeklies.yml");
        if (!file.isFile()) {
            plugin.getLogger().warning("weeklies.yml 不存在，周常系统不可用。");
            return;
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection sec = yaml.getConfigurationSection("tasks");
        if (sec != null) {
            for (String id : sec.getKeys(false)) {
                ConfigurationSection s = sec.getConfigurationSection(id);
                if (s == null) {
                    continue;
                }
                String rewardMaterial = s.getString("reward-material", "");
                String materialId = "";
                int materialAmount = 0;
                if (rewardMaterial.contains(":")) {
                    String[] parts = rewardMaterial.split(":");
                    materialId = parts[0].trim().toLowerCase(Locale.ROOT);
                    try {
                        materialAmount = Math.max(1, Integer.parseInt(parts[1].trim()));
                    } catch (NumberFormatException ignored) {
                        materialAmount = 1;
                    }
                }
                tasks.put(id.toLowerCase(Locale.ROOT), new WeeklyTask(
                        id.toLowerCase(Locale.ROOT),
                        s.getString("display", id),
                        io.sakurammo.config.ConfigManager.material(s.getString("icon")),
                        Math.max(1, s.getInt("goal", 1)),
                        Math.max(0.0D, s.getDouble("reward-money", 0.0D)),
                        Math.max(0, s.getInt("reward-token", 0)),
                        materialId, materialAmount));
            }
        }
        plugin.getLogger().info("已加载周常任务 " + tasks.size() + " 个。");
    }

    public Map<String, WeeklyTask> tasks() {
        return tasks;
    }

    public WeeklyTask task(String id) {
        return id == null ? null : tasks.get(id.toLowerCase(Locale.ROOT));
    }

    // ---------------- 周期（Cycle-ID） ----------------

    /** (日期 - 4h) 的 ISO 周 key：周一 04:00 前算上一周。 */
    private static String weekKey(LocalDate date) {
        int year = date.get(WeekFields.ISO.weekBasedYear());
        int week = date.get(WeekFields.ISO.weekOfWeekBasedYear());
        return year + "-W" + String.format(Locale.ROOT, "%02d", week);
    }

    /** 玩家视角的当前周期（处理 04:00 界限：04:00 前用昨天计算）。 */
    public static String cycleForNow() {
        LocalDate today = LocalDate.now();
        if (LocalTime.now().getHour() < 4) {
            today = today.minusDays(1);
        }
        return weekKey(today);
    }

    /**
     * 确保玩家的周常处于当前周期；跨周期则清零（ Cycle-ID 方案的重置点）。
     * 返回 true 表示刚发生重置。
     */
    public boolean ensureCycle(Player player) {
        var profile = plugin.playerData().getOrLoad(player);
        String current = cycleForNow();
        if (current.equals(profile.weeklyCycle())) {
            return false;
        }
        boolean hadOld = !profile.weeklyCycle().isBlank()
                || !profile.weeklyProgress().isEmpty()
                || !profile.weeklyClaimed().isEmpty();
        profile.resetWeekly();
        profile.weeklyCycle(current);
        plugin.playerData().save(profile);
        if (hadOld) {
            plugin.getLogger().info("[weekly] " + player.getName() + " 周常已随周期 " + current + " 重置。");
        }
        return true;
    }

    // ---------------- 进度 ----------------

    /**
     * 推进任务进度（事件挂钩调用）。跨周期先重置；已领取的任务不再累计。
     */
    public void progress(Player player, String taskId, int amount) {
        WeeklyTask task = task(taskId);
        if (task == null || amount <= 0) {
            return;
        }
        ensureCycle(player);
        var profile = plugin.playerData().get(player.getUniqueId());
        if (profile == null) {
            return;
        }
        if (profile.weeklyClaimed().contains(task.id())) {
            return; // 已领取，本周期不再累计
        }
        int before = profile.weeklyProgress().getOrDefault(task.id(), 0);
        if (before >= task.goal()) {
            return; // 已达标未领取，不再累计
        }
        int after = Math.min(task.goal(), before + amount);
        profile.weeklyProgress().put(task.id(), after);
        if (after >= task.goal()) {
            Msg.send(player, "weekly.completed", "task", task.display());
        }
    }

    /** 任务当前进度。 */
    public int progressOf(Player player, String taskId) {
        var profile = plugin.playerData().get(player.getUniqueId());
        return profile == null ? 0 : profile.weeklyProgress().getOrDefault(taskId, 0);
    }

    /** 是否可领取（达标且未领）。 */
    public boolean claimable(Player player, WeeklyTask task) {
        var profile = plugin.playerData().getOrLoad(player);
        return !profile.weeklyClaimed().contains(task.id())
                && progressOf(player, task.id()) >= task.goal();
    }

    /** 领取周常奖励：金币 + 代币 + 底材。 */
    public boolean claim(Player player, WeeklyTask task) {
        ensureCycle(player);
        var profile = plugin.playerData().getOrLoad(player);
        if (profile.weeklyClaimed().contains(task.id())) {
            Msg.send(player, "weekly.already-claimed");
            return false;
        }
        if (progressOf(player, task.id()) < task.goal()) {
            Msg.send(player, "weekly.not-complete",
                    "current", String.valueOf(progressOf(player, task.id())),
                    "goal", String.valueOf(task.goal()));
            return false;
        }
        profile.weeklyClaimed().add(task.id());
        plugin.playerData().save(profile);
        // 奖励发放
        if (task.rewardMoney() > 0) {
            plugin.economy().deposit(player, task.rewardMoney(), "economy.weekly-reward");
        }
        if (task.rewardToken() > 0) {
            profile.tokens(profile.tokens() + task.rewardToken());
            plugin.playerData().save(profile);
        }
        if (!task.rewardMaterialId().isBlank()) {
            ItemStack material = plugin.crafting().createMaterial(task.rewardMaterialId(), task.rewardMaterialAmount());
            if (material != null) {
                player.getInventory().addItem(material).values()
                        .forEach(left -> player.getWorld().dropItemNaturally(player.getLocation(), left));
            }
        }
        Msg.send(player, "weekly.claimed", "task", task.display(),
                "money", Text.number(task.rewardMoney()),
                "token", String.valueOf(task.rewardToken()));
        plugin.audit().log("weekly-claim", player.getName(),
                task.id() + " money=" + task.rewardMoney() + " token=" + task.rewardToken());
        return true;
    }

    // ---------------- 经验与升级（阶段四：职业等级改由原版经验等级承载） ----------------

    /** 升到下一级所需经验（保留常量供显示参考；实际升级由原版经验曲线承载）。 */
    public static long experienceForLevel(int level) {
        return 100L + 50L * Math.max(0, level);
    }

    /** 已获得天赋点总数（Lv.10 起，每 3 级 1 点，60 级封顶 17 点）。 */
    public static int earnedPoints(int level) {
        if (level < 10) {
            return 0;
        }
        return Math.min(17, (Math.min(60, level) - 10) / 3 + 1);
    }

    /** 登录等级同步豁免（一次性，防止 setLevel 迁移重复发点）。 */
    private final java.util.Set<java.util.UUID> levelSyncSuppressed =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    public void suppressNextLevelEvent(Player player) {
        levelSyncSuppressed.add(player.getUniqueId());
    }

    /**
     * 增加经验：直接给<b>原版经验</b>（经验条数字即职业等级，他人可见）。
     * 升级检测与天赋点发放统一在 PlayerLevelChangeEvent（PlayerListener 转发）。
     *
     * @return 原版等级变化数
     */
    public int addExperience(Player player, long amount) {
        if (amount <= 0) {
            return 0;
        }
        PlayerProfile profile = plugin.playerData().getOrLoad(player);
        int before = player.getLevel();
        player.giveExp((int) Math.min(amount, Integer.MAX_VALUE));
        int after = player.getLevel();
        if (after != profile.level()) {
            profile.level(after);
            plugin.playerData().save(profile);
        }
        return after - before;
    }

    /**
     * 原版等级变化（PlayerLevelChangeEvent 转发）：同步档案等级，
     * 按 earnedPoints 差值发放天赋点。登录迁移（suppress）时只对齐不发点。
     */
    public void handleLevelChange(Player player, int before, int after) {
        PlayerProfile profile = plugin.playerData().getOrLoad(player);
        if (levelSyncSuppressed.remove(player.getUniqueId())) {
            profile.level(after);
            plugin.playerData().save(profile);
            return;
        }
        profile.level(after);
        int pointsBefore = earnedPoints(before);
        int pointsAfter = earnedPoints(after);
        if (pointsAfter > pointsBefore) {
            profile.talentPoints(profile.talentPoints() + (pointsAfter - pointsBefore));
            Msg.send(player, "talent.points-gained", "points",
                    String.valueOf(pointsAfter - pointsBefore),
                    "total", String.valueOf(profile.talentPoints()));
        }
        if (after > before) {
            Msg.send(player, "talent.level-up", "level", String.valueOf(after));
        }
        plugin.playerData().save(profile);
    }

    // ---------------- 赛季（预留接口） ----------------

    /** 当前赛季标识（自然月；赛季结算正式启用时替换实现）。 */
    public static String currentSeasonId() {
        return LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM", Locale.ROOT));
    }

    /** 赛季结算钩子（预留）：清空代币、发放赛季奖励。 */
    public void settleSeasonIfChanged(Player player) {
        // v4.0 预留：赛季字段未入库，接口先行
    }
}
