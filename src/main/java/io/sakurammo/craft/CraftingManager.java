package io.sakurammo.craft;

import io.sakurammo.SakuraMMOPlugin;
import io.sakurammo.util.Text;
import io.sakurammo.util.Msg;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlotGroup;
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
 * 制造系统：底材（PDC 标记）、配方（recipes.yml）、成品生成（品质名 + 原版词条 + crafted 标记）、
 * 制造校验（材料 + 金币手续费）。
 * <p>
 * 防刷金：成品 PDC 记录配方价（底材市价折算 + 手续费），卖出只回收 25%；
 * 底材本身卖价为 0（不可回收换钱），制造-卖出循环必然亏损。
 */
public final class CraftingManager {

    /** 成品标记：值 = 配方 id。 */
    public static final NamespacedKey CRAFTED_KEY = NamespacedKey.minecraft("sakurammo_crafted");
    /** 成品价值标记（防刷金：卖出按此价 × 回收率）。 */
    public static final NamespacedKey CRAFTED_VALUE = NamespacedKey.minecraft("sakurammo_crafted_value");
    /** 底材标记：值 = 底材 id。 */
    public static final NamespacedKey MATERIAL_KEY = NamespacedKey.minecraft("sakurammo_material");

    private final SakuraMMOPlugin plugin;
    /** 底材 id → 定义。 */
    private final Map<String, MaterialDef> materials = new LinkedHashMap<>();
    /** 配方 id → 定义。 */
    private final Map<String, RecipeDef> recipes = new LinkedHashMap<>();

    public record MaterialDef(String id, String name, Material item, String color) {
    }

    /** cost-materials 的有序条目。 */
    public record CostEntry(String materialId, int amount) {
    }

    /**
     * @param outputItem v4.2：产物是固定属性物品 id（非空时忽略 item/attributes）
     * @param itemCosts  v4.2：以固定属性物品为材料（itemId → 数量）
     */
    public record RecipeDef(String id, String display, Material item, String color,
                            List<String> lore, Map<String, Double> attributes,
                            List<CostEntry> costs, double fee,
                            String outputItem, List<CostEntry> itemCosts) {
    }

    public CraftingManager(SakuraMMOPlugin plugin) {
        this.plugin = plugin;
        load();
    }

    public void load() {
        materials.clear();
        recipes.clear();
        File file = new File(plugin.getDataFolder(), "recipes.yml");
        if (!file.isFile()) {
            plugin.getLogger().warning("recipes.yml 不存在，制造系统不可用。");
            return;
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection mats = yaml.getConfigurationSection("materials");
        if (mats != null) {
            for (String id : mats.getKeys(false)) {
                ConfigurationSection sec = mats.getConfigurationSection(id);
                if (sec == null) {
                    continue;
                }
                Material material = Material.matchMaterial(sec.getString("item", "PAPER").toUpperCase(Locale.ROOT));
                materials.put(id.toLowerCase(Locale.ROOT), new MaterialDef(
                        id.toLowerCase(Locale.ROOT),
                        sec.getString("name", id),
                        material == null ? Material.PAPER : material,
                        sec.getString("color", "&f")));
            }
        }
        ConfigurationSection recipesSec = yaml.getConfigurationSection("recipes");
        if (recipesSec != null) {
            for (String id : recipesSec.getKeys(false)) {
                ConfigurationSection sec = recipesSec.getConfigurationSection(id);
                if (sec == null) {
                    continue;
                }
                Material material = Material.matchMaterial(sec.getString("item", "PAPER").toUpperCase(Locale.ROOT));
                Map<String, Double> attributes = new LinkedHashMap<>();
                ConfigurationSection attrs = sec.getConfigurationSection("attributes");
                if (attrs != null) {
                    for (String key : attrs.getKeys(false)) {
                        attributes.put(key.toLowerCase(Locale.ROOT), attrs.getDouble(key));
                    }
                }
                List<CostEntry> costs = new ArrayList<>();
                ConfigurationSection costSec = sec.getConfigurationSection("cost-materials");
                if (costSec != null) {
                    for (String key : costSec.getKeys(false)) {
                        costs.add(new CostEntry(key.toLowerCase(Locale.ROOT), Math.max(1, costSec.getInt(key))));
                    }
                }
                List<CostEntry> itemCosts = new ArrayList<>();
                ConfigurationSection itemCostSec = sec.getConfigurationSection("cost-items");
                if (itemCostSec != null) {
                    for (String key : itemCostSec.getKeys(false)) {
                        itemCosts.add(new CostEntry(key.toLowerCase(Locale.ROOT), Math.max(1, itemCostSec.getInt(key))));
                    }
                }
                List<String> lore = new ArrayList<>(sec.getStringList("lore"));
                recipes.put(id.toLowerCase(Locale.ROOT), new RecipeDef(
                        id.toLowerCase(Locale.ROOT),
                        sec.getString("display", id),
                        material == null ? Material.PAPER : material,
                        sec.getString("color", "&f"),
                        lore, attributes, costs,
                        Math.max(0.0D, sec.getDouble("fee", 0.0D)),
                        sec.getString("output-item"),
                        List.copyOf(itemCosts)));
            }
        }
        plugin.getLogger().info("已加载制造系统：" + materials.size() + " 底材 / " + recipes.size() + " 配方。");
    }

    public Map<String, RecipeDef> recipes() {
        return recipes;
    }

    public MaterialDef material(String id) {
        return id == null ? null : materials.get(id.toLowerCase(Locale.ROOT));
    }

    // ---------------- 底材物品 ----------------

    /** 生成 N 个带 PDC 标记的底材。 */
    public ItemStack createMaterial(String id, int amount) {
        MaterialDef def = material(id);
        if (def == null) {
            return null;
        }
        ItemStack item = new ItemStack(def.item(), Math.max(1, amount));
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(io.sakurammo.menu.Items.component(def.color() + def.name()));
            List<String> lore = new ArrayList<>();
            lore.add("&8底材 · 用于制造");
            meta.lore(lore.stream().map(io.sakurammo.menu.Items::component).toList());
            meta.getPersistentDataContainer().set(MATERIAL_KEY, PersistentDataType.STRING, def.id());
            item.setItemMeta(meta);
        }
        return item;
    }

    /** 物品是否为指定底材。 */
    public static boolean isMaterial(ItemStack item, String materialId) {
        if (item == null || !item.hasItemMeta() || materialId == null) {
            return false;
        }
        String value = item.getItemMeta().getPersistentDataContainer()
                .get(MATERIAL_KEY, PersistentDataType.STRING);
        return materialId.equalsIgnoreCase(value);
    }

    /** 物品里的底材 id（非底材返回 null）。 */
    public static String materialIdOf(ItemStack item) {
        if (item == null || !item.hasItemMeta()) {
            return null;
        }
        return item.getItemMeta().getPersistentDataContainer()
                .get(MATERIAL_KEY, PersistentDataType.STRING);
    }

    // ---------------- 库存清点 / 扣除 ----------------

    /** 玩家背包里某底材的数量。 */
    public int countMaterial(Player player, String materialId) {
        int count = 0;
        for (ItemStack item : player.getInventory().getStorageContents()) {
            if (item != null && isMaterial(item, materialId)) {
                count += item.getAmount();
            }
        }
        return count;
    }

    /** v4.2：按「固定属性物品 id」统计背包数量（PDC 判定，不认名字）。 */
    public int countItem(Player player, String itemId) {
        int count = 0;
        for (ItemStack item : player.getInventory().getStorageContents()) {
            if (item != null && itemId.equalsIgnoreCase(plugin.items().idOf(item))) {
                count += item.getAmount();
            }
        }
        return count;
    }

    private boolean takeItems(Player player, List<CostEntry> costs) {
        for (CostEntry entry : costs) {
            if (countItem(player, entry.materialId()) < entry.amount()) {
                return false;
            }
        }
        for (CostEntry entry : costs) {
            int remaining = entry.amount();
            ItemStack[] storage = player.getInventory().getStorageContents();
            for (int i = 0; i < storage.length && remaining > 0; i++) {
                ItemStack item = storage[i];
                if (item == null || !entry.materialId().equalsIgnoreCase(plugin.items().idOf(item))) {
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

    private boolean takeMaterials(Player player, List<CostEntry> costs) {
        // 先校验后扣除（两阶段，防半扣）
        for (CostEntry entry : costs) {
            if (countMaterial(player, entry.materialId()) < entry.amount()) {
                return false;
            }
        }
        for (CostEntry entry : costs) {
            int remaining = entry.amount();
            ItemStack[] storage = player.getInventory().getStorageContents();
            for (int i = 0; i < storage.length && remaining > 0; i++) {
                ItemStack item = storage[i];
                if (item == null || !isMaterial(item, entry.materialId())) {
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

    // ---------------- 制造 ----------------

    /** 配方的总标价（手续费 + 底材折价，底材按 25 金币/件折算计入成品价值）。 */
    public double recipeValue(RecipeDef recipe) {
        double materialsWorth = 0.0D;
        for (CostEntry entry : recipe.costs()) {
            materialsWorth += entry.amount() * 25.0D;
        }
        if (plugin.items() != null) {
            for (CostEntry entry : recipe.itemCosts()) {
                double unit = plugin.items().definition(entry.materialId())
                        .map(io.sakurammo.item.ItemDefinition::value).orElse(25.0D);
                materialsWorth += entry.amount() * Math.max(25.0D, unit * 0.25D);
            }
        }
        return recipe.fee() + materialsWorth;
    }

    /** 底材与费用是否齐全。 */
    public boolean canCraft(Player player, RecipeDef recipe) {
        for (CostEntry entry : recipe.costs()) {
            if (countMaterial(player, entry.materialId()) < entry.amount()) {
                return false;
            }
        }
        for (CostEntry entry : recipe.itemCosts()) {
            if (countItem(player, entry.materialId()) < entry.amount()) {
                return false;
            }
        }
        return recipe.fee() <= 0.0D || plugin.economy().balance(player.getUniqueId()) >= recipe.fee();
    }

    /**
     * 执行制造：扣底材 → 扣手续费 → 发成品（背包满掉脚下）。
     * 返回 null = 失败（消息已发）；非 null = 成品。
     */
    public ItemStack craft(Player player, RecipeDef recipe) {
        if (!takeMaterials(player, recipe.costs())) {
            Msg.send(player, "craft.no-materials");
            return null;
        }
        if (!takeItems(player, recipe.itemCosts())) {
            // 底材已扣但物品材料不足：退回底材，避免半扣
            for (CostEntry entry : recipe.costs()) {
                ItemStack refund = createMaterial(entry.materialId(), entry.amount());
                if (refund != null) {
                    player.getInventory().addItem(refund);
                }
            }
            Msg.send(player, "craft.no-materials");
            return null;
        }
        if (recipe.fee() > 0.0D
                && !plugin.economy().charge(player, recipe.fee(), "craft.fee-charged")) {
            // 扣费失败：退还底材
            for (CostEntry entry : recipe.costs()) {
                ItemStack refund = createMaterial(entry.materialId(), entry.amount());
                if (refund != null) {
                    player.getInventory().addItem(refund);
                }
            }
            return null;
        }
        ItemStack product = createProduct(recipe);
        player.getInventory().addItem(product).values()
                .forEach(left -> player.getWorld().dropItemNaturally(player.getLocation(), left));
        Msg.send(player, "craft.done", "item", recipe.display());
        plugin.weeklies().progress(player, "craft_item", 1);
        return product;
    }

    /** 成品：品质色名称 + lore + 原版 attribute modifiers + PDC（crafted 标记与价值）。 */
    public ItemStack createProduct(RecipeDef recipe) {
        if (recipe.outputItem() != null && plugin.items() != null) {
            ItemStack product = plugin.items().create(recipe.outputItem(), 1);
            if (product != null) {
                ItemMeta productMeta = product.getItemMeta();
                if (productMeta != null) {
                    productMeta.getPersistentDataContainer().set(CRAFTED_KEY,
                            PersistentDataType.STRING, recipe.id());
                    productMeta.getPersistentDataContainer().set(CRAFTED_VALUE,
                            PersistentDataType.DOUBLE, recipeValue(recipe));
                    product.setItemMeta(productMeta);
                }
                return product;
            }
            plugin.getLogger().warning("配方 " + recipe.id() + " 的产物物品 id 不存在：" + recipe.outputItem());
        }
        ItemStack item = new ItemStack(recipe.item());
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(io.sakurammo.menu.Items.component(
                    recipe.color() + recipe.display() + " &8[制造]"));
            List<String> loreLines = new ArrayList<>(recipe.lore());
            for (Map.Entry<String, Double> entry : recipe.attributes().entrySet()) {
                loreLines.add(attrLine(entry.getKey(), entry.getValue()));
            }
            loreLines.add("&8制作：" + recipe.display());
            meta.lore(loreLines.stream().map(io.sakurammo.menu.Items::component).toList());
            // 原版属性词条（1.21.4：AttributeModifier + EquipmentSlotGroup）
            for (Map.Entry<String, Double> entry : recipe.attributes().entrySet()) {
                Attribute attribute = attributeOf(entry.getKey());
                if (attribute == null) {
                    plugin.getLogger().warning("配方 " + recipe.id() + " 的词条属性无效：" + entry.getKey());
                    continue;
                }
                AttributeModifier modifier = new AttributeModifier(
                        UUID.randomUUID(),
                        "sakurammo_craft_" + entry.getKey(),
                        entry.getValue(),
                        AttributeModifier.Operation.ADD_NUMBER,
                        org.bukkit.inventory.EquipmentSlotGroup.ANY);
                meta.addAttributeModifier(attribute, modifier);
            }
            meta.getPersistentDataContainer().set(CRAFTED_KEY, PersistentDataType.STRING, recipe.id());
            meta.getPersistentDataContainer().set(CRAFTED_VALUE, PersistentDataType.DOUBLE, recipeValue(recipe));
            item.setItemMeta(meta);
        }
        return item;
    }

    private String attrLine(String attribute, double value) {
        String label = switch (attribute) {
            case "attack_damage" -> "攻击伤害";
            case "attack_speed" -> "攻击速度";
            case "armor" -> "护甲";
            case "armor_toughness" -> "护甲韧性";
            case "max_health" -> "最大生命";
            case "movement_speed" -> "移动速度";
            default -> attribute;
        };
        return "&b" + label + (value >= 0 ? " +" : " ") + Text.number(value);
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

    // ---------------- 卖出（/sm sell，防刷金口径） ----------------

    /**
     * 卖出主手物品。定价：
     * 底材 = 0（不可回收）；成品 = PDC 价值 × sell-refund-rate（默认 25%）；
     * 其他物品 = 按材质基础价表（普通原版装备极低）。
     */
    public double sellPrice(ItemStack item) {
        if (item == null || item.getType().isAir() || !item.getType().isItem()) {
            return 0.0D;
        }
        if (item.hasItemMeta()) {
            var pdc = item.getItemMeta().getPersistentDataContainer();
            if (pdc.has(MATERIAL_KEY, PersistentDataType.STRING)) {
                return 0.0D; // 底材不可卖
            }
            Double value = pdc.get(CRAFTED_VALUE, PersistentDataType.DOUBLE);
            if (value != null) {
                return Math.max(0.0D, value * plugin.economy().sellRefundRate());
            }
            // v4.2 固定属性物品：有 value 才可卖（材料 value=0 → 拒绝，防刷金）
            if (plugin.items() != null && plugin.items().isCustom(item)) {
                double itemValue = plugin.items().valueOf(item);
                return itemValue <= 0.0D ? 0.0D : itemValue * plugin.economy().sellRefundRate();
            }
        }
        return basePrice(item.getType());
    }

    private double basePrice(Material material) {
        String name = material.name();
        if (name.contains("NETHERITE")) return 40.0D;
        if (name.contains("DIAMOND")) return 25.0D;
        if (name.contains("GOLD")) return 12.0D;
        if (name.contains("IRON") || name.contains("CHAINMAIL")) return 6.0D;
        if (name.contains("STONE")) return 2.0D;
        if (name.contains("LEATHER")) return 1.0D;
        return 0.5D;
    }

    /** 卖出主手全部同类物品，返回获得金额（0 = 不可卖）。 */
    public double sellHand(Player player) {
        ItemStack hand = player.getInventory().getItemInMainHand();
        double unit = sellPrice(hand);
        if (unit <= 0.0D || hand.getAmount() <= 0) {
            return -1.0D;
        }
        double total = unit * hand.getAmount();
        player.getInventory().setItemInMainHand(null);
        plugin.economy().deposit(player.getUniqueId(), total);
        return total;
    }
}
