package io.sakurammo.hook;

import io.sakurammo.SakuraMMOPlugin;
import org.bukkit.Bukkit;

/**
 * PlaceholderAPI 扩展的隔离启动器。
 * <p>
 * PlaceholderHook 继承 PlaceholderExpansion，PAPI 缺失时该类无法加载，
 * 所以注册/注销都收敛在这里，主类只拿一个 Object 句柄。
 */
public final class PlaceholderBootstrap {

    private PlaceholderBootstrap() {
    }

    /** @return 已注册的扩展句柄；null 表示未注册 */
    public static Object register(SakuraMMOPlugin plugin) {
        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") == null) {
            plugin.getLogger().warning("未检测到 PlaceholderAPI，%sakurammo_xxx% 占位符不可用。");
            return null;
        }
        try {
            PlaceholderHook hook = new PlaceholderHook(plugin);
            return hook.register() ? hook : null;
        } catch (Throwable t) {
            plugin.getLogger().warning("PlaceholderAPI 扩展注册失败：" + t);
            return null;
        }
    }

    public static void unregister(Object handle) {
        if (handle instanceof PlaceholderHook hook) {
            try {
                hook.unregister();
            } catch (Throwable ignored) {
                // 关服阶段忽略
            }
        }
    }
}
