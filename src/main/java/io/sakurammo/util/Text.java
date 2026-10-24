package io.sakurammo.util;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 文本工具：把 &amp; 颜色码与 &amp;#RRGGBB 十六进制色转成 Minecraft 的 § 形式。
 */
public final class Text {

    private static final Pattern HEX = Pattern.compile("&#([A-Fa-f0-9]{6})");

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
}
