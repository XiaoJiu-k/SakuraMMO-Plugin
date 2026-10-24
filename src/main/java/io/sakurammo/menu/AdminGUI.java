package io.sakurammo.menu;

import io.sakurammo.SakuraMMOPlugin;
import io.sakurammo.util.Msg;
import io.sakurammo.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

/**
 * GM 后台（/sm admin，sakurammo.admin）：
 * <pre>
 *  4  数据看板（在线/实例/TPS/职业占比/经济产出回收）
 *  20 全服发金币（两步确认）  22 全服发底材（两步确认）
 *  24 清理挂起实例世界        30 在线玩家管理
 *  49 关闭
 * </pre>
 * 两步确认：点击后进入 confirm 状态（图标变为绿色确认 + 红色取消），再点确认才执行。
 */
public final class AdminGUI extends Menu {

    private final String confirm; // null = 无待确认操作；"money"/"material"/"resetweekly"

    public AdminGUI(SakuraMMOPlugin plugin, Player player) {
        this(plugin, player, null);
    }

    public AdminGUI(SakuraMMOPlugin plugin, Player player, String confirm) {
        super(plugin, player, 5, Msg.raw("admin.title"));
        this.confirm = confirm;
    }

    @Override
    protected void build() {
        set(4, dashboardIcon());

        if (confirm == null) {
            set(20, Items.of(Material.GOLD_BLOCK, Msg.raw("admin.giveall-money"),
                            List.of(Msg.raw("admin.confirm-first"))),
                    p -> new AdminGUI(plugin, p, "money").open());
            set(22, Items.of(Material.CHEST, Msg.raw("admin.giveall-material"),
                            List.of(Msg.raw("admin.confirm-first"))),
                    p -> new AdminGUI(plugin, p, "material").open());
        } else {
            String label = Msg.raw("money".equals(confirm)
                    ? "admin.confirm-money" : "admin.confirm-material");
            set(20, Items.of(Material.LIME_WOOL, Msg.raw("admin.confirm-yes", "action", label),
                            List.of(Msg.raw("admin.confirm-yes-lore"))),
                    p -> {
                        execute(p, confirm);
                        p.closeInventory();
                    });
            set(22, Items.of(Material.RED_WOOL, Msg.raw("admin.confirm-no"), List.of()),
                    p -> new AdminGUI(plugin, p).open());
        }

        set(24, Items.of(Material.WATER_BUCKET, Msg.raw("admin.cleanup"),
                        List.of(Msg.raw("admin.cleanup-lore"))),
                p -> {
                    int before = pendingInstanceDirs();
                    plugin.instances().startupCleanup();
                    Msg.send(p, "admin.cleanup-done", "count", String.valueOf(before));
                    plugin.audit().log(p.getName(), "admin-cleanup", "pending=" + before);
                });

        set(30, Items.of(Material.PLAYER_HEAD, Msg.raw("admin.players"),
                        List.of(Msg.raw("admin.players-lore"))),
                p -> {
                    // 在线玩家管理走命令（点击给出用法），避免二级头颅菜单过长
                    Msg.send(p, "admin.players-hint");
                });

        set(49, Items.of(Material.BARRIER, Msg.raw("menu.button-close"), List.of()),
                p -> p.closeInventory());
        fill(fillerMaterial());
    }

    private org.bukkit.inventory.ItemStack dashboardIcon() {
        var profileSample = plugin.playerData();
        var counts = new java.util.LinkedHashMap<String, Integer>();
        for (Player online : Bukkit.getOnlinePlayers()) {
            var profile = profileSample.get(online.getUniqueId());
            if (profile != null && profile.hasClass()) {
                var cd = plugin.config().classOf(profile.classId());
                counts.merge(cd == null ? profile.classId() : cd.displayName(), 1, Integer::sum);
            }
        }
        StringBuilder classes = new StringBuilder();
        counts.forEach((id, count) -> classes.append(id).append('×').append(count).append(' '));
        double tps = tps();
        List<String> lore = List.of(
                Msg.raw("admin.line-online", "count", String.valueOf(Bukkit.getOnlinePlayers().size())),
                Msg.raw("admin.line-instances", "count", String.valueOf(activeInstances())),
                Msg.raw("admin.line-tps", "tps", String.format(java.util.Locale.ROOT, "%.1f", tps)),
                Msg.raw("admin.line-classes", "classes", classes.isEmpty() ? "-" : classes.toString()),
                Msg.raw("admin.line-minted", "amount", Text.number(plugin.economy().totalMinted())),
                Msg.raw("admin.line-burned", "amount", Text.number(plugin.economy().totalBurned())));
        return Items.of(Material.SPYGLASS, Msg.raw("admin.dashboard"), lore);
    }

    private int activeInstances() {
        return plugin.instances().activeCount();
    }

    private int pendingInstanceDirs() {
        java.io.File container = Bukkit.getWorldContainer();
        java.io.File[] children = container.listFiles();
        int count = 0;
        if (children != null) {
            for (java.io.File child : children) {
                if (child.isDirectory() && child.getName().startsWith("instance_")) {
                    count++;
                }
            }
        }
        return count;
    }

    /** 近 1 分钟 TPS（Paper 内部统计，反射取值失败返回 -1）。 */
    private double tps() {
        try {
            Object server = Bukkit.getServer().getClass().getMethod("getServer").invoke(Bukkit.getServer());
            double[] recent = (double[]) server.getClass().getField("recentTps").get(server);
            return recent[2];
        } catch (Throwable t) {
            return -1.0D;
        }
    }

    private void execute(Player admin, String action) {
        if ("money".equals(action)) {
            double amount = plugin.config().main().adminGiveallMoney();
            for (Player online : Bukkit.getOnlinePlayers()) {
                plugin.economy().deposit(online, amount, "economy.giveall");
            }
            plugin.audit().log(admin.getName(), "admin-giveall-money",
                    "amount=" + amount + " players=" + Bukkit.getOnlinePlayers().size());
            Bukkit.broadcastMessage(Msg.prefix() + Msg.raw("admin.giveall-money-broadcast",
                    "amount", Text.number(amount)));
        } else if ("material".equals(action)) {
            for (Player online : Bukkit.getOnlinePlayers()) {
                ItemStack mat = plugin.crafting().createMaterial("blackiron_ingot", 2);
                if (mat != null) {
                    online.getInventory().addItem(mat);
                }
            }
            plugin.audit().log(admin.getName(), "admin-giveall-material", "blackiron_ingot x2");
            Bukkit.broadcastMessage(Msg.prefix() + Msg.raw("admin.giveall-material-broadcast"));
        }
    }
}
