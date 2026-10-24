package io.sakurammo.hook;

import io.sakurammo.SakuraMMOPlugin;
import io.sakurammo.player.PlayerProfile;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.plugin.EventExecutor;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 按键桥接（InputEngine）事件接入。
 * <p>
 * 原版客户端的 V / R / T / G 不发任何数据包，服务端无法感知。InputEngine
 * （客户端模组 + 服务端插件）会把按键事件以 Bukkit {@code PlayerKeyPressEvent}
 * 的形式在本服触发。本类用 {@link EventExecutor} 反射注册监听，
 * 不引入编译期依赖，兼容两代 API 包名：
 * <ul>
 *   <li>{@code dev.darkblade.mod.input_engine.server.api.PlayerKeyPressEvent}（现行）</li>
 *   <li>{@code me.codedbyyou.os.keyinput.api.events.PlayerKeyPressEvent}（旧版）</li>
 * </ul>
 * 事件到达后按按键名分发：R/T/F/G 施放对应槽位技能，V 打开菜单。
 * 未安装模组时 available()=false，全部走原生触发（F 键、潜行+快捷栏、命令）。
 */
public final class KeyBridgeHook implements Listener {

    private static final List<String> EVENT_CLASSES = List.of(
            "dev.darkblade.mod.input_engine.server.api.PlayerKeyPressEvent",
            "me.codedbyyou.os.keyinput.api.events.PlayerKeyPressEvent");

    private final SakuraMMOPlugin plugin;
    private final boolean available;
    /** 同一玩家同一键 200ms 去重（防按住重复触发 / 与原生 F 路径交叠）。 */
    private final Map<String, Long> lastFire = new ConcurrentHashMap<>();

    public KeyBridgeHook(SakuraMMOPlugin plugin) {
        this.plugin = plugin;
        Plugin inputEngine = findInputEngine();
        boolean ok = false;
        if (inputEngine != null && inputEngine.isEnabled()) {
            for (String className : EVENT_CLASSES) {
                Class<?> clazz;
                try {
                    clazz = Class.forName(className, true, inputEngine.getClass().getClassLoader());
                } catch (Throwable t) {
                    continue;
                }
                if (!Event.class.isAssignableFrom(clazz)) {
                    continue;
                }
                registerListener(clazz.asSubclass(Event.class));
                ok = true;
                plugin.getLogger().info("按键桥接事件已接入：" + className);
                break;
            }
            if (!ok) {
                plugin.getLogger().warning("检测到 InputEngine，但 PlayerKeyPressEvent 不可加载，"
                        + "R/T/G/V 走原生降级触发（F 键 / 潜行+快捷栏）。");
            }
        }
        this.available = ok;
        plugin.getLogger().info(ok
                ? "按键桥接就绪：1/2/3/4/V 按键直发可用（Shift 组合无需桥接）。"
                : "未检测到按键桥接模组：技能用 Shift+1~4 触发，菜单用 F 键或 /sm menu。");
    }

    public boolean available() {
        return available;
    }

    private Plugin findInputEngine() {
        for (Plugin candidate : Bukkit.getPluginManager().getPlugins()) {
            String normalized = candidate.getName().toLowerCase(Locale.ROOT)
                    .replace("_", "").replace("-", "");
            if (normalized.equals("inputengine")) {
                return candidate;
            }
        }
        return null;
    }

    /**
     * 用 EventExecutor 注册监听：不依赖编译期事件类型，
     * 事件对象在本类内全部反射读取。
     */
    private void registerListener(Class<? extends Event> eventClass) {
        EventExecutor executor = (listener, event) -> handle(event);
        Bukkit.getPluginManager().registerEvent(eventClass, this, EventPriority.NORMAL, executor, plugin);
    }

    private void handle(Event event) {
        try {
            Object playerObj = invoke(event, "getPlayer");
            if (!(playerObj instanceof Player player)) {
                return;
            }
            // 只处理「按下」；旧 API 没有该方法时视为按下
            Object pressed = invoke(event, "isPressed");
            if (pressed instanceof Boolean value && !value) {
                return;
            }
            String key = readKey(event);
            if (key == null) {
                if (plugin.config().main().debug()) {
                    plugin.getLogger().info("[debug] 桥接事件无法解析按键：" + event);
                }
                return;
            }
            long now = System.currentTimeMillis();
            String dedupKey = player.getUniqueId() + ":" + key;
            Long last = lastFire.get(dedupKey);
            if (last != null && now - last < 200L) {
                return;
            }
            lastFire.put(dedupKey, now);

            switch (key) {
                case "V" -> {
                    if (plugin.config().main().menuEnabled()) {
                        plugin.menus().open(player);
                    }
                }
                case "1", "2", "3", "4" -> {
                    PlayerProfile profile = plugin.playerData().get(player.getUniqueId());
                    String skillId = profile == null ? null : profile.skillBind(key);
                    if (skillId != null) {
                        plugin.playerListener().castBound(player, skillId,
                                io.sakurammo.skill.SkillService.keyDisplay(key));
                    } else if (plugin.config().main().debug()) {
                        plugin.getLogger().info("[debug] 桥接按键 " + key + "：槽位未绑定");
                    }
                }
                default -> {
                    if (plugin.config().main().debug()) {
                        plugin.getLogger().info("[debug] 桥接按键 " + key + "：不是技能/菜单键，忽略");
                    }
                }
            }
        } catch (Throwable t) {
            plugin.getLogger().warning("InputEngine 事件处理异常：" + t);
        }
    }

    /** 反射读取按键名，兼容多代 API 的 getter 命名。 */
    private String readKey(Event event) {
        // 新 API：getAction()（枚举/对象）→ name()/getId()/toString()
        Object action = invoke(event, "getAction");
        if (action != null) {
            Object name = invoke(action, "name");
            if (name == null) {
                name = invoke(action, "getId");
            }
            if (name == null) {
                name = invoke(action, "getIdentifier");
            }
            if (name == null) {
                name = action.toString();
            }
            return normalizeKey(name.toString());
        }
        // 旧 API / 其他形态：直接挂事件上的 getter
        for (String getter : List.of("getKeyName", "getKey", "getInput", "getCharacter", "getName")) {
            Object value = invoke(event, getter);
            if (value != null) {
                return normalizeKey(value.toString());
            }
        }
        return null;
    }

    /**
     * 归一化按键名：取 . / : 的末段，去空白与非字母数字。
     * 兼容 "key.keyboard.r"、"R"、"Skill 1"、"SKILL_1" 等形态；
     * 末尾带数字的 SKILL/KEY/SLOT 名称按序号映射四槽。
     */
    static String normalizeKey(String raw) {
        if (raw == null) {
            return null;
        }
        String value = raw.trim();
        int dot = Math.max(value.lastIndexOf('.'), value.lastIndexOf(':'));
        if (dot >= 0 && dot < value.length() - 1) {
            value = value.substring(dot + 1);
        }
        value = value.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "");
        if (value.isEmpty()) {
            return null;
        }
        if (value.length() > 1
                && (value.startsWith("SKILL") || value.startsWith("KEY") || value.startsWith("SLOT"))) {
            char tail = value.charAt(value.length() - 1);
            if (tail >= '1' && tail <= '4') {
                int index = tail - '1';
                if (index < PlayerProfile.SKILL_KEYS.size()) {
                    return PlayerProfile.SKILL_KEYS.get(index);
                }
            }
        }
        return value;
    }

    private static Object invoke(Object target, String methodName) {
        if (target == null) {
            return null;
        }
        try {
            Method method = target.getClass().getMethod(methodName);
            method.setAccessible(true);
            return method.invoke(target);
        } catch (NoSuchMethodException ignored) {
            return null;
        } catch (Exception ex) {
            return null;
        }
    }
}
