package io.sakurammo.hook;

import io.sakurammo.SakuraMMOPlugin;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;

/**
 * AuraSkills 属性修饰符挂钩。
 * <p>
 * 为什么用反射而不是编译期依赖：
 * <ul>
 *   <li>AuraSkills 的 API 包打在插件自己的 jar 里，不发布到公共 Maven 仓库；</li>
 *   <li>插件要求 softdepend，AuraSkills 缺失时必须能启动，编译期依赖会让类加载直接失败；</li>
 *   <li>反射走的是 AuraSkills 自己的类加载器，绕开 Paper 的插件类隔离差异。</li>
 * </ul>
 * 反射失败时降级为「以玩家身份执行 /skills modifier 命令」。
 * <p>
 * 注意：AuraSkills 的修饰符以名字为键（Map&lt;String, StatModifier&gt;），一个名字只能挂一项属性，
 * 所以实际命名是 {@code class_<职业id>_<stat>}，而不是任务书里写的 {@code class_<id>}
 * （同名会被拒绝，见 tests/测试记录.md S-007）。移除时按前缀批量清。
 */
public final class AuraSkillsHook {

    private static final String CLS_API = "dev.aurelium.auraskills.api.AuraSkillsApi";
    private static final String CLS_USER = "dev.aurelium.auraskills.api.user.SkillsUser";
    private static final String CLS_STAT = "dev.aurelium.auraskills.api.stat.Stat";
    private static final String CLS_STATS = "dev.aurelium.auraskills.api.stat.Stats";
    private static final String CLS_MOD = "dev.aurelium.auraskills.api.stat.StatModifier";

    public static final String MODIFIER_PREFIX = "class_";

    private final SakuraMMOPlugin plugin;
    private final Plugin auraSkills;
    private final ClassLoader loader;

    private boolean ready;
    private Object api;
    private Method mGetUser;
    private Method mAddModifier;
    private Method mRemoveModifier;
    private Method mGetModifiers;
    private Method mGetStatLevel;
    private Constructor<?> ctorModifier;
    private final Map<String, Object> statCache = new LinkedHashMap<>();

    public AuraSkillsHook(SakuraMMOPlugin plugin) {
        this.plugin = plugin;
        this.auraSkills = Bukkit.getPluginManager().getPlugin("AuraSkills");
        this.loader = auraSkills == null ? null : auraSkills.getClass().getClassLoader();
        this.ready = auraSkills != null && auraSkills.isEnabled() && setup();
        if (auraSkills == null) {
            plugin.getLogger().warning("未检测到 AuraSkills：职业属性加成不可用（已降级）。");
        } else if (!ready) {
            plugin.getLogger().warning("AuraSkills 反射挂钩失败，改用命令通道（/skills modifier）。");
        }
    }

    private boolean setup() {
        try {
            Class<?> apiClass = Class.forName(CLS_API, true, loader);
            api = apiClass.getMethod("get").invoke(null);
            if (api == null) {
                return false;
            }
            Class<?> userClass = Class.forName(CLS_USER, true, loader);
            Class<?> statClass = Class.forName(CLS_STAT, true, loader);
            Class<?> statsEnum = Class.forName(CLS_STATS, true, loader);
            Class<?> modClass = Class.forName(CLS_MOD, true, loader);

            mGetUser = apiClass.getMethod("getUser", UUID.class);
            mAddModifier = userClass.getMethod("addStatModifier", modClass);
            mRemoveModifier = userClass.getMethod("removeStatModifier", String.class);
            mGetModifiers = userClass.getMethod("getStatModifiers");
            mGetStatLevel = userClass.getMethod("getStatLevel", statClass);
            ctorModifier = modClass.getConstructor(String.class, statClass, double.class);

            for (String name : io.sakurammo.config.ConfigManager.VALID_STATS) {
                Object constant = enumConstant(statsEnum, name);
                if (constant != null) {
                    statCache.put(name, constant);
                }
            }
            if (statCache.isEmpty()) {
                return false;
            }
            plugin.getLogger().info("AuraSkills 挂钩就绪（API 反射），可用属性 " + statCache.keySet() + "。");
            return true;
        } catch (Throwable t) {
            plugin.getLogger().log(Level.WARNING, "AuraSkills 反射初始化失败", t);
            return false;
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object enumConstant(Class<?> enumClass, String name) {
        try {
            return Enum.valueOf((Class<? extends Enum>) enumClass.asSubclass(Enum.class),
                    name.toUpperCase(Locale.ROOT));
        } catch (Exception ex) {
            return null;
        }
    }

    public boolean available() {
        return ready;
    }

    public boolean pluginPresent() {
        return auraSkills != null;
    }

    /** AuraSkills 是否已经把这个玩家加载进自己的用户表（登录补发要等它就绪）。 */
    public boolean isUserLoaded(Player player) {
        return ready && user(player) != null;
    }

    /**
     * 先清掉该玩家身上所有 class_ 前缀的修饰符，再按当前职业重新挂上。
     * 这样无论 AuraSkills 是否持久化修饰符，结果都是确定的。
     *
     * @return 成功应用的属性条数；-1 表示失败（调用方应走命令兜底）
     */
    public int applyModifiers(Player player, String classId, Map<String, Double> stats) {
        if (!ready) {
            return -1;
        }
        try {
            Object user = user(player);
            if (user == null) {
                return -1;
            }
            removeByPrefix(user, MODIFIER_PREFIX);
            int applied = 0;
            for (Map.Entry<String, Double> entry : stats.entrySet()) {
                Object stat = statCache.get(entry.getKey());
                if (stat == null) {
                    plugin.getLogger().warning("跳过未知属性：" + entry.getKey());
                    continue;
                }
                String name = modifierName(classId, entry.getKey());
                Object modifier = ctorModifier.newInstance(name, stat, entry.getValue());
                mAddModifier.invoke(user, modifier);
                applied++;
            }
            return applied;
        } catch (Throwable t) {
            plugin.getLogger().log(Level.WARNING, "应用 AuraSkills 修饰符失败", t);
            return -1;
        }
    }

    /** 清掉所有 class_ 前缀的修饰符，返回移除条数。 */
    public int removeAllClassModifiers(Player player) {
        if (!ready) {
            return -1;
        }
        try {
            Object user = user(player);
            return user == null ? -1 : removeByPrefix(user, MODIFIER_PREFIX);
        } catch (Throwable t) {
            plugin.getLogger().log(Level.WARNING, "移除 AuraSkills 修饰符失败", t);
            return -1;
        }
    }

    public Set<String> modifierNames(Player player) {
        if (!ready) {
            return Collections.emptySet();
        }
        try {
            Object user = user(player);
            if (user == null) {
                return Collections.emptySet();
            }
            Map<?, ?> modifiers = (Map<?, ?>) mGetModifiers.invoke(user);
            if (modifiers == null) {
                return Collections.emptySet();
            }
            Set<String> names = new LinkedHashSet<>();
            for (Object key : modifiers.keySet()) {
                names.add(String.valueOf(key));
            }
            return names;
        } catch (Throwable t) {
            return Collections.emptySet();
        }
    }

    public double statLevel(Player player, String stat) {
        if (!ready) {
            return -1.0D;
        }
        try {
            Object user = user(player);
            Object constant = statCache.get(stat.toLowerCase(Locale.ROOT));
            if (user == null || constant == null) {
                return -1.0D;
            }
            Object value = mGetStatLevel.invoke(user, constant);
            return value instanceof Number number ? number.doubleValue() : -1.0D;
        } catch (Throwable t) {
            return -1.0D;
        }
    }

    private Object user(Player player) {
        try {
            return mGetUser.invoke(api, player.getUniqueId());
        } catch (Throwable t) {
            return null;
        }
    }

    private int removeByPrefix(Object user, String prefix) throws Exception {
        Map<?, ?> modifiers = (Map<?, ?>) mGetModifiers.invoke(user);
        if (modifiers == null || modifiers.isEmpty()) {
            return 0;
        }
        List<String> targets = new ArrayList<>();
        for (Object key : modifiers.keySet()) {
            String name = String.valueOf(key);
            if (name.startsWith(prefix)) {
                targets.add(name);
            }
        }
        for (String name : targets) {
            mRemoveModifier.invoke(user, name);
        }
        return targets.size();
    }

    // ---------------- 命令兜底（反射不可用时） ----------------

    public void applyViaCommand(Player player, String classId, Map<String, Double> stats) {
        for (Map.Entry<String, Double> entry : stats.entrySet()) {
            player.performCommand("skills modifier add " + player.getName() + " " + entry.getKey()
                    + " " + modifierName(classId, entry.getKey()) + " " + trim(entry.getValue()));
        }
    }

    public void removeViaCommand(Player player) {
        for (String classId : io.sakurammo.config.ConfigManager.DEFAULT_CLASS_IDS) {
            for (String stat : io.sakurammo.config.ConfigManager.VALID_STATS) {
                player.performCommand("skills modifier remove " + player.getName() + " "
                        + modifierName(classId, stat));
            }
        }
    }

    public static String modifierName(String classId, String stat) {
        return MODIFIER_PREFIX + classId + "_" + stat.toLowerCase(Locale.ROOT);
    }

    private static String trim(double value) {
        return io.sakurammo.util.Text.number(value);
    }
}
