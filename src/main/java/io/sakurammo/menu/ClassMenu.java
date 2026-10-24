package io.sakurammo.menu;

import io.sakurammo.SakuraMMOPlugin;
import io.sakurammo.classsystem.ClassManager;
import io.sakurammo.config.ClassDefinition;
import io.sakurammo.config.ConfigManager;
import io.sakurammo.config.ResourceDefinition;
import io.sakurammo.player.PlayerProfile;
import io.sakurammo.util.Msg;
import io.sakurammo.util.Text;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 职业选择 / 切换菜单（3 行，7 个职业放在 10–16）。
 */
public final class ClassMenu extends Menu {

    public ClassMenu(SakuraMMOPlugin plugin, Player player) {
        super(plugin, player, 3, Msg.raw("menu.class-title"));
    }

    @Override
    protected void build() {
        PlayerProfile profile = plugin.playerData().getOrLoad(player);
        boolean hasClass = profile.hasClass();

        int slot = 10;
        for (String id : ConfigManager.DEFAULT_CLASS_IDS) {
            ClassDefinition definition = plugin.config().classOf(id);
            if (definition == null) {
                continue;
            }
            boolean current = hasClass && id.equals(profile.classId());
            set(slot++, classIcon(definition, current, hasClass), p -> pick(definition));
        }

        set(22, Items.of(Material.ARROW,
                        Msg.raw("menu.button-back"), null),
                p -> {
                    if (plugin.playerData().getOrLoad(p).hasClass()) {
                        new MainMenu(plugin, p).open();
                    } else {
                        p.closeInventory();
                    }
                });

        set(26, Items.of(Material.BARRIER, Msg.raw("menu.button-close"), null),
                p -> p.closeInventory());

        fill(fillerMaterial());
    }

    private org.bukkit.inventory.ItemStack classIcon(ClassDefinition definition, boolean current, boolean hasClass) {
        Material material = definition.icon() == null ? Material.SHIELD : definition.icon();
        List<String> lore = new ArrayList<>();
        lore.add(Msg.raw("menu.class-lore-role", "role", definition.role()));
        if (!definition.description().isBlank()) {
            lore.add(Msg.raw("menu.class-lore-desc", "description", definition.description()));
        }
        lore.add(Msg.raw("menu.class-lore-stats", "stats", statsText(definition)));
        lore.add(Msg.raw("menu.class-lore-resources", "resources", resourcesText(definition)));
        lore.add("");
        if (current) {
            lore.add(Msg.raw("menu.class-lore-current"));
        } else if (!hasClass) {
            lore.add(Msg.raw("menu.class-lore-click-select"));
        } else {
            lore.add(Msg.raw("menu.class-lore-click-switch"));
        }
        String name = Msg.raw("menu.class-name",
                "display", definition.displayName(), "id", definition.id());
        return Items.of(material, current ? "&a" + Text.stripColor(name) : name, lore);
    }

    private void pick(ClassDefinition definition) {
        PlayerProfile profile = plugin.playerData().getOrLoad(player);
        if (!profile.hasClass()) {
            ClassManager.Result result = plugin.classManager().select(player, definition.id());
            if (result.message() != null) {
                player.sendMessage(Msg.prefix() + result.message());
            }
            if (result.ok()) {
                new MainMenu(plugin, player).open();
            } else {
                open();
            }
            return;
        }
        if (definition.id().equals(profile.classId())) {
            player.sendMessage(Msg.get("class.same-class", "class", definition.displayName()));
            open();
            return;
        }
        new ConfirmSwitchMenu(plugin, player, definition).open();
    }

    private String statsText(ClassDefinition definition) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Double> entry : definition.stats().entrySet()) {
            if (!sb.isEmpty()) {
                sb.append("&7, ");
            }
            sb.append("&f").append(Text.statCN(entry.getKey())).append(" &a+").append(Text.number(entry.getValue()));
        }
        return sb.isEmpty() ? Msg.raw("menu.none") : sb.toString();
    }

    private String resourcesText(ClassDefinition definition) {
        StringBuilder sb = new StringBuilder();
        for (ResourceDefinition resource : definition.resources().values()) {
            if (resource.reserved()) {
                continue;
            }
            if (!sb.isEmpty()) {
                sb.append("&7, ");
            }
            sb.append(resource.displayName()).append(" &70–").append(Text.number(resource.max()));
        }
        return sb.isEmpty() ? Msg.raw("menu.none") : sb.toString();
    }
}
