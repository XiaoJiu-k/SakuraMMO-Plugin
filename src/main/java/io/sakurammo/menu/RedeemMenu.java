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
 * 代币兑换菜单（/sm redeem）：周常/Boss/团本产出的代币换保底装备（防脸黑）。
 * <pre>
 *  4  代币余额
 *  10/12/14/16  兑换项
 *  23 返回主菜单   25 关闭
 * </pre>
 */
public final class RedeemMenu extends Menu {

    private static final int[] SLOTS = {10, 12, 14, 16};

    public RedeemMenu(SakuraMMOPlugin plugin, Player player) {
        super(plugin, player, 3, Msg.raw("redeem.title"));
    }

    @Override
    protected void build() {
        var profile = plugin.playerData().getOrLoad(player);
        set(4, Items.of(Material.SUNFLOWER,
                Msg.raw("redeem.header", "tokens", String.valueOf(profile.tokens())),
                List.of(Msg.raw("redeem.header-lore"))));
        int i = 0;
        for (ProductDef def : plugin.upgrades().redeems().values()) {
            if (i >= SLOTS.length) {
                break;
            }
            final ProductDef item = def;
            List<String> lore = new ArrayList<>(def.lore());
            for (Map.Entry<String, Double> entry : def.attributes().entrySet()) {
                lore.add("&b" + entry.getKey() + (entry.getValue() >= 0 ? " +" : " ")
                        + Text.number(entry.getValue()));
            }
            lore.add(Msg.raw("redeem.line-token", "cost", String.valueOf(def.tokenCost())));
            lore.add("");
            lore.add(Msg.raw(profile.tokens() >= def.tokenCost()
                    ? "redeem.line-click" : "redeem.line-no-token"));
            set(SLOTS[i], Items.of(def.item(),
                    Msg.raw("upgrade.item-name", "color", def.color(), "display", def.display()), lore),
                    p -> {
                        if (plugin.upgrades().redeem(p, item)) {
                            new RedeemMenu(plugin, p).open();
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
