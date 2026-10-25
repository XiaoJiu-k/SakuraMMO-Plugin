package io.sakurammo.menu;

import io.sakurammo.SakuraMMOPlugin;
import io.sakurammo.config.SkillDefinition;
import io.sakurammo.player.PlayerProfile;
import io.sakurammo.util.Msg;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 技能绑定菜单（/sm menu 或 /sm skills 打开）。
 * <pre>
 *  4   标题说明
 *  10-13  Shift+1 ~ Shift+4 四个按键槽（当前绑定）
 *  19-25  当前职业技能列表（点击 → 二级按键选择）
 *  31  返回角色面板   40  关闭
 * </pre>
 * 顶部键槽显示对应技能图标、名称与剩余冷却；下方技能若已绑定显示按键标记。
 */
public final class SkillBindMenu extends Menu {

    private static final int[] KEY_SLOTS = {10, 11, 12, 13};

    public SkillBindMenu(SakuraMMOPlugin plugin, Player player) {
        super(plugin, player, 5, Msg.raw("menu.skills-title"));
    }

    @Override
    protected void build() {
        PlayerProfile profile = plugin.playerData().getOrLoad(player);
        if (!profile.hasClass()) {
            set(22, Items.of(Material.BARRIER, Msg.raw("menu.skills-no-class"), null));
            fill(fillerMaterial());
            return;
        }
        io.sakurammo.config.ClassDefinition definition =
                plugin.config().classOf(profile.classId());
        if (definition == null) {
            fill(fillerMaterial());
            return;
        }

        // 顶部：Q/E/R/T 键槽
        for (int i = 0; i < PlayerProfile.SKILL_KEYS.size(); i++) {
            String key = PlayerProfile.SKILL_KEYS.get(i);
            String skillId = profile.skillBind(key);
            set(KEY_SLOTS[i], keyIcon(key, skillId),
                    p -> new KeySelectMenu(plugin, p, null).open()); // 点空槽去下面选
        }

        // 下方：职业技能列表（点击进二级按键选择）
        int slot = 19;
        for (String skillId : definition.skills()) {
            SkillDefinition skill = plugin.config().skill(skillId);
            if (skill == null || !skill.trigger().bindable()) {
                continue;
            }
            String boundKey = boundKeyOf(profile, skillId);
            set(slot, skillIcon(skill, boundKey),
                    p -> new KeySelectMenu(plugin, p, skillId).open());
            slot++;
            if (slot > 25) {
                break;
            }
        }

        set(31, Items.of(Material.ARROW, Msg.raw("menu.back-to-hub"), List.of()),
                p -> new MainMenu(plugin, p).open());
        set(40, Items.of(Material.BARRIER, Msg.raw("menu.button-close"), List.of()),
                p -> p.closeInventory());

        fill(fillerMaterial());
    }

    /** 键槽图标：已绑 → 技能图标 + 键名 + 技能名 + 冷却；未绑 → 空槽样式。 */
    private org.bukkit.inventory.ItemStack keyIcon(String key, String skillId) {
        String keyDisplay = io.sakurammo.skill.SkillService.keyDisplay(key);
        List<String> lore = new ArrayList<>();
        lore.add(Msg.raw("menu.key-slot-key", "key", keyDisplay));
        if (skillId == null) {
            lore.add(Msg.raw("menu.key-slot-empty"));
            lore.add(Msg.raw("menu.key-slot-hint"));
            return Items.of(Material.INK_SAC, Msg.raw("menu.key-slot-name", "key", keyDisplay), lore);
        }
        SkillDefinition skill = plugin.config().skill(skillId);
        String display = skill == null ? skillId : skill.displayName();
        long remain = plugin.skills().cooldownRemaining(player, skillId);
        lore.add(Msg.raw("menu.key-slot-bound", "skill", display));
        lore.add(remain <= 0 ? Msg.raw("skill.bind-ready")
                : Msg.raw("skill.bind-cooldown", "time",
                        String.format(Locale.ROOT, "%.1f", remain / 1000.0D)));
        Material material = skill == null || skill.icon() == null
                ? Material.BLAZE_POWDER : skill.icon();
        return Items.of(material, Msg.raw("menu.key-slot-name", "key", keyDisplay), lore);
    }

    /** 职业技能图标（含已绑定按键标记）。 */
    private org.bukkit.inventory.ItemStack skillIcon(SkillDefinition skill, String boundKey) {
        List<String> lore = new ArrayList<>();
        lore.add(Msg.raw("menu.skill-click-hint"));
        if (boundKey != null) {
            lore.add(Msg.raw("menu.skill-bound-key", "key", boundKey));
        }
        lore.add(Msg.raw("menu.skill-cooldown-line",
                "cooldown", plugin.skills().cooldownRemaining(player, skill.id()) <= 0
                        ? Msg.raw("skill.bind-ready")
                        : String.valueOf(plugin.skills().cooldownRemaining(player, skill.id()) / 1000) + "s"));
        Material material = skill.icon() == null ? Material.BLAZE_POWDER : skill.icon();
        String name = skill.displayName() + (boundKey == null ? "" : " " + boundKey);
        return Items.of(material, name, lore);
    }

    private static String boundKeyOf(PlayerProfile profile, String skillId) {
        for (String key : PlayerProfile.SKILL_KEYS) {
            if (skillId.equals(profile.skillBind(key))) {
                return key;
            }
        }
        return null;
    }
}
