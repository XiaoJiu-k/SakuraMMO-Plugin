package io.sakurammo.command;

import io.sakurammo.SakuraMMOPlugin;
import io.sakurammo.classsystem.ClassManager;
import io.sakurammo.config.ClassDefinition;
import io.sakurammo.config.ResourceDefinition;
import io.sakurammo.config.SkillDefinition;
import io.sakurammo.player.PlayerProfile;
import io.sakurammo.player.SkillBinding;
import io.sakurammo.skill.SkillService;
import io.sakurammo.util.Msg;
import io.sakurammo.util.Text;
import io.sakurammo.util.TimeFormat;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * /sm 主命令。别名 /sakurammo。
 */
public final class SakuraCommand implements CommandExecutor, TabCompleter {

    private static final List<String> ROOT = List.of("class", "resource", "bind", "unbind", "reload", "debug");
    private static final List<String> CLASS_SUB = List.of("list", "select", "switch", "info");

    private final SakuraMMOPlugin plugin;

    public SakuraCommand(SakuraMMOPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (args.length == 0) {
            help(sender, label);
            return true;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "class" -> {
                return handleClass(sender, args);
            }
            case "resource" -> {
                return handleResource(sender, args);
            }
            case "bind" -> {
                return handleBind(sender, args);
            }
            case "unbind" -> {
                return handleUnbind(sender);
            }
            case "reload" -> {
                return handleReload(sender);
            }
            case "debug" -> {
                return handleDebug(sender);
            }
            default -> {
                Msg.send(sender, "general.unknown-subcommand", "sub", sub);
                return true;
            }
        }
    }

    private void help(CommandSender sender, String label) {
        sender.sendMessage(Text.color("&8&m────────&r &dSakuraMMO &8&m────────"));
        sender.sendMessage(Text.color("&f/" + label + " class list &8— &7列出可选职业"));
        sender.sendMessage(Text.color("&f/" + label + " class select <id> &8— &7选择职业"));
        sender.sendMessage(Text.color("&f/" + label + " class switch <id> &8— &7切换职业"));
        sender.sendMessage(Text.color("&f/" + label + " class info [player] &8— &7查看职业信息"));
        sender.sendMessage(Text.color("&f/" + label + " resource &8— &7查看当前资源"));
        sender.sendMessage(Text.color("&f/" + label + " resource set <player> <res> <val> &8— &7调试"));
        sender.sendMessage(Text.color("&f/" + label + " bind <skillId> &8— &7绑定技能到主手物品"));
        sender.sendMessage(Text.color("&f/" + label + " unbind &8— &7解除当前槽位绑定"));
        sender.sendMessage(Text.color("&f/" + label + " reload &8— &7重载配置"));
        sender.sendMessage(Text.color("&f/" + label + " debug &8— &7调试信息"));
    }

    // ---------------- /sm class ----------------

    private boolean handleClass(CommandSender sender, String[] args) {
        if (args.length < 2) {
            Msg.send(sender, "general.unknown-subcommand", "sub", "class");
            return true;
        }
        String action = args[1].toLowerCase(Locale.ROOT);
        switch (action) {
            case "list" -> {
                return listClasses(sender);
            }
            case "select", "switch" -> {
                if (!(sender instanceof Player player)) {
                    Msg.send(sender, "general.player-only");
                    return true;
                }
                if (args.length < 3) {
                    sender.sendMessage(Text.color("&7用法：&f/sm class " + action + " <id>"));
                    return true;
                }
                ClassManager.Result result = action.equals("select")
                        ? plugin.classManager().select(player, args[2].toLowerCase(Locale.ROOT))
                        : plugin.classManager().switchTo(player, args[2].toLowerCase(Locale.ROOT));
                send(sender, result);
                return true;
            }
            case "info" -> {
                return info(sender, args);
            }
            default -> {
                Msg.send(sender, "general.unknown-subcommand", "sub", "class " + action);
                return true;
            }
        }
    }

    private boolean listClasses(CommandSender sender) {
        Msg.sendRaw(sender, "class.list-header");
        Player player = sender instanceof Player p ? p : null;
        PlayerProfile profile = player == null ? null : plugin.playerData().get(player.getUniqueId());
        String currentId = profile == null ? null : profile.classId();

        for (String id : io.sakurammo.config.ConfigManager.DEFAULT_CLASS_IDS) {
            ClassDefinition definition = plugin.config().classOf(id);
            if (definition == null) {
                continue;
            }
            boolean current = id.equals(currentId);
            Msg.sendRaw(sender, current ? "class.list-entry-current" : "class.list-entry",
                    "id", definition.id(),
                    "display", definition.displayName(),
                    "role", definition.role());
        }
        Msg.sendRaw(sender, "class.list-footer");
        Msg.sendRaw(sender, "class.list-hint");
        return true;
    }

    private boolean info(CommandSender sender, String[] args) {
        Player target;
        if (args.length >= 3) {
            target = Bukkit.getPlayerExact(args[2]);
        } else if (sender instanceof Player player) {
            target = player;
        } else {
            Msg.send(sender, "general.player-only");
            return true;
        }
        if (target == null) {
            Msg.send(sender, "general.player-not-found", "player", args.length >= 3 ? args[2] : "?");
            return true;
        }
        PlayerProfile profile = plugin.playerData().getOrLoad(target);
        Msg.sendRaw(sender, "class.info-header");
        sender.sendMessage(Text.color("&7玩家：&f" + target.getName()));
        if (!profile.hasClass()) {
            Msg.sendRaw(sender, "class.info-none");
            return true;
        }
        ClassDefinition definition = plugin.config().classOf(profile.classId());
        if (definition == null) {
            Msg.sendRaw(sender, "class.info-none");
            return true;
        }
        Msg.sendRaw(sender, "class.info-line-class",
                "display", definition.displayName(), "id", definition.id());
        Msg.sendRaw(sender, "class.info-line-role", "role", definition.role());
        Msg.sendRaw(sender, "class.info-line-desc", "description", definition.description());

        StringBuilder stats = new StringBuilder();
        for (Map.Entry<String, Double> entry : definition.stats().entrySet()) {
            if (!stats.isEmpty()) {
                stats.append("&7, ");
            }
            stats.append("&f").append(entry.getKey()).append(" &7+&f").append(Text.number(entry.getValue()));
        }
        Msg.sendRaw(sender, "class.info-line-stats", "stats", stats.isEmpty() ? "&7无" : stats.toString());

        StringBuilder resources = new StringBuilder();
        for (ResourceDefinition resource : definition.resources().values()) {
            if (resource.reserved()) {
                continue;
            }
            if (!resources.isEmpty()) {
                resources.append("&7, ");
            }
            resources.append(resource.displayName()).append(" &7")
                    .append(Text.number(plugin.resources().get(target, resource.id())))
                    .append("/").append(Text.number(resource.max()));
        }
        Msg.sendRaw(sender, "class.info-line-resources",
                "resources", resources.isEmpty() ? "&7无" : resources.toString());

        StringBuilder skills = new StringBuilder();
        for (String skillId : definition.skills()) {
            SkillDefinition skill = plugin.config().skill(skillId);
            if (!skills.isEmpty()) {
                skills.append("&7, ");
            }
            skills.append("&f").append(skill == null ? skillId : skill.displayName());
        }
        Msg.sendRaw(sender, "class.info-line-skills", "skills", skills.isEmpty() ? "&7无" : skills.toString());
        return true;
    }

    // ---------------- /sm resource ----------------

    private boolean handleResource(CommandSender sender, String[] args) {
        if (args.length >= 2 && args[1].equalsIgnoreCase("set")) {
            return resourceSet(sender, args);
        }
        if (!(sender instanceof Player player)) {
            Msg.send(sender, "general.player-only");
            return true;
        }
        PlayerProfile profile = plugin.playerData().getOrLoad(player);
        if (!profile.hasClass()) {
            Msg.send(sender, "class.none-selected");
            return true;
        }
        ClassDefinition definition = plugin.config().classOf(profile.classId());
        if (definition == null) {
            Msg.send(sender, "class.none-selected");
            return true;
        }
        Msg.sendRaw(sender, "resource.header");
        for (ResourceDefinition resource : definition.resources().values()) {
            if (resource.reserved()) {
                continue;
            }
            Msg.sendRaw(sender, "resource.line",
                    "display", resource.displayName(),
                    "current", Text.number(plugin.resources().get(player, resource.id())),
                    "max", Text.number(resource.max()));
        }
        return true;
    }

    private boolean resourceSet(CommandSender sender, String[] args) {
        if (!sender.hasPermission("sakurammo.admin")) {
            Msg.send(sender, "general.no-permission");
            return true;
        }
        if (args.length < 5) {
            sender.sendMessage(Text.color("&7用法：&f/sm resource set <player> <resource> <value>"));
            return true;
        }
        Player target = Bukkit.getPlayerExact(args[2]);
        if (target == null) {
            Msg.send(sender, "general.player-not-found", "player", args[2]);
            return true;
        }
        ResourceDefinition resource = plugin.resources().definitionOf(target, args[3].toLowerCase(Locale.ROOT));
        if (resource == null) {
            Msg.send(sender, "resource.unknown", "id", args[3]);
            return true;
        }
        double value;
        try {
            value = Double.parseDouble(args[4]);
        } catch (NumberFormatException ex) {
            Msg.send(sender, "resource.invalid-number", "value", args[4]);
            return true;
        }
        plugin.resources().set(target, resource.id(), value);
        Msg.send(sender, "resource.set",
                "player", target.getName(),
                "resource", Text.stripColor(resource.displayName()),
                "value", Text.number(plugin.resources().get(target, resource.id())));
        return true;
    }

    // ---------------- /sm bind / unbind ----------------

    private boolean handleBind(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            Msg.send(sender, "general.player-only");
            return true;
        }
        if (args.length < 2) {
            player.sendMessage(Text.color("&7用法：&f/sm bind <skillId>"));
            return true;
        }
        SkillService.Result result = plugin.skills().bind(player, args[1].toLowerCase(Locale.ROOT));
        send(sender, result);
        return true;
    }

    private boolean handleUnbind(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            Msg.send(sender, "general.player-only");
            return true;
        }
        SkillService.Result result = plugin.skills().unbind(player);
        send(sender, result);
        return true;
    }

    // ---------------- /sm reload / debug ----------------

    private boolean handleReload(CommandSender sender) {
        if (!sender.hasPermission("sakurammo.admin")) {
            Msg.send(sender, "general.no-permission");
            return true;
        }
        Msg.send(sender, "reload.start");
        plugin.reloadEverything();
        Msg.send(sender, "reload.done",
                "classes", String.valueOf(plugin.config().classCount()),
                "skills", String.valueOf(plugin.config().skillCount()),
                "messages", String.valueOf(Msg.size()));
        Msg.sendRaw(sender, "reload.playerdata-untouched");
        return true;
    }

    private boolean handleDebug(CommandSender sender) {
        if (!sender.hasPermission("sakurammo.admin")) {
            Msg.send(sender, "general.no-permission");
            return true;
        }
        if (!(sender instanceof Player player)) {
            Msg.send(sender, "general.player-only");
            return true;
        }
        PlayerProfile profile = plugin.playerData().getOrLoad(player);
        Msg.sendRaw(sender, "debug.header");
        Msg.sendRaw(sender, "debug.line-class",
                "class", profile.hasClass() ? profile.classId() : "&7(未选择)");

        StringBuilder resources = new StringBuilder();
        for (Map.Entry<String, Double> entry : plugin.resources().snapshot(player).entrySet()) {
            if (!resources.isEmpty()) {
                resources.append("&7, ");
            }
            resources.append("&f").append(entry.getKey()).append("=")
                    .append(Text.number(entry.getValue()));
        }
        Msg.sendRaw(sender, "debug.line-resources",
                "resources", resources.isEmpty() ? Msg.raw("debug.none") : resources.toString());

        long now = System.currentTimeMillis();
        StringBuilder cooldowns = new StringBuilder();
        for (Map.Entry<String, Long> entry : profile.cooldowns().entrySet()) {
            long remain = entry.getValue() - now;
            if (remain <= 0) {
                continue;
            }
            if (!cooldowns.isEmpty()) {
                cooldowns.append("&7, ");
            }
            cooldowns.append("&f").append(entry.getKey()).append(" &7")
                    .append(TimeFormat.humanize(remain));
        }
        Msg.sendRaw(sender, "debug.line-cooldowns",
                "cooldowns", cooldowns.isEmpty() ? Msg.raw("debug.none") : cooldowns.toString());

        Msg.sendRaw(sender, "debug.line-modifiers",
                "modifiers", plugin.aura().modifierNames(player).isEmpty()
                        ? Msg.raw("debug.none")
                        : String.join("&7, &f", plugin.aura().modifierNames(player)));

        Map<String, Boolean> hooks = new LinkedHashMap<>();
        hooks.put("AuraSkills", plugin.aura().available());
        hooks.put("MythicMobs", plugin.mythic().available());
        hooks.put("Vault", plugin.vault().available());
        hooks.put("PlaceholderAPI", plugin.placeholderRegistered());
        StringBuilder hookText = new StringBuilder();
        for (Map.Entry<String, Boolean> entry : hooks.entrySet()) {
            if (!hookText.isEmpty()) {
                hookText.append("&7, ");
            }
            hookText.append(entry.getValue() ? "&a" : "&c").append(entry.getKey());
        }
        Msg.sendRaw(sender, "debug.line-hooks", "hooks", hookText.toString());

        StringBuilder bound = new StringBuilder();
        for (Map.Entry<Integer, SkillBinding> entry : profile.boundSkills().entrySet()) {
            if (!bound.isEmpty()) {
                bound.append("&7, ");
            }
            bound.append("&f").append(entry.getKey()).append("&7→&f")
                    .append(entry.getValue().skillId());
        }
        Msg.sendRaw(sender, "debug.line-bound",
                "bound", bound.isEmpty() ? Msg.raw("debug.none") : bound.toString());
        Msg.sendRaw(sender, "debug.line-data",
                "file", plugin.playerData().fileOf(player.getUniqueId()).getName());
        return true;
    }

    private void send(CommandSender sender, ClassManager.Result result) {
        if (result.message() == null) {
            return;
        }
        sender.sendMessage(Msg.prefix() + result.message());
    }

    private void send(CommandSender sender, SkillService.Result result) {
        if (result.message() == null) {
            return;
        }
        sender.sendMessage(Msg.prefix() + result.message());
    }

    // ---------------- Tab 补全 ----------------

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                      @NotNull String alias, @NotNull String[] args) {
        if (args.length == 1) {
            return filter(ROOT, args[0]);
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        if (sub.equals("class")) {
            if (args.length == 2) {
                return filter(CLASS_SUB, args[1]);
            }
            if (args.length == 3) {
                String action = args[1].toLowerCase(Locale.ROOT);
                if (action.equals("select") || action.equals("switch")) {
                    return filter(new ArrayList<>(plugin.config().classes().keySet()), args[2]);
                }
                if (action.equals("info")) {
                    return filter(onlineNames(), args[2]);
                }
            }
            return List.of();
        }
        if (sub.equals("bind")) {
            if (args.length == 2) {
                List<String> ids = new ArrayList<>();
                if (sender instanceof Player player) {
                    PlayerProfile profile = plugin.playerData().get(player.getUniqueId());
                    if (profile != null && profile.hasClass()) {
                        ClassDefinition definition = plugin.config().classOf(profile.classId());
                        if (definition != null) {
                            for (String skillId : definition.skills()) {
                                SkillDefinition skill = plugin.config().skill(skillId);
                                if (skill != null && skill.trigger().bindable()) {
                                    ids.add(skill.id());
                                }
                            }
                        }
                    }
                }
                return filter(ids, args[1]);
            }
            return List.of();
        }
        if (sub.equals("resource")) {
            if (args.length == 2) {
                return filter(List.of("set"), args[1]);
            }
            if (args.length == 3 && args[1].equalsIgnoreCase("set")) {
                return filter(onlineNames(), args[2]);
            }
            if (args.length == 4 && args[1].equalsIgnoreCase("set")) {
                List<String> ids = new ArrayList<>();
                Player target = Bukkit.getPlayerExact(args[2]);
                if (target != null) {
                    ids.addAll(plugin.resources().snapshot(target).keySet());
                }
                return filter(ids, args[3]);
            }
            return List.of();
        }
        return List.of();
    }

    private static List<String> onlineNames() {
        List<String> names = new ArrayList<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            names.add(player.getName());
        }
        return names;
    }

    private static List<String> filter(List<String> candidates, String prefix) {
        String lower = prefix == null ? "" : prefix.toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (String candidate : candidates) {
            if (candidate.toLowerCase(Locale.ROOT).startsWith(lower)) {
                out.add(candidate);
            }
        }
        return out;
    }
}
