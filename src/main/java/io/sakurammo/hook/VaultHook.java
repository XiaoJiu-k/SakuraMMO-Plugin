package io.sakurammo.hook;

import io.sakurammo.SakuraMMOPlugin;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.RegisteredServiceProvider;

/**
 * Vault 经济挂钩（可选）。服务端没有经济实现时 available() 返回 false，费用检查自动跳过。
 * <p>
 * 这个类只在检测到 Vault 插件后由 {@link VaultBootstrap} 实例化，避免 Vault 缺失时
 * NoClassDefFoundError 打断插件启动。
 */
public final class VaultHook implements EconomyBridge {

    private final Economy economy;

    public VaultHook(SakuraMMOPlugin plugin) {
        Economy found = null;
        try {
            RegisteredServiceProvider<Economy> registration =
                    Bukkit.getServicesManager().getRegistration(Economy.class);
            if (registration != null) {
                found = registration.getProvider();
            }
        } catch (Throwable t) {
            plugin.getLogger().warning("读取 Vault 经济服务失败：" + t.getMessage());
        }
        this.economy = found;
        if (economy == null) {
            plugin.getLogger().info("未检测到 Vault 经济实现，切换职业费用检查已跳过。");
        } else {
            plugin.getLogger().info("Vault 经济挂钩就绪：" + economy.getName());
        }
    }

    @Override
    public boolean available() {
        return economy != null;
    }

    @Override
    public boolean has(OfflinePlayer player, double amount) {
        if (economy == null) {
            return true;
        }
        try {
            return economy.has(player, amount);
        } catch (Throwable t) {
            return false;
        }
    }

    @Override
    public boolean withdraw(OfflinePlayer player, double amount) {
        if (economy == null) {
            return true;
        }
        try {
            return economy.withdrawPlayer(player, amount).transactionSuccess();
        } catch (Throwable t) {
            return false;
        }
    }
}
