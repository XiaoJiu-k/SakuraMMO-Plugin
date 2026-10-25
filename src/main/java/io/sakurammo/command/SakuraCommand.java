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
import org.bukkit.inventory.ItemStack;
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

    private static final List<String> ROOT = List.of("menu", "skills", "class", "resource", "party", "dungeon",
            "raid", "talent", "craft", "money", "sell", "addxp", "givemat",
            "weekly", "upgrade", "redeem", "admin",
            "bind", "unbind", "binds", "cast", "reload", "debug", "item");
    private static final List<String> CLASS_SUB = List.of("list", "select", "switch", "info", "reset");
    private static final List<String> PARTY_SUB = List.of("create", "invite", "accept", "leave", "kick", "disband", "info");
    private static final List<String> DUNGEON_SUB = List.of("start", "leave", "info", "stop");
    private static final List<String> ITEM_SUB = List.of("give", "list");

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
            case "menu", "skills" -> {
                return handleMenu(sender);
            }
            case "class" -> {
                return handleClass(sender, args);
            }
            case "resource" -> {
                return handleResource(sender, args);
            }
            case "party" -> {
                return handleParty(sender, args);
            }
            case "dungeon" -> {
                return handleDungeon(sender, args);
            }
            case "talent" -> {
                return handleTalent(sender);
            }
            case "craft" -> {
                return handleCraft(sender);
            }
            case "money" -> {
                return handleMoney(sender, args);
            }
            case "sell" -> {
                return handleSell(sender);
            }
            case "addxp" -> {
                return handleAddXp(sender, args);
            }
            case "givemat" -> {
                return handleGiveMat(sender, args);
            }
            case "raid" -> {
                return handleRaid(sender, args);
            }
            case "weekly" -> {
                if (!(sender instanceof Player player)) {
                    Msg.send(sender, "general.player-only");
                    return true;
                }
                new io.sakurammo.menu.WeeklyMenu(plugin, player).open();
                return true;
            }
            case "upgrade" -> {
                if (!(sender instanceof Player player)) {
                    Msg.send(sender, "general.player-only");
                    return true;
                }
                new io.sakurammo.menu.UpgradeMenu(plugin, player).open();
                return true;
            }
            case "redeem" -> {
                if (!(sender instanceof Player player)) {
                    Msg.send(sender, "general.player-only");
                    return true;
                }
                new io.sakurammo.menu.RedeemMenu(plugin, player).open();
                return true;
            }
            case "admin" -> {
                if (args.length >= 2 && args[1].equalsIgnoreCase("translatex")) {
                    return handleTranslateX(sender);
                }
                return handleAdmin(sender, args);
            }
            case "bind" -> {
                return handleBind(sender, args);
            }
            case "unbind" -> {
                return handleUnbind(sender, args);
            }
            case "binds" -> {
                return handleBinds(sender);
            }
            case "cast" -> {
                return handleCast(sender, args);
            }
            case "reload" -> {
                return handleReload(sender);
            }
            case "debug" -> {
                return handleDebug(sender);
            }
            case "item" -> {
                return handleItem(sender, args);
            }
            default -> {
                Msg.send(sender, "general.unknown-subcommand", "sub", sub);
                return true;
            }
        }
    }

    private void help(CommandSender sender, String label) {
        sender.sendMessage(Text.color("&8&m────────&r &dSakuraMMO &8&m────────"));
        sender.sendMessage(Text.color("&f/" + label + " menu &8— &7打开菜单（F 键 / Shift 组合见菜单内说明）"));
        sender.sendMessage(Text.color("&f/" + label + " class list &8— &7列出可选职业"));
        sender.sendMessage(Text.color("&f/" + label + " class select <id> &8— &7选择职业"));
        sender.sendMessage(Text.color("&f/" + label + " class switch <id> &8— &7切换职业"));
        sender.sendMessage(Text.color("&f/" + label + " class info [player] &8— &7查看职业信息"));
        sender.sendMessage(Text.color("&f/" + label + " class reset <player> &8— &7&c[管理员]&7 撤销玩家职业，重新选择"));
        sender.sendMessage(Text.color("&f/" + label + " resource &8— &7查看当前资源"));
        sender.sendMessage(Text.color("&f/" + label + " resource set <player> <res> <val> &8— &7调试"));
        sender.sendMessage(Text.color("&f/" + label + " party create|invite|accept|leave|kick|disband|info &8— &7队伍"));
        sender.sendMessage(Text.color("&f/" + label + " dungeon start <id>|leave|info &8— &7副本（队长开本）"));
        sender.sendMessage(Text.color("&f/" + label + " talent &8— &7打开天赋树（加点/洗点）"));
        sender.sendMessage(Text.color("&f/" + label + " craft &8— &7打开制造菜单"));
        sender.sendMessage(Text.color("&f/" + label + " money &8— &7查看金币"));
        sender.sendMessage(Text.color("&f/" + label + " sell &8— &7卖出主手物品（底材不可卖）"));
        sender.sendMessage(Text.color("&f/" + label + " addxp <player> <amount> &8— &7&c[管理员]&7 调试加经验"));
        sender.sendMessage(Text.color("&f/" + label + " givemat <player> <id> <amount> &8— &7&c[管理员]&7 发放制造底材"));
        sender.sendMessage(Text.color("&f/" + label + " raid create|invite|accept|start|leave|info &8— &7团本（10-20 人）"));
        sender.sendMessage(Text.color("&f/" + label + " weekly &8— &7周常任务（周一 4:00 重置）"));
        sender.sendMessage(Text.color("&f/" + label + " upgrade &8— &7装备升格（继承词条）"));
        sender.sendMessage(Text.color("&f/" + label + " redeem &8— &7代币兑换保底装备"));
        sender.sendMessage(Text.color("&f/" + label + " admin [reset <player> <weekly/economy/dungeon>] &8— &7&c[管理员]&7 GM 后台"));
        sender.sendMessage(Text.color("&f/" + label + " bind <skillId> <1|2|3|4> &8— &7技能绑定到 Shift+1~4"));
        sender.sendMessage(Text.color("&f/" + label + " unbind <1|2|3|4> &8— &7解绑按键"));
        sender.sendMessage(Text.color("&f/" + label + " binds &8— &7列出按键绑定与冷却"));
        sender.sendMessage(Text.color("&f/" + label + " cast slot <1|2|3|4> &8— &7按键施法命令入口"));
        sender.sendMessage(Text.color("&f/" + label + " cast <skillId> &8— &7直接施放（绕过绑定，调试用）"));
        sender.sendMessage(Text.color("&f/" + label + " reload &8— &7重载配置"));
        sender.sendMessage(Text.color("&f/" + label + " debug &8— &7调试信息"));
    }

    // ---------------- /sm menu ----------------

    private boolean handleMenu(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            Msg.send(sender, "general.player-only");
            return true;
        }
        if (!plugin.config().main().menuEnabled()) {
            Msg.send(sender, "menu.disabled");
            return true;
        }
        // 主菜单（职业 / 副本两个入口）；未选职业 → 职业选择
        plugin.menus().open(player);
        return true;
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
            case "reset" -> {
                return resetClass(sender, args);
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

    /** /sm class reset <player> —— 管理员撤销玩家职业（在线立即生效；离线改档案，登录时清残留）。 */
    private boolean resetClass(CommandSender sender, String[] args) {
        if (!sender.hasPermission("sakurammo.admin")) {
            Msg.send(sender, "general.no-permission");
            return true;
        }
        if (args.length < 3) {
            sender.sendMessage(Text.color("&7用法：&f/sm class reset <player>"));
            return true;
        }
        String name = args[2];
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) {
            send(sender, plugin.classManager().reset(online));
            return true;
        }
        // 离线：扫档案按 name 匹配，文件级重置
        java.io.File file = plugin.playerData().findOfflineFile(name);
        if (file == null) {
            Msg.send(sender, "class.reset-no-data", "player", name);
            return true;
        }
        String oldClass = plugin.playerData().resetFile(file);
        if (oldClass == null) {
            Msg.send(sender, "class.reset-no-class", "player", name);
            return true;
        }
        plugin.getLogger().info("管理员离线重置了 " + name + " 的职业（原：" + oldClass + "）。");
        Msg.send(sender, "class.reset-done-offline",
                "player", name, "old", plugin.classManager().displayOf(oldClass));
        return true;
    }

    private boolean info(CommandSender sender, String[] args) {        Player target;
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
            stats.append("&f").append(Text.statCN(entry.getKey())).append(" &7+&f").append(Text.number(entry.getValue()));
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

    // ---------------- /sm bind / unbind / binds（P0.6 按键版） ----------------

    private boolean handleBind(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            Msg.send(sender, "general.player-only");
            return true;
        }
        if (args.length < 3) {
            player.sendMessage(Text.color("&7用法：&f/sm bind <skillId> <1|2|3|4>"));
            return true;
        }
        SkillService.Result result = plugin.skills().bind(player, args[1].toLowerCase(Locale.ROOT), args[2]);
        send(sender, result);
        return true;
    }

    private boolean handleUnbind(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            Msg.send(sender, "general.player-only");
            return true;
        }
        if (args.length < 2) {
            player.sendMessage(Text.color("&7用法：&f/sm unbind <1|2|3|4>"));
            return true;
        }
        SkillService.Result result = plugin.skills().unbind(player, args[1]);
        send(sender, result);
        return true;
    }

    /** /sm binds：列出 R/T/F/G 四个按键的绑定与冷却。 */
    private boolean handleBinds(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            Msg.send(sender, "general.player-only");
            return true;
        }
        PlayerProfile profile = plugin.playerData().getOrLoad(player);
        Msg.sendRaw(sender, "skill.bind-list-header");
        for (String key : PlayerProfile.SKILL_KEYS) {
            String skillId = profile.skillBind(key);
            String keyDisplay = SkillService.keyDisplay(key);
            if (skillId == null) {
                Msg.sendRaw(sender, "skill.bind-list-entry-empty", "key", keyDisplay);
                continue;
            }
            SkillDefinition skill = plugin.config().skill(skillId);
            String display = skill == null ? skillId : skill.displayName();
            long remain = plugin.skills().cooldownRemaining(player, skillId);
            String cooldown = remain <= 0
                    ? Msg.raw("skill.bind-ready")
                    : Msg.raw("skill.bind-cooldown", "time",
                            String.format(java.util.Locale.ROOT, "%.1f", remain / 1000.0D));
            Msg.sendRaw(sender, "skill.bind-list-entry",
                    "key", keyDisplay, "skill", display, "id", skillId, "cooldown", cooldown);
        }
        String bridge = plugin.keyBridge().available()
                ? Msg.raw("skill.bridge-ok") : Msg.raw("skill.bridge-missing-hint");
        Msg.sendRaw(sender, "skill.bind-list-footer", "bridge", bridge);
        return true;
    }

    private boolean handleCast(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            Msg.send(sender, "general.player-only");
            return true;
        }
        // /sm cast slot <1-4>：按键槽位命令施法（Shift+1~4 的命令入口）
        if (args.length >= 2 && args[1].equalsIgnoreCase("slot")) {
            int slotNo;
            try {
                slotNo = Integer.parseInt(args[2]);
            } catch (ArrayIndexOutOfBoundsException | NumberFormatException ex) {
                player.sendMessage(Text.color("&7用法：&f/sm cast slot <1|2|3|4>"));
                return true;
            }
            String key = SkillService.keyOfSlot(slotNo);
            if (key == null) {
                player.sendMessage(Text.color("&7用法：&f/sm cast slot <1|2|3|4>"));
                return true;
            }
            String skillId = plugin.skills().bindOf(player, key);
            if (skillId == null) {
                plugin.hud().castMessage(player, Msg.raw("skill.key-empty", "key", SkillService.keyDisplay(key)), 2);
                return true;
            }
            plugin.playerListener().castBound(player, skillId, SkillService.keyDisplay(key));
            return true;
        }
        if (args.length < 2) {
            player.sendMessage(Text.color("&7用法：&f/sm cast <skillId> 或 /sm cast slot <1|2|3|4>"));
            return true;
        }
        SkillDefinition definition = plugin.config().skill(args[1].toLowerCase(Locale.ROOT));
        if (definition == null) {
            Msg.send(sender, "skill.unknown", "id", args[1]);
            return true;
        }
        SkillService.Result result = plugin.skills().cast(player, definition);
        if (result.ok()) {
            sender.sendMessage(Msg.get("skill.cast-manual-ok", "skill", definition.displayName()));
        } else if (result.message() != null) {
            sender.sendMessage(Msg.prefix() + result.message());
        }
        return true;
    }

    // ---------------- /sm party（阶段二：队伍） ----------------

    private boolean handleParty(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            Msg.send(sender, "general.player-only");
            return true;
        }
        String action = args.length >= 2 ? args[1].toLowerCase(Locale.ROOT) : "info";
        switch (action) {
            case "create" -> plugin.parties().create(player);
            case "invite" -> {
                if (args.length < 3) {
                    player.sendMessage(Text.color("&7用法：&f/sm party invite <玩家>"));
                } else {
                    plugin.parties().invite(player, args[2]);
                }
            }
            case "accept" -> plugin.parties().accept(player);
            case "leave" -> plugin.parties().leave(player);
            case "kick" -> {
                if (args.length < 3) {
                    player.sendMessage(Text.color("&7用法：&f/sm party kick <玩家>"));
                } else {
                    plugin.parties().kick(player, args[2]);
                }
            }
            case "disband" -> plugin.parties().disband(player);
            case "info" -> plugin.parties().info(player);
            default -> Msg.send(sender, "general.unknown-subcommand", "sub", "party " + action);
        }
        return true;
    }

    // ---------------- /sm dungeon（阶段二：副本） ----------------

    private boolean handleDungeon(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            Msg.send(sender, "general.player-only");
            return true;
        }
        String action = args.length >= 2 ? args[1].toLowerCase(Locale.ROOT) : "info";
        switch (action) {
            case "start" -> {
                if (args.length < 3) {
                    StringBuilder ids = new StringBuilder();
                    for (String id : plugin.instances().availableDungeons()) {
                        ids.append(ids.isEmpty() ? "" : "&7, &f").append(id);
                    }
                    player.sendMessage(Text.color("&7可用副本：&f" + (ids.isEmpty() ? "无" : ids)));
                    player.sendMessage(Text.color("&7用法：&f/sm dungeon start <id>"));
                } else {
                    plugin.instances().start(player, args[2].toLowerCase(Locale.ROOT));
                }
            }
            case "leave" -> plugin.instances().leave(player);
            case "info" -> dungeonInfo(player);
            case "stop" -> {
                if (!player.hasPermission("sakurammo.admin")) {
                    Msg.send(sender, "general.no-permission");
                    return true;
                }
                var party = plugin.parties().partyOf(player.getUniqueId());
                if (party == null) {
                    Msg.send(sender, "party.not-in");
                } else {
                    plugin.instances().forceEnd(party, "admin-stop");
                }
            }
            case "valve" -> {
                // 调试/无障碍后备通道：直接开启阀门 N（等价于扳拉杆）
                var party = plugin.parties().partyOf(player.getUniqueId());
                var instance = party == null ? null : plugin.instances().byRun(party.runId());
                if (instance == null) {
                    Msg.send(sender, "dungeon.not-in");
                    return true;
                }
                int n;
                try {
                    n = Integer.parseInt(args[2]) - 1;
                } catch (Exception ex) {
                    player.sendMessage(Text.color("&7用法：&f/sm dungeon valve <1|2>"));
                    return true;
                }
                var def = io.sakurammo.instance.DungeonDefinition.load(plugin, instance.dungeonId);
                if (def == null || !plugin.instances().openValve(instance, def, n, player)) {
                    player.sendMessage(Text.color("&c阀门 " + (n + 1) + " 无法开启（已开过/阶段不对/序号非法）。"));
                }
            }
            default -> Msg.send(sender, "general.unknown-subcommand", "sub", "dungeon " + action);
        }
        return true;
    }

    private void dungeonInfo(Player player) {
        var party = plugin.parties().partyOf(player.getUniqueId());
        if (party == null) {
            Msg.send(player, "party.not-in");
            return;
        }
        var instance = plugin.instances().byRun(party.runId());
        if (instance == null) {
            Msg.send(player, "dungeon.info-idle");
            return;
        }
        Msg.send(player, "dungeon.info-running",
                "dungeon", party.dungeonId() == null ? "?" : party.dungeonId(),
                "phase", String.valueOf(instance.phase),
                "run", instance.runId.substring(0, 8));
    }

    // ---------------- /sm talent / craft / money / sell / addxp（阶段三） ----------------

    private boolean handleTalent(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            Msg.send(sender, "general.player-only");
            return true;
        }
        new io.sakurammo.menu.TalentMenu(plugin, player).open();
        return true;
    }

    private boolean handleCraft(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            Msg.send(sender, "general.player-only");
            return true;
        }
        new io.sakurammo.menu.CraftMenu(plugin, player).open();
        return true;
    }

    private boolean handleMoney(CommandSender sender, String[] args) {
        // /sm money set <player> <amount>：管理员调试设值
        if (args.length >= 3 && args[1].equalsIgnoreCase("set")) {
            if (!sender.hasPermission("sakurammo.admin")) {
                Msg.send(sender, "general.no-permission");
                return true;
            }
            Player target = Bukkit.getPlayerExact(args[2]);
            if (target == null) {
                Msg.send(sender, "general.player-not-found", "player", args[2]);
                return true;
            }
            double amount;
            try {
                amount = Double.parseDouble(args[3]);
            } catch (NumberFormatException | ArrayIndexOutOfBoundsException ex) {
                sender.sendMessage(Text.color("&7用法：&f/sm money set <player> <amount>"));
                return true;
            }
            plugin.economy().setBalance(target.getUniqueId(), amount);
            sender.sendMessage(Text.color("&a已将 &f" + target.getName() + " &a的金币设为 &f" + amount));
            return true;
        }
        if (sender instanceof Player player) {
            Msg.send(sender, "economy.balance",
                    "balance", io.sakurammo.util.Text.number(plugin.economy().balanceOf(player)));
            return true;
        }
        sender.sendMessage(Msg.prefix() + "economy available=" + plugin.economy().available());
        return true;
    }

    private boolean handleSell(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            Msg.send(sender, "general.player-only");
            return true;
        }
        double total = plugin.crafting().sellHand(player);
        if (total < 0) {
            Msg.send(sender, "economy.sell-unsellable");
        } else {
            Msg.send(sender, "economy.sell-done",
                    "amount", io.sakurammo.util.Text.number(total),
                    "balance", io.sakurammo.util.Text.number(plugin.economy().balanceOf(player)));
        }
        return true;
    }

    private boolean handleAddXp(CommandSender sender, String[] args) {
        if (!sender.hasPermission("sakurammo.admin")) {
            Msg.send(sender, "general.no-permission");
            return true;
        }
        if (args.length < 3) {
            sender.sendMessage(Text.color("&7用法：&f/sm addxp <player> <amount>"));
            return true;
        }
        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            Msg.send(sender, "general.player-not-found", "player", args[1]);
            return true;
        }
        long amount;
        try {
            amount = Long.parseLong(args[2]);
        } catch (NumberFormatException ex) {
            sender.sendMessage(Text.color("&c经验值不合法：" + args[2]));
            return true;
        }
        int gained = plugin.talents().addExperience(target, amount);
        sender.sendMessage(Text.color("&a已给 &f" + target.getName() + " &a加 &f" + amount
                + " &a经验（升级 " + gained + " 级，当前 Lv." + plugin.playerData().getOrLoad(target).level() + "）。"));
        return true;
    }

    /** /sm raid —— 复用队伍系统（人数上限覆盖 20），开本走团本配置。 */
    private boolean handleRaid(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            Msg.send(sender, "general.player-only");
            return true;
        }
        String action = args.length >= 2 ? args[1].toLowerCase(Locale.ROOT) : "info";
        switch (action) {
            case "create" -> plugin.parties().create(player, plugin.config().main().raidMaxMembers());
            case "invite" -> {
                if (args.length < 3) {
                    player.sendMessage(Text.color("&7用法：&f/sm raid invite <玩家>"));
                } else {
                    plugin.parties().invite(player, args[2]);
                }
            }
            case "accept" -> plugin.parties().accept(player);
            case "leave" -> plugin.parties().leave(player);
            case "kick" -> {
                if (args.length < 3) {
                    player.sendMessage(Text.color("&7用法：&f/sm raid kick <玩家>"));
                } else {
                    plugin.parties().kick(player, args[2]);
                }
            }
            case "disband" -> plugin.parties().disband(player);
            case "info" -> plugin.parties().info(player);
            case "start" -> {
                if (args.length < 3) {
                    player.sendMessage(Text.color("&7可用团本：&fraid_blackiron&7。用法：&f/sm raid start raid_blackiron"));
                } else {
                    plugin.instances().start(player, args[2].toLowerCase(Locale.ROOT));
                }
            }
            default -> Msg.send(sender, "general.unknown-subcommand", "sub", "raid " + action);
        }
        return true;
    }

    /** /sm admin [reset <player> <weekly|economy|dungeon>]：GUI 或直接重置。 */
    private boolean handleAdmin(CommandSender sender, String[] args) {
        if (!sender.hasPermission("sakurammo.admin")) {
            Msg.send(sender, "general.no-permission");
            return true;
        }
        if (args.length >= 4 && args[1].equalsIgnoreCase("reset")) {
            Player target = Bukkit.getPlayerExact(args[2]);
            if (target == null) {
                Msg.send(sender, "general.player-not-found", "player", args[2]);
                return true;
            }
            var profile = plugin.playerData().getOrLoad(target);
            switch (args[3].toLowerCase(Locale.ROOT)) {
                case "weekly" -> {
                    profile.resetWeekly();
                    profile.weeklyCycle("");
                    plugin.playerData().save(profile);
                    sender.sendMessage(Text.color("&a已重置 &f" + target.getName() + " &a的周常进度。"));
                }
                case "economy" -> {
                    plugin.economy().setBalance(target.getUniqueId(), 0.0D);
                    sender.sendMessage(Text.color("&a已清零 &f" + target.getName() + " &a的金币。"));
                }
                case "dungeon" -> {
                    var party = plugin.parties().partyOf(target.getUniqueId());
                    if (party != null) {
                        plugin.instances().forceEnd(party, "admin-reset");
                        sender.sendMessage(Text.color("&a已强制结束 &f" + target.getName() + " &a的副本。"));
                    } else {
                        sender.sendMessage(Text.color("&7该玩家没有进行中的副本。"));
                    }
                }
                default -> sender.sendMessage(Text.color("&c类型：weekly / economy / dungeon"));
            }
            plugin.audit().log(sender.getName(), "admin-reset", args[3] + " → " + target.getName());
            return true;
        }
        if (sender instanceof Player player) {
            new io.sakurammo.menu.AdminGUI(plugin, player).open();
            return true;
        }
        sender.sendMessage(Text.color("&7控制台请用：&f/sm admin reset <player> <weekly|economy|dungeon>"));
        return true;
    }

    /** /sm admin translatex：手持物品的 RPGLoot 英文词条汉化（测试/修正两用）。 */
    private boolean handleTranslateX(CommandSender sender) {
        if (!sender.hasPermission("sakurammo.admin")) {
            Msg.send(sender, "general.no-permission");
            return true;
        }
        if (!(sender instanceof Player player)) {
            Msg.send(sender, "general.player-only");
            return true;
        }
        ItemStack hand = player.getInventory().getItemInMainHand();
        boolean changed = io.sakurammo.loot.RpgLootTranslator.translate(hand);
        sender.sendMessage(Text.color(changed
                ? "&a已将主手物品的英文词条汉化。"
                : "&7主手物品没有需要汉化的英文词条。"));
        return true;
    }

    private boolean handleGiveMat(CommandSender sender, String[] args) {
        if (!sender.hasPermission("sakurammo.admin")) {
            Msg.send(sender, "general.no-permission");
            return true;
        }
        if (args.length < 4) {
            sender.sendMessage(Text.color("&7用法：&f/sm givemat <player> <材料id> <数量>"));
            return true;
        }
        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            Msg.send(sender, "general.player-not-found", "player", args[1]);
            return true;
        }
        ItemStack item = plugin.crafting().createMaterial(args[2].toLowerCase(Locale.ROOT), 1);
        if (item == null) {
            sender.sendMessage(Text.color("&c未知底材：" + args[2]
                    + "（可用：blackiron_ingot / mana_crystal / dark_core）"));
            return true;
        }
        int amount;
        try {
            amount = Math.max(1, Math.min(2304, Integer.parseInt(args[3])));
        } catch (NumberFormatException ex) {
            sender.sendMessage(Text.color("&c数量不合法：" + args[3]));
            return true;
        }
        while (amount > 0) {
            int give = Math.min(64, amount);
            ItemStack stack = plugin.crafting().createMaterial(args[2].toLowerCase(Locale.ROOT), give);
            target.getInventory().addItem(stack);
            amount -= give;
        }
        sender.sendMessage(Text.color("&a已发放底材 &f" + args[2] + " ×" + args[3] + " &a给 &f" + target.getName()));
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

    /** /sm item give <player> <id> [数量] 与 /sm item list（GM 发放与查 id）。 */
    private boolean handleItem(CommandSender sender, String[] args) {
        if (!sender.hasPermission("sakurammo.admin")) {
            Msg.send(sender, "general.no-permission");
            return true;
        }
        if (args.length < 2) {
            Msg.send(sender, "item.usage");
            return true;
        }
        String action = args[1].toLowerCase(Locale.ROOT);
        if (action.equals("list")) {
            var all = new java.util.ArrayList<>(plugin.items().all());
            all.sort(java.util.Comparator.comparing(io.sakurammo.item.ItemDefinition::tier)
                    .thenComparing(io.sakurammo.item.ItemDefinition::id));
            Msg.send(sender, "item.list-header", "count", String.valueOf(all.size()));
            for (io.sakurammo.item.ItemDefinition def : all) {
                Msg.send(sender, "item.list-entry",
                        "id", def.id(),
                        "name", def.display(),
                        "rarity", def.rarity().label(),
                        "tier", String.valueOf(def.tier()));
            }
            return true;
        }
        if (!action.equals("give")) {
            Msg.send(sender, "item.usage");
            return true;
        }
        if (args.length < 4) {
            Msg.send(sender, "item.usage");
            return true;
        }
        Player target = org.bukkit.Bukkit.getPlayer(args[2]);
        if (target == null) {
            Msg.send(sender, "general.player-not-found", "player", args[2]);
            return true;
        }
        int amount = 1;
        if (args.length >= 5) {
            try {
                amount = Math.max(1, Math.min(64, Integer.parseInt(args[4])));
            } catch (NumberFormatException ignored) {
                Msg.send(sender, "item.bad-amount");
                return true;
            }
        }
        String itemId = args[3].toLowerCase(Locale.ROOT);
        ItemStack item = plugin.items().create(itemId, amount);
        if (item == null) {
            Msg.send(sender, "item.unknown-id", "id", itemId);
            return true;
        }
        target.getInventory().addItem(item).values()
                .forEach(left -> target.getWorld().dropItemNaturally(target.getLocation(), left));
        plugin.audit().log(sender.getName(), "item-give",
                target.getName() + " " + itemId + " x" + amount);
        Msg.send(sender, "item.given",
                "player", target.getName(),
                "item", plugin.items().definition(itemId).map(io.sakurammo.item.ItemDefinition::display).orElse(itemId),
                "amount", String.valueOf(amount));
        return true;
    }

    private java.util.List<String> itemIds() {
        return plugin.items().all().stream().map(io.sakurammo.item.ItemDefinition::id).toList();
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
        hooks.put("Vault", plugin.economy().available());
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
        Msg.sendRaw(sender, "debug.line-triggers",
                "menu", String.valueOf(plugin.playerListener().menuCount()),
                "combo", String.valueOf(plugin.playerListener().comboCount()),
                "bridge", String.valueOf(plugin.keyBridge().available()));
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
                if (action.equals("info") || action.equals("reset")) {
                    return filter(onlineNames(), args[2]);
                }
            }
            return List.of();
        }
        if (sub.equals("item")) {
            if (args.length == 2) {
                return filter(ITEM_SUB, args[1]);
            }
            if (args.length == 3 && args[1].equalsIgnoreCase("give")) {
                return filter(onlineNames(), args[2]);
            }
            if (args.length == 4 && args[1].equalsIgnoreCase("give")) {
                return filter(new ArrayList<>(itemIds()), args[3]);
            }
            return List.of();
        }
        if (sub.equals("bind")) {
            if (args.length == 3) {
                return filter(PlayerProfile.SKILL_KEYS, args[2]);
            }
            return List.of();
        }
        if (sub.equals("unbind")) {
            if (args.length == 2) {
                return filter(PlayerProfile.SKILL_KEYS, args[1]);
            }
            return List.of();
        }
        if (sub.equals("cast")) {
            if (args.length == 2) {
                List<String> ids = new ArrayList<>();
                if (sender instanceof Player player) {
                    PlayerProfile profile = plugin.playerData().get(player.getUniqueId());
                    if (profile != null && profile.hasClass()) {
                        ClassDefinition definition = plugin.config().classOf(profile.classId());
                        if (definition != null) {
                            ids.addAll(definition.skills());
                        }
                    }
                }
                return filter(ids, args[1]);
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
        if (sub.equals("party")) {
            if (args.length == 2) {
                return filter(PARTY_SUB, args[1]);
            }
            if (args.length == 3 && (args[1].equalsIgnoreCase("invite") || args[1].equalsIgnoreCase("kick"))) {
                return filter(onlineNames(), args[2]);
            }
            return List.of();
        }
        if (sub.equals("dungeon")) {
            if (args.length == 2) {
                return filter(DUNGEON_SUB, args[1]);
            }
            if (args.length == 3 && args[1].equalsIgnoreCase("start")) {
                return filter(plugin.instances().availableDungeons(), args[2]);
            }
            if (args.length == 3 && args[1].equalsIgnoreCase("valve")) {
                return filter(List.of("1", "2"), args[2]);
            }
            return List.of();
        }
        if (sub.equals("raid")) {
            if (args.length == 2) {
                return filter(List.of("create", "invite", "accept", "leave", "kick", "disband", "info", "start"), args[1]);
            }
            if (args.length == 3 && (args[1].equalsIgnoreCase("invite") || args[1].equalsIgnoreCase("kick"))) {
                return filter(onlineNames(), args[2]);
            }
            if (args.length == 3 && args[1].equalsIgnoreCase("start")) {
                return filter(plugin.instances().availableDungeons(), args[2]);
            }
            return List.of();
        }
        if (sub.equals("admin")) {
            if (args.length == 2 && "reset".startsWith(args[1].toLowerCase(Locale.ROOT))) {
                return List.of("reset");
            }
            if (args.length == 3 && args[1].equalsIgnoreCase("reset")) {
                return filter(onlineNames(), args[2]);
            }
            if (args.length == 4 && args[1].equalsIgnoreCase("reset")) {
                return filter(List.of("weekly", "economy", "dungeon"), args[3]);
            }
            return List.of();
        }
        if (sub.equals("addxp")) {
            if (args.length == 2) {
                return filter(onlineNames(), args[1]);
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
