package io.sakurammo.menu;

import io.sakurammo.SakuraMMOPlugin;
import io.sakurammo.classsystem.ClassManager;
import io.sakurammo.config.ClassDefinition;
import io.sakurammo.config.MainConfig;
import io.sakurammo.player.PlayerProfile;
import io.sakurammo.util.Msg;
import io.sakurammo.util.Text;
import io.sakurammo.util.TimeFormat;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

/**
 * 切换职业的二次确认。切换有冷却和费用，不能一点就生效。
 */
public final class ConfirmSwitchMenu extends Menu {

    private final ClassDefinition target;

    public ConfirmSwitchMenu(SakuraMMOPlugin plugin, Player player, ClassDefinition target) {
        super(plugin, player, 3, Msg.raw("menu.confirm-title"));
        this.target = target;
    }

    @Override
    protected void build() {
        PlayerProfile profile = plugin.playerData().getOrLoad(player);
        MainConfig config = plugin.config().main();
        ClassDefinition current = plugin.config().classOf(profile.classId());

        List<String> lore = new ArrayList<>();
        lore.add(Msg.raw("menu.confirm-lore",
                "old", current == null ? "-" : current.displayName(),
                "new", target.displayName()));
        if (config.switchCost() > 0.0D) {
            lore.add(Msg.raw("menu.confirm-cost", "cost", Text.number(config.switchCost())));
        }
        long remaining = profile.switchCooldownUntil() - System.currentTimeMillis();
        if (remaining > 0) {
            lore.add(Msg.raw("menu.confirm-cooldown", "time", TimeFormat.humanize(remaining)));
        }

        Material icon = target.icon() == null ? Material.SHIELD : target.icon();
        set(13, Items.of(icon,
                Msg.raw("menu.class-name", "display", target.displayName(), "id", target.id()),
                lore));

        set(11, Items.of(Material.LIME_CONCRETE,
                        Msg.raw("menu.button-confirm"), null),
                p -> confirm());

        set(15, Items.of(Material.RED_CONCRETE,
                        Msg.raw("menu.button-cancel"), null),
                p -> new ClassMenu(plugin, p).open());

        fill(fillerMaterial());
    }

    private void confirm() {
        ClassManager.Result result = plugin.classManager().switchTo(player, target.id());
        if (result.message() != null) {
            player.sendMessage(Msg.prefix() + result.message());
        }
        if (result.ok()) {
            new MainMenu(plugin, player).open();
        } else {
            new ClassMenu(plugin, player).open();
        }
    }
}
