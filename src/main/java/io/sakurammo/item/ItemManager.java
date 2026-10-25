package io.sakurammo.item;

import io.sakurammo.SakuraMMOPlugin;
import io.sakurammo.menu.Items;
import io.sakurammo.util.Text;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * 固定属性物品引擎（v4.2 模块 A）。
 * <p>
 * 与 RPGLoot 的分工：RPGLoot 负责**随机词条**掉落，这里负责**固定属性**的职业套装 / Boss 专属武器。
 * 物品身份只认 PDC（{@code sakurammo:item_id}），不认名字与 lore——铁砧改名刷不出假货。
 * <p>
 * 配置：{@code plugins/SakuraMMO/items/*.yml}（equipment.yml / materials.yml / sets.yml 均可，按文件名无意义，合并加载）
 */
public final class ItemManager {

    /** 物品 id（身份真源）。 */
    public static final NamespacedKey KEY_ID = new NamespacedKey("sakurammo", "item_id");
    /** 所属套装 id。 */
    public static final NamespacedKey KEY_SET = new NamespacedKey("sakurammo", "item_set");
    /** 阶位（1~3）。 */
    public static final NamespacedKey KEY_TIER = new NamespacedKey("sakurammo", "item_tier");
    /** 卖出价值（防刷金：按配置价值 × 回收率）。 */
    public static final NamespacedKey KEY_VALUE = new NamespacedKey("sakurammo", "item_value");

    private static final String SET_PREFIX = "set_";

    private final SakuraMMOPlugin plugin;
    private final Map<String, ItemDefinition> definitions = new LinkedHashMap<>();
    private final Map<String, SetBonus> sets = new LinkedHashMap<>();

    public ItemManager(SakuraMMOPlugin plugin) {
        this.plugin = plugin;
    }

    // ==================== 加载 ====================

    public void load() {
        definitions.clear();
        sets.clear();
        File dir = new File(plugin.getDataFolder(), "items");
        File[] files = dir.listFiles((d, name) -> name.toLowerCase(Locale.ROOT).endsWith(".yml"));
        if (files == null || files.length == 0) {
            plugin.getLogger().warning("items/ 下没有物品定义文件（items/*.yml）");
            return;
        }
        for (File file : files) {
            loadFile(file);
        }
        plugin.getLogger().info("已加载 " + definitions.size() + " 件固定属性物品 / " + sets.size() + " 个套装。");
    }

    private void loadFile(File file) {
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection items = cfg.getConfigurationSection("items");
        if (items != null) {
            for (String id : items.getKeys(false)) {
                ConfigurationSection sec = items.getConfigurationSection(id);
                if (sec == null) {
                    continue;
                }
                Material material = Material.matchMaterial(sec.getString("material", ""));
                if (material == null) {
                    plugin.getLogger().warning("物品 " + id + " 的材质无效：" + sec.getString("material"));
                    continue;
                }
                Map<String, Double> attributes = new LinkedHashMap<>();
                ConfigurationSection attrSec = sec.getConfigurationSection("attributes");
                if (attrSec != null) {
                    for (String key : attrSec.getKeys(false)) {
                        attributes.put(key.toLowerCase(Locale.ROOT), attrSec.getDouble(key));
                    }
                }
                List<ItemEffect> effects = new ArrayList<>();
                for (Map<?, ?> raw : sec.getMapList("effects")) {
                    ItemEffect.Type type = ItemEffect.Type.parse(String.valueOf(raw.get("type")));
                    if (type == null) {
                        plugin.getLogger().warning("物品 " + id + " 的效果类型无效：" + raw.get("type"));
                        continue;
                    }
                    effects.add(new ItemEffect(
                            type,
                            clamp01(num(raw.get("chance"), 0.05D)),
                            num(raw.get("value"), 1.0D),
                            Math.max(0.0D, num(raw.get("cooldown"), 3.0D))));
                }
                definitions.put(id.toLowerCase(Locale.ROOT), new ItemDefinition(
                        id.toLowerCase(Locale.ROOT),
                        sec.getString("display", id),
                        material,
                        ItemDefinition.Rarity.parse(sec.getString("rarity", "COMMON")),
                        Math.max(1, sec.getInt("tier", 1)),
                        blankToNull(sec.getString("set")),
                        list(sec.getStringList("lore")),
                        Map.copyOf(attributes),
                        List.copyOf(effects),
                        Math.max(0.0D, sec.getDouble("value", 0.0D))));
            }
        }

        ConfigurationSection setSec = cfg.getConfigurationSection("sets");
        if (setSec != null) {
            for (String setId : setSec.getKeys(false)) {
                ConfigurationSection s = setSec.getConfigurationSection(setId);
                if (s == null) {
                    continue;
                }
                sets.put(setId.toLowerCase(Locale.ROOT), new SetBonus(
                        setId.toLowerCase(Locale.ROOT),
                        s.getString("display", setId),
                        stats(s.getConfigurationSection("bonus-2")),
                        stats(s.getConfigurationSection("bonus-4"))));
            }
        }
    }

    private static List<String> list(List<String> raw) {
        return raw == null ? List.of() : List.copyOf(raw);
    }

    private static Map<String, Double> stats(ConfigurationSection sec) {
        if (sec == null) {
            return Map.of();
        }
        Map<String, Double> out = new LinkedHashMap<>();
        for (String key : sec.getKeys(false)) {
            out.put(key.toLowerCase(Locale.ROOT), sec.getDouble(key));
        }
        return Map.copyOf(out);
    }

    private static double num(Object raw, double fallback) {
        if (raw instanceof Number number) {
            return number.doubleValue();
        }
        try {
            return Double.parseDouble(String.valueOf(raw));
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static double clamp01(double value) {
        return Math.max(0.0D, Math.min(1.0D, value));
    }

    private static String blankToNull(String raw) {
        return raw == null || raw.isBlank() ? null : raw.toLowerCase(Locale.ROOT);
    }

    // ==================== 查询 ====================

    public Collection<ItemDefinition> all() {
        return List.copyOf(definitions.values());
    }

    public Optional<ItemDefinition> definition(String id) {
        return id == null ? Optional.empty() : Optional.ofNullable(definitions.get(id.toLowerCase(Locale.ROOT)));
    }

    public Collection<SetBonus> setBonuses() {
        return List.copyOf(sets.values());
    }

    public Optional<SetBonus> setBonus(String setId) {
        return setId == null ? Optional.empty() : Optional.ofNullable(sets.get(setId.toLowerCase(Locale.ROOT)));
    }

    /** 是否为本引擎生成的物品（只认 PDC）。 */
    public boolean isCustom(ItemStack item) {
        return idOf(item) != null;
    }

    /** 物品 id（非本引擎物品返回 null）。 */
    public String idOf(ItemStack item) {
        if (item == null || item.getType().isAir() || !item.hasItemMeta()) {
            return null;
        }
        return item.getItemMeta().getPersistentDataContainer().get(KEY_ID, PersistentDataType.STRING);
    }

    /** 卖出价值（非本引擎物品返回 0，调用方回落到材质基价）。 */
    public double valueOf(ItemStack item) {
        if (item == null || !item.hasItemMeta()) {
            return 0.0D;
        }
        Double value = item.getItemMeta().getPersistentDataContainer().get(KEY_VALUE, PersistentDataType.DOUBLE);
        return value == null ? 0.0D : value;
    }

    // ==================== 生成 ====================

    public ItemStack create(String id, int amount) {
        ItemDefinition def = definitions.get(id.toLowerCase(Locale.ROOT));
        if (def == null) {
            return null;
        }
        ItemStack item = new ItemStack(def.material(), Math.max(1, amount));
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return item;
        }

        meta.displayName(Items.component(def.rarity().color() + def.display()));

        List<String> lore = new ArrayList<>();
        lore.add("&8" + def.rarity().label() + " · T" + def.tier());
        if (def.setId() != null) {
            SetBonus bonus = sets.get(def.setId());
            if (bonus != null) {
                lore.add("&7套装：&f" + bonus.display());
            }
        }
        if (!def.lore().isEmpty()) {
            lore.add("");
            lore.addAll(def.lore());
        }
        if (!def.attributes().isEmpty()) {
            lore.add("");
            def.attributes().forEach((attr, value) ->
                    lore.add("&7" + Text.attrCN(attr) + "：&a+" + Text.number(value)));
        }
        if (!def.effects().isEmpty()) {
            lore.add("");
            for (ItemEffect effect : def.effects()) {
                lore.add("&d✦ " + effectLine(effect));
            }
        }
        meta.lore(lore.stream().map(Items::component).toList());

        // 原版属性词条：每件装备的 modifier 用独立 UUID，重复 UUID 会互相覆盖（任务书坑表）
        EquipmentSlotGroup group = slotGroupOf(def.material());
        for (Map.Entry<String, Double> entry : def.attributes().entrySet()) {
            Attribute attribute = attributeOf(entry.getKey());
            if (attribute == null) {
                plugin.getLogger().warning("物品 " + def.id() + " 的属性无效：" + entry.getKey());
                continue;
            }
            meta.addAttributeModifier(attribute, new AttributeModifier(
                    UUID.randomUUID(),
                    "sakurammo_item_" + def.id() + "_" + entry.getKey(),
                    entry.getValue(),
                    AttributeModifier.Operation.ADD_NUMBER,
                    group));
        }

        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        pdc.set(KEY_ID, PersistentDataType.STRING, def.id());
        pdc.set(KEY_TIER, PersistentDataType.INTEGER, def.tier());
        pdc.set(KEY_VALUE, PersistentDataType.DOUBLE, def.value());
        if (def.setId() != null) {
            pdc.set(KEY_SET, PersistentDataType.STRING, def.setId());
        }
        item.setItemMeta(meta);
        return item;
    }

    private String effectLine(ItemEffect effect) {
        String trigger = "命中时 " + Math.round(effect.chance() * 100) + "%";
        String body = switch (effect.type()) {
            case LIGHTNING -> "召来落雷，额外 " + Text.number(effect.value()) + " 点伤害";
            case POISON -> "使目标中毒（" + Text.number(effect.value()) + " 级强度）";
            case LIFESTEAL -> "吸取 " + Math.round(effect.value() * 100) + "% 伤害回复自身";
            case SLOW -> "使目标缓慢（" + Text.number(effect.value()) + " 级）";
            case BURN -> "点燃目标 " + Text.number(effect.value()) + " 秒";
        };
        String cd = effect.cooldownSeconds() > 0
                ? "&8（内置冷却 " + Text.number(effect.cooldownSeconds()) + "s）" : "";
        return trigger + "：&f" + body + cd;
    }

    private static EquipmentSlotGroup slotGroupOf(Material material) {
        String name = material.name();
        if (name.endsWith("_HELMET") || name.equals("CARVED_PUMPKIN") || name.equals("PLAYER_HEAD")) {
            return EquipmentSlotGroup.HEAD;
        }
        if (name.endsWith("_CHESTPLATE") || name.equals("ELYTRA")) {
            return EquipmentSlotGroup.CHEST;
        }
        if (name.endsWith("_LEGGINGS")) {
            return EquipmentSlotGroup.LEGS;
        }
        if (name.endsWith("_BOOTS")) {
            return EquipmentSlotGroup.FEET;
        }
        return EquipmentSlotGroup.HAND;
    }

    private static Attribute attributeOf(String name) {
        return switch (name.toLowerCase(Locale.ROOT)) {
            case "attack_damage" -> Attribute.ATTACK_DAMAGE;
            case "attack_speed" -> Attribute.ATTACK_SPEED;
            case "armor" -> Attribute.ARMOR;
            case "armor_toughness" -> Attribute.ARMOR_TOUGHNESS;
            case "max_health" -> Attribute.MAX_HEALTH;
            case "movement_speed" -> Attribute.MOVEMENT_SPEED;
            case "knockback_resistance" -> Attribute.KNOCKBACK_RESISTANCE;
            case "attack_knockback" -> Attribute.ATTACK_KNOCKBACK;
            case "luck" -> Attribute.LUCK;
            default -> null;
        };
    }

    // ==================== 套装 ====================

    /**
     * 重算套装加成：统计已穿戴的套装件数，2 件 / 4 件分别挂 AuraSkills modifier。
     * 命名前缀 {@code set_}，与 class_ / talent_ / passive_ 互斥（聚合入口仍只有 AuraSkills）。
     */
    public void applySetBonuses(Player player) {
        if (plugin.aura() == null || !plugin.aura().available()) {
            return;
        }
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (ItemStack armor : player.getInventory().getArmorContents()) {
            if (armor == null || armor.getType().isAir() || !armor.hasItemMeta()) {
                continue;
            }
            String setId = armor.getItemMeta().getPersistentDataContainer()
                    .get(KEY_SET, PersistentDataType.STRING);
            if (setId != null) {
                counts.merge(setId.toLowerCase(Locale.ROOT), 1, Integer::sum);
            }
        }

        plugin.aura().removeModifiersByPrefix(player, SET_PREFIX);
        counts.forEach((setId, count) -> {
            SetBonus bonus = sets.get(setId);
            if (bonus == null) {
                return;
            }
            if (count >= 2) {
                bonus.bonus2().forEach((stat, value) ->
                        plugin.aura().applyNamedModifier(player, SET_PREFIX + setId + "_2_" + stat, stat, value));
            }
            if (count >= 4) {
                bonus.bonus4().forEach((stat, value) ->
                        plugin.aura().applyNamedModifier(player, SET_PREFIX + setId + "_4_" + stat, stat, value));
            }
        });
    }

    /** 装备栏（含手）的套装件数统计，供菜单显示。 */
    public int countSetPieces(Player player, String setId) {
        if (setId == null) {
            return 0;
        }
        int count = 0;
        for (ItemStack armor : player.getInventory().getArmorContents()) {
            if (armor == null || armor.getType().isAir() || !armor.hasItemMeta()) {
                continue;
            }
            String id = armor.getItemMeta().getPersistentDataContainer().get(KEY_SET, PersistentDataType.STRING);
            if (setId.equalsIgnoreCase(id)) {
                count++;
            }
        }
        return count;
    }

    /** 玩家全身已穿戴的套装 id（去重），登录时补挂用。 */
    public Set<String> equippedSets(Player player) {
        Set<String> out = new LinkedHashSet<>();
        for (ItemStack armor : player.getInventory().getArmorContents()) {
            if (armor == null || armor.getType().isAir() || !armor.hasItemMeta()) {
                continue;
            }
            String id = armor.getItemMeta().getPersistentDataContainer().get(KEY_SET, PersistentDataType.STRING);
            if (id != null) {
                out.add(id.toLowerCase(Locale.ROOT));
            }
        }
        return out;
    }

    /** 套装定义：2 件与 4 件的属性加成。 */
    public record SetBonus(String id, String display, Map<String, Double> bonus2, Map<String, Double> bonus4) {
    }

    /** 装备槽位（供监听器判断手持/护甲）。 */
    public static EquipmentSlot slotOf(ItemStack item) {
        Material material = item.getType();
        String name = material.name();
        if (name.endsWith("_HELMET")) {
            return EquipmentSlot.HEAD;
        }
        if (name.endsWith("_CHESTPLATE") || name.equals("ELYTRA")) {
            return EquipmentSlot.CHEST;
        }
        if (name.endsWith("_LEGGINGS")) {
            return EquipmentSlot.LEGS;
        }
        if (name.endsWith("_BOOTS")) {
            return EquipmentSlot.FEET;
        }
        return EquipmentSlot.HAND;
    }
}
