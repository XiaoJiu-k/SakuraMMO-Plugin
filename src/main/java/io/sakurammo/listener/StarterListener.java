package io.sakurammo.listener;

import io.sakurammo.SakuraMMOPlugin;
import io.sakurammo.event.ClassChangeEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

/**
 * 新手指引物品的发放时机（v4.2.3）。
 * <p>
 * 三个入口，全部走 {@link io.sakurammo.item.StarterItemManager#claim}（幂等）：
 * <ul>
 *   <li>职业变更完成（选择 / 切换）→ 给新职业的新手物品；</li>
 *   <li>登录 → 兜底（历史角色、服务器重启导致待归还清单丢失）；</li>
 *   <li>{@code /sm starter} → 玩家主动重领（在命令里处理）。</li>
 * </ul>
 */
public final class StarterListener implements Listener {

    private final SakuraMMOPlugin plugin;

    public StarterListener(SakuraMMOPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onClassChange(ClassChangeEvent event) {
        // 延后 1 tick：先让职业面板的提示发出去，再补发物品提示，顺序更自然
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (event.player().isOnline()) {
                plugin.starter().grantForClass(event.player(), event.newClassId());
            }
        });
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        // 登录兜底：死了没复活就退出、或服务器重启丢了待归还清单时，把新手物品补回来
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (event.getPlayer().isOnline()) {
                var profile = plugin.playerData().getOrLoad(event.getPlayer());
                if (profile != null && profile.hasClass()) {
                    plugin.starter().grantForClass(event.getPlayer(), profile.classId());
                }
            }
        }, 40L);
    }
}
