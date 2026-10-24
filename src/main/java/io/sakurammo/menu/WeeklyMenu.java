package io.sakurammo.menu;

import io.sakurammo.SakuraMMOPlugin;
import io.sakurammo.weekly.WeeklyManager;
import io.sakurammo.weekly.WeeklyManager.WeeklyTask;
import io.sakurammo.util.Msg;
import io.sakurammo.util.Text;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

/**
 * 周常任务菜单（/sm weekly）：显示全部任务进度与奖励，达标可点击领取。
 * <pre>
 *  4  周期与代币
 *  10-16  任务列表（达标=绿，点击领取；未达标=灰）
 *  23 返回主菜单   25 关闭
 * </pre>
 */
public final class WeeklyMenu extends Menu {

    private static final int[] TASK_SLOTS = {10, 11, 12, 13, 14, 15, 16};

    public WeeklyMenu(SakuraMMOPlugin plugin, Player player) {
        super(plugin, player, 3, Msg.raw("weekly.title"));
    }

    @Override
    protected void build() {
        plugin.weeklies().ensureCycle(player); // 打开菜单即完成跨周期重置
        var profile = plugin.playerData().getOrLoad(player);
        set(4, Items.of(Material.WRITABLE_BOOK,
                Msg.raw("weekly.header", "cycle", plugin.weeklies().cycleForNow(),
                        "tokens", String.valueOf(profile.tokens())),
                List.of(Msg.raw("weekly.header-lore"))));

        int i = 0;
        for (WeeklyTask task : plugin.weeklies().tasks().values()) {
            if (i >= TASK_SLOTS.length) {
                break;
            }
            final WeeklyTask def = task;
            int progress = plugin.weeklies().progressOf(player, task.id());
            boolean claimable = plugin.weeklies().claimable(player, task);
            boolean claimed = profile.weeklyClaimed().contains(task.id());
            List<String> lore = new ArrayList<>();
            lore.add(Msg.raw(claimed ? "weekly.line-claimed" : "weekly.line-progress",
                    "current", String.valueOf(Math.min(progress, task.goal())),
                    "goal", String.valueOf(task.goal())));
            lore.add(Msg.raw("weekly.line-reward",
                    "money", Text.number(task.rewardMoney()),
                    "token", String.valueOf(task.rewardToken())));
            if (!task.rewardMaterialId().isBlank()) {
                var mat = plugin.crafting().material(task.rewardMaterialId());
                lore.add(Msg.raw("weekly.line-material",
                        "name", mat == null ? task.rewardMaterialId() : mat.name(),
                        "amount", String.valueOf(task.rewardMaterialAmount())));
            }
            lore.add("");
            if (claimed) {
                lore.add(Msg.raw("weekly.line-claimed-mark"));
            } else if (claimable) {
                lore.add(Msg.raw("weekly.line-click-claim"));
            } else {
                lore.add(Msg.raw("weekly.line-not-complete"));
            }
            Material icon = claimed ? Material.LIME_DYE
                    : claimable ? Material.GOLD_INGOT
                    : task.icon() == null ? Material.GRAY_DYE : task.icon();
            set(TASK_SLOTS[i], Items.of(icon,
                    Msg.raw("weekly.task-name",
                            "name", task.display(),
                            "current", String.valueOf(Math.min(progress, task.goal())),
                            "goal", String.valueOf(task.goal())), lore),
                    p -> {
                        if (plugin.weeklies().claim(p, def)) {
                            new WeeklyMenu(plugin, p).open();
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
