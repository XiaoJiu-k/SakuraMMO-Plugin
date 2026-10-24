package io.sakurammo.economy;

import io.sakurammo.SakuraMMOPlugin;
import io.sakurammo.util.Text;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.RegisteredServiceProvider;

import java.io.File;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * 经济总管：余额存储（economy.yml，原子写）、Vault provider 注册、
 * 掉金/卖出定价、费用收取封装。实现 {@link EconomyBridge}，主类统一走本类。
 * <p>
 * 防刷金（任务书红线）：卖出定价全部低于产出成本——
 * 制成品按配方价的 {@code sell-refund-rate}（默认 25%）回收，材料不可卖（0 价），
 * 制造消耗的底材+手续费永远大于卖出回收，制造-卖出循环必然亏钱。
 */
public final class EconomyManager implements io.sakurammo.hook.EconomyBridge {

    private final SakuraMMOPlugin plugin;
    private final File file;
    private final Map<UUID, Double> balances = new ConcurrentHashMap<>();
    private SakuraEconomy provider;
    /** 外部 Vault 经济（EssentialsX 等）。 */
    private Economy external;
    /** 经济审计：累计产出（deposit）/ 回收（withdraw），AdminGUI 看板用。 */
    private volatile double totalMinted = 0.0D;
    private volatile double totalBurned = 0.0D;
    /** 按名字查找的离线余额缓存（name → uuid），从存档扫描构建。 */
    private final Map<String, UUID> nameIndex = new ConcurrentHashMap<>();

    public EconomyManager(SakuraMMOPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "economy.yml");
        load();
    }

    /**
     * 挂接 Vault：已有外部经济就用外部；否则注册内置 SakuraEconomy。
     *
     * @return 经济功能是否可用
     */
    public boolean hookVault() {
        if (Bukkit.getPluginManager().getPlugin("Vault") == null) {
            plugin.getLogger().warning("未检测到 Vault，金币功能不可用（洗点/制造/卖出已禁用）。");
            return false;
        }
        RegisteredServiceProvider<Economy> registration =
                Bukkit.getServicesManager().getRegistration(Economy.class);
        if (registration != null && registration.getProvider() != null
                && !SakuraEconomy.class.isInstance(registration.getProvider())) {
            external = registration.getProvider();
            plugin.getLogger().info("检测到外部 Vault 经济实现：" + external.getName() + "。");
            return true;
        }
        provider = new SakuraEconomy(this);
        Bukkit.getServicesManager().register(Economy.class, provider, plugin,
                org.bukkit.plugin.ServicePriority.Normal);
        plugin.getLogger().info("内置经济 SakuraEconomy 已注册为 Vault provider（余额存 economy.yml）。");
        return true;
    }

    @Override
    public boolean available() {
        return external != null || provider != null;
    }

    // ---------------- EconomyBridge 实现（统一入口） ----------------

    @Override
    public boolean has(OfflinePlayer player, double amount) {
        if (external != null) {
            try {
                return external.has(player, amount);
            } catch (Throwable t) {
                return false;
            }
        }
        return provider != null && balance(player.getUniqueId()) >= amount;
    }

    @Override
    public boolean withdraw(OfflinePlayer player, double amount) {
        boolean ok;
        if (external != null) {
            try {
                ok = external.withdrawPlayer(player, amount).transactionSuccess();
            } catch (Throwable t) {
                return false;
            }
        } else {
            ok = provider != null && withdraw(player.getUniqueId(), amount).transactionSuccess();
        }
        if (ok && amount > 0) {
            totalBurned += amount;
            audit().log(player.getName(), "economy-withdraw",
                    Text.number(amount) + " balance=" + Text.number(balanceOf(player)));
        }
        return ok;
    }

    /** 存款（掉金/副本结算）。 */
    public boolean deposit(OfflinePlayer player, double amount) {
        boolean ok;
        if (external != null) {
            try {
                ok = external.depositPlayer(player, amount).transactionSuccess();
            } catch (Throwable t) {
                return false;
            }
        } else {
            ok = provider != null && deposit(player.getUniqueId(), amount).transactionSuccess();
        }
        if (ok && amount > 0) {
            totalMinted += amount;
            audit().log(player.getName(), "economy-deposit",
                    Text.number(amount) + " balance=" + Text.number(balanceOf(player)));
        }
        return ok;
    }

    public double totalMinted() {
        return totalMinted;
    }

    public double totalBurned() {
        return totalBurned;
    }

    public io.sakurammo.audit.AuditLogger audit() {
        return plugin.audit();
    }

    /** 当前余额（统一入口）。 */
    public double balanceOf(OfflinePlayer player) {
        if (external != null) {
            try {
                return external.getBalance(player);
            } catch (Throwable t) {
                return 0.0D;
            }
        }
        return provider == null ? 0.0D : balance(player.getUniqueId());
    }

    // ---------------- 余额（economy.yml） ----------------

    private void load() {
        if (!file.isFile()) {
            return;
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        ConfigurationToMap(yaml);
        plugin.getLogger().info("已加载 " + balances.size() + " 个金币账户。");
    }

    @SuppressWarnings("unchecked")
    private void ConfigurationToMap(YamlConfiguration yaml) {
        for (String key : yaml.getKeys(false)) {
            try {
                UUID uuid = UUID.fromString(key);
                balances.put(uuid, yaml.getDouble(key));
            } catch (IllegalArgumentException ignored) {
                // 非法键跳过
            }
        }
    }

    public synchronized void saveNow() {
        YamlConfiguration yaml = new YamlConfiguration();
        for (Map.Entry<UUID, Double> entry : balances.entrySet()) {
            yaml.set(entry.getKey().toString(), entry.getValue());
        }
        Path target = file.toPath();
        Path tmp = target.resolveSibling(file.getName() + ".tmp");
        try {
            yaml.save(tmp.toFile());
            try {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ex) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException ex) {
            plugin.getLogger().log(Level.SEVERE, "保存 economy.yml 失败", ex);
        }
    }

    public void saveAsync() {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, this::saveNow);
    }

    public double balance(UUID uuid) {
        return balances.getOrDefault(uuid, 0.0D);
    }

    public void setBalance(UUID uuid, double value) {
        balances.put(uuid, Math.max(0.0D, Math.round(value * 100.0D) / 100.0D));
        saveAsync();
    }

    public EconomyResponse deposit(UUID uuid, double amount) {
        if (amount < 0) {
            return new EconomyResponse(amount, balance(uuid), EconomyResponse.ResponseType.FAILURE, "金额为负");
        }
        setBalance(uuid, balance(uuid) + amount);
        return new EconomyResponse(amount, balance(uuid), EconomyResponse.ResponseType.SUCCESS, null);
    }

    public EconomyResponse withdraw(UUID uuid, double amount) {
        if (amount < 0) {
            return new EconomyResponse(amount, balance(uuid), EconomyResponse.ResponseType.FAILURE, "金额为负");
        }
        if (balance(uuid) < amount) {
            return new EconomyResponse(amount, balance(uuid), EconomyResponse.ResponseType.FAILURE, "余额不足");
        }
        setBalance(uuid, balance(uuid) - amount);
        return new EconomyResponse(amount, balance(uuid), EconomyResponse.ResponseType.SUCCESS, null);
    }

    // ---------------- Economy 接口的按名访问（离线玩家支持） ----------------

    public boolean hasAccount(UUID uuid) {
        return balances.containsKey(uuid);
    }

    public boolean hasAccount(String name) {
        return uuidByName(name) != null;
    }

    public double balanceByName(String name) {
        UUID uuid = uuidByName(name);
        return uuid == null ? 0.0D : balance(uuid);
    }

    public EconomyResponse depositByName(String name, double amount) {
        UUID uuid = uuidByName(name);
        return uuid == null
                ? new EconomyResponse(0, 0, EconomyResponse.ResponseType.FAILURE, "找不到玩家档案")
                : deposit(uuid, amount);
    }

    public EconomyResponse withdrawByName(String name, double amount) {
        UUID uuid = uuidByName(name);
        return uuid == null
                ? new EconomyResponse(0, 0, EconomyResponse.ResponseType.FAILURE, "找不到玩家档案")
                : withdraw(uuid, amount);
    }

    private UUID uuidByName(String name) {
        String wanted = name.toLowerCase(Locale.ROOT);
        UUID cached = nameIndex.get(wanted);
        if (cached != null) {
            return cached;
        }
        // 在线优先
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) {
            nameIndex.put(wanted, online.getUniqueId());
            return online.getUniqueId();
        }
        // 扫 playerdata（playerData 已有该能力）
        io.sakurammo.player.PlayerDataManager data = plugin.playerData();
        if (data != null) {
            UUID uuid = data.findOfflineUuid(name);
            if (uuid != null) {
                nameIndex.put(wanted, uuid);
                return uuid;
            }
        }
        return null;
    }

    // ---------------- 便捷封装（消费 / 掉金 / 卖出） ----------------

    /** 收费；余额不足返回 false 并提示。 */
    public boolean charge(Player player, double amount, String purposeKey) {
        if (amount <= 0.0D) {
            return true;
        }
        if (!available()) {
            MsgEconomyDisabled(player);
            return false;
        }
        EconomyResponse response = withdraw(player.getUniqueId(), amount);
        if (!response.transactionSuccess()) {
            io.sakurammo.util.Msg.send(player, "economy.not-enough",
                    "cost", Text.number(amount),
                    "balance", Text.number(balance(player.getUniqueId())));
            return false;
        }
        io.sakurammo.util.Msg.send(player, purposeKey, "cost", Text.number(amount));
        return true;
    }

    private void MsgEconomyDisabled(Player player) {
        io.sakurammo.util.Msg.send(player, "economy.disabled");
    }

    /** 掉金（击杀/副本结算）。 */
    public void deposit(Player player, double amount, String causeKey) {
        if (amount <= 0.0D) {
            return;
        }
        if (!available()) {
            return;
        }
        deposit(player.getUniqueId(), amount);
        io.sakurammo.util.Msg.send(player, causeKey, "amount", Text.number(amount),
                "balance", Text.number(balance(player.getUniqueId())));
    }

    public double killMoneyMin() {
        return plugin.config().main().killMoneyMin();
    }

    public double killMoneyMax() {
        return plugin.config().main().killMoneyMax();
    }

    public double bossMoneyMin() {
        return plugin.config().main().bossMoneyMin();
    }

    public double bossMoneyMax() {
        return plugin.config().main().bossMoneyMax();
    }

    public double sellRefundRate() {
        return plugin.config().main().sellRefundRate();
    }

    /** OfflinePlayer 形态（Vault 接口用）。 */
    public double balance(OfflinePlayer player) {
        return balance(player.getUniqueId());
    }
}
