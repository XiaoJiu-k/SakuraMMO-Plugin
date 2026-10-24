package io.sakurammo.menu;

import io.sakurammo.SakuraMMOPlugin;
import io.sakurammo.weekly.UpgradeManager;
import io.sakurammo.weekly.UpgradeManager.ProductDef;
import io.sakurammo.util.Msg;
import io.sakurammo.util.Text;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 装备追赶菜单（/sm upgrade）：手持旧装备 → 点击升级（继承词条）。
 * <pre>
 *  4  代币余额
 *  10/12  升级项（点击 = 手持升级）
 *  23 返回主菜单   25 关闭
 * </pre>
 */
public final class UpgradeMenu extends Menu {

    private static final int[] SLOTS = {10, 12, 14, 16};

    public UpgradeMenu(SakuraMMOPlugin plugin, Player player) {
        super(plugin, player, 3, Msg.raw("upgrade.title"));
    }

    @Override
    protected void build() {
        var profile = plugin.playerData().getOrLoad(player);
        set(4, Items.of(Material.ANVIL,
                Msg.raw("upgrade.header", "tokens", String.valueOf(profile.tokens())),
                List.of(Msg.raw("upgrade.header-lore"))));
        int i = 0;
        for (ProductDef def : plugin.upgrades().upgrades().values()) {
            if (i >= SLOTS.length) {
                break;
            }
            final ProductDef item = def;
            List<String> lore = new ArrayList<>(def.lore());
            lore.add(Msg.raw("upgrade.line-from", "item", def.fromItem()));
            for (Map.Entry<String, Integer> entry : def.costMaterials().entrySet()) {
                var mat = plugin.crafting().material(entry.getKey());
                int have = plugin.crafting().countMaterial(player, entry.getKey());
                lore.add(Msg.raw(have >= entry.getValue() ? "craft.line-ok" : "craft.line-missing",
                        "name", mat == null ? entry.getKey() : mat.name(),
                        "have", String.valueOf(have), "need", String.valueOf(entry.getValue())));
            }
            if (def.fee() > 0) {
                lore.add(Msg.raw("upgrade.line-fee", "fee", Text.number(def.fee())));
            }
            if (def.tokenCost() > 0) {
                lore.add(Msg.raw("upgrade.line-token", "cost", String.valueOf(def.tokenCost())));
            }
            lore.add("");
            lore.add(Msg.raw("upgrade.line-click"));
            set(SLOTS[i], Items.of(def.item(),
                    Msg.raw("upgrade.item-name", "color", def.color(), "display", def.display()), lore),
                    p -> {
                        if (plugin.upgrades().upgradeHand(p, item)) {
                            p.closeInventory();
                        }
                    });
            i++;
        }
        set(23, Items.of(Material.ARROW, Msg.raw("menu.back-to-hub"), List.of()),
                p -> new MainMenu(plugin, p).open());
        set(25, Items.of(Material.BARRIER, Msg.raw("menu.button-close"), List.of()),
                p -> p.closeInventory());
        fill(fillerMaterial());
    }
}
