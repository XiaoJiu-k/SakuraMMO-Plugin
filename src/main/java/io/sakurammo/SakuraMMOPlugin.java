package io.sakurammo;

import io.sakurammo.classsystem.ClassManager;
import io.sakurammo.command.SakuraCommand;
import io.sakurammo.config.ConfigManager;
import io.sakurammo.hook.AuraSkillsHook;
import io.sakurammo.hook.EconomyBridge;
import io.sakurammo.hook.MythicMobsHook;
import io.sakurammo.hook.PlaceholderBootstrap;
import io.sakurammo.hook.VaultBootstrap;
import io.sakurammo.hud.HudService;
import io.sakurammo.listener.CombatListener;
import io.sakurammo.listener.PlayerListener;
import io.sakurammo.player.PlayerDataManager;
import io.sakurammo.resource.ResourceService;
import io.sakurammo.skill.SkillService;
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
    private MythicMobsHook mythic;
    private EconomyBridge vault = new EconomyBridge.Noop();

    private ClassManager classManager;
    private ResourceService resources;
    private SkillService skills;
    private HudService hud;

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
        mythic = new MythicMobsHook(this);
        vault = VaultBootstrap.create(this);

        classManager = new ClassManager(this);
        resources = new ResourceService(this);
        skills = new SkillService(this);
        hud = new HudService(this);

        registerCommand();
        Bukkit.getPluginManager().registerEvents(new PlayerListener(this), this);
        Bukkit.getPluginManager().registerEvents(new CombatListener(this), this);

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

    public AuraSkillsHook aura() {
        return aura;
    }

    public MythicMobsHook mythic() {
        return mythic;
    }

    public EconomyBridge vault() {
        return vault;
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

    public boolean placeholderRegistered() {
        return placeholderRegistered;
    }
}
