package io.sakurammo.menu;

import io.sakurammo.SakuraMMOPlugin;
import io.sakurammo.player.PlayerProfile;
import io.sakurammo.talent.TalentManager;
import io.sakurammo.talent.TalentNode;
import io.sakurammo.util.Msg;
import io.sakurammo.util.Text;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;

import java.util.ArrayList;
import java.util.List;

/**
 * 天赋树 GUI（/sm talent 或 主菜单入口）。
 * <pre>
 *  4   职业与可用点数
 *  19/28/37  分支 A 节点（自上而下，需前置）
 *  25/34/43  分支 B 节点
 *  49  洗点（花费金币，全额返还）
 *  23  返回主菜单   53 关闭
 * </pre>
 * 左键加点、右键减点（免费微调，不得破坏其他节点前置）。
 */
public final class TalentMenu extends Menu {

    public TalentMenu(SakuraMMOPlugin plugin, Player player) {
        super(plugin, player, 6, Msg.raw("talent.title"));
    }

    @Override
    protected void build() {
        PlayerProfile profile = plugin.playerData().getOrLoad(player);
        if (!profile.hasClass()) {
            set(22, Items.of(Material.NAME_TAG, Msg.raw("menu.skills-no-class"), null));
            fill(fillerMaterial());
            return;
        }
        var definition = plugin.config().classOf(profile.classId());
        set(4, Items.of(definition == null || definition.icon() == null
                        ? Material.NETHER_STAR : definition.icon(),
                Msg.raw("talent.header", "display",
                        definition == null ? "?" : definition.displayName(),
                        "points", String.valueOf(profile.talentPoints()),
                        "earned", String.valueOf(TalentManager.earnedPoints(profile.level()))),
                List.of(Msg.raw("talent.header-lore",
                        "level", String.valueOf(profile.level()),
                        "exp", String.valueOf(profile.experience()),
                        "need", String.valueOf(TalentManager.experienceForLevel(profile.level()))))));

        List<TalentNode> branchA = plugin.talents().branchOf(profile.classId(), true);
        List<TalentNode> branchB = plugin.talents().branchOf(profile.classId(), false);
        int[] slotsA = {19, 28, 37};
        int[] slotsB = {25, 34, 43};
        for (int i = 0; i < branchA.size() && i < slotsA.length; i++) {
            final TalentNode node = branchA.get(i);
            set(slotsA[i], nodeIcon(profile, node), p -> handleClick(p, node));
        }
        for (int i = 0; i < branchB.size() && i < slotsB.length; i++) {
            final TalentNode node = branchB.get(i);
            set(slotsB[i], nodeIcon(profile, node), p -> handleClick(p, node));
        }

        set(49, Items.of(Material.MILK_BUCKET,
                        Msg.raw("talent.reset-button"),
                        List.of(Msg.raw("talent.reset-button-lore",
                                "cost", Text.number(plugin.talents().resetCost())))),
                p -> {
                    if (plugin.talents().reset(p)) {
                        new TalentMenu(plugin, p).open();
                    }
                });
        set(23, Items.of(Material.ARROW, Msg.raw("menu.back-to-hub"), List.of()),
                p -> new MainMenu(plugin, p).open());
        set(53, Items.of(Material.BARRIER, Msg.raw("menu.button-close"), List.of()),
                p -> p.closeInventory());
        fill(fillerMaterial());
    }

    private org.bukkit.inventory.ItemStack nodeIcon(PlayerProfile profile, TalentNode node) {
        int level = plugin.talents().levelOf(profile, node);
        boolean maxed = level >= node.max();
        boolean met = plugin.talents().requirementMet(profile, node);
        List<String> lore = new ArrayList<>();
        lore.add(Msg.raw("talent.node-desc", "desc", node.desc()));
        if (level > 0) {
            lore.add(Msg.raw("talent.node-current", "value",
                    Text.number(level * node.perLevel())));
        }
        if (!met) {
            TalentNode required = plugin.talents().node(node.requiresNode());
            lore.add(Msg.raw("talent.node-requirement", "name",
                    required == null ? String.valueOf(node.requiresNode()) : required.name(),
                    "points", String.valueOf(node.requiresPoints())));
        }
        lore.add("");
        lore.add(Msg.raw(maxed ? "talent.node-maxed" : "talent.node-left-click"));
        if (level > 0) {
            lore.add(Msg.raw("talent.node-right-click"));
        }
        Material material = node.icon() == null ? Material.PAPER
                : (level > 0 ? node.icon() : Material.GRAY_DYE);
        String name = Msg.raw(level > 0 ? "talent.node-name" : "talent.node-name-empty",
                "name", node.name(), "level", String.valueOf(level), "max", String.valueOf(node.max()));
        return Items.of(level > 0 || met ? material : Material.COAL, name, lore);
    }

    private void handleClick(Player player, TalentNode node) {
        if (clickType == ClickType.RIGHT) {
            plugin.talents().removePoint(player, node);
        } else {
            plugin.talents().addPoint(player, node);
        }
        new TalentMenu(plugin, player).open(); // 刷新
    }
}
