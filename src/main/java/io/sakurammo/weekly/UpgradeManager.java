package io.sakurammo.weekly;

import io.sakurammo.SakuraMMOPlugin;
import io.sakurammo.util.Msg;
import io.sakurammo.util.Text;
import org.bukkit.attribute.Attribute;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * 装备追赶（阶段四模块 C）：
 * <ul>
 *   <li><b>升级</b>：手持旧装备 + 底材 + 金币 + 代币 → 新版本装备；
 *       旧物品的全部 attribute modifiers（RPGLoot 掉落词条/制造词条）原样继承，再叠加新词条</li>
 *   <li><b>代币兑换</b>：代币 → 保底装备（防脸黑）</li>
 * </ul>
 * 配置：upgrade.yml（upgrades = 升级表；redeems = 代币兑换表）。
 */
public final class UpgradeManager {

    private final SakuraMMOPlugin plugin;
    private final Map<String, ProductDef> upgrades = new LinkedHashMap<>();
    private final Map<String, ProductDef> redeems = new LinkedHashMap<>();

    /** 升级/兑换共用的产物定义。 */
    public record ProductDef(String id, String display, Material item, String color,
                             List<String> lore, Map<String, Double> attributes,
                             String fromItem, boolean requireCrafted,
                             Map<String, Integer> costMaterials, double fee, int tokenCost) {
    }

    public UpgradeManager(SakuraMMOPlugin plugin) {
        this.plugin = plugin;
        load();
    }

    public void load() {
        upgrades.clear();
        redeems.clear();
        File file = new File(plugin.getDataFolder(), "upgrade.yml");
        if (!file.isFile()) {
            plugin.getLogger().warning("upgrade.yml 不存在，追赶系统不可用。");
            return;
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        parseInto(yaml.getConfigurationSection("upgrades"), upgrades, true);
        parseInto(yaml.getConfigurationSection("redeems"), redeems, false);
        plugin.getLogger().info("已加载追赶系统：" + upgrades.size() + " 升级 / " + redeems.size() + " 兑换。");
    }

    private void parseInto(ConfigurationSection root, Map<String, ProductDef> target, boolean isUpgrade) {
        if (root == null) {
            return;
        }
        for (String id : root.getKeys(false)) {
            ConfigurationSection s = root.getConfigurationSection(id);
            if (s == null) {
                continue;
            }
            Material material = Material.matchMaterial(s.getString("item", "PAPER").toUpperCase(Locale.ROOT));
            Map<String, Double> attributes = new LinkedHashMap<>();
            ConfigurationSection attrs = s.getConfigurationSection("attributes");
            if (attrs != null) {
                for (String key : attrs.getKeys(false)) {
                    attributes.put(key.toLowerCase(Locale.ROOT), attrs.getDouble(key));
                }
            }
            Map<String, Integer> costs = new LinkedHashMap<>();
            ConfigurationSection costSec = s.getConfigurationSection("cost-materials");
            if (costSec != null) {
                for (String key : costSec.getKeys(false)) {
                    costs.put(key.toLowerCase(Locale.ROOT), Math.max(1, costSec.getInt(key)));
                }
            }
            ProductDef def = new ProductDef(
                    id.toLowerCase(Locale.ROOT),
                    s.getString("display", id),
                    material == null ? Material.PAPER : material,
                    s.getString("color", "&f"),
                    new ArrayList<>(s.getStringList("lore")),
                    attributes,
                    isUpgrade ? s.getString("from-item", "") : "",
                    isUpgrade && s.getBoolean("require-crafted", false),
                    costs,
                    Math.max(0.0D, s.getDouble("fee", 0.0D)),
                    Math.max(0, s.getInt("token-cost", 0)));
            target.put(def.id(), def);
        }
    }

    public Map<String, ProductDef> upgrades() {
        return upgrades;
    }

    public Map<String, ProductDef> redeems() {
        return redeems;
    }

    // ---------------- 产物构建（继承旧词条） ----------------

    /**
     * 构建产物。{@code inherited} 非空时先复制其全部 attribute modifiers（词条继承），
     * 再叠加配置词条。PDC：sakurammo_crafted（来源 id）+ sakurammo_crafted_value（卖价折算，防刷金）。
     */
    public ItemStack build(ProductDef def, ItemStack inherited) {
        ItemStack item = new ItemStack(def.item());
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return item;
        }
        meta.displayName(io.sakurammo.menu.Items.component(def.color() + def.display()
                + (inherited == null ? " &8[兑换]" : " &8[升格]")));
        List<String> loreLines = new ArrayList<>(def.lore());
        // 继承旧物品词条（展示）
        if (inherited != null && inherited.hasItemMeta()
                && inherited.getItemMeta().getAttributeModifiers() != null) {
            inherited.getItemMeta().getAttributeModifiers().forEach((attr, mod) ->
                    loreLines.add("&7继承 &b" + Text.attrCN(attr.getKey().getKey()) + " "
                            + (mod.getAmount() >= 0 ? "+" : "") + Text.number(mod.getAmount())));
        }
        for (Map.Entry<String, Double> entry : def.attributes().entrySet()) {
            loreLines.add("&b" + entry.getKey() + (entry.getValue() >= 0 ? " +" : " ")
                    + Text.number(entry.getValue()));
        }
        meta.lore(loreLines.stream().map(io.sakurammo.menu.Items::component).toList());

        // 继承词条（原样复制旧 modifiers）
        if (inherited != null && inherited.hasItemMeta()
                && inherited.getItemMeta().getAttributeModifiers() != null) {
            inherited.getItemMeta().getAttributeModifiers().forEach(meta::addAttributeModifier);
        }
        // 新词条
        for (Map.Entry<String, Double> entry : def.attributes().entrySet()) {
            Attribute attr = attributeOf(entry.getKey());
            if (attr == null) {
                plugin.getLogger().warning("追赶产物 " + def.id() + " 的词条无效：" + entry.getKey());
                continue;
            }
            meta.addAttributeModifier(attr, new org.bukkit.attribute.AttributeModifier(
                    UUID.randomUUID(), "sakurammo_upgrade_" + entry.getKey(), entry.getValue(),
                    org.bukkit.attribute.AttributeModifier.Operation.ADD_NUMBER,
                    org.bukkit.inventory.EquipmentSlotGroup.ANY));
        }
        double value = def.fee() + def.tokenCost() * 50.0D;
        meta.getPersistentDataContainer().set(io.sakurammo.craft.CraftingManager.CRAFTED_KEY,
                PersistentDataType.STRING, "upgrade_" + def.id());
        meta.getPersistentDataContainer().set(io.sakurammo.craft.CraftingManager.CRAFTED_VALUE,
                PersistentDataType.DOUBLE, value);
        item.setItemMeta(meta);
        return item;
    }

    private Attribute attributeOf(String name) {
        return switch (name) {
            case "attack_damage" -> Attribute.ATTACK_DAMAGE;
            case "attack_speed" -> Attribute.ATTACK_SPEED;
            case "armor" -> Attribute.ARMOR;
            case "armor_toughness" -> Attribute.ARMOR_TOUGHNESS;
            case "max_health" -> Attribute.MAX_HEALTH;
            case "movement_speed" -> Attribute.MOVEMENT_SPEED;
            default -> null;
        };
    }

    // ---------------- 升级 ----------------

    /** 材料是否足够。 */
    private boolean hasMaterials(Player player, Map<String, Integer> costs) {
        for (Map.Entry<String, Integer> entry : costs.entrySet()) {
            if (plugin.crafting().countMaterial(player, entry.getKey()) < entry.getValue()) {
                return false;
            }
        }
        return true;
    }

    private boolean takeMaterials(Player player, Map<String, Integer> costs) {
        for (Map.Entry<String, Integer> entry : costs.entrySet()) {
            if (plugin.crafting().countMaterial(player, entry.getKey()) < entry.getValue()) {
                return false;
            }
        }
        for (Map.Entry<String, Integer> entry : costs.entrySet()) {
            int remaining = entry.getValue();
            ItemStack[] storage = player.getInventory().getStorageContents();
            for (int i = 0; i < storage.length && remaining > 0; i++) {
                ItemStack item = storage[i];
                if (item == null || io.sakurammo.craft.CraftingManager.materialIdOf(item) == null
                        || !entry.getKey().equalsIgnoreCase(
                        io.sakurammo.craft.CraftingManager.materialIdOf(item))) {
                    continue;
                }
                int take = Math.min(remaining, item.getAmount());
                remaining -= take;
                if (take >= item.getAmount()) {
                    player.getInventory().setItem(i, null);
                } else {
                    item.setAmount(item.getAmount() - take);
                }
            }
        }
        player.updateInventory();
        return true;
    }

    /**
     * 手持升级。校验：手持材质/来源要求 → 材料 → 代币 → 金币。
     * 成功：替换主手为成品（继承旧词条）。
     */
    public boolean upgradeHand(Player player, ProductDef def) {
        ItemStack hand = player.getInventory().getItemInMainHand();
        if (hand.getType() != def.item()) {
            Msg.send(player, "upgrade.wrong-hand", "item", def.item().name());
            return false;
        }
        if (def.requireCrafted() && (hand.getItemMeta() == null
                || !hand.getItemMeta().getPersistentDataContainer()
                .has(io.sakurammo.craft.CraftingManager.CRAFTED_KEY, PersistentDataType.STRING))) {
            Msg.send(player, "upgrade.need-crafted");
            return false;
        }
        if (!hasMaterials(player, def.costMaterials())) {
            Msg.send(player, "craft.no-materials");
            return false;
        }
        var profile = plugin.playerData().getOrLoad(player);
        if (def.tokenCost() > 0 && profile.tokens() < def.tokenCost()) {
            Msg.send(player, "upgrade.no-token", "cost", String.valueOf(def.tokenCost()),
                    "balance", String.valueOf(profile.tokens()));
            return false;
        }
        if (def.fee() > 0 && !plugin.economy().charge(player, def.fee(), "economy.fee-charged")) {
            return false;
        }
        if (!takeMaterials(player, def.costMaterials())) {
            return false;
        }
        if (def.tokenCost() > 0) {
            profile.tokens(profile.tokens() - def.tokenCost());
            plugin.playerData().save(profile);
        }
        ItemStack product = build(def, hand);
        player.getInventory().setItemInMainHand(product);
        Msg.send(player, "upgrade.done", "item", def.display());
        plugin.audit().log("upgrade", player.getName(), def.id());
        return true;
    }

    /** 代币兑换（防脸黑保底）。 */
    public boolean redeem(Player player, ProductDef def) {
        var profile = plugin.playerData().getOrLoad(player);
        if (def.tokenCost() > 0 && profile.tokens() < def.tokenCost()) {
            Msg.send(player, "upgrade.no-token", "cost", String.valueOf(def.tokenCost()),
                    "balance", String.valueOf(profile.tokens()));
            return false;
        }
        profile.tokens(profile.tokens() - def.tokenCost());
        plugin.playerData().save(profile);
        ItemStack product = build(def, null);
        player.getInventory().addItem(product).values()
                .forEach(left -> player.getWorld().dropItemNaturally(player.getLocation(), left));
        Msg.send(player, "redeem.done", "item", def.display(),
                "token", String.valueOf(def.tokenCost()),
                "balance", String.valueOf(profile.tokens()));
        plugin.audit().log("redeem", player.getName(),
                def.id() + " token=" + def.tokenCost());
        return true;
    }
}
