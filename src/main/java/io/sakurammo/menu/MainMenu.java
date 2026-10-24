package io.sakurammo.menu;

import io.sakurammo.SakuraMMOPlugin;
import io.sakurammo.config.ClassDefinition;
import io.sakurammo.config.ResourceDefinition;
import io.sakurammo.config.SkillDefinition;
import io.sakurammo.player.PlayerProfile;
import io.sakurammo.player.SkillBinding;
import io.sakurammo.skill.SkillService;
import io.sakurammo.util.Msg;
import io.sakurammo.util.Text;
import io.sakurammo.util.TimeFormat;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 角色面板（按 F 打开）。
 * <pre>
 *  4  职业      13 资源
 * 19-23 技能（点击绑定到主手）
 * 40 切换职业  49 关闭
 * </pre>
 */
public final class MainMenu extends Menu {

    public MainMenu(SakuraMMOPlugin plugin, Player player) {
        super(plugin, player, 6, Msg.raw("menu.main-title"));
    }

    @Override
    protected void build() {
        PlayerProfile profile = plugin.playerData().getOrLoad(player);
        ClassDefinition definition = profile.hasClass() ? plugin.config().classOf(profile.classId()) : null;
        if (definition == null) {
            return;
        }

        set(4, classIcon(definition, profile), p -> new ClassMenu(plugin, p).open());

        ResourceDefinition primary = definition.primaryResource();
        if (primary != null) {
            set(13, resourceIcon(primary));
        }

        int slot = 19;
        for (String skillId : definition.skills()) {
            SkillDefinition skill = plugin.config().skill(skillId);
            if (skill == null) {
                continue;
            }
            set(slot++, skillIcon(skill, profile), p -> bind(skill));
            if (slot > 23) {
                break;
            }
        }

        set(40, Items.of(Material.NETHER_STAR,
                        Msg.raw("menu.button-switch-class"),
                        List.of(Msg.raw("menu.button-switch-class-lore"))),
                p -> new ClassMenu(plugin, p).open());

        set(49, Items.of(Material.BARRIER,
                        Msg.raw("menu.button-close"), null),
                p -> p.closeInventory());

        fill(fillerMaterial());
    }

    // ---------------- 图标 ----------------

    private org.bukkit.inventory.ItemStack classIcon(ClassDefinition definition, PlayerProfile profile) {
        Material material = definition.icon() == null ? Material.SHIELD : definition.icon();
        List<String> lore = new ArrayList<>();
        lore.add(Msg.raw("menu.class-lore-role", "role", definition.role()));
        if (!definition.description().isBlank()) {
            lore.add(Msg.raw("menu.class-lore-desc", "description", definition.description()));
        }
        lore.add(Msg.raw("menu.class-lore-stats", "stats", statsText(definition)));
        lore.add(Msg.raw("menu.class-lore-resources", "resources", resourcesText(definition, profile)));
        lore.add("");
        lore.add(Msg.raw("menu.class-lore-switch-hint"));
        return Items.of(material,
                Msg.raw("menu.class-name", "display", definition.displayName(), "id", definition.id()),
                lore);
    }

    private org.bukkit.inventory.ItemStack resourceIcon(ResourceDefinition resource) {
        List<String> lore = new ArrayList<>();
        lore.add(Msg.raw("menu.resource-lore-regen", "regen", Text.number(resource.regenPerSecond())));
        lore.add(Msg.raw("menu.resource-lore-events",
                "on_damage", Text.number(resource.onDamageTaken()),
                "on_attack", Text.number(resource.onAttack()),
                "on_kill", Text.number(resource.onKill())));
        return Items.of(Material.EXPERIENCE_BOTTLE,
                Msg.raw("menu.resource-name",
                        "display", resource.displayName(),
                        "current", Text.number(plugin.resources().get(player, resource.id())),
                        "max", Text.number(resource.max())),
                lore);
    }

    private org.bukkit.inventory.ItemStack skillIcon(SkillDefinition skill, PlayerProfile profile) {
        List<String> lore = new ArrayList<>();
        lore.add(Msg.raw("menu.skill-lore-type",
                "type", skill.trigger().bindable() ? Msg.raw("menu.trigger-active") : Msg.raw("menu.trigger-passive")));
        if (skill.cost() > 0) {
            lore.add(Msg.raw("menu.skill-lore-cost",
                    "cost", Text.number(skill.cost()),
                    "resource", resourceDisplay(skill)));
        }
        if (skill.cooldownSeconds() > 0) {
            lore.add(Msg.raw("menu.skill-lore-cooldown",
                    "cooldown", Text.number(skill.cooldownSeconds())));
        }
        long remaining = profile.cooldownUntil(skill.id()) - System.currentTimeMillis();
        if (remaining > 0) {
            lore.add(Msg.raw("menu.skill-lore-cooldown-now", "time", TimeFormat.humanize(remaining)));
        }

        int slot = player.getInventory().getHeldItemSlot();
        SkillBinding binding = profile.boundSkills().get(slot);
        if (binding != null && binding.skillId().equals(skill.id())) {
            lore.add(Msg.raw("menu.skill-lore-bound", "slot", String.valueOf(slot)));
        } else {
            lore.add(Msg.raw("menu.skill-lore-unbound"));
        }
        lore.add("");
        lore.add(Msg.raw(skill.trigger().bindable()
                ? "menu.skill-lore-click-bind" : "menu.skill-lore-passive"));

        return Items.of(skill.iconOrDefault(), Msg.raw("menu.skill-name", "display", skill.displayName()), lore);
    }

    // ---------------- 交互 ----------------

    private void bind(SkillDefinition skill) {
        if (!skill.trigger().bindable()) {
            player.sendMessage(Msg.get("skill.passive-not-bindable", "skill", skill.displayName()));
            return;
        }
        SkillService.Result result = plugin.skills().bind(player, skill.id());
        if (result.message() != null) {
            player.sendMessage(Msg.prefix() + result.message());
        }
        open();
    }

    // ---------------- 文本 ----------------

    private String statsText(ClassDefinition definition) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Double> entry : definition.stats().entrySet()) {
            if (!sb.isEmpty()) {
                sb.append("&7, ");
            }
            sb.append("&f").append(entry.getKey()).append(" &a+").append(Text.number(entry.getValue()));
        }
        return sb.isEmpty() ? Msg.raw("menu.none") : sb.toString();
    }

    private String resourcesText(ClassDefinition definition, PlayerProfile profile) {
        StringBuilder sb = new StringBuilder();
        for (ResourceDefinition resource : definition.resources().values()) {
            if (resource.reserved()) {
                continue;
            }
            if (!sb.isEmpty()) {
                sb.append("&7, ");
            }
            sb.append(resource.displayName()).append(" &f")
                    .append(Text.number(plugin.resources().get(player, resource.id())))
                    .append("&7/&f").append(Text.number(resource.max()));
        }
        return sb.isEmpty() ? Msg.raw("menu.none") : sb.toString();
    }

    private String resourceDisplay(SkillDefinition skill) {
        ClassDefinition definition = plugin.config().classOf(skill.classId());
        ResourceDefinition resource = definition == null ? null : definition.resource(skill.resource());
        return resource == null ? skill.resource() : Text.stripColor(resource.displayName());
    }
}
