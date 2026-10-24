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
        for (Map.Entry<Integer, SkillBinding> entry : profile.boundSkills().entrySet()) {
            SkillBinding binding = entry.getValue();
            yaml.set("bound-skills." + entry.getKey() + ".skill", binding.skillId());
            yaml.set("bound-skills." + entry.getKey() + ".material",
                    binding.material() == null ? null : binding.material().name());
        }
        yaml.set("level", profile.level());
        yaml.set("experience", profile.experience());
        yaml.set("specialization", profile.specialization());
        for (Map.Entry<String, Integer> entry : profile.skillSnapshots().entrySet()) {
            yaml.set("skill-snapshots." + entry.getKey(), entry.getValue());
        }

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
