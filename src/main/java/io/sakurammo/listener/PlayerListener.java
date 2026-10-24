package io.sakurammo.listener;

import io.sakurammo.SakuraMMOPlugin;
import io.sakurammo.config.ClassDefinition;
import io.sakurammo.config.MainConfig;
import io.sakurammo.config.ResourceDefinition;
import io.sakurammo.config.SkillDefinition;
import io.sakurammo.player.PlayerProfile;
import io.sakurammo.skill.SkillService;
import io.sakurammo.util.Msg;
import io.sakurammo.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerToggleSneakEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 玩家生命周期 + 技能触发入口（v3 按键布局：Shift+1/2/3/4 施法，F 菜单）。
 * <ul>
 *   <li>Shift + 快捷栏 1/2/3/4 → 施放技能槽 1-4：{@link PlayerItemHeldEvent}，
 *       取消切槽并施法（只拦已绑定槽，5-9 放行）——原版客户端 100% 可触发</li>
 *   <li>F 键（副手交换）→ 打开菜单；潜行 + F 保留原版副手交换（换手出口）</li>
 *   <li>V 键 → 菜单（KeyBridgeHook 的 InputEngine 反射监听，装模组后可用）</li>
 * </ul>
 */
public final class PlayerListener implements Listener {

    private final SakuraMMOPlugin plugin;

    /** 诊断计数：F 键开菜单次数 / Shift 组合键施法次数 */
    private int menuCount;
    private int comboCount;

    /** 同一玩家 150ms 内的重复施法去重（多触发路径防双发）。 */
    private final Map<UUID, Long> lastCast = new ConcurrentHashMap<>();

    public PlayerListener(SakuraMMOPlugin plugin) {
        this.plugin = plugin;
    }

    public int menuCount() {
        return menuCount;
    }

    public int comboCount() {
        return comboCount;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        PlayerProfile profile = plugin.playerData().getOrLoad(player);

        // 有职业但没有资源记录（首次上线/旧数据）→ 按初始值补一份
        if (profile.hasClass() && profile.resources().isEmpty()) {
            plugin.resources().resetToInitial(player);
        }
        if (profile.hasClass()) {
            plugin.classManager().scheduleApplyOnJoin(player);
        } else if (plugin.aura().available()) {
            // 无职业登录（如被管理员离线重置）→ 清掉 AuraSkills 残留的 class_ 修饰符
            plugin.classManager().scheduleModifierCleanup(player);
        }
        // 阶段二：队伍在线标记 + 副本断线重连（还在本内→传送回去；本已结束→恢复背包回城）
        plugin.parties().markOnline(player.getUniqueId());
        plugin.instances().handleRejoin(player);
        // 阶段三：天赋 modifier 登录重挂（任务书坑表：离线修改上线要生效）
        plugin.talents().scheduleApplyOnJoin(player);
        // 阶段四：周常 Cycle-ID 校验（跨周期登录即重置，无遍历）
        plugin.weeklies().ensureCycle(player);
        // 阶段四：职业等级 → 原版等级显示（档案等级高于原版时一次性迁移，豁免发点）
        if (profile.level() > player.getLevel() && profile.level() > 1) {
            plugin.weeklies().suppressNextLevelEvent(player);
            player.setLevel(Math.min(profile.level(), 60));
        }
    }

    /** 阶段四：原版等级变化 → 同步职业档案 + 天赋点发放（Lv.10 起每 3 级 1 点）。 */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onLevelChange(org.bukkit.event.player.PlayerLevelChangeEvent event) {
        plugin.weeklies().handleLevelChange(event.getPlayer(), event.getOldLevel(), event.getNewLevel());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        plugin.hud().clearOverride(player.getUniqueId());
        plugin.classManager().revokeSkillPermissions(player);
        plugin.parties().markOffline(player.getUniqueId());
        plugin.instances().handleQuit(player);
        plugin.talents().clearCasts(player.getUniqueId());
        plugin.playerData().unload(player.getUniqueId());
    }

    /**
     * F 键（副手交换）打开菜单。潜行 + F 保留原版副手交换（给需要换手的玩家留出口）。
     * V 键的菜单入口在 KeyBridgeHook（装桥接模组后直发）。
     */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onSwapHands(PlayerSwapHandItemsEvent event) {
        MainConfig config = plugin.config().main();
        if (!config.menuEnabled() || !config.menuInterceptSwap()) {
            return;
        }
        Player player = event.getPlayer();
        if (config.menuShiftBypass() && player.isSneaking()) {
            return; // 潜行 + F：原版副手交换
        }
        event.setCancelled(true);
        menuCount++;
        // 下一 tick 再开，避免和副手交换动作在同一 tick 打架
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (player.isOnline()) {
                plugin.menus().open(player);
            }
        });
    }

    /**
     * Shift + 快捷栏 1/2/3/4 → 技能槽 1-4（原版客户端的技能触发，空手可用）。
     * 只拦截「绑定了技能的槽」；5-9 与未绑定槽保持原版切槽。
     */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onItemHeld(PlayerItemHeldEvent event) {
        if (!plugin.config().main().keybindShiftHotbarEnabled()) {
            return;
        }
        Player player = event.getPlayer();
        if (!player.isSneaking()) {
            return;
        }
        int slot = event.getNewSlot();
        if (slot < 0 || slot >= PlayerProfile.SKILL_KEYS.size()) {
            return; // 快捷栏 5-9：正常切槽
        }
        String key = PlayerProfile.SKILL_KEYS.get(slot);
        PlayerProfile profile = plugin.playerData().get(player.getUniqueId());
        if (profile == null || !profile.hasClass()) {
            return;
        }
        String skillId = profile.skillBind(key);
        if (skillId == null) {
            return; // 该槽未绑定 → 正常切槽
        }
        event.setCancelled(true);
        comboCount++;
        if (plugin.config().main().debug()) {
            plugin.getLogger().info("[debug] " + player.getName() + " Shift+" + key
                    + " 触发：" + skillId);
        }
        castBound(player, skillId, SkillService.keyDisplay(key));
    }

    /**
     * Shift 按下的瞬间检查当前选中槽位（阶段四用户反馈：已站在槽位上时
     * Shift+数字无法施法——同槽数字键客户端不发任何包，PlayerItemHeldEvent
     * 收不到）。当前槽 1~4 且有绑定时立即施放；空槽/5-9/未绑定 = 正常潜行不误触。
     */
    @EventHandler(priority = EventPriority.NORMAL)
    public void onSneakPress(PlayerToggleSneakEvent event) {
        if (!event.isSneaking()) {
            return; // 只处理按下瞬间
        }
        if (!plugin.config().main().keybindShiftHotbarEnabled()) {
            return;
        }
        Player player = event.getPlayer();
        int slot = player.getInventory().getHeldItemSlot();
        if (slot < 0 || slot >= PlayerProfile.SKILL_KEYS.size()) {
            return;
        }
        String key = PlayerProfile.SKILL_KEYS.get(slot);
        PlayerProfile profile = plugin.playerData().get(player.getUniqueId());
        if (profile == null || !profile.hasClass()) {
            return;
        }
        String skillId = profile.skillBind(key);
        if (skillId == null) {
            return; // 当前槽未绑定 → 正常潜行
        }
        comboCount++;
        if (plugin.config().main().debug()) {
            plugin.getLogger().info("[debug] " + player.getName() + " Shift+" + key
                    + "（潜行触发，当前槽位）：" + skillId);
        }
        castBound(player, skillId, SkillService.keyDisplay(key));
    }

    /** 按键、组合键与 /sm cast slot 共用的施放入口：查技能 → 五项校验 → MythicMobs。 */
    public void castBound(Player player, String skillId, String keyLabel) {
        long now = System.currentTimeMillis();
        Long last = lastCast.get(player.getUniqueId());
        if (last != null && now - last < 150L) {
            return; // 多触发路径去重
        }
        lastCast.put(player.getUniqueId(), now);

        SkillDefinition definition = plugin.config().skill(skillId);
        if (definition == null) {
            plugin.hud().castMessage(player, Msg.raw("skill.unknown", "id", skillId), 2);
            return;
        }
        SkillService.Result result = plugin.skills().cast(player, definition);
        if (result.ok()) {
            // 行 1 = 施法提示（独立淡出）；行 2 资源条由 HUD 周期渲染，不再抢占拼接
            plugin.hud().recordSkillCast(player, definition.id(), definition.displayName());
            plugin.hud().castMessage(player, Msg.raw("hud.cast-ok",
                    "skill", "[" + keyLabel + "] " + definition.displayName(), "resource", ""), 2);
        } else if (result.message() != null) {
            plugin.hud().castMessage(player, "[" + keyLabel + "] " + result.message(), 2);
        }
    }
}
