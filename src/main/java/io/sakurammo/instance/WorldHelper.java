package io.sakurammo.instance;

import io.sakurammo.SakuraMMOPlugin;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.WorldType;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.List;
import java.util.logging.Level;
import java.util.stream.Stream;

/**
 * 世界文件工具：模板复制、目录递归删除、模板世界引导生成。
 * <p>
 * 大文件 IO 一律异步执行（任务书坑表 1/2），世界加载/卸载只走主线程。
 */
public final class WorldHelper {

    /** 相对模板中心的竞技场布局（黑铁王庭）。 */
    public static final int ARENA_HALF = 20;        // 平台半径（-20..19）
    public static final int ENTRY_Z = 17;           // 进本出生点
    public static final int BOSS1_Z = 6;            // Boss 1（压力板守门人）
    public static final int DOOR_Z = 0;             // 门墙（Boss1 死后打通）
    public static final int BOSS2_Z = -6;           // Boss 2（双阀）
    public static final int BOSS3_Z = -12;          // Boss 3（熔炉）
    public static final int CHEST_Z = -16;          // 奖励箱台
    public static final int VALVE_X = 8;            // 阀门柱（x=±8, z=DOOR_Z-1）

    private final SakuraMMOPlugin plugin;

    /** bootstrapTemplate 的失败哨兵（合法地表 Y 可能为负，不能拿 -1 当失败）。 */
    public static final int BOOTSTRAP_FAILED = Integer.MIN_VALUE;

    public WorldHelper(SakuraMMOPlugin plugin) {
        this.plugin = plugin;
    }

    public File worldFolder(String worldName) {
        return new File(Bukkit.getWorldContainer(), worldName);
    }

    /** 模板文件夹是否存在。 */
    public boolean templateExists(String templateWorld) {
        File folder = worldFolder(templateWorld);
        return folder.isDirectory() && new File(folder, "level.dat").isFile();
    }

    /**
     * 模板不存在时的引导生成：超平坦世界 + 竞技场建筑，然后卸载留盘。
     * 返回竞技场地表绝对 Y（配置写入用，超平坦通常是负数，如 -60），
     * 失败/已存在返回 {@link #BOOTSTRAP_FAILED}。
     */
    public int bootstrapTemplate(String templateWorld) {
        if (templateExists(templateWorld)) {
            return BOOTSTRAP_FAILED;
        }
        plugin.getLogger().info("模板世界 " + templateWorld + " 不存在，自动生成超平坦竞技场…");
        World world = WorldCreator.name(templateWorld)
                .type(WorldType.FLAT)
                .generateStructures(false)
                .environment(World.Environment.NORMAL)
                .createWorld();
        if (world == null) {
            plugin.getLogger().severe("模板世界 " + templateWorld + " 创建失败。");
            return BOOTSTRAP_FAILED;
        }
        int baseY = world.getHighestBlockYAt(0, 0) + 1;
        buildArena(world, baseY);
        world.setSpawnLocation(0, baseY + 1, ENTRY_Z);
        // 卸载并保存 → 留下模板文件夹（此后永不加载，仅复制）
        Bukkit.unloadWorld(world, true);
        plugin.getLogger().info("模板世界已生成并保存：" + templateWorld + "（baseY=" + baseY + "）");
        return baseY;
    }

    /** 在模板世界里搭黑铁王庭竞技场（纯原版方块，坐标见常量）。 */
    private void buildArena(World world, int y) {
        // 四周围墙（高 4）+ 顶灯
        for (int x = -ARENA_HALF - 1; x <= ARENA_HALF; x++) {
            for (int z = -ARENA_HALF - 1; z <= ARENA_HALF; z++) {
                boolean edge = x == -ARENA_HALF - 1 || x == ARENA_HALF
                        || z == -ARENA_HALF - 1 || z == ARENA_HALF;
                if (!edge) {
                    continue;
                }
                for (int dy = 1; dy <= 4; dy++) {
                    world.getBlockAt(x, y + dy, z).setType(
                            (x + z) % 8 == 0 ? Material.SEA_LANTERN : Material.IRON_BLOCK);
                }
            }
        }
        // 入口台 + Boss 台 + 奖励台
        pad(world, y, 0, ENTRY_Z, 3, Material.POLISHED_ANDESITE);
        pad(world, y, 0, BOSS1_Z, 5, Material.POLISHED_BLACKSTONE_BRICKS);
        pad(world, y, 0, BOSS2_Z, 5, Material.POLISHED_BLACKSTONE_BRICKS);
        pad(world, y, 0, BOSS3_Z, 5, Material.POLISHED_BLACKSTONE_BRICKS);
        pad(world, y, 0, CHEST_Z, 3, Material.POLISHED_ANDESITE);
        // 门墙两侧的实心封边（门洞本身留空，实例开始时插件用铁栏杆封上）
        for (int dy = 1; dy <= 3; dy++) {
            for (int x = -ARENA_HALF; x <= ARENA_HALF - 1; x++) {
                if (Math.abs(x) <= 2) {
                    continue; // 门洞 x∈[-2,2]
                }
                world.getBlockAt(x, y + dy, DOOR_Z).setType(Material.IRON_BLOCK);
            }
        }
        // Boss2 的两根阀门柱（铁块 + 顶部拉杆）
        valvePillar(world, y, -VALVE_X, DOOR_Z - 1);
        valvePillar(world, y, VALVE_X, DOOR_Z - 1);
        // 地面照明（防刷怪）
        for (int x = -ARENA_HALF + 4; x <= ARENA_HALF - 4; x += 8) {
            for (int z = -ARENA_HALF + 4; z <= ARENA_HALF - 4; z += 8) {
                world.getBlockAt(x, y, z).setType(Material.SEA_LANTERN);
            }
        }
    }

    private void pad(World world, int y, int cx, int cz, int half, Material material) {
        for (int x = cx - half; x <= cx + half; x++) {
            for (int z = cz - half; z <= cz + half; z++) {
                world.getBlockAt(x, y, z).setType(material);
            }
        }
    }

    private void valvePillar(World world, int y, int x, int z) {
        world.getBlockAt(x, y, z).setType(Material.IRON_BLOCK);
        Block lever = world.getBlockAt(x, y + 1, z);
        lever.setType(Material.LEVER);
    }

    /**
     * 异步复制模板世界文件夹 → 实例文件夹。
     * 跳过 session.lock / uid.dat（会话锁与唯一 id 不能共享）。
     *
     * @return true 复制完成
     */
    public boolean copyWorld(String templateWorld, String instanceName) {
        File src = worldFolder(templateWorld);
        File dst = worldFolder(instanceName);
        if (dst.isDirectory()) {
            deleteFolder(dst); // 残留（理论上是启动清理漏网）
        }
        if (!dst.mkdirs()) {
            plugin.getLogger().severe("无法创建实例世界目录：" + dst);
            return false;
        }
        try (Stream<Path> paths = Files.walk(src.toPath())) {
            for (Path from : (Iterable<Path>) paths::iterator) {
                Path to = dst.toPath().resolve(src.toPath().relativize(from).toString());
                String name = from.getFileName().toString();
                if (name.equals("session.lock") || name.equals("uid.dat")) {
                    continue;
                }
                if (Files.isDirectory(from)) {
                    Files.createDirectories(to);
                } else {
                    Files.copy(from, to, StandardCopyOption.REPLACE_EXISTING);
                }
            }
            return true;
        } catch (IOException ex) {
            plugin.getLogger().log(Level.SEVERE, "复制世界失败：" + templateWorld + " → " + instanceName, ex);
            deleteFolder(dst);
            return false;
        }
    }

    /** 异步递归删除目录（卸载世界之后调用，防文件占用卡主线程）。 */
    public void deleteFolderAsync(File folder) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> deleteFolder(folder));
    }

    /** 同步递归删除（仅小目录或启动清理时用）。 */
    public void deleteFolder(File folder) {
        if (folder == null || !folder.exists()) {
            return;
        }
        try (Stream<Path> paths = Files.walk(folder.toPath())) {
            paths.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.delete(p);
                } catch (IOException ignored) {
                    // Windows 句柄延迟释放，重试一次
                    try {
                        Thread.sleep(150L);
                        Files.delete(p);
                    } catch (Exception ignored2) {
                        plugin.getLogger().warning("无法删除实例残留文件：" + p);
                    }
                }
            });
        } catch (Exception ex) {
            plugin.getLogger().warning("删除目录失败：" + folder + " - " + ex.getMessage());
        }
    }

    /** 启动清理：删除所有 instance_ 开头的世界文件夹（宕机残留，任务书兜底机制）。 */
    public void cleanupOrphanInstances() {
        File container = Bukkit.getWorldContainer();
        File[] children = container.listFiles();
        if (children == null) {
            return;
        }
        for (File child : children) {
            if (child.isDirectory() && child.getName().startsWith("instance_")) {
                plugin.getLogger().warning("发现残留实例世界，正在删除：" + child.getName());
                deleteFolderAsync(child);
            }
        }
    }

    // ---------------- 副本配置 ----------------

    /**
     * 读取 dungeons/&lt;id&gt;.yml。模板是自动生成时（baseY &gt;= 0），用真实坐标重写配置。
     */
    /**
     * 按模板实测坐标重写副本配置。
     * <p>
     * v4.2 修正：早期版本把 display-name / Boss 名 / 奖励物品写死成黑铁王庭，
     * 导致新副本（新手矿洞等）模板生成后被改成黑铁王庭的 Boss。
     * 现在只重写**坐标**，其余字段（副本名、模式、等级、人数、Boss mob、奖励）全部保留原配置。
     */
    public void rewriteDungeonConfig(String dungeonId, String templateWorld, int baseY) {
        File file = new File(plugin.getDataFolder(), "dungeons/" + dungeonId + ".yml");
        YamlConfiguration existing = file.isFile()
                ? YamlConfiguration.loadConfiguration(file) : new YamlConfiguration();

        String display = existing.getString("display-name", dungeonId);
        String mode = existing.getString("mode", "dungeon");
        int minMembers = Math.max(1, existing.getInt("min-members", 1));
        int maxMembers = Math.max(1, existing.getInt("max-members", 5));
        int minLevel = Math.max(1, existing.getInt("min-level", 1));
        List<String> rewardItems = existing.getStringList("reward.items");
        if (rewardItems.isEmpty()) {
            rewardItems = List.of("GOLDEN_APPLE:2", "EXPERIENCE_BOTTLE:4");
        }
        String[] mobs = {
                existing.getString("bosses.1.mob", "IronJawGatekeeper"),
                existing.getString("bosses.2.mob", "AquimixWarden"),
                existing.getString("bosses.3.mob", "ForgemasterVhal")
        };

        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("id", dungeonId);
        yaml.set("display-name", display);
        yaml.set("mode", mode);
        yaml.set("min-level", minLevel);
        yaml.set("min-members", minMembers);
        yaml.set("max-members", maxMembers);
        yaml.set("template-world", templateWorld);
        yaml.set("spawn.x", 0.5);
        yaml.set("spawn.y", baseY + 1);
        yaml.set("spawn.z", ENTRY_Z + 0.5);
        yaml.set("spawn.yaw", 180.0);
        yaml.set("spawn.pitch", 0.0);
        // 回城点 = 主世界测试场 (0, 69, 0)
        World main = Bukkit.getWorlds().isEmpty() ? null : Bukkit.getWorlds().get(0);
        yaml.set("exit.world", main != null ? main.getName() : "world");
        yaml.set("exit.x", 0.5);
        yaml.set("exit.y", 69);
        yaml.set("exit.z", 0.5);
        yaml.set("exit.yaw", 0.0);
        yaml.set("exit.pitch", 0.0);
        yaml.set("bosses.1.mob", mobs[0]);
        yaml.set("bosses.1.x", 0.5);
        yaml.set("bosses.1.y", baseY + 1);
        yaml.set("bosses.1.z", BOSS1_Z + 0.5);
        yaml.set("bosses.1.door.min", "-2," + (baseY + 1) + "," + DOOR_Z);
        yaml.set("bosses.1.door.max", "2," + (baseY + 3) + "," + DOOR_Z);
        yaml.set("bosses.2.mob", mobs[1]);
        yaml.set("bosses.2.x", 0.5);
        yaml.set("bosses.2.y", baseY + 1);
        yaml.set("bosses.2.z", BOSS2_Z + 0.5);
        yaml.set("bosses.2.valves", java.util.List.of(
                -VALVE_X + "," + (baseY + 1) + "," + (DOOR_Z - 1),
                VALVE_X + "," + (baseY + 1) + "," + (DOOR_Z - 1)));
        yaml.set("bosses.3.mob", mobs[2]);
        yaml.set("bosses.3.x", 0.5);
        yaml.set("bosses.3.y", baseY + 1);
        yaml.set("bosses.3.z", BOSS3_Z + 0.5);
        yaml.set("bosses.3.phases", java.util.List.of(0.7, 0.35));
        yaml.set("reward.chest", "0," + (baseY + 1) + "," + CHEST_Z);
        yaml.set("reward.items", rewardItems);
        try {
            file.getParentFile().mkdirs();
            yaml.save(file);
            plugin.getLogger().info("副本配置已按模板实测坐标重写：" + file.getName() + "（baseY=" + baseY + "）");
        } catch (IOException ex) {
            plugin.getLogger().log(Level.SEVERE, "写入副本配置失败", ex);
        }
    }

    /** 便捷：加载配置段（供 DungeonDefinition 用）。 */
    public ConfigurationSection loadDungeonSection(String dungeonId) {
        File file = new File(plugin.getDataFolder(), "dungeons/" + dungeonId + ".yml");
        if (!file.isFile()) {
            return null;
        }
        return YamlConfiguration.loadConfiguration(file).getConfigurationSection("");
    }

    public static Location location(World world, double x, double y, double z, float yaw, float pitch) {
        return new Location(world, x, y, z, yaw, pitch);
    }
}
