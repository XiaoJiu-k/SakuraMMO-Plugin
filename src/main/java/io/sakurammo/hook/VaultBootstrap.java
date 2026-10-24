package io.sakurammo.hook;

import io.sakurammo.SakuraMMOPlugin;
import org.bukkit.Bukkit;

/**
 * Vault 桥的隔离启动器。
 * <p>
 * 主类只引用本类（不直接引用 VaultHook），这样 Vault 缺失时 VaultHook 及其
 * net.milkbowl.vault.* 依赖根本不会被解析。
 */
public final class VaultBootstrap {

    private VaultBootstrap() {
    }

    public static EconomyBridge create(SakuraMMOPlugin plugin) {
        if (Bukkit.getPluginManager().getPlugin("Vault") == null) {
            plugin.getLogger().warning("未检测到 Vault，切换职业费用检查已跳过。");
            return new EconomyBridge.Noop();
        }
        try {
            return new VaultHook(plugin);
        } catch (Throwable t) {
            plugin.getLogger().warning("Vault 挂钩失败，费用检查已跳过：" + t);
            return new EconomyBridge.Noop();
        }
    }
}
