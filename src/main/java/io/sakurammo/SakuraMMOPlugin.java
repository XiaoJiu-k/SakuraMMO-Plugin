package io.sakurammo;

import io.sakurammo.audit.AuditLogger;
import io.sakurammo.classsystem.ClassManager;
import io.sakurammo.command.SakuraCommand;
import io.sakurammo.config.ConfigManager;
import io.sakurammo.craft.CraftingManager;
import io.sakurammo.economy.EconomyManager;
import io.sakurammo.hook.AuraSkillsHook;
import io.sakurammo.hook.MythicMobsHook;
import io.sakurammo.hook.PlaceholderBootstrap;
import io.sakurammo.hud.HudService;
import io.sakurammo.instance.InstanceManager;
import io.sakurammo.listener.CombatListener;
import io.sakurammo.listener.DungeonListener;
import io.sakurammo.listener.PlayerListener;
import io.sakurammo.loot.LootManager;
import io.sakurammo.menu.MenuManager;
import io.sakurammo.party.PartyManager;
import io.sakurammo.player.PlayerDataManager;
import io.sakurammo.resource.ResourceService;
import io.sakurammo.skill.SkillService;
import io.sakurammo.talent.TalentManager;
import io.sakurammo.weekly.UpgradeManager;
import io.sakurammo.weekly.WeeklyManager;
import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

/**
 * SakuraMMO —— 职业 / 资源 / 技能入口层。
 * <p>
 * 职责边界（任务书 §1）：
 * <ul>
 *   <li>本插件：职业选择与切换、资源循环、技能入口（绑定 / 校验 / 冷却）、玩家数据</li>
 *   <li>不负责：技能效果（MythicMobs）、属性聚合（AuraSkills）、装备词条（RPGLoot）</li>
 * </ul>
 */
public final class SakuraMMOPlugin extends JavaPlugin {

    private ConfigManager config;
    private PlayerDataManager playerData;

    private AuraSkillsHook aura;
    private io.sakurammo.hook.KeyBridgeHook keyBridge;
    private MythicMobsHook mythic;
    private EconomyManager economyManager;
    private TalentManager talents;
    private CraftingManager crafting;
    private AuditLogger audit;
    private WeeklyManager weeklies;
    private UpgradeManager upgrades;

    private ClassManager classManager;
    private ResourceService resources;
    private SkillService skills;
    private HudService hud;
    private MenuManager menus;
    private PlayerListener playerListener;
    private PartyManager parties;
    private InstanceManager instances;
    private LootManager loot;

    private Object placeholderHandle;
    private boolean placeholderRegistered;

    private BukkitTask autoSaveTask;

    @Override
    public void onEnable() {
        long startedAt = System.currentTimeMillis();

        config = new ConfigManager(this);
        config.loadAll();

        playerData = new PlayerDataManager(this);

        aura = new AuraSkillsHook(this);
        keyBridge = new io.sakurammo.hook.KeyBridgeHook(this);
        mythic = new MythicMobsHook(this);
        audit = new AuditLogger(this);
        audit.start();
        economyManager = new EconomyManager(this);
        economyManager.hookVault(); // 外部经济优先；无则注册内置 SakuraEconomy

        classManager = new ClassManager(this);
        resources = new ResourceService(this);
        skills = new SkillService(this);
        hud = new HudService(this);
        menus = new MenuManager(this);

        // 阶段二：队伍 / 副本实例 / 结算
        parties = new PartyManager(this);
        loot = new LootManager(this);
        instances = new InstanceManager(this);
        // 阶段三：天赋 / 制造
        talents = new TalentManager(this);
        crafting = new CraftingManager(this);
        // 阶段四：周常 / 追赶
        weeklies = new WeeklyManager(this);
        upgrades = new UpgradeManager(this);
        instances.startupCleanup(); // 兜底：删除宕机残留的 instance_* 世界目录
        parties.recoverFromCrash(); // 上次非正常结束的副本队伍 → IDLE（玩家背包在其上线时恢复）

        registerCommand();
        playerListener = new PlayerListener(this);
        Bukkit.getPluginManager().registerEvents(playerListener, this);
        Bukkit.getPluginManager().registerEvents(new CombatListener(this), this);
        Bukkit.getPluginManager().registerEvents(new DungeonListener(this), this);
        menus.register();
        parties.start();

        placeholderHandle = PlaceholderBootstrap.register(this);
        placeholderRegistered = placeholderHandle != null;

        resources.start();
        hud.start();
        startAutoSave();
        skills.validateMythicSkills();

        // 插件被热加载时可能已有在线玩家
        for (Player player : Bukkit.getOnlinePlayers()) {
            playerData.getOrLoad(player);
            classManager.scheduleApplyOnJoin(player);
        }

        getLogger().info("SakuraMMO 已启用（" + (System.currentTimeMillis() - startedAt) + " ms）："
                + config.classCount() + " 职业 / " + config.skillCount() + " 技能 / 挂钩 "
                + (aura.available() ? "AuraSkills✓" : "AuraSkills✗") + " "
                + (mythic.available() ? "MythicMobs✓" : "MythicMobs✗") + " "
                + (placeholderRegistered ? "PAPI✓" : "PAPI✗"));
    }

    @Override
    public void onDisable() {
        if (autoSaveTask != null) {
            autoSaveTask.cancel();
            autoSaveTask = null;
        }
        if (parties != null) {
            parties.stop();
        }
        // 副本兜底：还在线的副本玩家先恢复背包再停服（世界目录交给下次启动清理）
        if (instances != null) {
            instances.shutdownRestore();
        }
        if (resources != null) {
            resources.stop();
        }
        if (hud != null) {
            hud.stop();
        }
        if (playerData != null) {
            playerData.saveAll();
            getLogger().info("玩家数据已保存（" + playerData.cachedCount() + " 份）。");
        }
        if (parties != null) {
            parties.saveNow();
        }
        if (economyManager != null) {
            economyManager.saveNow();
            audit.stop();
        }
        PlaceholderBootstrap.unregister(placeholderHandle);
        placeholderHandle = null;
        placeholderRegistered = false;
    }

    /** /sm reload：只重载配置，不动玩家数据。 */
    public void reloadEverything() {
        config.loadAll();
        resources.start();
        hud.start();
        classManager.refreshOnlinePlayers();
        skills.validateMythicSkills();
        talents.load();
        crafting.load();
        weeklies.load();
        upgrades.load();
        startAutoSave();
    }

    private void registerCommand() {
        PluginCommand command = getCommand("sm");
        if (command == null) {
            getLogger().severe("命令 sm 未在 plugin.yml 中注册，插件功能不可用。");
            return;
        }
        SakuraCommand executor = new SakuraCommand(this);
        command.setExecutor(executor);
        command.setTabCompleter(executor);
    }

    private void startAutoSave() {
        if (autoSaveTask != null) {
            autoSaveTask.cancel();
        }
        long intervalTicks = Math.max(20L, config.main().saveIntervalSeconds() * 20L);
        autoSaveTask = Bukkit.getScheduler().runTaskTimer(this, () -> {
            playerData.saveDirty();
            if (config.main().debug()) {
                getLogger().info("[debug] 定时保存完成，缓存 " + playerData.cachedCount() + " 份。");
            }
        }, intervalTicks, intervalTicks);
    }

    // ---------------- 访问器 ----------------

    public ConfigManager config() {
        return config;
    }

    public PlayerDataManager playerData() {
        return playerData;
    }

    public io.sakurammo.hook.KeyBridgeHook keyBridge() {
        return keyBridge;
    }

    public AuraSkillsHook aura() {
        return aura;
    }

    public MythicMobsHook mythic() {
        return mythic;
    }

    public EconomyManager economy() {
        return economyManager;
    }

    public TalentManager talents() {
        return talents;
    }

    public CraftingManager crafting() {
        return crafting;
    }

    public AuditLogger audit() {
        return audit;
    }

    public WeeklyManager weeklies() {
        return weeklies;
    }

    public UpgradeManager upgrades() {
        return upgrades;
    }

    public ClassManager classManager() {
        return classManager;
    }

    public ResourceService resources() {
        return resources;
    }

    public SkillService skills() {
        return skills;
    }

    public HudService hud() {
        return hud;
    }

    public MenuManager menus() {
        return menus;
    }

    public PartyManager parties() {
        return parties;
    }

    public InstanceManager instances() {
        return instances;
    }

    public LootManager loot() {
        return loot;
    }

    public PlayerListener playerListener() {
        return playerListener;
    }

    public boolean placeholderRegistered() {
        return placeholderRegistered;
    }
}
