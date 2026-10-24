package io.sakurammo.menu;

import io.sakurammo.SakuraMMOPlugin;
import io.sakurammo.util.Msg;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.List;

/**
 * 主菜单（按 F / /sm menu 打开）——只有两个入口，与副本菜单同级：
 * <pre>
 *  11  职业（点击进入职业面板：属性/资源/技能/绑定）
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

        set(15, Items.of(Material.BRICKS,
                        Msg.raw("menu.hub-dungeon"),
                        List.of(Msg.raw("menu.hub-dungeon-lore"))),
                p -> new DungeonMenu(plugin, p).open());

        set(22, Items.of(Material.BARRIER, Msg.raw("menu.button-close"), List.of()),
                p -> p.closeInventory());

        fill(fillerMaterial());
    }
}
