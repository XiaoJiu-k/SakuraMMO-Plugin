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
 *  10-16  副本列表（点击由队长开本）
 *  21  离开副本（副本中显示）
 *  23  返回角色面板        25  关闭
 * </pre>
 * 开本的全部校验（队长 / 队伍状态 / 人数 / 模板）由 InstanceManager.start 负责，
 * 菜单只做入口与展示。
 */
public final class DungeonMenu extends Menu {

    public DungeonMenu(SakuraMMOPlugin plugin, Player player) {
        super(plugin, player, 3, Msg.raw("menu.dungeon-title"));
    }

    @Override
    protected void build() {
        set(4, statusIcon());
        fill(fillerMaterial());

        List<String> dungeons = plugin.instances().availableDungeons();
        int slot = 10;
        for (String id : dungeons) {
            if (slot > 16) {
                break;
            }
            final String dungeonId = id;
            set(slot, dungeonIcon(dungeonId), p -> plugin.instances().start(p, dungeonId));
            slot += 2; // 10/12/14/16 留缝更易读
        }

        Party party = plugin.parties().partyOf(player.getUniqueId());
        if (party != null && plugin.instances().byRun(party.runId()) != null) {
            set(21, Items.of(Material.OAK_DOOR, Msg.raw("menu.dungeon-leave"),
                            List.of(Msg.raw("menu.dungeon-leave-lore"))),
                    p -> plugin.instances().leave(p));
        }
        set(23, Items.of(Material.ARROW, Msg.raw("menu.back-to-hub"), List.of()),
                p -> new MainMenu(plugin, p).open());
        set(25, Items.of(Material.BARRIER, Msg.raw("menu.button-close"), List.of()),
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
            lore.add(Msg.raw("menu.dungeon-entry-members",
                    "min", String.valueOf(def.minMembers), "max", String.valueOf(def.maxMembers)));
            lore.add(Msg.raw("menu.dungeon-entry-boss",
                    "boss1", def.boss1.mob(), "boss2", def.boss2.mob(), "boss3", def.boss3.mob()));
        } else {
            lore.add(Msg.raw("menu.dungeon-entry-broken"));
        }
        lore.add("");
        lore.add(Msg.raw("menu.dungeon-entry-click"));
        return Items.of(Material.BRICKS,
                Msg.raw("menu.dungeon-entry-name", "display", display, "id", id), lore);
    }
}
