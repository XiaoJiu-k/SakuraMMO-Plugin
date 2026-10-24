package io.sakurammo.talent;

import io.sakurammo.SakuraMMOPlugin;
import io.sakurammo.hook.AuraSkillsHook;
import io.sakurammo.player.PlayerProfile;
import io.sakurammo.util.Msg;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 天赋树：配置解析（talents.yml）、加点/减点/洗点、经验与升级、数值生效。
 * <p>
 * 生效通道（任务书 §1 模块 A）：
 * <ul>
 *   <li>stat 类 → AuraSkills modifier {@code talent_<节点id>}（唯一聚合入口仍是 AuraSkills，不重复计算）</li>
 *   <li>cooldown / cost → SkillService 施法时实时读（冷却×(1-缩减)、消耗×(1-降低)）</li>
 *   <li>resource-max / resource-regen → ResourceService 的有效上限/回复</li>
 *   <li>skill-damage → 施法后 600ms 窗口内的玩家伤害事件按倍率放大（CombatListener），
 *       并通过 PAPI {@code %sakurammo_talent_mult_<skillid>%} 暴露给 MythicMobs 高级用法</li>
 * </ul>
 */
public final class TalentManager {

    /** AuraSkills 里天赋修饰符的命名前缀（与 class_ 前缀互斥，避免互相清除）。 */
    public static final String MODIFIER_PREFIX = "talent_";
    /** 技能伤害倍率的生效窗口（毫秒）。 */
    public static final long CAST_WINDOW_MS = 600L;

    private final SakuraMMOPlugin plugin;
    private double resetCost = 500.0D;
    /** classId → 分支 A（节点保序）。 */
    private final Map<String, List<TalentNode>> branchesA = new LinkedHashMap<>();
    private final Map<String, List<TalentNode>> branchesB = new LinkedHashMap<>();
    private final Map<String, TalentNode> byId = new LinkedHashMap<>();
    /** 施法窗口：玩家 → (技能id → 窗口截止时间)。 */
    private final Map<java.util.UUID, Map<String, Long>> activeCasts = new ConcurrentHashMap<>();

    public TalentManager(SakuraMMOPlugin plugin) {
        this.plugin = plugin;
        load();
    }

    // ---------------- 配置 ----------------

    public void load() {
        branchesA.clear();
        branchesB.clear();
        byId.clear();
        File file = new File(plugin.getDataFolder(), "talents.yml");
        if (!file.isFile()) {
            plugin.getLogger().warning("talents.yml 不存在，天赋系统不可用。");
            return;
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        resetCost = Math.max(0.0D, yaml.getDouble("reset-cost", 500.0D));
        for (String classId : yaml.getKeys(false)) {
            if (classId.equalsIgnoreCase("reset-cost")) {
                continue;
            }
            ConfigurationSection root = yaml.getConfigurationSection(classId);
            if (root == null) {
                continue;
            }
            branchesA.put(classId, parseBranch(root.getConfigurationSection("branch-a"), classId));
            branchesB.put(classId, parseBranch(root.getConfigurationSection("branch-b"), classId));
        }
        int total = byId.size();
        plugin.getLogger().info("已加载天赋树：" + branchesA.size() + " 职业 / " + total + " 节点。");
    }

    private List<TalentNode> parseBranch(ConfigurationSection branch, String classId) {
        List<TalentNode> nodes = new ArrayList<>();
        if (branch == null) {
            return nodes;
        }
        ConfigurationSection nodesSec = branch.getConfigurationSection("nodes");
        if (nodesSec == null) {
            return nodes;
        }
        for (String id : nodesSec.getKeys(false)) {
            ConfigurationSection sec = nodesSec.getConfigurationSection(id);
            if (sec == null) {
                continue;
            }
            String nodeId = id.toLowerCase(Locale.ROOT);
            ConfigurationSection req = sec.getConfigurationSection("requires");
            String statId = sec.getString("stat", "");
            TalentNode node = new TalentNode(
                    nodeId,
                    sec.getString("name", id),
                    io.sakurammo.config.ConfigManager.material(sec.getString("icon")),
                    Math.max(1, sec.getInt("max", 1)),
                    req == null ? null : req.getString("node"),
                    req == null ? 0 : Math.max(0, req.getInt("points", 0)),
                    kindOf(sec, statId),
                    targetOf(sec, statId),
                    perLevelOf(sec),
                    sec.getString("desc", ""));
            nodes.add(node);
            byId.put(nodeId, node);
            if (node.kind() == TalentNode.Kind.STAT
                    && !io.sakurammo.config.ConfigManager.VALID_STATS.contains(node.target())) {
                plugin.getLogger().warning("天赋节点 " + nodeId + " 的属性 " + node.target()
                        + " 不是 AuraSkills 支持的 9 项之一，该节点不会生效。");
            }
        }
        return nodes;
    }

    private TalentNode.Kind kindOf(ConfigurationSection sec, String statId) {
        if (statId != null && !statId.isBlank()) {
            return TalentNode.Kind.STAT;
        }
        if (sec.contains("cooldown")) {
            return TalentNode.Kind.COOLDOWN;
        }
        if (sec.contains("cost")) {
            return TalentNode.Kind.COST;
        }
        if (sec.contains("resource-max")) {
            return TalentNode.Kind.RESOURCE_MAX;
        }
        if (sec.contains("resource-regen")) {
            return TalentNode.Kind.RESOURCE_REGEN;
        }
        if (sec.contains("skill-damage")) {
            return TalentNode.Kind.SKILL_DAMAGE;
        }
        return TalentNode.Kind.STAT; // 无效果配置的节点按 STAT 空目标处理（不会生效）
    }

    private String targetOf(ConfigurationSection sec, String statId) {
        if (statId != null && !statId.isBlank()) {
            return statId.toLowerCase(Locale.ROOT);
        }
        for (String key : List.of("resource-max", "resource-regen", "skill-damage")) {
            if (sec.contains(key)) {
                return sec.getString(key, "").toLowerCase(Locale.ROOT);
            }
        }
        return "";
    }

    private double perLevelOf(ConfigurationSection sec) {
        if (sec.contains("per-level")) {
            return Math.max(0.0D, sec.getDouble("per-level", 0.0D));
        }
        // cooldown / cost 节点：值直接写在键上（cooldown: 0.05）
        for (String key : List.of("cooldown", "cost")) {
            if (sec.contains(key)) {
                return Math.max(0.0D, sec.getDouble(key, 0.0D));
            }
        }
        return 0.0D;
    }

    public double resetCost() {
        return resetCost;
    }

    public List<TalentNode> branchOf(String classId, boolean branchA) {
        return (branchA ? branchesA : branchesB).getOrDefault(classId, List.of());
    }

    public TalentNode node(String nodeId) {
        return nodeId == null ? null : byId.get(nodeId.toLowerCase(Locale.ROOT));
    }

    /** 节点当前生效值（等级 × 每级值）。 */
    public double nodeValue(PlayerProfile profile, TalentNode node) {
        Integer level = profile.talents().get(node.id());
        return level == null ? 0.0D : level * node.perLevel();
    }

    // ---------------- 点数 ----------------

    /** 已获得天赋点总数（Lv.10 起，每 3 级 1 点，60 级封顶 17 点）。 */
    public static int earnedPoints(int level) {
        if (level < 10) {
            return 0;
        }
        return Math.min(17, (Math.min(60, level) - 10) / 3 + 1);
    }

    /** 节点当前等级。 */
    public int levelOf(PlayerProfile profile, TalentNode node) {
        return profile.talents().getOrDefault(node.id(), 0);
    }

    /** 前置是否满足。 */
    public boolean requirementMet(PlayerProfile profile, TalentNode node) {
        if (node.requiresNode() == null || node.requiresNode().isBlank()) {
            return true;
        }
        TalentNode required = node(node.requiresNode());
        return required != null && levelOf(profile, required) >= node.requiresPoints();
    }

    /** 加 1 点。失败返回提示键（party 风格的原始提示由调用方组装）。 */
    public boolean addPoint(Player player, TalentNode node) {
        PlayerProfile profile = plugin.playerData().getOrLoad(player);
        if (!profile.hasClass()) {
            Msg.send(player, "class.none-selected");
            return false;
        }
        if (!node.id().startsWith(profile.classId() + "_")) {
            Msg.send(player, "talent.wrong-class");
            return false;
        }
        if (profile.talentPoints() <= 0) {
            Msg.send(player, "talent.no-points");
            return false;
        }
        if (levelOf(profile, node) >= node.max()) {
            Msg.send(player, "talent.maxed", "name", node.name());
            return false;
        }
        if (!requirementMet(profile, node)) {
            TalentNode required = node(node.requiresNode());
            Msg.send(player, "talent.requirement", "name",
                    required == null ? node.requiresNode() : required.name(),
                    "points", String.valueOf(node.requiresPoints()));
            return false;
        }
        profile.talents().merge(node.id(), 1, Integer::sum);
        profile.talentPoints(profile.talentPoints() - 1);
        applyTalentModifiers(player);
        plugin.playerData().save(profile);
        Msg.send(player, "talent.invested", "name", node.name(),
                "level", String.valueOf(levelOf(profile, node)) + "/" + node.max());
        return true;
    }

    /** 减 1 点（免费微调）：返还点数，且不得破坏其他已投节点的前置。 */
    public boolean removePoint(Player player, TalentNode node) {
        PlayerProfile profile = plugin.playerData().getOrLoad(player);
        int current = levelOf(profile, node);
        if (current <= 0) {
            Msg.send(player, "talent.empty", "name", node.name());
            return false;
        }
        // 模拟减点后检查所有已投节点的前置
        profile.talents().put(node.id(), current - 1);
        for (Map.Entry<String, Integer> entry : profile.talents().entrySet()) {
            if (entry.getValue() <= 0) {
                continue;
            }
            TalentNode other = node(entry.getKey());
            if (other != null && !requirementMet(profile, other)) {
                profile.talents().put(node.id(), current); // 还原
                Msg.send(player, "talent.blocked-by-dependent", "name", node.name());
                return false;
            }
        }
        if (current - 1 == 0) {
            profile.talents().remove(node.id());
        }
        profile.talentPoints(profile.talentPoints() + 1);
        applyTalentModifiers(player);
        plugin.playerData().save(profile);
        Msg.send(player, "talent.refunded", "name", node.name());
        return true;
    }

    /** 洗点（花费金币）：清空全部已投点数并全额返还。 */
    public boolean reset(Player player) {
        PlayerProfile profile = plugin.playerData().getOrLoad(player);
        int invested = profile.investedTalentPoints();
        if (invested == 0 && profile.talentPoints() == 0) {
            Msg.send(player, "talent.nothing-to-reset");
            return false;
        }
        if (resetCost > 0.0D) {
            if (!plugin.economy().available()) {
                Msg.send(player, "talent.reset-no-economy");
                return false;
            }
            if (!plugin.economy().withdraw(player, resetCost)) {
                Msg.send(player, "talent.reset-no-money",
                        "cost", io.sakurammo.util.Text.number(resetCost));
                return false;
            }
        }
        profile.talents().clear();
        profile.talentPoints(earnedPoints(profile.level()));
        applyTalentModifiers(player);
        plugin.playerData().save(profile);
        Msg.send(player, "talent.reset-done", "points", String.valueOf(profile.talentPoints()),
                "cost", io.sakurammo.util.Text.number(resetCost));
        return true;
    }

    // ---------------- 经验与升级 ----------------

    /** 升到下一级所需经验（线性：100 + 50×当前等级）。 */
    public static long experienceForLevel(int level) {
        return 100L + 50L * Math.max(0, level);
    }

    /**
     * 增加经验并处理升级。返回升到的等级变化数。
     */
    public int addExperience(Player player, long amount) {
        if (amount <= 0) {
            return 0;
        }
        PlayerProfile profile = plugin.playerData().getOrLoad(player);
        int before = profile.level();
        long exp = profile.experience() + amount;
        while (profile.level() < 60) {
            long need = experienceForLevel(profile.level());
            if (exp < need) {
                break;
            }
            exp -= need;
            profile.level(profile.level() + 1);
        }
        profile.experience(Math.min(exp, Long.MAX_VALUE));
        int gained = profile.level() - before;
        if (gained > 0) {
            // 每 3 级（Lv.10 起）1 点：按新旧等级区间内的点数差发放
            int pointsBefore = earnedPoints(before);
            int pointsAfter = earnedPoints(profile.level());
            if (pointsAfter > pointsBefore) {
                profile.talentPoints(profile.talentPoints() + (pointsAfter - pointsBefore));
                Msg.send(player, "talent.points-gained", "points", String.valueOf(pointsAfter - pointsBefore),
                        "total", String.valueOf(profile.talentPoints()));
            }
            Msg.send(player, "talent.level-up", "level", String.valueOf(profile.level()));
        }
        return gained;
    }

    // ---------------- 数值生效 ----------------

    /**
     * 重挂该玩家全部 STAT 类天赋 modifier（先清 talent_ 前缀再加）。
     * 登录 / 加点 / 减点 / 洗点都会调用。
     */
    public void applyTalentModifiers(Player player) {
        PlayerProfile profile = plugin.playerData().getOrLoad(player);
        AuraSkillsHook hook = plugin.aura();
        if (!hook.available()) {
            return;
        }
        hook.removeModifiersByPrefix(player, MODIFIER_PREFIX);
        if (!profile.hasClass()) {
            return;
        }
        for (TalentNode node : branchOf(profile.classId(), true)) {
            applyIfStat(player, profile, node);
        }
        for (TalentNode node : branchOf(profile.classId(), false)) {
            applyIfStat(player, profile, node);
        }
    }

    private void applyIfStat(Player player, PlayerProfile profile, TalentNode node) {
        if (node.kind() != TalentNode.Kind.STAT || node.target().isBlank()
                || !io.sakurammo.config.ConfigManager.VALID_STATS.contains(node.target())) {
            return;
        }
        int level = levelOf(profile, node);
        if (level <= 0) {
            return;
        }
        plugin.aura().applyNamedModifier(player, MODIFIER_PREFIX + node.id(),
                node.target(), level * node.perLevel());
    }

    /** 登录重挂（等 AuraSkills 用户就绪后调用，见 ClassManager 的时序）。 */
    public void scheduleApplyOnJoin(Player player) {
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (player.isOnline()) {
                applyTalentModifiers(player);
            }
        }, 40L);
    }

    // ---------------- 聚合查询（SkillService / ResourceService / PAPI 用） ----------------

    /** 全局冷却缩减（0~0.6 封顶）。 */
    public double cooldownReduction(PlayerProfile profile) {
        return Math.min(0.6D, sumEffects(profile, TalentNode.Kind.COOLDOWN));
    }

    /** 全局资源消耗降低（0~0.6 封顶）。 */
    public double costReduction(PlayerProfile profile) {
        return Math.min(0.6D, sumEffects(profile, TalentNode.Kind.COST));
    }

    /** 职业资源上限加成。 */
    public double resourceMaxBonus(PlayerProfile profile, String resourceId) {
        return sumTargeted(profile, TalentNode.Kind.RESOURCE_MAX, resourceId);
    }

    /** 职业资源回复加成（每秒）。 */
    public double resourceRegenBonus(PlayerProfile profile, String resourceId) {
        return sumTargeted(profile, TalentNode.Kind.RESOURCE_REGEN, resourceId);
    }

    /** 技能伤害倍率（1.0 = 无加成）。 */
    public double skillMultiplier(PlayerProfile profile, String skillId) {
        return 1.0D + sumTargeted(profile, TalentNode.Kind.SKILL_DAMAGE, skillId);
    }

    private double sumEffects(PlayerProfile profile, TalentNode.Kind kind) {
        return sumTargeted(profile, kind, null);
    }

    private double sumTargeted(PlayerProfile profile, TalentNode.Kind kind, String target) {
        if (profile == null || !profile.hasClass()) {
            return 0.0D;
        }
        double sum = 0.0D;
        for (List<TalentNode> branch : List.of(branchOf(profile.classId(), true),
                branchOf(profile.classId(), false))) {
            for (TalentNode node : branch) {
                if (node.kind() != kind) {
                    continue;
                }
                if (target != null && !node.target().equals(target.toLowerCase(Locale.ROOT))) {
                    continue;
                }
                sum += levelOf(profile, node) * node.perLevel();
            }
        }
        return sum;
    }

    // ---------------- 施法窗口（技能伤害倍率） ----------------

    /** 施法成功后调用：600ms 内该玩家的伤害事件按 skillMultiplier 放大。 */
    public void markCast(Player player, String skillId) {
        double mult = skillMultiplier(plugin.playerData().get(player.getUniqueId()), skillId);
        if (plugin.config().main().debug()) {
            plugin.getLogger().info("[debug] markCast " + player.getName() + " " + skillId
                    + " mult=" + mult);
        }
        if (Math.abs(mult - 1.0D) < 1.0E-9D) {
            return; // 无加成不记窗口
        }
        activeCasts.computeIfAbsent(player.getUniqueId(), k -> new ConcurrentHashMap<>())
                .put(skillId, System.currentTimeMillis() + CAST_WINDOW_MS);
    }

    /** 窗口内该玩家的最大技能倍率（无则 1.0）。 */
    public double activeMultiplier(Player player) {
        Map<String, Long> casts = activeCasts.get(player.getUniqueId());
        if (casts == null || casts.isEmpty()) {
            return 1.0D;
        }
        long now = System.currentTimeMillis();
        double max = 1.0D;
        for (Map.Entry<String, Long> entry : casts.entrySet()) {
            if (plugin.config().main().debug()) {
                plugin.getLogger().info("[debug] activeMultiplier " + player.getName()
                        + " 查询 " + entry.getKey() + " 剩余 " + (entry.getValue() - now) + "ms");
            }
            if (entry.getValue() > now) {
                var profileNow = plugin.playerData().get(player.getUniqueId());
                double mult = skillMultiplier(profileNow, entry.getKey());
                if (plugin.config().main().debug()) {
                    plugin.getLogger().info("[debug] activeMultiplier 计算 " + entry.getKey()
                            + " → mult=" + mult + " profile=" + (profileNow == null ? "NULL"
                            : profileNow.classId() + " talents=" + profileNow.talents()));
                }
                max = Math.max(max, mult);
            }
        }
        casts.values().removeIf(expire -> expire <= now);
        return max;
    }

    public void clearCasts(java.util.UUID uuid) {
        activeCasts.remove(uuid);
    }
}
