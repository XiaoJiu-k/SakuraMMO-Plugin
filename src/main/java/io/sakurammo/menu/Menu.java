package io.sakurammo.menu;

import io.sakurammo.SakuraMMOPlugin;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 菜单基类。实现 InventoryHolder，监听器靠 {@code holder instanceof Menu} 识别自家界面，
 * 不会误伤箱子、工作台等原版容器。
 */
public abstract class Menu implements InventoryHolder {

    protected final SakuraMMOPlugin plugin;
    protected final Player player;
    protected final Inventory inventory;
    private final Map<Integer, Consumer<Player>> actions = new HashMap<>();

    protected Menu(SakuraMMOPlugin plugin, Player player, int rows, String title) {
        this.plugin = plugin;
        this.player = player;
        this.inventory = Bukkit.createInventory(this, rows * 9, Items.component(title));
    }

    protected abstract void build();

    /** 构建并打开。重复调用即「刷新」。 */
    public void open() {
        actions.clear();
        inventory.clear();
        build();
        player.openInventory(inventory);
    }

    public void click(int slot) {
        Consumer<Player> action = actions.get(slot);
        if (action != null) {
            action.accept(player);
        }
    }

    protected void set(int slot, ItemStack item) {
        set(slot, item, null);
    }

    protected void set(int slot, ItemStack item, Consumer<Player> action) {
        if (slot < 0 || slot >= inventory.getSize()) {
            return;
        }
        inventory.setItem(slot, item);
        if (action != null) {
            actions.put(slot, action);
        }
    }

    /** 用填充物补齐所有空位。放在 build() 最后调用。 */
    protected void fill(Material filler) {
        if (filler == null || !filler.isItem()) {
            filler = Material.GRAY_STAINED_GLASS_PANE;
        }
        ItemStack pane = Items.of(filler, " ", null);
        for (int i = 0; i < inventory.getSize(); i++) {
            if (inventory.getItem(i) == null) {
                inventory.setItem(i, pane);
            }
        }
    }

    protected Material fillerMaterial() {
        return Items.materialOr(plugin.config().main().menuFiller(), Material.GRAY_STAINED_GLASS_PANE);
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }
}
