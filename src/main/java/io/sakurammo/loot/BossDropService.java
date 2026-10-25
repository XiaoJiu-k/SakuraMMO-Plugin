package io.sakurammo.loot;

import io.sakurammo.SakuraMMOPlugin;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Boss 掉落表（v4.2）。
 * <p>
 * 为什么由插件 roll，而不是配 MythicMobs DropTable：MM 的掉落走原版物品栈，
 * 自定义物品的身份在 PDC（{@code sakurammo:item_id}）里，经 MM 掉落容易丢 NBT；
 * 而且「保底一件装备」这类规则在 MM 里表达不了。
 * <p>
 * 配置：{@code plugins/SakuraMMO/drops.yml} → dungeons.&lt;副本id&gt;
 * <pre>
 * dungeons:
 *   novice_mine:
 *     materials: { rough_crystal: [2, 4] }     # 必掉，数量区间
 *     items:                                    # 概率掉落
 *       - { id: miner_blade, chance: 0.12 }
 *     guarantee-final: true                     # 最后一个 Boss 至少掉一件装备（防脸黑）
 * </pre>
 */
public final class BossDropService {

    private final SakuraMMOPlugin plugin;
    private final Map<String, Table> tables = new LinkedHashMap<>();

    public BossDropService(SakuraMMOPlugin plugin) {
        this.plugin = plugin;
        load();
    }

    public void load() {
        tables.clear();
        File file = new File(plugin.getDataFolder(), "drops.yml");
        if (!file.isFile()) {
            plugin.getLogger().warning("缺少 drops.yml，Boss 不会掉落固定属性物品。");
            return;
        }
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection root = cfg.getConfigurationSection("dungeons");
        if (root == null) {
            return;
        }
        for (String dungeonId : root.getKeys(false)) {
            ConfigurationSection sec = root.getConfigurationSection(dungeonId);
            if (sec == null) {
                continue;
            }
            Map<String, int[]> materials = new LinkedHashMap<>();
            ConfigurationSection matSec = sec.getConfigurationSection("materials");
            if (matSec != null) {
                for (String id : matSec.getKeys(false)) {
                    List<Integer> range = matSec.getIntegerList(id);
                    int min = range.isEmpty() ? 1 : range.get(0);
                    int max = range.size() < 2 ? min : range.get(1);
                    materials.put(id.toLowerCase(Locale.ROOT), new int[]{min, Math.max(min, max)});
                }
            }
            List<Entry> items = new ArrayList<>();
            for (Map<?, ?> raw : sec.getMapList("items")) {
                String id = String.valueOf(raw.get("id"));
                double chance = raw.get("chance") instanceof Number number ? number.doubleValue() : 0.1D;
                items.add(new Entry(id.toLowerCase(Locale.ROOT), Math.max(0.0D, Math.min(1.0D, chance))));
            }
            tables.put(dungeonId.toLowerCase(Locale.ROOT),
                    new Table(materials, List.copyOf(items), sec.getBoolean("guarantee-final", true)));
        }
        plugin.getLogger().info("已加载 " + tables.size() + " 张 Boss 掉落表。");
    }

    /** 副本是否有独立掉落表（没有则退回旧的底材逻辑）。 */
    public boolean has(String dungeonId) {
        return tables.containsKey(dungeonId.toLowerCase(Locale.ROOT));
    }

    /**
     * 结算一次 Boss 掉落：必掉材料 + 概率装备；最后一个 Boss 保底一件装备。
     *
     * @param dungeonId 副本 id
     * @param bossIndex 本 Boss 序号（1 起）
     * @param bossCount 本副本 Boss 总数（判断是否最后一个）
     */
    public void roll(String dungeonId, int bossIndex, int bossCount, World world, Location at) {
        Table table = tables.get(dungeonId.toLowerCase(Locale.ROOT));
        if (table == null) {
            return;
        }
        ThreadLocalRandom random = ThreadLocalRandom.current();

        for (Map.Entry<String, int[]> entry : table.materials.entrySet()) {
            int min = entry.getValue()[0];
            int max = entry.getValue()[1];
            int amount = max <= min ? min : random.nextInt(min, max + 1);
            if (amount <= 0) {
                continue;
            }
            drop(world, at, entry.getKey(), amount);
        }

        Entry guaranteed = null;
        int dropped = 0;
        for (Entry entry : table.items) {
            if (random.nextDouble() < entry.chance()) {
                drop(world, at, entry.id(), 1);
                dropped++;
                if (guaranteed == null) {
                    guaranteed = entry;
                }
            }
        }
        boolean isFinal = bossIndex >= bossCount;
        if (isFinal && dropped == 0 && table.guaranteeFinal() && !table.items().isEmpty()) {
            Entry pick = table.items().get(random.nextInt(table.items().size()));
            drop(world, at, pick.id(), 1);
            if (plugin.config().main().debug()) {
                plugin.getLogger().info("[debug] 保底掉落 " + dungeonId + " → " + pick.id());
            }
        }
    }

    /**
     * 给菜单用的掉落说明（每行一句）。
     * 例：`必掉：粗糙矿晶 ×2-4`、`装备：矿工短剑 12%、矿工头盔 10%`、`末位首领必掉一件装备`。
     */
    public List<String> describe(String dungeonId) {
        Table table = tables.get(dungeonId.toLowerCase(Locale.ROOT));
        if (table == null) {
            return List.of();
        }
        List<String> lines = new ArrayList<>();
        if (!table.materials().isEmpty()) {
            StringBuilder sb = new StringBuilder();
            table.materials().forEach((id, range) -> {
                if (!sb.isEmpty()) {
                    sb.append("、");
                }
                sb.append(materialName(id));
                if (range[1] > 0) {
                    sb.append(" ×").append(range[0]);
                    if (range[1] != range[0]) {
                        sb.append('-').append(range[1]);
                    }
                }
            });
            lines.add(sb.toString());
        }
        if (!table.items().isEmpty()) {
            StringBuilder sb = new StringBuilder();
            int shown = 0;
            for (Entry entry : table.items()) {
                if (shown++ >= 4) {
                    sb.append(" 等");
                    break;
                }
                if (!sb.isEmpty()) {
                    sb.append("、");
                }
                sb.append(itemName(entry.id()))
                        .append(' ').append(Math.round(entry.chance() * 100)).append('%');
            }
            lines.add(sb.toString());
        }
        if (table.guaranteeFinal()) {
            lines.add("末位首领必掉一件装备");
        }
        return lines;
    }

    /** 材料显示名：新物品库优先，其次旧制造底材。 */
    public String materialName(String id) {
        if (plugin.items() != null) {
            var def = plugin.items().definition(id);
            if (def.isPresent()) {
                return def.get().display();
            }
        }
        var material = plugin.crafting() == null ? null : plugin.crafting().material(id);
        return material == null ? id : material.name();
    }

    /** 装备显示名（查物品库）。 */
    public String itemName(String id) {
        if (plugin.items() != null) {
            var def = plugin.items().definition(id);
            if (def.isPresent()) {
                return def.get().display();
            }
        }
        return id;
    }

    /** 材料可能来自旧制造底材或新物品库，两边都查。 */
    private void drop(World world, Location at, String id, int amount) {
        var item = plugin.items() == null ? null : plugin.items().create(id, amount);
        if (item == null) {
            item = plugin.crafting().createMaterial(id, amount);
        }
        if (item == null) {
            plugin.getLogger().warning("掉落表引用了不存在的物品：" + id);
            return;
        }
        world.dropItemNaturally(at, item);
    }

    private record Entry(String id, double chance) {
    }

    private record Table(Map<String, int[]> materials, List<Entry> items, boolean guaranteeFinal) {
    }
}
