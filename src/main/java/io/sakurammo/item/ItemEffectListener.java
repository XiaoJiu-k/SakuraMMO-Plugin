package io.sakurammo.item;

import io.sakurammo.SakuraMMOPlugin;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 固定属性物品的运行时行为（v4.2 模块 A）：
 * <ul>
 *   <li>命中类特殊效果（落雷 / 中毒 / 吸血 / 缓慢 / 灼烧），带概率与内置冷却</li>
 *   <li>套装加成：换装、登录、关背包时重算（AuraSkills modifier 前缀 {@code set_}）</li>
 * </ul>
 */
public final class ItemEffectListener implements Listener {

    private final SakuraMMOPlugin plugin;
    /** 冷却键：玩家 UUID + 效果类型 → 下次可触发的毫秒时间戳。 */
    private final Map<String, Long> cooldowns = new ConcurrentHashMap<>();
    /** 递归护栏：效果造成的伤害不再触发效果。 */
    private final java.util.Set<UUID> applying = ConcurrentHashMap.newKeySet();

    /** 每个玩家上次的套装签名（套装 id:件数），变化时才重挂，避免每 5 秒刷一遍 modifier。 */
    private final Map<UUID, String> setSignatures = new ConcurrentHashMap<>();

    public ItemEffectListener(SakuraMMOPlugin plugin) {
        this.plugin = plugin;
        // 兜底轮询：装备可能通过 API / 快捷栏 / 插件途径变化，不一定会触发交互事件。
        // 每 5 秒比对一次「套装签名」，只有变化才重算（4 件物品读取，开销可忽略）。
        plugin.getServer().getScheduler().runTaskTimer(plugin, this::refreshAll, 100L, 100L);
    }

    private void refreshAll() {
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            refreshIfChanged(player);
        }
    }

    private void refreshIfChanged(Player player) {
        StringBuilder sb = new StringBuilder();
        for (ItemStack armor : player.getInventory().getArmorContents()) {
            if (armor == null || armor.getType().isAir() || !armor.hasItemMeta()) {
                continue;
            }
            String setId = armor.getItemMeta().getPersistentDataContainer()
                    .get(ItemManager.KEY_SET, org.bukkit.persistence.PersistentDataType.STRING);
            if (setId != null) {
                sb.append(setId).append(':').append(armor.getType().name()).append(',');
            }
        }
        String signature = sb.toString();
        String previous = setSignatures.put(player.getUniqueId(), signature);
        if (!signature.equals(previous == null ? "" : previous)) {
            plugin.items().applySetBonuses(player);
            if (plugin.config().main().debug() && !signature.isEmpty()) {
                plugin.getLogger().info("[debug] 套装变化 " + player.getName() + " → " + signature);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHit(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player player)) {
            return;
        }
        if (!(event.getEntity() instanceof LivingEntity target)) {
            return;
        }
        if (!applying.add(target.getUniqueId())) {
            return;
        }
        try {
            ItemStack weapon = player.getInventory().getItemInMainHand();
            ItemManager manager = plugin.items();
            String itemId = manager.idOf(weapon);
            if (itemId == null) {
                return;
            }
            ItemDefinition def = manager.definition(itemId).orElse(null);
            if (def == null || def.effects().isEmpty()) {
                return;
            }
            long now = System.currentTimeMillis();
            for (ItemEffect effect : def.effects()) {
                if (ThreadLocalRandom.current().nextDouble() > effect.chance()) {
                    continue;
                }
                String key = player.getUniqueId() + ":" + effect.cooldownKey();
                Long readyAt = cooldowns.get(key);
                if (readyAt != null && readyAt > now) {
                    continue;
                }
                cooldowns.put(key, now + (long) (effect.cooldownSeconds() * 1000L));
                apply(player, target, effect);
                if (plugin.config().main().debug()) {
                    plugin.getLogger().info("[debug] 物品效果触发 " + def.id() + " → "
                            + effect.type() + " on " + target.getName());
                }
            }
        } finally {
            applying.remove(target.getUniqueId());
        }
    }

    private void apply(Player player, LivingEntity target, ItemEffect effect) {
        switch (effect.type()) {
            case LIGHTNING -> {
                target.getWorld().strikeLightningEffect(target.getLocation());
                target.damage(Math.max(1.0D, effect.value()), player);
            }
            case POISON -> target.addPotionEffect(new PotionEffect(
                    PotionEffectType.POISON, 100, (int) Math.max(0, effect.value() - 1), true, true));
            case LIFESTEAL -> {
                double heal = Math.max(0.5D, effect.value() * 2.0D);
                player.setHealth(Math.min(player.getMaxHealth(), player.getHealth() + heal));
            }
            case SLOW -> target.addPotionEffect(new PotionEffect(
                    PotionEffectType.SLOWNESS, 100, (int) Math.max(0, effect.value() - 1), true, true));
            case BURN -> target.setFireTicks((int) Math.max(1, effect.value()) * 20);
        }
    }

    /** 右键穿装备（不打开背包的路径）：1 tick 后重算套装。 */
    @EventHandler
    public void onEquip(org.bukkit.event.player.PlayerInteractEvent event) {
        if (event.getHand() != org.bukkit.inventory.EquipmentSlot.HAND) {
            return;
        }
        org.bukkit.inventory.ItemStack item = event.getItem();
        if (item == null || item.getType().isAir()) {
            return;
        }
        String name = item.getType().name();
        if (!(name.endsWith("_HELMET") || name.endsWith("_CHESTPLATE")
                || name.endsWith("_LEGGINGS") || name.endsWith("_BOOTS"))) {
            return;
        }
        org.bukkit.entity.Player player = event.getPlayer();
        plugin.getServer().getScheduler().runTask(plugin,
                () -> plugin.items().applySetBonuses(player));
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        plugin.getServer().getScheduler().runTaskLater(plugin,
                () -> {
                    if (player.isOnline()) {
                        setSignatures.remove(player.getUniqueId());
                        refreshIfChanged(player);
                    }
                }, 45L);
    }

    @EventHandler
    public void onQuit(org.bukkit.event.player.PlayerQuitEvent event) {
        setSignatures.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        if (event.getPlayer() instanceof Player player) {
            plugin.items().applySetBonuses(player);
        }
    }
}
