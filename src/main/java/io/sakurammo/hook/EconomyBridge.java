package io.sakurammo.hook;

import org.bukkit.OfflinePlayer;

/**
 * 经济桥。主类只依赖这个接口，避免把 Vault 类型带进启动路径
 * （Vault 缺失时 VaultHook 整个类都加载不了）。
 */
public interface EconomyBridge {

    boolean available();

    boolean has(OfflinePlayer player, double amount);

    boolean withdraw(OfflinePlayer player, double amount);

    /** Vault 缺失时的空实现：永远「够钱」，不扣款。 */
    final class Noop implements EconomyBridge {
        @Override
        public boolean available() {
            return false;
        }

        @Override
        public boolean has(OfflinePlayer player, double amount) {
            return true;
        }

        @Override
        public boolean withdraw(OfflinePlayer player, double amount) {
            return true;
        }
    }
}
