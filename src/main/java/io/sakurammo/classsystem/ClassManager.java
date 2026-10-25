package io.sakurammo.classsystem;

import io.sakurammo.SakuraMMOPlugin;
import io.sakurammo.config.ClassDefinition;
import io.sakurammo.config.MainConfig;
import io.sakurammo.event.ClassChangeEvent;
import io.sakurammo.hook.AuraSkillsHook;
import io.sakurammo.player.PlayerProfile;
import io.sakurammo.util.Msg;
import io.sakurammo.util.Text;
import io.sakurammo.util.TimeFormat;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.permissions.PermissionAttachment;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 职业层：选择 / 切换 / 属性修饰符应用 / 技能权限发放。
 * <p>
 * 策略是「登录补发 + 切换时应用」：不依赖 AuraSkills 是否持久化 modifier。
 * 每次应用都是「先清掉所有 class_ 前缀的修饰符，再按当前职业重新挂」，因此天然幂等。
 */
public final class ClassManager {

    private final SakuraMMOPlugin plugin;
    private final Map<UUID, PermissionAttachment> skillPermissions = new HashMap<>();

    public ClassManager(SakuraMMOPlugin plugin) {
        this.plugin = plugin;
    }

    public record Result(boolean ok, String message) {
        public static Result ok(String message) {
            return new Result(true, message);
        }

        public static Result fail(String message) {
            return new Result(false, message);
        }
    }

    // ---------------- 查询 ----------------

    public ClassDefinition definitionOf(Player player) {
        PlayerProfile profile = plugin.playerData().get(player.getUniqueId());
        if (profile == null || !profile.hasClass()) {
            return null;
        }
        return plugin.config().classOf(profile.classId());
    }

    public String displayOf(String classId) {
        ClassDefinition definition = plugin.config().classOf(classId);
        return definition == null ? classId : definition.displayName();
    }

    // ---------------- 选择 / 切换 ----------------

    public Result select(Player player, String classId) {
        PlayerProfile profile = plugin.playerData().getOrLoad(player);
        if (profile.hasClass()) {
            return Result.fail(Msg.raw("class.already-selected",
                    "class", displayOf(profile.classId())));
        }
        ClassDefinition definition = plugin.config().classOf(classId);
        if (definition == null) {
            return Result.fail(Msg.raw("class.unknown", "id", classId));
        }
        MainConfig config = plugin.config().main();
        if (config.requireClassPermission() && !player.hasPermission("sakurammo.class." + definition.id())) {
            return Result.fail(Msg.raw("class.not-permitted", "class", definition.displayName()));
        }
        return change(player, profile, definition, false);
    }

    public Result switchTo(Player player, String classId) {
        PlayerProfile profile = plugin.playerData().getOrLoad(player);
        if (!profile.hasClass()) {
            return Result.fail(Msg.raw("class.not-selected-yet"));
        }
        ClassDefinition definition = plugin.config().classOf(classId);
        if (definition == null) {
            return Result.fail(Msg.raw("class.unknown", "id", classId));
        }
        if (definition.id().equals(profile.classId())) {
            return Result.fail(Msg.raw("class.same-class", "class", definition.displayName()));
        }
        MainConfig config = plugin.config().main();
        if (config.requireClassPermission() && !player.hasPermission("sakurammo.class." + definition.id())) {
            return Result.fail(Msg.raw("class.not-permitted", "class", definition.displayName()));
        }

        long now = System.currentTimeMillis();
        if (profile.switchCooldownUntil() > now) {
            return Result.fail(Msg.raw("class.switch-cooldown",
                    "time", TimeFormat.humanize(profile.switchCooldownUntil() - now)));
        }
        if (config.switchCost() > 0.0D && plugin.economy().available()) {
            if (!plugin.economy().has(player, config.switchCost())) {
                return Result.fail(Msg.raw("class.switch-cost-fail",
                        "cost", Text.number(config.switchCost())));
            }
            if (!plugin.economy().withdraw(player, config.switchCost())) {
                return Result.fail(Msg.raw("class.switch-cost-fail",
                        "cost", Text.number(config.switchCost())));
            }
        }
        return change(player, profile, definition, true);
    }

    private Result change(Player player, PlayerProfile profile, ClassDefinition target, boolean isSwitch) {
        String oldId = profile.classId();
        String oldDisplay = oldId == null ? "-" : displayOf(oldId);

        AuraSkillsHook hook = plugin.aura();
        if (hook.available()) {
            hook.removeAllClassModifiers(player);
        } else if (hook.pluginPresent()) {
            hook.removeViaCommand(player);
        }

        revokeSkillPermissions(player);

        profile.classId(target.id());
        profile.classSelectedAt(System.currentTimeMillis());
        profile.clearCooldowns();
        if (isSwitch && plugin.config().main().switchCooldownMillis() > 0L) {
            profile.switchCooldownUntil(System.currentTimeMillis()
                    + plugin.config().main().switchCooldownMillis());
        }

        plugin.resources().resetToInitial(player);
        grantSkillPermissions(player, target);
        applyModifiers(player);
        plugin.playerData().save(profile);

        Bukkit.getPluginManager().callEvent(new ClassChangeEvent(player, oldId, target.id()));

        String message = isSwitch
                ? Msg.raw("class.switched", "old", oldDisplay, "new", target.displayName())
                : Msg.raw("class.selected", "class", target.displayName(), "role", target.role());
        return Result.ok(message);
    }

    // ---------------- 管理员重置 ----------------

    /**
     * 管理员重置：撤销玩家的职业，回到「未选择」状态（可重新 select，免切换冷却）。
     * 在线玩家立即生效：清 AuraSkills 修饰符、技能权限、资源、冷却、绑定。
     */
    public Result reset(Player target) {
        PlayerProfile profile = plugin.playerData().getOrLoad(target);
        if (!profile.hasClass()) {
            return Result.fail(Msg.raw("class.reset-no-class", "player", target.getName()));
        }
        String oldDisplay = displayOf(profile.classId());

        AuraSkillsHook hook = plugin.aura();
        if (hook.available()) {
            hook.removeAllClassModifiers(target);
        } else if (hook.pluginPresent()) {
            hook.removeViaCommand(target);
        }
        revokeSkillPermissions(target);

        profile.classId(null);
        profile.classSelectedAt(0L);
        profile.switchCooldownUntil(0L);
        profile.clearResources();
        profile.clearCooldowns();
        profile.boundSkills().clear();
        profile.markDirty();
        plugin.playerData().save(profile);

        Msg.send(target, "class.reset-notify");
        plugin.getLogger().info("管理员重置了 " + target.getName() + " 的职业（原：" + profile.classId() + "）。");
        return Result.ok(Msg.raw("class.reset-done",
                "player", target.getName(), "old", oldDisplay));
    }

    /** 登录兜底：被离线重置的玩家登录时，清掉 AuraSkills 里残留的 class_ 修饰符。 */
    public void scheduleModifierCleanup(Player player) {
        new BukkitRunnable() {
            private int attempts = 0;

            @Override
            public void run() {
                if (!player.isOnline()) {
                    cancel();
                    return;
                }
                attempts++;
                AuraSkillsHook hook = plugin.aura();
                if (hook.available() && hook.isUserLoaded(player)) {
                    hook.removeAllClassModifiers(player);
                    cancel();
                    return;
                }
                if (attempts >= 20) {
                    cancel();
                }
            }
        }.runTaskTimer(plugin, 1L, 10L);
    }

    // ---------------- 修饰符 ----------------

    /** 立即应用一次（失败不重试）。 */
    public void applyModifiers(Player player) {
        tryApply(player, true);
        plugin.passives().applyPassives(player);
    }

    /** 登录补发：先补技能权限（不依赖 AuraSkills），再每 10 tick 重试修饰符，最多 20 次（10 秒）。 */
    public void scheduleApplyOnJoin(Player player) {
        PlayerProfile profile = plugin.playerData().get(player.getUniqueId());
        if (profile != null && profile.hasClass()) {
            ClassDefinition definition = plugin.config().classOf(profile.classId());
            if (definition != null) {
                // PermissionAttachment 不跨会话，必须每次登录重发
                grantSkillPermissions(player, definition);
            }
        }
        new BukkitRunnable() {
            private int attempts = 0;

            @Override
            public void run() {
                if (!player.isOnline()) {
                    cancel();
                    return;
                }
                attempts++;
                if (tryApply(player, false) || attempts >= 20) {
                    if (attempts >= 20) {
                        plugin.getLogger().warning("玩家 " + player.getName()
                                + " 的 AuraSkills 修饰符补发超时，请检查 AuraSkills 是否正常加载。");
                    }
                    cancel();
                }
            }
        }.runTaskTimer(plugin, 1L, 10L);
    }

    /**
     * @param force true 时即使 AuraSkills 用户还没就绪也直接调用一次
     * @return true 表示已处理完成，不需要重试
     */
    private boolean tryApply(Player player, boolean force) {
        PlayerProfile profile = plugin.playerData().get(player.getUniqueId());
        if (profile == null || !profile.hasClass()) {
            return true;
        }
        ClassDefinition definition = plugin.config().classOf(profile.classId());
        if (definition == null) {
            plugin.getLogger().warning("玩家 " + player.getName() + " 的职业 " + profile.classId()
                    + " 在配置里不存在，跳过属性应用。");
            return true;
        }
        AuraSkillsHook hook = plugin.aura();
        if (!hook.pluginPresent()) {
            return true;
        }
        if (!hook.available()) {
            hook.applyViaCommand(player, definition.id(), definition.stats());
            return true;
        }
        if (!force && !hook.isUserLoaded(player)) {
            return false;
        }
        int applied = hook.applyModifiers(player, definition.id(), definition.stats());
        if (applied < 0) {
            if (force) {
                plugin.getLogger().warning("玩家 " + player.getName()
                        + " 的 AuraSkills 修饰符应用失败（将重试）。");
                return false;
            }
            return false;
        }
        if (plugin.config().main().debug()) {
            plugin.getLogger().info("[debug] " + player.getName() + " 应用职业修饰符 "
                    + definition.id() + " × " + applied + " 项");
        }
        return true;
    }

    // ---------------- 技能权限 ----------------

    private void grantSkillPermissions(Player player, ClassDefinition definition) {
        revokeSkillPermissions(player);
        PermissionAttachment attachment = player.addAttachment(plugin);
        for (String skillId : definition.skills()) {
            attachment.setPermission("sakurammo.skill." + skillId, true);
        }
        skillPermissions.put(player.getUniqueId(), attachment);
    }

    public void revokeSkillPermissions(Player player) {
        PermissionAttachment attachment = skillPermissions.remove(player.getUniqueId());
        if (attachment != null) {
            try {
                player.removeAttachment(attachment);
            } catch (IllegalArgumentException ignored) {
                // 玩家已离线
            }
        }
    }

    /** 重载配置后重新发放权限（职业定义里的技能列表可能变了）。 */
    public void refreshOnlinePlayers() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            PlayerProfile profile = plugin.playerData().get(player.getUniqueId());
            if (profile == null || !profile.hasClass()) {
                continue;
            }
            ClassDefinition definition = plugin.config().classOf(profile.classId());
            if (definition != null) {
                grantSkillPermissions(player, definition);
                applyModifiers(player);
            }
        }
    }
}
