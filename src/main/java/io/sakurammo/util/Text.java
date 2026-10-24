package io.sakurammo.util;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 文本工具：把 &amp; 颜色码与 &amp;#RRGGBB 十六进制色转成 Minecraft 的 § 形式；
 * 属性/词条 id 的中文映射（菜单与命令显示用）。
 */
public final class Text {

    private static final Pattern HEX = Pattern.compile("&#([A-Fa-f0-9]{6})");

    /** AuraSkills 属性 id → 中文名。 */
    private static final Map<String, String> STAT_CN = Map.ofEntries(
            Map.entry("strength", "力量"),
            Map.entry("health", "生命"),
            Map.entry("regeneration", "回复"),
            Map.entry("luck", "幸运"),
            Map.entry("wisdom", "智慧"),
            Map.entry("toughness", "韧性"),
            Map.entry("crit_chance", "暴击率"),
            Map.entry("crit_damage", "暴击伤害"),
            Map.entry("speed", "移速"));

    /** 原版属性 id → 中文名（装备词条显示用）。 */
    private static final Map<String, String> ATTR_CN = Map.ofEntries(
            Map.entry("attack_damage", "攻击伤害"),
            Map.entry("attack_speed", "攻击速度"),
            Map.entry("armor", "护甲"),
            Map.entry("armor_toughness", "护甲韧性"),
            Map.entry("max_health", "最大生命"),
            Map.entry("movement_speed", "移动速度"));

    private Text() {
    }

    public static String color(String input) {
        if (input == null || input.isEmpty()) {
            return "";
        }
        Matcher matcher = HEX.matcher(input);
        StringBuilder out = new StringBuilder(input.length() + 16);
        while (matcher.find()) {
            StringBuilder replacement = new StringBuilder("§x");
            for (char c : matcher.group(1).toCharArray()) {
                replacement.append('§').append(c);
            }
            matcher.appendReplacement(out, replacement.toString());
        }
        matcher.appendTail(out);
        return out.toString().replace('&', '§');
    }

    public static String stripColor(String input) {
        if (input == null) {
            return "";
        }
        return input.replaceAll("(?i)[&§][0-9A-FK-ORX]", "");
    }

    /** 把 1234.0 显示成 1234，1234.5 保留一位小数。 */
    public static String number(double value) {
        if (Math.abs(value - Math.rint(value)) < 1.0E-6) {
            return String.valueOf((long) Math.rint(value));
        }
        return String.format(java.util.Locale.ROOT, "%.1f", value);
    }

    /** 属性 id 中文名（未知原样返回）。 */
    public static String statCN(String stat) {
        if (stat == null) {
            return "";
        }
        return STAT_CN.getOrDefault(stat.toLowerCase(java.util.Locale.ROOT), stat);
    }

    /** 原版属性 id 中文名（未知原样返回，兼容 minecraft: / generic: 前缀）。 */
    public static String attrCN(String attr) {
        if (attr == null) {
            return "";
        }
        String key = attr.toLowerCase(java.util.Locale.ROOT);
        key = key.substring(key.lastIndexOf('.') + 1);
        return ATTR_CN.getOrDefault(key, key);
    }
}
