package io.sakurammo.menu;

import io.sakurammo.SakuraMMOPlugin;
import io.sakurammo.util.Msg;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

/**
 * 主菜单（按 F / /sm menu 打开）——三个入口：
 * <pre>
 *  11  职业（点击进入职业面板：属性/资源/技能/绑定）
 *  13  周常（点击进入周常任务：进度/领奖/代币，图标显示可领取数）
 *  15  副本（点击进入副本菜单：状态/开本/离场）
 *  22  关闭
 * </pre>
 */
public final class MainMenu extends Menu {

    public MainMenu(SakuraMMOPlugin plugin, Player player) {
        super(plugin, player, 3, Msg.raw("menu.hub-title"));
    }

    @Override
    protected void build() {
        set(11, Items.of(Material.DIAMOND_CHESTPLATE,
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
        set(13, Items.of(claimable > 0 ? Material.GOLD_INGOT : Material.CLOCK,
                        Msg.raw("menu.hub-weekly"), weeklyLore),
                p -> new WeeklyMenu(plugin, p).open());

        set(15, Items.of(Material.BRICKS,
                        Msg.raw("menu.hub-dungeon"),
                        List.of(Msg.raw("menu.hub-dungeon-lore"))),
                p -> new DungeonMenu(plugin, p).open());

        set(22, Items.of(Material.BARRIER, Msg.raw("menu.button-close"), List.of()),
                p -> p.closeInventory());

        fill(fillerMaterial());
    }
}
