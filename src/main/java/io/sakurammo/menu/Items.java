package io.sakurammo.menu;

import io.sakurammo.util.Text;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;

/**
 * 菜单物品构建工具。
 */
public final class Items {

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacySection();

    private Items() {
    }

    /** 把 &amp; 颜色码转成 Adventure Component，并强制关闭斜体（原版物品名默认斜体）。 */
    public static Component component(String legacy) {
        return LEGACY.deserialize(Text.color(legacy == null ? "" : legacy))
                .decoration(TextDecoration.ITALIC, false);
    }

    public static ItemStack of(Material material, String name, List<String> lore) {
        ItemStack item = new ItemStack(material == null ? Material.PAPER : material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(component(name));
            if (lore != null && !lore.isEmpty()) {
                List<Component> lines = new ArrayList<>(lore.size());
                for (String line : lore) {
                    lines.add(component(line));
                }
                meta.lore(lines);
            }
            item.setItemMeta(meta);
        }
        return item;
    }

    public static Material materialOr(String raw, Material fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        Material material = Material.matchMaterial(raw.trim().toUpperCase(java.util.Locale.ROOT));
        if (material == null || !material.isItem()) {
            return fallback;
        }
        return material;
    }
}
