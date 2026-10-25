package io.sakurammo.player;

import io.sakurammo.SakuraMMOPlugin;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * playerdata/&lt;uuid&gt;.yml 的读写。
 * <p>
 * 写入策略：先写 &lt;uuid&gt;.yml.tmp，再 Files.move 覆盖，避免崩溃时留下半截文件。
 */
public final class PlayerDataManager {

    private final SakuraMMOPlugin plugin;
    private final File dir;
    private final Map<UUID, PlayerProfile> cache = new ConcurrentHashMap<>();

    public PlayerDataManager(SakuraMMOPlugin plugin) {
        this.plugin = plugin;
        this.dir = new File(plugin.getDataFolder(), "playerdata");
        if (!dir.isDirectory() && !dir.mkdirs()) {
            plugin.getLogger().severe("无法创建 playerdata 目录：" + dir.getAbsolutePath());
        }
    }

    public File fileOf(UUID uuid) {
        return new File(dir, uuid + ".yml");
    }

    /** 按玩家名找离线档案（扫描 name 字段），找不到返回 null。 */
    public File findOfflineFile(String name) {
        File found = scanByName(name);
        return found;
    }

    /** 按玩家名找离线玩家的 UUID（扫描 playerdata 的 name 字段），找不到返回 null。 */
    public UUID findOfflineUuid(String name) {
        File file = scanByName(name);
        if (file == null) {
            return null;
        }
        try {
            String uuid = YamlConfiguration.loadConfiguration(file).getString("uuid");
            return uuid == null ? null : UUID.fromString(uuid);
        } catch (Exception ex) {
            return null;
        }
    }

    private File scanByName(String name) {
        File[] files = dir.listFiles((d, n) -> n.endsWith(".yml"));
        if (files == null) {
            return null;
        }
        String wanted = name.toLowerCase(java.util.Locale.ROOT);
        for (File file : files) {
            try {
                if (file.getName().endsWith(".tmp")) {
                    continue;
                }
                String stored = YamlConfiguration.loadConfiguration(file).getString("name");
                if (stored != null && stored.toLowerCase(java.util.Locale.ROOT).equals(wanted)) {
                    return file;
                }
            } catch (Exception ignored) {
                // 跳过读不了的文件
            }
        }
        return null;
    }

    /**
     * 离线重置：清档案里的职业相关字段（class / 冷却 / 资源 / 绑定），
     * 保留 name/level/experience 等成长字段。原子写回。
     *
     * @return 重置前的职业 id（档案里没有职业则返回 null，不写文件）
     */
    public String resetFile(File file) {
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        String oldClass = yaml.getString("class");
        if (oldClass == null || oldClass.isBlank()) {
            return null;
        }
        yaml.set("class", null);
        yaml.set("class-selected-at", null);
        yaml.set("switch-cooldown-until", null);
        yaml.set("resources", null);
        yaml.set("cooldowns", null);
        yaml.set("bound-skills", null);
        Path targetPath = file.toPath();
        Path tmpPath = targetPath.resolveSibling(file.getName() + ".tmp");
        try {
            yaml.save(tmpPath.toFile());
            try {
                Files.move(tmpPath, targetPath, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ex) {
                Files.move(tmpPath, targetPath, StandardCopyOption.REPLACE_EXISTING);
            }
            return oldClass;
        } catch (IOException ex) {
            plugin.getLogger().log(Level.SEVERE, "离线重置玩家档案失败：" + file.getName(), ex);
            return null;
        }
    }

    public PlayerProfile get(UUID uuid) {
        return cache.get(uuid);
    }

    public PlayerProfile getOrLoad(Player player) {
        PlayerProfile cached = cache.get(player.getUniqueId());
        if (cached != null) {
            cached.name(player.getName());
            return cached;
        }
        return load(player.getUniqueId(), player.getName());
    }

    public PlayerProfile load(UUID uuid, String name) {
        PlayerProfile profile = new PlayerProfile(uuid, name);
        File file = fileOf(uuid);
        if (file.isFile()) {
            YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
            String storedName = yaml.getString("name");
            if (storedName != null && !storedName.isBlank()) {
                profile.name(storedName);
            }
            String classId = yaml.getString("class");
            if (classId != null && !classId.isBlank()) {
                profile.classId(classId.toLowerCase(java.util.Locale.ROOT));
            }
            profile.classSelectedAt(yaml.getLong("class-selected-at", 0L));
            profile.switchCooldownUntil(yaml.getLong("switch-cooldown-until", 0L));

            ConfigurationSection res = yaml.getConfigurationSection("resources");
            if (res != null) {
                for (String key : res.getKeys(false)) {
                    profile.resource(key, res.getDouble(key));
                }
            }
            ConfigurationSection cd = yaml.getConfigurationSection("cooldowns");
            if (cd != null) {
                for (String key : cd.getKeys(false)) {
                    profile.cooldownUntil(key, cd.getLong(key));
                }
            }
            ConfigurationSection bound = yaml.getConfigurationSection("bound-skills");
            if (bound != null) {
                for (String key : bound.getKeys(false)) {
                    ConfigurationSection entry = bound.getConfigurationSection(key);
                    if (entry == null) {
                        continue;
                    }
                    String skillId = entry.getString("skill");
                    if (skillId == null) {
                        continue;
                    }
                    Material material = parseMaterial(entry.getString("material"));
                    try {
                        profile.boundSkills().put(Integer.parseInt(key), new SkillBinding(skillId, material));
                    } catch (NumberFormatException ignored) {
                        plugin.getLogger().warning("玩家 " + uuid + " 的绑定槽位非法：" + key);
                    }
                }
            }
            // P0.6：skill-binds（按键 -> skillId）
            ConfigurationSection binds = yaml.getConfigurationSection("skill-binds");
            if (binds != null) {
                for (String key : binds.getKeys(false)) {
                    String skillId = binds.getString(key);
                    if (skillId != null && !skillId.isBlank() && !"null".equals(skillId)) {
                        profile.skillBinds().put(key.toUpperCase(java.util.Locale.ROOT), skillId);
                    }
                }
            }
            profile.migratedFromBoundSkills(yaml.getBoolean("migrated-from-bound-skills", false));
            // 旧槽位绑定迁移（仅一次）：bound-skills 的 "0" → 当前布局槽位 1
            if (!profile.migratedFromBoundSkills() && !profile.boundSkills().isEmpty()) {
                SkillBinding legacy = profile.boundSkills().get(0);
                if (legacy != null && profile.skillBind(PlayerProfile.SKILL_KEYS.get(0)) == null) {
                    profile.skillBind(PlayerProfile.SKILL_KEYS.get(0), legacy.skillId());
                    plugin.getLogger().info("玩家 " + profile.name() + " 的旧槽位绑定已迁移：0 → "
                            + PlayerProfile.SKILL_KEYS.get(0) + "（" + legacy.skillId() + "）");
                }
                profile.migratedFromBoundSkills(true);
            }
            // 按键布局迁移：v1(Q/E/R/T) / v2(R/T/F/G) → v3(Shift+1~4)，按槽位顺序映射，仅一次
            int layout = yaml.getInt("bind-layout",
                    profile.skillBinds().isEmpty() ? 3 : 1);
            if (layout < 3) {
                java.util.List<String> legacyKeys = layout == 2
                        ? PlayerProfile.LEGACY_KEYS_V2 : PlayerProfile.LEGACY_KEYS_V1;
                java.util.Map<String, String> legacyBinds = new java.util.LinkedHashMap<>(profile.skillBinds());
                profile.skillBinds().clear();
                for (int i = 0; i < PlayerProfile.SKILL_KEYS.size() && i < legacyKeys.size(); i++) {
                    String skillId = legacyBinds.get(legacyKeys.get(i));
                    if (skillId != null) {
                        profile.skillBinds().put(PlayerProfile.SKILL_KEYS.get(i), skillId);
                    }
                }
                profile.markDirty();
                plugin.getLogger().info("玩家 " + profile.name() + " 的按键布局已迁移："
                        + legacyKeys + " → " + PlayerProfile.SKILL_KEYS + "（" + profile.skillBinds() + "）");
            }
            profile.bindLayoutVersion(3);
            profile.level(yaml.getInt("level", 1));
            profile.experience(yaml.getLong("experience", 0L));
            String spec = yaml.getString("specialization");
            if (spec != null && !spec.isBlank()) {
                profile.specialization(spec);
            }
            ConfigurationSection snap = yaml.getConfigurationSection("skill-snapshots");
            if (snap != null) {
                for (String key : snap.getKeys(false)) {
                    profile.skillSnapshots().put(key, snap.getInt(key));
                }
            }
            // 阶段二：副本数据（背包快照 / run-id / 幂等领取 / 奖励邮箱）
            String inv = yaml.getString("inventory-snapshot");
            if (inv != null && !inv.isBlank() && !"null".equals(inv)) {
                profile.inventorySnapshot(inv);
            }
            profile.inventoryIsolated(yaml.getBoolean("inventory-isolated", false));
            String run = yaml.getString("active-run");
            if (run != null && !run.isBlank() && !"null".equals(run)) {
                profile.activeRun(run);
            }
            profile.claimedRewards().addAll(yaml.getStringList("claimed-rewards"));
            profile.rewardMailbox().addAll(yaml.getStringList("reward-mailbox"));
            // 阶段三：天赋
            profile.talentPoints(yaml.getInt("talent-points", 0));
            ConfigurationSection tal = yaml.getConfigurationSection("talents");
            if (tal != null) {
                for (String key : tal.getKeys(false)) {
                    profile.talents().put(key, tal.getInt(key));
                }
            }
            // 阶段四：代币 / 周常
            profile.tokens(yaml.getInt("tokens", 0));
            profile.weeklyCycle(yaml.getString("weekly-cycle", ""));
            ConfigurationSection wk = yaml.getConfigurationSection("weekly-progress");
            if (wk != null) {
                for (String key : wk.getKeys(false)) {
                    profile.weeklyProgress().put(key, wk.getInt(key));
                }
            }
            profile.weeklyClaimed().addAll(yaml.getStringList("weekly-claimed"));
            profile.clean();
        }
        cache.put(uuid, profile);
        return profile;
    }

    public void save(PlayerProfile profile) {
        File target = fileOf(profile.uuid());
        Path targetPath = target.toPath();
        Path tmpPath = targetPath.resolveSibling(target.getName() + ".tmp");

        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("uuid", profile.uuid().toString());
        yaml.set("name", profile.name());
        yaml.set("class", profile.classId());
        yaml.set("class-selected-at", profile.classSelectedAt());
        yaml.set("switch-cooldown-until", profile.switchCooldownUntil());
        for (Map.Entry<String, Double> entry : profile.resources().entrySet()) {
            yaml.set("resources." + entry.getKey(), entry.getValue());
        }
        for (Map.Entry<String, Long> entry : profile.cooldowns().entrySet()) {
            if (entry.getValue() != null && entry.getValue() > 0) {
                yaml.set("cooldowns." + entry.getKey(), entry.getValue());
            }
        }
        // P0.6：只写 skill-binds；bound-skills 不再写入（读取兼容保留在 load）
        for (Map.Entry<String, String> entry : profile.skillBinds().entrySet()) {
            yaml.set("skill-binds." + entry.getKey(), entry.getValue());
        }
        yaml.set("bind-layout", profile.bindLayoutVersion());
        yaml.set("migrated-from-bound-skills", profile.migratedFromBoundSkills() ? true : null);
        yaml.set("level", profile.level());
        yaml.set("experience", profile.experience());
        yaml.set("specialization", profile.specialization());
        for (Map.Entry<String, Integer> entry : profile.skillSnapshots().entrySet()) {
            yaml.set("skill-snapshots." + entry.getKey(), entry.getValue());
        }
        yaml.set("inventory-snapshot", profile.inventorySnapshot());
        yaml.set("inventory-isolated", profile.inventoryIsolated());
        yaml.set("active-run", profile.activeRun());
        yaml.set("claimed-rewards", profile.claimedRewards().isEmpty() ? null : profile.claimedRewards());
        yaml.set("reward-mailbox", profile.rewardMailbox().isEmpty() ? null : profile.rewardMailbox());
        yaml.set("talent-points", profile.talentPoints());
        if (!profile.talents().isEmpty()) {
            for (Map.Entry<String, Integer> entry : profile.talents().entrySet()) {
                yaml.set("talents." + entry.getKey(), entry.getValue());
            }
        }
        yaml.set("tokens", profile.tokens());
        yaml.set("weekly-cycle", profile.weeklyCycle().isEmpty() ? null : profile.weeklyCycle());
        if (!profile.weeklyProgress().isEmpty()) {
            for (Map.Entry<String, Integer> entry : profile.weeklyProgress().entrySet()) {
                yaml.set("weekly-progress." + entry.getKey(), entry.getValue());
            }
        }
        yaml.set("weekly-claimed", profile.weeklyClaimed().isEmpty() ? null : profile.weeklyClaimed());

        try {
            yaml.save(tmpPath.toFile());
            try {
                Files.move(tmpPath, targetPath, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ex) {
                Files.move(tmpPath, targetPath, StandardCopyOption.REPLACE_EXISTING);
            }
            profile.clean();
        } catch (IOException ex) {
            plugin.getLogger().log(Level.SEVERE, "保存玩家数据失败：" + target.getName(), ex);
        }
    }

    public void saveAll() {
        for (PlayerProfile profile : cache.values()) {
            save(profile);
        }
    }

    public void saveDirty() {
        for (PlayerProfile profile : cache.values()) {
            if (profile.dirty()) {
                save(profile);
            }
        }
    }

    public void unload(UUID uuid) {
        PlayerProfile profile = cache.remove(uuid);
        if (profile != null) {
            save(profile);
        }
    }

    public void saveAndKeep(UUID uuid) {
        PlayerProfile profile = cache.get(uuid);
        if (profile != null) {
            save(profile);
        }
    }

    public int cachedCount() {
        return cache.size();
    }

    private static Material parseMaterial(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        Material material = Material.matchMaterial(raw);
        return material == null ? null : material;
    }
}
