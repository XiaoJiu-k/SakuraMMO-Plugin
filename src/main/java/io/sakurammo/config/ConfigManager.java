package io.sakurammo.config;

import io.sakurammo.util.Msg;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Level;

/**
 * 统一配置入口：config.yml / messages_&lt;lang&gt;.yml / classes/*.yml / skills.yml
 */
public final class ConfigManager {

    /** 固定的 7 个职业 id（任务书 §3 P0-1） */
    public static final List<String> DEFAULT_CLASS_IDS = List.of(
            "ironwarden", "paladin", "priest", "assassin", "ranger", "elementalist", "warlock");

    /** AuraSkills 真实存在的 9 项属性。写别的会在这里被拦下并 WARN。 */
    public static final List<String> VALID_STATS = List.of(
            "strength", "health", "regeneration", "luck", "wisdom",
            "toughness", "crit_chance", "crit_damage", "speed");

    /** 固定属性物品定义文件（首次启动释放到 items/）。 */
    public static final List<String> DEFAULT_ITEM_FILES =
            List.of("materials.yml", "equipment.yml", "sets.yml");

    /** 内置副本 id（首次启动释放到 dungeons/，模板引导生成后会按实测坐标重写）。 */
    public static final List<String> DEFAULT_DUNGEON_IDS = List.of("blackiron");

    private final JavaPlugin plugin;

    private MainConfig main = MainConfig.defaults();
    private final Map<String, ClassDefinition> classes = new LinkedHashMap<>();
    private final Map<String, SkillDefinition> skills = new LinkedHashMap<>();

    public ConfigManager(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void loadAll() {
        plugin.saveDefaultConfig();
        plugin.reloadConfig();
        main = readMain(plugin.getConfig());

        saveIfAbsent("skills.yml");
        saveIfAbsent("messages_" + main.language() + ".yml");
        for (String id : DEFAULT_CLASS_IDS) {
            saveIfAbsent("classes/" + id + ".yml");
        }
        for (String id : DEFAULT_DUNGEON_IDS) {
            saveIfAbsent("dungeons/" + id + ".yml");
        }
        saveIfAbsent("talents.yml");
        saveIfAbsent("recipes.yml");
        saveIfAbsent("weeklies.yml");
        saveIfAbsent("upgrade.yml");
        for (String file : DEFAULT_ITEM_FILES) {
            saveIfAbsent("items/" + file);
        }

        String messagePath = "messages_" + main.language() + ".yml";
        mergeMessageDefaults(messagePath);
        Msg.load(new File(plugin.getDataFolder(), messagePath), plugin.getLogger());
        classes.clear();
        skills.clear();
        loadClasses();
        loadSkills();
        validateCrossReferences();
    }

    private MainConfig readMain(org.bukkit.configuration.file.FileConfiguration cfg) {
        MainConfig d = MainConfig.defaults();
        return new MainConfig(
                cfg.getString("language", d.language()),
                Math.max(10, cfg.getInt("save-interval-seconds", d.saveIntervalSeconds())),
                Math.max(0, cfg.getInt("switch-cooldown-hours", d.switchCooldownHours())),
                Math.max(0.0D, cfg.getDouble("switch-cost", d.switchCost())),
                cfg.getBoolean("require-class-permission", d.requireClassPermission()),
                cfg.getBoolean("hud.enabled", d.hudEnabled()),
                Math.max(1, cfg.getInt("hud.update-interval-ticks", d.hudIntervalTicks())),
                cfg.getString("hud.format", d.hudFormat()),
                Math.max(1, cfg.getInt("resource.tick-interval-ticks", d.resourceTickIntervalTicks())),
                cfg.getBoolean("menu.enabled", d.menuEnabled()),
                cfg.getBoolean("menu.intercept-swap", d.menuInterceptSwap()),
                cfg.getBoolean("menu.shift-bypass", d.menuShiftBypass()),
                cfg.getString("menu.filler", d.menuFiller()),
                cfg.getBoolean("keybinds.shift-hotbar.enabled", d.keybindShiftHotbarEnabled()),
                Math.max(2, cfg.getInt("party.max-members", d.partyMaxMembers())),
                Math.max(0, cfg.getInt("party.offline-kick-minutes", d.partyOfflineKickMinutes())),
                Math.max(1, cfg.getInt("dungeon.cleanup-delay-seconds", d.dungeonCleanupDelaySeconds())),
                cfg.getBoolean("dungeon.auto-create-template", d.dungeonAutoTemplate()),
                Math.max(0.0D, cfg.getDouble("economy.kill-money-min", d.killMoneyMin())),
                Math.max(0.0D, cfg.getDouble("economy.kill-money-max", d.killMoneyMax())),
                Math.max(0.0D, cfg.getDouble("economy.boss-money-min", d.bossMoneyMin())),
                Math.max(0.0D, cfg.getDouble("economy.boss-money-max", d.bossMoneyMax())),
                Math.min(1.0D, Math.max(0.0D, cfg.getDouble("economy.sell-refund-rate", d.sellRefundRate()))),
                Math.max(0, cfg.getInt("economy.xp-per-kill", d.xpPerKill())),
                Math.max(0, cfg.getInt("dungeon.max-concurrent", d.maxConcurrentInstances())),
                Math.max(10, cfg.getInt("raid.max-members", d.raidMaxMembers())),
                Math.max(0, cfg.getInt("raid.clear-tokens", d.raidClearTokens())),
                Math.max(0.0D, cfg.getDouble("admin.giveall-money", d.adminGiveallMoney())),
                cfg.getBoolean("items.infinite-durability", d.infiniteDurability()),
                cfg.getBoolean("items.repair-on-join", d.repairOnJoin()),
                cfg.getBoolean("debug", d.debug())
        );
    }

    private void saveIfAbsent(String path) {
        File target = new File(plugin.getDataFolder(), path);
        if (target.isFile()) {
            return;
        }
        try {
            plugin.saveResource(path, false);
        } catch (IllegalArgumentException ex) {
            plugin.getLogger().log(Level.WARNING, "内置资源缺失，无法写出默认文件：" + path, ex);
        }
    }

    /**
     * 把 jar 内默认语言文件里「用户文件还没有的键」补进去。
     * 这样插件升级新增消息时，不需要用户手动删文件重建。
     */
    private void mergeMessageDefaults(String messagePath) {
        File userFile = new File(plugin.getDataFolder(), messagePath);
        if (!userFile.isFile()) {
            return;
        }
        try (java.io.InputStream in = plugin.getResource(messagePath)) {
            if (in == null) {
                return;
            }
            YamlConfiguration defaults = YamlConfiguration.loadConfiguration(
                    new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8));
            YamlConfiguration user = YamlConfiguration.loadConfiguration(userFile);
            List<String> added = new ArrayList<>();
            for (String key : defaults.getKeys(true)) {
                if (defaults.isConfigurationSection(key) || user.contains(key)) {
                    continue;
                }
                user.set(key, defaults.get(key));
                added.add(key);
            }
            if (!added.isEmpty()) {
                user.save(userFile);
                plugin.getLogger().info("语言文件补齐 " + added.size() + " 条新条目：" + added);
            }
        } catch (Exception ex) {
            plugin.getLogger().log(Level.WARNING, "补齐语言文件失败", ex);
        }
    }

    private void loadClasses() {        File dir = new File(plugin.getDataFolder(), "classes");
        if (!dir.isDirectory() && !dir.mkdirs()) {
            plugin.getLogger().severe("无法创建 classes 目录：" + dir.getAbsolutePath());
            return;
        }
        File[] files = dir.listFiles((d, name) -> name.toLowerCase(Locale.ROOT).endsWith(".yml"));
        if (files == null) {
            return;
        }
        for (File file : files) {
            try {
                ClassDefinition def = readClass(file);
                if (def != null) {
                    classes.put(def.id(), def);
                }
            } catch (Exception ex) {
                plugin.getLogger().log(Level.SEVERE, "职业文件解析失败：" + file.getName(), ex);
            }
        }
        plugin.getLogger().info("已加载 " + classes.size() + " 个职业：" + classes.keySet());
    }

    private ClassDefinition readClass(File file) {
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        String fallbackId = file.getName().substring(0, file.getName().length() - 4);
        String id = yaml.getString("id", fallbackId).toLowerCase(Locale.ROOT);

        Map<String, Double> stats = new LinkedHashMap<>();
        ConfigurationSection statsSection = yaml.getConfigurationSection("stats");
        if (statsSection != null) {
            for (String key : statsSection.getKeys(false)) {
                String stat = key.toLowerCase(Locale.ROOT);
                if (!VALID_STATS.contains(stat)) {
                    plugin.getLogger().warning("职业 " + id + " 的属性 " + key
                            + " 不是 AuraSkills 支持的 9 项之一，已忽略。");
                    continue;
                }
                stats.put(stat, statsSection.getDouble(key));
            }
        }

        Map<String, ResourceDefinition> resources = new LinkedHashMap<>();
        ConfigurationSection resSection = yaml.getConfigurationSection("resources");
        if (resSection != null) {
            for (String key : resSection.getKeys(false)) {
                ConfigurationSection res = resSection.getConfigurationSection(key);
                if (res == null) {
                    continue;
                }
                double max = Math.max(1.0D, res.getDouble("max", 100.0D));
                resources.put(key.toLowerCase(Locale.ROOT), new ResourceDefinition(
                        key.toLowerCase(Locale.ROOT),
                        res.getString("display-name", key),
                        max,
                        clamp(res.getDouble("initial", max), 0.0D, max),
                        Math.max(0.0D, res.getDouble("regen-per-second", 0.0D)),
                        Math.max(0.0D, res.getDouble("on-damage-taken", 0.0D)),
                        Math.max(0.0D, res.getDouble("on-attack", 0.0D)),
                        Math.max(0.0D, res.getDouble("on-kill", 0.0D)),
                        res.getBoolean("reserved", false)
                ));
            }
        }
        if (resources.isEmpty()) {
            plugin.getLogger().warning("职业 " + id + " 没有定义任何 resources，资源系统对它不可用。");
        }

        List<String> skillIds = new ArrayList<>(yaml.getStringList("skills"));
        List<String> allowed = new ArrayList<>(yaml.getStringList("equipment.allowed"));
        List<String> forbidden = new ArrayList<>(yaml.getStringList("equipment.forbidden"));

        return new ClassDefinition(
                id,
                yaml.getString("display-name", id),
                yaml.getString("role", ""),
                yaml.getString("description", ""),
                material(yaml.getString("icon")),
                stats,
                resources,
                skillIds,
                allowed,
                forbidden
        );
    }

    private void loadSkills() {
        File file = new File(plugin.getDataFolder(), "skills.yml");
        if (!file.isFile()) {
            plugin.getLogger().warning("skills.yml 不存在，技能入口为空。");
            return;
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        for (String id : yaml.getKeys(false)) {
            ConfigurationSection sec = yaml.getConfigurationSection(id);
            if (sec == null) {
                continue;
            }
            skills.put(id.toLowerCase(Locale.ROOT), new SkillDefinition(
                    id.toLowerCase(Locale.ROOT),
                    sec.getString("display-name", id),
                    sec.getString("class", "").toLowerCase(Locale.ROOT),
                    sec.getString("mythic-skill", id),
                    sec.getString("resource", "").toLowerCase(Locale.ROOT),
                    Math.max(0.0D, sec.getDouble("cost", 0.0D)),
                    Math.max(0.0D, sec.getDouble("cooldown", 0.0D)),
                    TriggerType.parse(sec.getString("trigger", "RIGHT_CLICK")),
                    sec.getString("unlock.permission", ""),
                    material(sec.getString("icon")),
                    Math.max(1, sec.getInt("unlock-level", 1)),
                    sec.getString("passive-stat", "").toLowerCase(Locale.ROOT),
                    Math.max(0.0D, sec.getDouble("passive-value", 0.0D)),
                    sec.getString("passive-special", "").toLowerCase(Locale.ROOT)
            ));
        }
        plugin.getLogger().info("已加载 " + skills.size() + " 个技能入口定义。");
    }

    private void validateCrossReferences() {
        for (SkillDefinition skill : skills.values()) {
            if (!classes.containsKey(skill.classId())) {
                plugin.getLogger().warning("技能 " + skill.id() + " 指向不存在的职业：" + skill.classId());
                continue;
            }
            ClassDefinition owner = classes.get(skill.classId());
            if (!skill.resource().isEmpty() && owner.resource(skill.resource()) == null) {
                plugin.getLogger().warning("技能 " + skill.id() + " 的资源 " + skill.resource()
                        + " 在职业 " + owner.id() + " 里不存在。");
            }
        }
        for (ClassDefinition def : classes.values()) {
            for (String skillId : def.skills()) {
                if (!skills.containsKey(skillId)) {
                    plugin.getLogger().warning("职业 " + def.id() + " 引用了未定义的技能：" + skillId);
                }
            }
        }
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    /** 解析 Material 名，失败返回 null（调用方用默认值兜底）。 */
    public static org.bukkit.Material material(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        org.bukkit.Material material = org.bukkit.Material.matchMaterial(raw.trim().toUpperCase(Locale.ROOT));
        if (material == null) {
            return null;
        }
        return material.isItem() ? material : null;
    }

    public MainConfig main() {
        return main;
    }

    public Map<String, ClassDefinition> classes() {
        return Collections.unmodifiableMap(classes);
    }

    public ClassDefinition classOf(String id) {
        return id == null ? null : classes.get(id.toLowerCase(Locale.ROOT));
    }

    public Map<String, SkillDefinition> skills() {
        return Collections.unmodifiableMap(skills);
    }

    public SkillDefinition skill(String id) {
        return id == null ? null : skills.get(id.toLowerCase(Locale.ROOT));
    }

    public int classCount() {
        return classes.size();
    }

    public int skillCount() {
        return skills.size();
    }

    public JavaPlugin plugin() {
        return plugin;
    }
}
