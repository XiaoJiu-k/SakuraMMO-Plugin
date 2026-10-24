package io.sakurammo.menu;

import io.sakurammo.SakuraMMOPlugin;
import io.sakurammo.player.PlayerProfile;
import io.sakurammo.skill.SkillService;
import io.sakurammo.util.Msg;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

/**
 * 二级按键选择菜单：为指定技能选择 Shift+1~4 槽位。
 * <pre>
 *  4  目标技能
 *  19-22  Shift+1 / Shift+2 / Shift+3 / Shift+4（点击即绑定并返回技能绑定菜单）
 *  40  返回（不绑定）
 * </pre>
 * skillId 为 null 时表示「管理模式」：点击按键槽位进入该槽位的技能选择
 * ——简化实现：null 时四键点击同样绑定（提示先去下方选技能），主要入口是技能侧。
 */
public final class KeySelectMenu extends Menu {

    private static final int[] KEY_SLOTS = {19, 20, 21, 22};

    private final String skillId;

    public KeySelectMenu(SakuraMMOPlugin plugin, Player player, String skillId) {
        super(plugin, player, 5, Msg.raw("menu.key-select-title"));
        this.skillId = skillId;
    }

    @Override
    protected void build() {
        if (skillId == null) {
            set(22, Items.of(Material.PAPER, Msg.raw("menu.key-select-no-skill"), List.of()));
            set(40, Items.of(Material.ARROW, Msg.raw("menu.back-to-skills"), List.of()),
                    p -> new SkillBindMenu(plugin, p).open());
            fill(fillerMaterial());
            return;
        }
        var definition = plugin.config().skill(skillId);
        String display = definition == null ? skillId : definition.displayName();
        Material material = definition == null || definition.icon() == null
                ? Material.BLAZE_POWDER : definition.icon();
        set(4, Items.of(material, display, List.of(Msg.raw("menu.select-key-line"))));

        PlayerProfile profile = plugin.playerData().getOrLoad(player);
        for (int i = 0; i < PlayerProfile.SKILL_KEYS.size(); i++) {
            String key = PlayerProfile.SKILL_KEYS.get(i);
            String current = profile.skillBind(key);
            List<String> lore = new ArrayList<>();
            if (current != null) {
                var bound = plugin.config().skill(current);
                lore.add(Msg.raw("menu.key-occupied", "skill",
                        bound == null ? current : bound.displayName()));
            } else {
                lore.add(Msg.raw("menu.key-free"));
            }
            final String k = key;
            set(KEY_SLOTS[i], Items.of(keyMaterial(key),
                            Msg.raw("menu.key-name", "key", key), lore),
                    p -> {
                        SkillService.Result result = plugin.skills().bind(p, skillId, k);
                        if (result.message() != null) {
                            p.sendMessage(io.sakurammo.util.Msg.prefix() + result.message());
                        }
                        // 绑定成功回到技能绑定菜单（可见标记立即更新）
                        new SkillBindMenu(plugin, p).open();
                    });
        }

        set(40, Items.of(Material.ARROW, Msg.raw("menu.back-to-skills"), List.of()),
                p -> new SkillBindMenu(plugin, p).open());
        fill(fillerMaterial());
    }

    private static Material keyMaterial(String key) {
        return switch (key) {
            case "1" -> Material.ORANGE_DYE;
            case "2" -> Material.LIME_DYE;
            case "3" -> Material.LIGHT_BLUE_DYE;
            case "4" -> Material.MAGENTA_DYE;
            default -> Material.GRAY_DYE;
        };
    }
}
