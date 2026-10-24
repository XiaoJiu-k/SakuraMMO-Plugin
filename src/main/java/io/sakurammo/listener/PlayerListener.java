package io.sakurammo.listener;

import io.sakurammo.SakuraMMOPlugin;
import io.sakurammo.config.SkillDefinition;
import io.sakurammo.player.PlayerProfile;
import io.sakurammo.player.SkillBinding;
import io.sakurammo.skill.SkillService;
import io.sakurammo.util.Text;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

/**
 * 玩家生命周期 + 技能触发入口。
 * <p>
 * 注意要同时监听两种事件：
 * <ul>
 *   <li>{@link PlayerInteractEvent} —— 右键空气 / 方块、左键空气 / 方块</li>
 *   <li>{@link PlayerInteractEntityEvent} —— 右键实体（打怪、点训练假人），原版不会触发前一个事件</li>
 * </ul>
 */
public final class PlayerListener implements Listener {

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacySection();

    private final SakuraMMOPlugin plugin;

    /** 诊断计数：证明交互事件到底有没有到达插件 */
    private int interactCount;
    private int entityInteractCount;

    public PlayerListener(SakuraMMOPlugin plugin) {
        this.plugin = plugin;
    }

    public int interactCount() {
        return interactCount;
    }

    public int entityInteractCount() {
        return entityInteractCount;
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
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        plugin.classManager().revokeSkillPermissions(player);
        plugin.playerData().unload(player.getUniqueId());
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        interactCount++;
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        Action action = event.getAction();
        boolean rightClick = action == Action.RIGHT_CLICK_AIR || action == Action.RIGHT_CLICK_BLOCK;
        boolean leftClick = action == Action.LEFT_CLICK_AIR || action == Action.LEFT_CLICK_BLOCK;
        if (!rightClick && !leftClick) {
            return;
        }
        boolean onBlock = action == Action.RIGHT_CLICK_BLOCK || action == Action.LEFT_CLICK_BLOCK;
        if (tryCast(event.getPlayer(), rightClick, leftClick, onBlock) && onBlock) {
            // 只拦「对方块」：避免技能触发时顺手放了方块。
            // 空气右键不拦，否则弓类武器会因为绑定技能而无法蓄力。
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        entityInteractCount++;
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        if (tryCast(event.getPlayer(), true, false, false)) {
            event.setCancelled(true);
        }
    }

    /**
     * 主手绑定校验 + 施法。
     *
     * @return true 表示技能确实施放成功（调用方据此决定是否取消原版行为）
     */
    private boolean tryCast(Player player, boolean rightClick, boolean leftClick, boolean onBlock) {
        PlayerProfile profile = plugin.playerData().get(player.getUniqueId());
        if (profile == null || !profile.hasClass()) {
            return false;
        }

        int slot = player.getInventory().getHeldItemSlot();
        SkillBinding binding = profile.boundSkills().get(slot);
        if (binding == null) {
            dbg(player, "槽位 " + slot + " 没有绑定技能");
            return false;
        }
        SkillDefinition definition = plugin.config().skill(binding.skillId());
        if (definition == null) {
            dbg(player, "绑定指向的技能 " + binding.skillId() + " 不存在");
            return false;
        }
        if (!definition.trigger().matches(rightClick, leftClick, onBlock)) {
            dbg(player, definition.id() + " 触发方式不匹配（trigger=" + definition.trigger()
                    + " right=" + rightClick + " left=" + leftClick + " block=" + onBlock + "）");
            return false;
        }

        // 主手物品必须和绑定时一致，否则视为「换武器了」，不触发也不刷屏
        ItemStack hand = player.getInventory().getItemInMainHand();
        Material material = hand == null ? Material.AIR : hand.getType();
        if (binding.material() != null && material != binding.material()) {
            dbg(player, "主手材质不匹配：需要 " + binding.material() + "，当前 " + material);
            return false;
        }

        SkillService.Result result = plugin.skills().cast(player, definition);
        if (result.ok()) {
            return true;
        }
        if (result.message() != null) {
            player.sendActionBar(LEGACY.deserialize(Text.color(result.message())));
        }
        return false;
    }

    private void dbg(Player player, String message) {
        if (plugin.config().main().debug()) {
            plugin.getLogger().info("[debug] " + player.getName() + " 触发检查：" + message);
        }
    }
}
