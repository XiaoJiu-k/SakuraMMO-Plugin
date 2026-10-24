package io.sakurammo.menu;

import io.sakurammo.SakuraMMOPlugin;
import io.sakurammo.util.Msg;
import io.sakurammo.util.Text;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

/**
 * 主菜单（按 F / /sm menu 打开）——四个入口：
 * <pre>
 *  10  职业（点击进入职业面板：属性/资源/技能/绑定）
 *  12  周常（进度/领奖/代币，图标显示可领取数）
 *  14  制造（底材+金币打造装备，图标显示可制造数与金币余额）
 *  16  副本（点击进入副本菜单：状态/开本/离场）
 *  22  关闭
 * </pre>
 */
public final class MainMenu extends Menu {

    public MainMenu(SakuraMMOPlugin plugin, Player player) {
        super(plugin, player, 3, Msg.raw("menu.hub-title"));
    }

    @Override
    protected void build() {
        set(10, Items.of(Material.DIAMOND_CHESTPLATE,
                        Msg.raw("menu.hub-class"),
                        List.of(Msg.raw("menu.hub-class-lore"))),
                p -> new ClassHubMenu(plugin, p).open());

        // 周常按钮：统计可领取数显示在 lore
        int claimable = 0;
        for (var task : plugin.weeklies().tasks().values()) {
            if (plugin.weeklies().claimable(player, task)) {
                claimable++;
            }
        }
        List<String> weeklyLore = new ArrayList<>();
        weeklyLore.add(Msg.raw("menu.hub-weekly-lore"));
        weeklyLore.add(claimable > 0
                ? Msg.raw("menu.hub-weekly-claimable", "count", String.valueOf(claimable))
                : Msg.raw("menu.hub-weekly-none"));
        set(12, Items.of(claimable > 0 ? Material.GOLD_INGOT : Material.CLOCK,
                        Msg.raw("menu.hub-weekly"), weeklyLore),
                p -> new WeeklyMenu(plugin, p).open());

        // 制造按钮：统计可制造配方数 + 金币余额
        int craftable = 0;
        for (var recipe : plugin.crafting().recipes().values()) {
            if (plugin.crafting().canCraft(player, recipe)) {
                craftable++;
            }
        }
        List<String> craftLore = new ArrayList<>();
        craftLore.add(Msg.raw("menu.hub-craft-lore"));
        craftLore.add(Msg.raw("menu.hub-craft-money",
                "balance", Text.number(plugin.economy().balanceOf(player))));
        craftLore.add(craftable > 0
                ? Msg.raw("menu.hub-craft-ready", "count", String.valueOf(craftable))
                : Msg.raw("menu.hub-craft-none"));
        set(14, Items.of(craftable > 0 ? Material.CRAFTING_TABLE : Material.STONECUTTER,
                        Msg.raw("menu.hub-craft"), craftLore),
                p -> new CraftMenu(plugin, p).open());

        set(16, Items.of(Material.BRICKS,
                        Msg.raw("menu.hub-dungeon"),
                        List.of(Msg.raw("menu.hub-dungeon-lore"))),
                p -> new DungeonMenu(plugin, p).open());

        set(22, Items.of(Material.BARRIER, Msg.raw("menu.button-close"), List.of()),
                p -> p.closeInventory());

        fill(fillerMaterial());
    }
}
