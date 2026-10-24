package io.sakurammo.hook;

import io.sakurammo.SakuraMMOPlugin;
import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.logging.Level;

/**
 * MythicMobs 施法挂钩。
 * <p>
 * 5.13.0 的真实入口（javap 核对过）：
 * <pre>
 *   io.lumine.mythic.bukkit.MythicBukkit.inst()                       -> MythicBukkit
 *   .getAPIHelper()                                                   -> BukkitAPIHelper
 *   .castSkill(org.bukkit.entity.Entity, java.lang.String)            -> boolean
 *   .getSkillManager().getSkillNames()                                -> Collection&lt;String&gt;
 * </pre>
 * 同样走反射：MythicMobs 的 API 不发布到可访问的公共仓库（repo.lumine.io 在本机不可达），
 * 且要求 softdepend 降级。
 */
public final class MythicMobsHook {

    private static final String CLS_MAIN = "io.lumine.mythic.bukkit.MythicBukkit";

    private final SakuraMMOPlugin plugin;
    private final Plugin mythicMobs;
    private final ClassLoader loader;

    private boolean ready;
    private Object apiHelper;
    private Method mCastSkill;
    private Method mGetSkillNames;
    private Set<String> skillNames = Collections.emptySet();

    public MythicMobsHook(SakuraMMOPlugin plugin) {
        this.plugin = plugin;
        this.mythicMobs = Bukkit.getPluginManager().getPlugin("MythicMobs");
        this.loader = mythicMobs == null ? null : mythicMobs.getClass().getClassLoader();
        this.ready = mythicMobs != null && mythicMobs.isEnabled() && setup();
        if (mythicMobs == null) {
            plugin.getLogger().warning("未检测到 MythicMobs：技能无法施放（已降级）。");
        } else if (!ready) {
            plugin.getLogger().warning("MythicMobs 反射挂钩失败：技能无法施放（已降级）。");
        }
    }

    private boolean setup() {
        try {
            Class<?> mainClass = Class.forName(CLS_MAIN, true, loader);
            Object instance = mainClass.getMethod("inst").invoke(null);
            if (instance == null) {
                return false;
            }
            apiHelper = mainClass.getMethod("getAPIHelper").invoke(instance);
            if (apiHelper == null) {
                return false;
            }
            mCastSkill = apiHelper.getClass().getMethod("castSkill", Entity.class, String.class);

            // getSkillManager 有协变返回的两个重载（bridge + 实现），逐个试，谁能拿到技能名表就用谁
            for (Method method : mainClass.getMethods()) {
                if (!method.getName().equals("getSkillManager") || method.getParameterCount() != 0) {
                    continue;
                }
                try {
                    Object manager = method.invoke(instance);
                    if (manager == null) {
                        continue;
                    }
                    Method names = manager.getClass().getMethod("getSkillNames");
                    Object result = names.invoke(manager);
                    if (result instanceof Collection<?> collection) {
                        Set<String> set = new LinkedHashSet<>();
                        for (Object name : collection) {
                            set.add(String.valueOf(name));
                        }
                        mGetSkillNames = names;
                        skillNames = set;
                        break;
                    }
                } catch (Throwable ignored) {
                    // 换下一个重载
                }
            }
            plugin.getLogger().info("MythicMobs 挂钩就绪，已注册技能 " + skillNames.size() + " 个。");
            return true;
        } catch (Throwable t) {
            plugin.getLogger().log(Level.WARNING, "MythicMobs 反射初始化失败", t);
            return false;
        }
    }

    public boolean available() {
        return ready;
    }

    public boolean pluginPresent() {
        return mythicMobs != null;
    }

    /** 让玩家作为施法者执行 MythicMobs 技能。 */
    public boolean castSkill(Player caster, String mythicSkill) {
        if (!ready || mythicSkill == null || mythicSkill.isBlank()) {
            return false;
        }
        try {
            Object result = mCastSkill.invoke(apiHelper, caster, mythicSkill);
            return result instanceof Boolean value && value;
        } catch (Throwable t) {
            plugin.getLogger().log(Level.WARNING, "MythicMobs castSkill 调用失败：" + mythicSkill, t);
            return false;
        }
    }

    public boolean skillExists(String mythicSkill) {
        if (mythicSkill == null || mythicSkill.isBlank()) {
            return false;
        }
        if (skillNames.isEmpty()) {
            return true; // 拿不到名表时不做否定判断，避免误报
        }
        return skillNames.contains(mythicSkill.toLowerCase(Locale.ROOT))
                || skillNames.contains(mythicSkill);
    }

    public Set<String> skillNames() {
        return Collections.unmodifiableSet(skillNames);
    }
}
