package io.sakurammo.hook;

import io.sakurammo.SakuraMMOPlugin;
import io.sakurammo.config.ClassDefinition;
import io.sakurammo.config.ResourceDefinition;
import io.sakurammo.player.PlayerProfile;
import io.sakurammo.util.Text;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;

import java.util.Locale;

/**
 * PlaceholderAPI 扩展：%sakurammo_xxx%
 * <p>
 * 只在检测到 PlaceholderAPI 后才实例化（类本身继承 PlaceholderExpansion）。
 */
public final class PlaceholderHook extends PlaceholderExpansion {

    private final SakuraMMOPlugin plugin;

    public PlaceholderHook(SakuraMMOPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public String getIdentifier() {
        return "sakurammo";
    }

    @Override
    public String getAuthor() {
        return "Sakura";
    }

    @Override
    public String getVersion() {
        try {
            return plugin.getPluginMeta().getVersion();
        } catch (Throwable t) {
            return "1.0.0";
        }
    }

    /** 必须为 true，否则 /papi reload 之后扩展会消失。 */
    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public boolean canRegister() {
        return true;
    }

    @Override
    public String onRequest(OfflinePlayer offlinePlayer, String params) {
        if (offlinePlayer == null) {
            return "";
        }
        PlayerProfile profile = plugin.playerData().get(offlinePlayer.getUniqueId());
        if (profile == null) {
            return "";
        }
        String key = params == null ? "" : params.toLowerCase(Locale.ROOT);
        ClassDefinition definition = profile.hasClass() ? plugin.config().classOf(profile.classId()) : null;

        switch (key) {
            case "class":
                return profile.classId() == null ? "" : profile.classId();
            case "class_display":
                return definition == null ? "" : definition.displayName();
            case "level":
                return String.valueOf(profile.level());
            case "experience":
                return String.valueOf(profile.experience());
            case "specialization":
                return profile.specialization() == null ? "" : profile.specialization();
            case "resource":
            case "resource_current": {
                ResourceDefinition primary = definition == null ? null : definition.primaryResource();
                return primary == null ? "" : Text.number(profile.resource(primary.id()));
            }
            case "resource_max": {
                ResourceDefinition primary = definition == null ? null : definition.primaryResource();
                return primary == null ? "" : Text.number(primary.max());
            }
            default:
                break;
        }

        if (key.startsWith("resource_")) {
            String rest = key.substring("resource_".length());
            boolean wantMax = rest.endsWith("_max");
            if (wantMax) {
                rest = rest.substring(0, rest.length() - "_max".length());
            }
            ResourceDefinition definition2 = definition == null ? null : definition.resource(rest);
            if (definition2 == null) {
                return "";
            }
            return Text.number(wantMax ? definition2.max() : profile.resource(definition2.id()));
        }
        return null;
    }
}
