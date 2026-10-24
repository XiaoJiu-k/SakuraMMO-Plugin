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
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

/**
 * 玩家生命周期 + 技能右键入口。
 */
public final class PlayerListener implements Listener {

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacySection();

    private final SakuraMMOPlugin plugin;

    public PlayerListener(SakuraMMOPlugin plugin) {
        this.plugin = plugin;
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

        Player player = event.getPlayer();
        PlayerProfile profile = plugin.playerData().get(player.getUniqueId());
        if (profile == null || !profile.hasClass()) {
            return;
        }

        int slot = player.getInventory().getHeldItemSlot();
        SkillBinding binding = profile.boundSkills().get(slot);
        if (binding == null) {
            return;
        }
        SkillDefinition definition = plugin.config().skill(binding.skillId());
        if (definition == null) {
            return;
        }
        if (!definition.trigger().matches(rightClick, leftClick, onBlock)) {
            return;
        }

        // 主手物品必须和绑定时一致，否则视为「换武器了」，不触发也不刷屏
        ItemStack hand = player.getInventory().getItemInMainHand();
        Material material = hand == null ? Material.AIR : hand.getType();
        if (binding.material() != null && material != binding.material()) {
            if (plugin.config().main().debug()) {
                plugin.getLogger().info("[debug] " + player.getName() + " 槽位 " + slot
                        + " 绑定的 " + binding.skillId() + " 需要 " + binding.material()
                        + "，当前 " + material + "，已跳过。");
            }
            return;
        }

        SkillService.Result result = plugin.skills().cast(player, definition);
        if (result.ok()) {
            event.setCancelled(true);
        } else if (result.message() != null) {
            actionBar(player, result.message());
        }
    }

    private void actionBar(Player player, String message) {
        if (message == null || message.isEmpty()) {
            return;
        }
        player.sendActionBar(LEGACY.deserialize(Text.color(message)));
    }
}
