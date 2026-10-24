package io.sakurammo.party;

import io.sakurammo.SakuraMMOPlugin;
import io.sakurammo.util.Msg;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * 队伍管理：命令逻辑、离线保留计时、parties.yml 持久化。
 * <p>
 * 持久化策略：每次队伍变动立即异步保存（先写 .tmp 再原子移动，防半截文件）。
 * 停服时 saveNow() 同步落盘。
 */
public final class PartyManager {

    /** 邀请有效期（毫秒）。 */
    private static final long INVITE_TTL = 60_000L;
    /** 离线扫描周期（tick）。 */
    private static final long SCAN_PERIOD_TICKS = 20L * 30;

    private final SakuraMMOPlugin plugin;
    private final File file;
    private final Map<String, Party> parties = new ConcurrentHashMap<>();

    private BukkitTask scanTask;

    public PartyManager(SakuraMMOPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "parties.yml");
        load();
    }

    // ---------------- 查询 ----------------

    public Party byId(String id) {
        return id == null ? null : parties.get(id);
    }

    /** 玩家所在队伍（null = 没有队伍）。 */
    public Party partyOf(UUID uuid) {
        for (Party party : parties.values()) {
            if (party.isMember(uuid)) {
                return party;
            }
        }
        return null;
    }

    public List<Party> all() {
        return new ArrayList<>(parties.values());
    }

    private static String name(UUID uuid) {
        Player player = Bukkit.getPlayer(uuid);
        return player != null ? player.getName() : String.valueOf(uuid);
    }

    private void broadcast(Party party, String key, String... kv) {
        String message = Msg.prefix() + Msg.raw(key, kv);
        for (UUID uuid : party.members()) {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null) {
                player.sendMessage(message);
            }
        }
    }

    // ---------------- 命令逻辑 ----------------

    public void create(Player player) {
        if (partyOf(player.getUniqueId()) != null) {
            Msg.send(player, "party.already-in");
            return;
        }
        Party party = new Party(UUID.randomUUID().toString(), player.getUniqueId());
        parties.put(party.id(), party);
        saveAsync();
        Msg.send(player, "party.created", "max",
                String.valueOf(plugin.config().main().partyMaxMembers()));
    }

    public void invite(Player leader, String targetName) {
        Party party = partyOf(leader.getUniqueId());
        if (party == null) {
            Msg.send(leader, "party.not-in");
            return;
        }
        if (!party.isLeader(leader.getUniqueId())) {
            Msg.send(leader, "party.not-leader");
            return;
        }
        if (party.state().equals("RUNNING") || party.state().equals("CLEARED")) {
            Msg.send(leader, "party.busy");
            return;
        }
        Player target = Bukkit.getPlayerExact(targetName);
        if (target == null) {
            Msg.send(leader, "general.player-not-found", "player", targetName);
            return;
        }
        if (target.getUniqueId().equals(leader.getUniqueId())) {
            Msg.send(leader, "party.invite-self");
            return;
        }
        if (party.isMember(target.getUniqueId())) {
            Msg.send(leader, "party.invite-member", "player", target.getName());
            return;
        }
        int max = plugin.config().main().partyMaxMembers();
        if (party.members().size() >= max) {
            Msg.send(leader, "party.full", "max", String.valueOf(max));
            return;
        }
        party.invites().put(target.getUniqueId(), System.currentTimeMillis() + INVITE_TTL);
        saveAsync();
        Msg.send(leader, "party.invite-sent", "player", target.getName());
        Msg.send(target, "party.invite-received", "player", leader.getName());
    }

    public void accept(Player player) {
        Party found = null;
        long now = System.currentTimeMillis();
        for (Party party : parties.values()) {
            Long expire = party.invites().get(player.getUniqueId());
            if (expire != null) {
                if (expire < now) {
                    party.invites().remove(player.getUniqueId());
                } else if (found == null) {
                    found = party;
                }
            }
        }
        if (found == null) {
            Msg.send(player, "party.no-invite");
            return;
        }
        if (partyOf(player.getUniqueId()) != null) {
            Msg.send(player, "party.already-in");
            found.invites().remove(player.getUniqueId());
            saveAsync();
            return;
        }
        if (found.members().size() >= plugin.config().main().partyMaxMembers()) {
            Msg.send(player, "party.full", "max",
                    String.valueOf(plugin.config().main().partyMaxMembers()));
            found.invites().remove(player.getUniqueId());
            saveAsync();
            return;
        }
        found.invites().remove(player.getUniqueId());
        found.members().add(player.getUniqueId());
        found.offlineSince().remove(player.getUniqueId());
        saveAsync();
        broadcast(found, "party.joined", "player", player.getName(),
                "size", String.valueOf(found.members().size()));
    }

    public void leave(Player player) {
        Party party = partyOf(player.getUniqueId());
        if (party == null) {
            Msg.send(player, "party.not-in");
            return;
        }
        plugin.instances().memberLeaving(party, player.getUniqueId(), true);
        removeMember(party, player.getUniqueId());
        Msg.send(player, "party.left");
    }

    public void kick(Player leader, String targetName) {
        Party party = partyOf(leader.getUniqueId());
        if (party == null) {
            Msg.send(leader, "party.not-in");
            return;
        }
        if (!party.isLeader(leader.getUniqueId())) {
            Msg.send(leader, "party.not-leader");
            return;
        }
        Player target = Bukkit.getPlayerExact(targetName);
        UUID targetId = null;
        if (target != null) {
            targetId = target.getUniqueId();
        } else {
            // 离线踢人：按名字扫档案（成员可能离线）
            targetId = plugin.playerData().findOfflineUuid(targetName);
        }
        if (targetId == null || !party.isMember(targetId)) {
            Msg.send(leader, "party.kick-not-member", "player", targetName);
            return;
        }
        if (targetId.equals(leader.getUniqueId())) {
            Msg.send(leader, "party.kick-self");
            return;
        }
        plugin.instances().memberLeaving(party, targetId, false);
        removeMember(party, targetId);
        broadcast(party, "party.kicked", "player", targetName);
        if (target != null) {
            Msg.send(target, "party.kicked-target");
        }
    }

    public void disband(Player leader) {
        Party party = partyOf(leader.getUniqueId());
        if (party == null) {
            Msg.send(leader, "party.not-in");
            return;
        }
        if (!party.isLeader(leader.getUniqueId())) {
            Msg.send(leader, "party.not-leader");
            return;
        }
        // 副本进行中解散：强制结束实例（在线成员会被传送回主城并恢复背包）
        plugin.instances().forceEnd(party, "disband");
        List<String> names = new ArrayList<>();
        for (UUID uuid : party.members()) {
            Player member = Bukkit.getPlayer(uuid);
            if (member != null) {
                names.add(member.getName());
            }
        }
        parties.remove(party.id());
        saveAsync();
        for (String memberName : names) {
            Player member = Bukkit.getPlayerExact(memberName);
            if (member != null) {
                Msg.send(member, "party.disbanded");
            }
        }
    }

    /** 按 id 解散队伍（单人副本队伍用完即散用）。队伍不存在时静默。 */
    public void disbandById(String id) {
        if (id == null || parties.remove(id) == null) {
            return;
        }
        saveAsync();
        plugin.getLogger().info("[party] 单人队伍 " + id.substring(0, 8) + " 已自动解散。");
    }

    /** 移除成员并处理队长交接/空队解散（不触发副本逻辑，调用方负责）。 */
    private void removeMember(Party party, UUID uuid) {
        party.members().remove(uuid);
        party.invites().remove(uuid);
        party.offlineSince().remove(uuid);
        if (party.members().isEmpty()) {
            parties.remove(party.id());
        } else if (uuid.equals(party.leader())) {
            // 队长离队：晋升最早加入的在线成员
            UUID next = null;
            for (UUID candidate : party.members()) {
                if (Bukkit.getPlayer(candidate) != null) {
                    next = candidate;
                    break;
                }
            }
            if (next == null) {
                next = party.members().get(0);
            }
            party.leader(next);
            broadcast(party, "party.leader-changed", "player", name(next));
        }
        saveAsync();
    }

    public void info(Player player) {
        Party party = partyOf(player.getUniqueId());
        if (party == null) {
            Msg.send(player, "party.not-in");
            return;
        }
        StringBuilder memberLine = new StringBuilder();
        for (UUID uuid : party.members()) {
            if (!memberLine.isEmpty()) {
                memberLine.append("&7, ");
            }
            Player member = Bukkit.getPlayer(uuid);
            String displayName = member != null ? member.getName() : name(uuid);
            boolean offline = member == null;
            if (uuid.equals(party.leader())) {
                displayName = "&6★ " + displayName;
            }
            memberLine.append(offline ? "&8" : "&f").append(displayName);
        }
        String stateText = switch (party.state()) {
            case "RUNNING" -> Msg.raw("party.state-running");
            case "CLEARED" -> Msg.raw("party.state-cleared");
            default -> Msg.raw("party.state-idle");
        };
        Msg.send(player, "party.info", "id", party.id().substring(0, 8),
                "members", memberLine.toString(), "state", stateText);
    }

    // ---------------- 在线/离线钩子 ----------------

    public void markOnline(UUID uuid) {
        Party party = partyOf(uuid);
        if (party != null && party.offlineSince().remove(uuid) != null) {
            saveAsync();
        }
    }

    public void markOffline(UUID uuid) {
        Party party = partyOf(uuid);
        if (party != null) {
            party.offlineSince().put(uuid, System.currentTimeMillis());
            saveAsync();
        }
    }

    /** 离线保留扫描：超过保留期踢出（副本进行中的成员除外，任务书红线）。 */
    private void scanOffline() {
        long retain = plugin.config().main().partyOfflineKickMinutes() * 60_000L;
        if (retain <= 0) {
            return;
        }
        long now = System.currentTimeMillis();
        boolean changed = false;
        for (Party party : List.copyOf(parties.values())) {
            boolean inDungeon = "RUNNING".equals(party.state()) || "CLEARED".equals(party.state());
            for (UUID uuid : List.copyOf(party.offlineSince().keySet())) {
                Long since = party.offlineSince().get(uuid);
                if (since == null || now - since < retain) {
                    continue;
                }
                if (inDungeon) {
                    continue; // 副本中的离线成员保留至副本结束
                }
                Player online = Bukkit.getPlayer(uuid);
                if (online != null && online.isOnline()) {
                    party.offlineSince().remove(uuid); // 实际在线，清掉错误标记
                    continue;
                }
                plugin.getLogger().info("[party] " + name(uuid) + " 离线超时，自动移出队伍 " + party.id());
                removeMember(party, uuid);
                broadcast(party, "party.offline-kicked", "player", name(uuid));
                changed = true;
            }
        }
        if (changed) {
            saveAsync();
        }
    }

    /**
     * 启动崩溃恢复：状态非 IDLE 的队伍说明上次停服/宕机时副本没正常结束。
     * 实例世界目录已被启动清理删掉，这里把队伍拉回 IDLE，防止永远开不了新本；
     * 玩家档案里的 active-run 由 handleRejoin 在其上线时恢复背包。
     */
    public void recoverFromCrash() {
        boolean changed = false;
        for (Party party : parties.values()) {
            if (!"IDLE".equals(party.state())) {
                plugin.getLogger().warning("[party] 队伍 " + party.id().substring(0, 8)
                        + " 上次处于 " + party.state() + "，已重置为 IDLE（实例世界随启动清理销毁）。");
                party.state("IDLE");
                party.runId(null);
                party.dungeonId(null);
                changed = true;
            }
        }
        if (changed) {
            saveAsync();
        }
    }

    public void start() {
        scanTask = Bukkit.getScheduler().runTaskTimer(plugin, this::scanOffline,
                SCAN_PERIOD_TICKS, SCAN_PERIOD_TICKS);
    }

    public void stop() {
        if (scanTask != null) {
            scanTask.cancel();
            scanTask = null;
        }
    }

    // ---------------- 持久化 ----------------

    private void load() {
        if (!file.isFile()) {
            return;
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection root = yaml.getConfigurationSection("parties");
        if (root == null) {
            return;
        }
        for (String id : root.getKeys(false)) {
            ConfigurationSection sec = root.getConfigurationSection(id);
            if (sec == null) {
                continue;
            }
            try {
                Party party = new Party(id, UUID.fromString(sec.getString("leader")));
                party.members().clear();
                for (String member : sec.getStringList("members")) {
                    party.members().add(UUID.fromString(member));
                }
                if (!party.isMember(party.leader())) {
                    party.members().add(0, party.leader());
                }
                ConfigurationSection invites = sec.getConfigurationSection("invites");
                if (invites != null) {
                    for (String key : invites.getKeys(false)) {
                        party.invites().put(UUID.fromString(key), invites.getLong(key));
                    }
                }
                ConfigurationSection offline = sec.getConfigurationSection("offline-since");
                if (offline != null) {
                    for (String key : offline.getKeys(false)) {
                        party.offlineSince().put(UUID.fromString(key), offline.getLong(key));
                    }
                }
                party.state(sec.getString("state", "IDLE"));
                party.dungeonId(sec.getString("dungeon"));
                String runId = sec.getString("run-id");
                party.runId(runId == null || runId.isBlank() ? null : runId);
                parties.put(id, party);
            } catch (Exception ex) {
                plugin.getLogger().warning("parties.yml 里的队伍 " + id + " 解析失败，已跳过：" + ex.getMessage());
            }
        }
        plugin.getLogger().info("已加载 " + parties.size() + " 支队伍。");
    }

    /** 每次变动立即异步保存（任务书要求）。 */
    public void saveAsync() {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, this::saveNow);
    }

    public synchronized void saveNow() {
        YamlConfiguration yaml = new YamlConfiguration();
        for (Party party : parties.values()) {
            String base = "parties." + party.id() + ".";
            List<String> members = new ArrayList<>();
            for (UUID uuid : party.members()) {
                members.add(uuid.toString());
            }
            yaml.set(base + "leader", party.leader().toString());
            yaml.set(base + "members", members);
            if (!party.invites().isEmpty()) {
                for (Map.Entry<UUID, Long> entry : party.invites().entrySet()) {
                    yaml.set(base + "invites." + entry.getKey(), entry.getValue());
                }
            }
            if (!party.offlineSince().isEmpty()) {
                for (Map.Entry<UUID, Long> entry : party.offlineSince().entrySet()) {
                    yaml.set(base + "offline-since." + entry.getKey(), entry.getValue());
                }
            }
            yaml.set(base + "state", party.state());
            yaml.set(base + "dungeon", party.dungeonId());
            yaml.set(base + "run-id", party.runId());
        }
        Path target = file.toPath();
        Path tmp = target.resolveSibling(file.getName() + ".tmp");
        try {
            yaml.save(tmp.toFile());
            try {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ex) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException ex) {
            plugin.getLogger().log(Level.SEVERE, "保存 parties.yml 失败", ex);
        }
    }
}
