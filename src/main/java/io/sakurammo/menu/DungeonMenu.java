package io.sakurammo.menu;

import io.sakurammo.SakuraMMOPlugin;
import io.sakurammo.party.Party;
import io.sakurammo.util.Msg;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

/**
 * 副本菜单（角色面板 → 副本入口）。
 * <pre>
 *  4   当前队伍 / 副本状态
 *  10-16 / 19-25  副本列表（按等级排序，带等级要求与掉落说明）
 *  45  离开副本（副本中显示）
 *  49  返回角色面板        53  关闭
 * </pre>
 * 开本的全部校验（队长 / 队伍状态 / 人数 / 模板）由 InstanceManager.start 负责，
 * 菜单只做入口与展示。
 */
public final class DungeonMenu extends Menu {

    public DungeonMenu(SakuraMMOPlugin plugin, Player player) {
        super(plugin, player, 6, Msg.raw("menu.dungeon-title"));
    }

    @Override
    protected void build() {
        set(4, statusIcon());
        fill(fillerMaterial());

        // 按等级门槛排序，形成清晰的进阶路线；最多展示 14 个
        List<String> dungeons = new ArrayList<>(plugin.instances().availableDungeons());
        dungeons.sort(java.util.Comparator.comparingInt(id -> {
            var d = io.sakurammo.instance.DungeonDefinition.load(plugin, id);
            return d == null ? 999 : d.minLevel;
        }));
        int[] slots = {10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25};
        for (int i = 0; i < dungeons.size() && i < slots.length; i++) {
            final String dungeonId = dungeons.get(i);
            set(slots[i], dungeonIcon(dungeonId), p -> plugin.instances().start(p, dungeonId));
        }

        Party party = plugin.parties().partyOf(player.getUniqueId());
        if (party != null && plugin.instances().byRun(party.runId()) != null) {
            set(45, Items.of(Material.OAK_DOOR, Msg.raw("menu.dungeon-leave"),
                            List.of(Msg.raw("menu.dungeon-leave-lore"))),
                    p -> plugin.instances().leave(p));
        }
        set(49, Items.of(Material.ARROW, Msg.raw("menu.back-to-hub"), List.of()),
                p -> new MainMenu(plugin, p).open());
        set(53, Items.of(Material.BARRIER, Msg.raw("menu.button-close"), List.of()),
                p -> p.closeInventory());
    }

    /** 状态图标：空闲 / 副本进行中（阶段） / 已通关待离场。 */
    private org.bukkit.inventory.ItemStack statusIcon() {
        Party party = plugin.parties().partyOf(player.getUniqueId());
        if (party == null) {
            return Items.of(Material.NAME_TAG, Msg.raw("menu.dungeon-no-party"),
                    List.of(Msg.raw("menu.dungeon-no-party-lore")));
        }
        var instance = plugin.instances().byRun(party.runId());
        if (instance == null) {
            return Items.of(Material.NAME_TAG, Msg.raw("menu.dungeon-party-idle",
                            "size", String.valueOf(party.members().size())),
                    List.of(Msg.raw("menu.dungeon-start-hint")));
        }
        List<String> lore = new ArrayList<>();
        lore.add(Msg.raw("menu.dungeon-status-phase", "phase", String.valueOf(instance.phase)));
        lore.add(Msg.raw("menu.dungeon-status-run", "run", instance.runId.substring(0, 8)));
        return Items.of(Material.LODESTONE, Msg.raw("menu.dungeon-status-running"), lore);
    }

    private org.bukkit.inventory.ItemStack dungeonIcon(String id) {
        var def = io.sakurammo.instance.DungeonDefinition.load(plugin, id);
        String display = def == null ? id : def.displayName;
        List<String> lore = new ArrayList<>();
        if (def != null) {
            lore.add(Msg.raw("menu.dungeon-entry-level", "level", String.valueOf(def.minLevel)));
            lore.add(Msg.raw("menu.dungeon-entry-members",
                    "min", String.valueOf(def.minMembers), "max", String.valueOf(def.maxMembers)));
            lore.add(Msg.raw("menu.dungeon-entry-boss-count", "count", "3"));

            // 掉落说明：必掉材料 / 装备概率 / 保底
            List<String> drops = plugin.bossDrops().describe(id);
            if (!drops.isEmpty()) {
                lore.add("");
                lore.add(Msg.raw("menu.dungeon-entry-drops-title"));
                lore.add(Msg.raw("menu.dungeon-drop-materials", "list", drops.get(0)));
                if (drops.size() >= 2) {
                    lore.add(Msg.raw("menu.dungeon-drop-items", "list", drops.get(1)));
                }
                if (drops.size() >= 3) {
                    lore.add(Msg.raw("menu.dungeon-drop-guarantee"));
                }
            }
        } else {
            lore.add(Msg.raw("menu.dungeon-entry-broken"));
        }
        lore.add("");
        lore.add(Msg.raw("menu.dungeon-entry-click"));
        return Items.of(Material.BRICKS,
                Msg.raw("menu.dungeon-entry-name", "display", display, "id", id), lore);
    }
}
