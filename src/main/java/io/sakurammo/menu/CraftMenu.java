package io.sakurammo.menu;

import io.sakurammo.SakuraMMOPlugin;
import io.sakurammo.craft.CraftingManager;
import io.sakurammo.craft.CraftingManager.RecipeDef;
import io.sakurammo.util.Msg;
import io.sakurammo.util.Text;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

/**
 * 制造菜单（主菜单入口 / /sm craft）。
 * <pre>
 *  4   余额与说明
 *  10/12/14/16  配方列表（点击制造；左键）
 *  23  返回主菜单   25 关闭
 * </pre>
 * 需求材料、持有量、手续费直接显示在 lore；材料不足或余额不足时点击会给出提示。
 */
public final class CraftMenu extends Menu {

    private static final int[] RECIPE_SLOTS = {10, 12, 14, 16, 20, 22, 24};

    public CraftMenu(SakuraMMOPlugin plugin, Player player) {
        super(plugin, player, 3, Msg.raw("craft.title"));
    }

    @Override
    protected void build() {
        set(4, Items.of(Material.GOLD_INGOT,
                Msg.raw("craft.header", "balance", Text.number(plugin.economy().balanceOf(player))),
                List.of(Msg.raw("craft.header-lore"))));

        int i = 0;
        for (RecipeDef recipe : plugin.crafting().recipes().values()) {
            if (i >= RECIPE_SLOTS.length) {
                break;
            }
            final RecipeDef def = recipe;
            set(RECIPE_SLOTS[i], recipeIcon(def), p -> {
                if (plugin.crafting().craft(p, def) != null) {
                    new CraftMenu(plugin, p).open(); // 刷新余量
                }
            });
            i++;
        }

        set(20, Items.of(Material.ANVIL, Msg.raw("craft.goto-upgrade"),
                        List.of(Msg.raw("craft.goto-upgrade-lore"))),
                p -> new UpgradeMenu(plugin, p).open());
        set(22, Items.of(Material.SUNFLOWER, Msg.raw("craft.goto-redeem"),
                        List.of(Msg.raw("craft.goto-redeem-lore"))),
                p -> new RedeemMenu(plugin, p).open());
        set(23, Items.of(Material.ARROW, Msg.raw("menu.back-to-hub"), List.of()),
                p -> new MainMenu(plugin, p).open());
        set(25, Items.of(Material.BARRIER, Msg.raw("menu.button-close"), List.of()),
                p -> p.closeInventory());
        fill(fillerMaterial());
    }

    private org.bukkit.inventory.ItemStack recipeIcon(RecipeDef recipe) {
        List<String> lore = new ArrayList<>();
        for (CraftingManager.CostEntry entry : recipe.costs()) {
            CraftingManager.MaterialDef material = plugin.crafting().material(entry.materialId());
            String name = material == null ? entry.materialId() : material.name();
            String color = material == null ? "&f" : material.color();
            int have = plugin.crafting().countMaterial(player, entry.materialId());
            boolean enough = have >= entry.amount();
            lore.add(Msg.raw(enough ? "craft.line-ok" : "craft.line-missing",
                    "name", color + name,
                    "have", String.valueOf(have),
                    "need", String.valueOf(entry.amount())));
        }
        lore.add(Msg.raw("craft.line-fee", "fee", Text.number(recipe.fee())));
        lore.add("");
        boolean can = plugin.crafting().canCraft(player, recipe);
        lore.add(Msg.raw(can ? "craft.click-craft" : "craft.cannot-craft"));
        return Items.of(recipe.item(), Msg.raw("craft.recipe-name",
                "color", recipe.color(), "display", recipe.display()), lore);
    }
}
