package io.sakurammo.loot;

import io.sakurammo.util.Text;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.List;

/**
 * RPGLoot 掉落物汉化（阶段四用户需求）。
 * <p>
 * RPGLoot 0.9.0 的稀有度/词条/套装文本为硬编码英文（无语言文件），
 * 这里在掉落生成时对物品 lore 逐行做词级映射替换：
 * 稀有度（Uncommon→罕见）、词条（Attack Damage→攻击伤害…）、
 * 套装结构词（Set→套装、pcs→件、+ Special→+ 特殊效果）。
 * 随机生成的英文物品名（如 Cursed Prong）为词库生成、无法穷举，保留原文。
 * 静态工具：掉落监听与 /sm admin translatex（手持修正）共用。
 */
public final class RpgLootTranslator {

    /** 有序映射：顺序敏感（"Attack Damage" 先于 "Attack Speed" 匹配）。 */
    private static final String[][] WORD_MAP = {
            {"Attack Damage", "攻击伤害"},
            {"Attack Speed", "攻击速度"},
            {"Crit Chance", "暴击率"},
            {"Crit Damage", "暴击伤害"},
            {"Movement Speed", "移动速度"},
            {"Life Steal", "生命偷取"},
            {"Max Health", "最大生命"},
            {"Health", "生命"},
            {"Armor", "护甲"},
            {"Thorns", "荆棘"},
            {"Luck", "幸运"},
            {"Common", "普通"},
            {"Uncommon", "罕见"},
            {"Rare", "稀有"},
            {"Epic", "史诗"},
            {"Legendary", "传说"},
            {"+ Special", "+ 特殊效果"},
            {"pcs:", "件："},
            {"Set", "套装"},
    };

    private RpgLootTranslator() {
    }

    /** 是否为疑似 RPGLoot 生成物（lore/名称含英文特征词）。 */
    public static boolean looksLikeRpgLoot(ItemStack item) {
        if (item == null || !item.hasItemMeta()) {
            return false;
        }
        ItemMeta meta = item.getItemMeta();
        StringBuilder all = new StringBuilder();
        if (meta.hasDisplayName()) {
            all.append(meta.getDisplayName());
        }
        if (meta.hasLore()) {
            for (String line : meta.getLore()) {
                all.append(line).append('\n');
            }
        }
        String text = all.toString();
        for (String[] pair : WORD_MAP) {
            if (text.contains(pair[0])) {
                return true;
            }
        }
        return false;
    }

    /**
     * 原位汉化物品的显示名与 lore（仅当含英文特征词时改动）。
     *
     * @return true = 物品被修改
     */
    public static boolean translate(ItemStack item) {
        if (item == null || !item.hasItemMeta()) {
            return false;
        }
        ItemMeta meta = item.getItemMeta();
        boolean changed = false;
        if (meta.hasDisplayName()) {
            String name = meta.getDisplayName();
            String translated = translateLine(name);
            if (!translated.equals(name)) {
                meta.setDisplayName(translated);
                changed = true;
            }
        }
        if (meta.hasLore()) {
            List<String> lore = meta.getLore();
            List<String> translatedLore = new java.util.ArrayList<>(lore.size());
            for (String line : lore) {
                translatedLore.add(translateLine(line));
            }
            if (!translatedLore.equals(lore)) {
                meta.setLore(translatedLore);
                changed = true;
            }
        }
        if (changed) {
            item.setItemMeta(meta);
        }
        return changed;
    }

    /** 单行词级替换。 */
    private static String translateLine(String line) {
        String colored = Text.color(line); // 统一色码后替换，避免 & 符号干扰词边界
        for (String[] pair : WORD_MAP) {
            colored = colored.replace(pair[0], pair[1]);
        }
        return colored;
    }
}
