package io.sakurammo.menu;

import io.sakurammo.SakuraMMOPlugin;
import io.sakurammo.player.PlayerProfile;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;

/**
 * 菜单总控：打开入口分发、点击分发、拖拽拦截。
 * <p>
 * 打开方式（v2 按键布局）：潜行 + F（PlayerListener 拦截副手交换）、
 * V 键（InputEngine 桥接，KeyBridgeHook 分发）、/sm menu 命令。
 * F 单独按下已改为技能槽，不再开菜单。
 */
public final class MenuManager implements Listener {

    private final SakuraMMOPlugin plugin;

    public MenuManager(SakuraMMOPlugin plugin) {
        this.plugin = plugin;
    }

    public void register() {
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    /** 打开入口：没选职业直接进职业选择，选过则进角色面板。 */
    public void open(Player player) {
        PlayerProfile profile = plugin.playerData().getOrLoad(player);
        if (!profile.hasClass()) {
            new ClassMenu(plugin, player).open();
            return;
        }
        new MainMenu(plugin, player).open();
    }

    // ---------------- 点击 ----------------

    @EventHandler(priority = EventPriority.LOWEST)
    public void onClick(InventoryClickEvent event) {
        Inventory top = event.getView().getTopInventory();
        if (!(top.getHolder() instanceof Menu menu)) {
            return;
        }
        event.setCancelled(true);
        if (event.getClickedInventory() == null || !event.getClickedInventory().equals(top)) {
            return; // 点在玩家自己背包里，只拦不处理
        }
        if (event.getWhoClicked() instanceof Player) {
            menu.click(event.getRawSlot(), event.getClick());
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof Menu) {
            event.setCancelled(true);
        }
    }
}
