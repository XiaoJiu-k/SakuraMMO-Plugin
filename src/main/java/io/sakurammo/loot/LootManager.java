package io.sakurammo.loot;

import io.sakurammo.SakuraMMOPlugin;
import io.sakurammo.craft.CraftingManager;
import io.sakurammo.instance.DungeonDefinition;
import io.sakurammo.instance.DungeonInstance;
import io.sakurammo.player.PlayerProfile;
import io.sakurammo.util.Msg;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.io.BukkitObjectInputStream;
import org.bukkit.util.io.BukkitObjectOutputStream;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;

/**
 * 背包隔离 + 奖励幂等结算。
 * <p>
 * 隔离（任务书模块 C）：进本保存快照并清空；出本丢弃副本内物品、恢复快照。
 * <p>
 * 幂等（红线）：领奖顺序固定为
 * <b>1. 标记 claimed + 奖励写入邮箱 → 2. 阻塞落盘 playerdata → 3. 发放</b>。
 * 发放发生在出本恢复时（邮箱随快照一并入包），任何时刻强杀都不会重复领取：
 * 强杀在步骤 2 前 → 玩家可再领（未标记，不亏）；在 2 后 → 标记已落盘，再点被拒（不重）。
 */
public final class LootManager {

    private final SakuraMMOPlugin plugin;

    public LootManager(SakuraMMOPlugin plugin) {
        this.plugin = plugin;
    }

    // ---------------- 背包序列化 ----------------

    /**
     * 背包全量序列化。1.21 的 {@code getContents()} 本就返回 41 格
     * （36 背包 + 4 盔甲 + 1 副手），不能再叠加盔甲/副手（会超 4 格上限炸 setArmorContents）。
     */
    public String serializeInventory(Player player) {
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream();
             BukkitObjectOutputStream out = new BukkitObjectOutputStream(bytes)) {
            ItemStack[] contents = player.getInventory().getContents();
            out.writeInt(contents.length);
            for (ItemStack item : contents) {
                out.writeObject(item);
            }
            out.flush();
            return Base64.getEncoder().encodeToString(bytes.toByteArray());
        } catch (Exception ex) {
            plugin.getLogger().severe("序列化背包失败（" + player.getName() + "）：" + ex.getMessage());
            return null;
        }
    }

    private ItemStack[] deserialize(String base64) {
        try (BukkitObjectInputStream in = new BukkitObjectInputStream(
                new ByteArrayInputStream(Base64.getDecoder().decode(base64)))) {
            int count = in.readInt();
            ItemStack[] items = new ItemStack[count];
            for (int i = 0; i < count; i++) {
                Object obj = in.readObject();
                items[i] = obj instanceof ItemStack stack ? stack : null;
            }
            return items;
        } catch (Exception ex) {
            plugin.getLogger().severe("反序列化背包失败：" + ex.getMessage());
            return null;
        }
    }

    // ---------------- 进本 / 出本 ----------------

    /**
     * 进本（v4.2.1 起）：<b>不再动玩家背包</b>——装备随身带进，副本里捡到的也随身带出。
     * <p>
     * 旧行为是「快照 + 清空 → 出本恢复」，本意是隔离主世界神装，实际后果是
     * 进本后赤手空拳、副本战利品出本被丢弃。新策略下全程不覆盖玩家背包，
     * 因此也不存在复制风险（没有任何一步会写入别人的旧数据）。
     */
    public boolean snapshotAndClear(Player player) {
        return true;
    }

    /**
     * 出本：清理副本标记 → 发放奖励邮箱（幂等：标记早已落盘）。
     * <p>
     * 兼容：老存档（清空隔离时代进本）仍有 {@code inventory-snapshot}，
     * 此时按快照恢复（含副本内捡到的旧式底材随身带出）；新存档没有快照，直接跳过，
     * 玩家背包原样保留。
     */
    public void restore(Player player) {
        PlayerProfile profile = plugin.playerData().getOrLoad(player);
        String snapshot = profile.inventorySnapshot();

        if (snapshot != null) {
            // ===== 老存档兼容：清空隔离时代的恢复路径 =====
            List<ItemStack> carriedMaterials = new ArrayList<>();
            for (ItemStack item : player.getInventory().getStorageContents()) {
                if (item != null && !item.getType().isAir()
                        && CraftingManager.materialIdOf(item) != null) {
                    carriedMaterials.add(item.clone());
                }
            }
            player.getInventory().clear();
            player.getInventory().setArmorContents(null);

            ItemStack[] items = deserialize(snapshot);
            if (items != null) {
                // 旧版（2.0.0 首轮）序列化含 46 项（41 格 + 重复盔甲/副手），截断到背包容量保证可恢复
                int capacity = player.getInventory().getContents().length;
                if (items.length > capacity) {
                    plugin.getLogger().warning("背包快照 " + items.length + " 项超过容量 " + capacity
                            + "（旧格式），已截断恢复。");
                    ItemStack[] trimmed = new ItemStack[capacity];
                    System.arraycopy(items, 0, trimmed, 0, capacity);
                    items = trimmed;
                }
                player.getInventory().setContents(items);
            }
            for (ItemStack material : carriedMaterials) {
                player.getInventory().addItem(material).values()
                        .forEach(left -> player.getWorld().dropItemNaturally(player.getLocation(), left));
            }
        }

        profile.inventorySnapshot(null);
        profile.inventoryIsolated(false);

        // 邮箱奖励发放（幂等的「发放」步骤：标记早已落盘）
        List<String> mailbox = new ArrayList<>(profile.rewardMailbox());
        if (!mailbox.isEmpty()) {
            for (String raw : mailbox) {
                ItemStack[] items = deserialize(raw);
                if (items == null) {
                    continue;
                }
                for (ItemStack item : items) {
                    if (item == null) {
                        continue;
                    }
                    player.getInventory().addItem(item).values()
                            .forEach(left -> player.getWorld().dropItemNaturally(player.getLocation(), left));
                }
            }
            profile.rewardMailbox().clear();
            Msg.send(player, "loot.mailbox-delivered", "count", String.valueOf(mailbox.size()));
        }
        profile.activeRun(null);
        player.updateInventory();
        plugin.playerData().save(profile); // 最终状态阻塞落盘
    }

    // ---------------- 奖励箱幂等领取 ----------------

    /**
     * 点击奖励箱：run-id + 玩家已领取标记幂等。
     * 顺序：标记 + 入邮箱 → 阻塞落盘 → 提示（发放延迟到出本，见类注释）。
     *
     * @return true = 发放成功；false = 已领取过（拒绝交互）
     */
    public boolean claimChest(Player player, DungeonInstance instance, DungeonDefinition definition) {
        PlayerProfile profile = plugin.playerData().getOrLoad(player);
        String runId = instance.runId;
        if (profile.claimedRewards().contains(runId)) {
            Msg.send(player, "loot.already-claimed");
            return false;
        }
        List<ItemStack> items = parseRewards(definition);
        if (items.isEmpty()) {
            Msg.send(player, "loot.empty");
            return false;
        }
        profile.claimedRewards().add(runId);
        profile.rewardMailbox().add(serializeItems(items));
        plugin.playerData().save(profile); // 阻塞保存：先落盘后发放（红线顺序）
        Msg.send(player, "loot.claimed", "count", String.valueOf(items.size()));
        plugin.getLogger().info("[loot] " + player.getName() + " 领取 run=" + runId
                + " 奖励 " + items.size() + " 件（已标记并落盘）");
        plugin.weeklies().progress(player, "claim_chest", 1);
        return true;
    }

    /** "MATERIAL:amount" 列表 → 物品。 */
    public List<ItemStack> parseRewards(DungeonDefinition definition) {
        List<ItemStack> items = new ArrayList<>();
        for (String raw : definition.rewardItems) {
            String[] parts = raw.split(":");
            Material material = Material.matchMaterial(parts[0].trim().toUpperCase(Locale.ROOT));
            if (material == null || !material.isItem()) {
                plugin.getLogger().warning("副本 " + definition.id + " 的奖励物品非法：" + raw);
                continue;
            }
            int amount = 1;
            if (parts.length > 1) {
                try {
                    amount = Math.max(1, Math.min(64, Integer.parseInt(parts[1].trim())));
                } catch (NumberFormatException ignored) {
                    // 保持 1
                }
            }
            items.add(new ItemStack(material, amount));
        }
        return items;
    }

    private String serializeItems(List<ItemStack> items) {
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream();
             BukkitObjectOutputStream out = new BukkitObjectOutputStream(bytes)) {
            out.writeInt(items.size());
            for (ItemStack item : items) {
                out.writeObject(item);
            }
            out.flush();
            return Base64.getEncoder().encodeToString(bytes.toByteArray());
        } catch (Exception ex) {
            plugin.getLogger().severe("序列化奖励失败：" + ex.getMessage());
            return "";
        }
    }
}
