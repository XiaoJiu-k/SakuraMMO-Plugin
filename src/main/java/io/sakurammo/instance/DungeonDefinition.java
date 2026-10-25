package io.sakurammo.instance;

import io.sakurammo.SakuraMMOPlugin;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * dungeons/&lt;id&gt;.yml 的强类型视图。
 * <pre>
 * id / display-name / min-members / max-members / template-world
 * spawn: x,y,z,yaw,pitch            进本出生点（实例世界内）
 * exit:  world,x,y,z,yaw,pitch      回城点
 * bosses.1: mob,x,y,z + door(min/max "x,y,z")
 * bosses.2: mob,x,y,z + valves(["x,y,z",...])
 * bosses.3: mob,x,y,z + phases([0.7,0.35])
 * reward: chest("x,y,z") + items(["IRON_SWORD:1",...])
 * </pre>
 */
public final class DungeonDefinition {

    public final String id;
    public final String displayName;
    /** dungeon = 5 人本（死亡链）；raid = 团本（Boss 同场全灭通关）。 */
    public final String mode;
    public final int minMembers;
    public final int maxMembers;
    /** 进入等级要求（玩家原版等级）；1 = 不限。 */
    public final int minLevel;
    public final String templateWorld;
    public final double[] spawn;   // x,y,z,yaw,pitch（实例世界内）
    public final String exitWorld;
    public final double[] exit;    // x,y,z,yaw,pitch
    public final BossDef boss1;
    public final BossDef boss2;
    public final BossDef boss3;
    public final int[] chest;      // x,y,z
    public final List<String> rewardItems;

    public record BossDef(String mob, double x, double y, double z,
                          int[] doorMin, int[] doorMax,
                          List<int[]> valves, List<Double> phases) {
    }

    private DungeonDefinition(String id, String displayName, String mode, int minMembers, int maxMembers,
                              int minLevel, String templateWorld, double[] spawn, String exitWorld, double[] exit,
                              BossDef boss1, BossDef boss2, BossDef boss3,
                              int[] chest, List<String> rewardItems) {
        this.id = id;
        this.displayName = displayName;
        this.mode = mode;
        this.minMembers = minMembers;
        this.maxMembers = maxMembers;
        this.minLevel = minLevel;
        this.templateWorld = templateWorld;
        this.spawn = spawn;
        this.exitWorld = exitWorld;
        this.exit = exit;
        this.boss1 = boss1;
        this.boss2 = boss2;
        this.boss3 = boss3;
        this.chest = chest;
        this.rewardItems = rewardItems;
    }

    public Location spawnLoc(World world) {
        return new Location(world, spawn[0], spawn[1], spawn[2], (float) spawn[3], (float) spawn[4]);
    }

    public Location exitLoc() {
        World world = Bukkit.getWorld(exitWorld);
        Location loc = new Location(world, exit[0], exit[1], exit[2], (float) exit[3], (float) exit[4]);
        if (world == null && !Bukkit.getWorlds().isEmpty()) {
            // 主世界名变了等极端情况：回主世界出生点兜底
            Location spawn = Bukkit.getWorlds().get(0).getSpawnLocation();
            loc.setWorld(spawn.getWorld());
            loc.setX(spawn.getX());
            loc.setY(spawn.getY());
            loc.setZ(spawn.getZ());
        }
        return loc;
    }

    /** 加载并校验；配置缺失或非法返回 null 并打 WARN。 */
    public static DungeonDefinition load(SakuraMMOPlugin plugin, String dungeonId) {
        File file = new File(plugin.getDataFolder(), "dungeons/" + dungeonId + ".yml");
        if (!file.isFile()) {
            return null;
        }
        try {
            YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
            ConfigurationSection spawnSec = yaml.getConfigurationSection("spawn");
            ConfigurationSection exitSec = yaml.getConfigurationSection("exit");
            if (spawnSec == null || exitSec == null) {
                plugin.getLogger().warning("副本 " + dungeonId + " 缺少 spawn/exit 段。");
                return null;
            }
            double[] spawn = {
                    spawnSec.getDouble("x"), spawnSec.getDouble("y"), spawnSec.getDouble("z"),
                    spawnSec.getDouble("yaw"), spawnSec.getDouble("pitch")};
            double[] exit = {
                    exitSec.getDouble("x"), exitSec.getDouble("y"), exitSec.getDouble("z"),
                    exitSec.getDouble("yaw"), exitSec.getDouble("pitch")};
            BossDef b1 = boss(yaml, "1");
            BossDef b2 = boss(yaml, "2");
            BossDef b3 = boss(yaml, "3");
            if (b1 == null || b2 == null || b3 == null) {
                plugin.getLogger().warning("副本 " + dungeonId + " 的 bosses.1/2/3 配置不完整。");
                return null;
            }
            int[] chest = coords(yaml.getString("reward.chest"));
            List<String> items = yaml.getStringList("reward.items");
            return new DungeonDefinition(
                    yaml.getString("id", dungeonId),
                    yaml.getString("display-name", dungeonId),
                    "raid".equalsIgnoreCase(yaml.getString("mode", "dungeon")) ? "raid" : "dungeon",
                    Math.max(1, yaml.getInt("min-members", 2)),
                    Math.max(1, yaml.getInt("max-members", 5)),
                    Math.max(1, yaml.getInt("min-level", 1)),
                    yaml.getString("template-world", "template_" + dungeonId.toLowerCase(Locale.ROOT)),
                    spawn, exitSec.getString("world", "world"), exit,
                    b1, b2, b3, chest, items);
        } catch (Exception ex) {
            plugin.getLogger().warning("副本 " + dungeonId + " 配置解析失败：" + ex.getMessage());
            return null;
        }
    }

    private static BossDef boss(YamlConfiguration yaml, String index) {
        ConfigurationSection sec = yaml.getConfigurationSection("bosses." + index);
        if (sec == null || sec.getString("mob") == null) {
            return null;
        }
        List<int[]> valves = new ArrayList<>();
        for (String raw : sec.getStringList("valves")) {
            int[] at = coords(raw);
            if (at != null) {
                valves.add(at);
            }
        }
        List<Double> phases = new ArrayList<>();
        for (double phase : sec.getDoubleList("phases")) {
            phases.add(phase);
        }
        return new BossDef(
                sec.getString("mob"),
                sec.getDouble("x"), sec.getDouble("y"), sec.getDouble("z"),
                coords(sec.getString("door.min")), coords(sec.getString("door.max")),
                valves, phases);
    }

    /** "x,y,z" → int[3]。 */
    private static int[] coords(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String[] parts = raw.split(",");
        if (parts.length < 3) {
            return null;
        }
        try {
            return new int[]{Integer.parseInt(parts[0].trim()),
                    Integer.parseInt(parts[1].trim()), Integer.parseInt(parts[2].trim())};
        } catch (NumberFormatException ex) {
            return null;
        }
    }
}
