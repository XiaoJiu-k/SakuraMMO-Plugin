package io.sakurammo.party;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 队伍运行时模型。持久化到 parties.yml（PartyManager 负责）。
 * <p>
 * state：IDLE（可开本）/ RUNNING（副本进行中）/ CLEARED（已通关，等全员离场结算）。
 */
public final class Party {

    /** 队伍 id（UUID 字符串） */
    private final String id;
    private UUID leader;
    /** 成员按加入顺序排列（含队长）。 */
    private final List<UUID> members = new ArrayList<>();
    /** 待处理邀请：受邀者 uuid -> 过期时间（epoch millis）。 */
    private final Map<UUID, Long> invites = new HashMap<>();
    /** 离线计时：成员 uuid -> 最后在线时间。为空表示全员在线。 */
    private final Map<UUID, Long> offlineSince = new HashMap<>();

    private String state = "IDLE";
    private String dungeonId;
    private String runId;

    public Party(String id, UUID leader) {
        this.id = id;
        this.leader = leader;
        this.members.add(leader);
    }

    public String id() {
        return id;
    }

    public UUID leader() {
        return leader;
    }

    public boolean isLeader(UUID uuid) {
        return uuid.equals(leader);
    }

    /** 队长转移（队长被踢/离线超时晋升时）。 */
    public void leader(UUID uuid) {
        this.leader = uuid;
    }

    public List<UUID> members() {
        return members;
    }

    public boolean isMember(UUID uuid) {
        return members.contains(uuid);
    }

    public Map<UUID, Long> invites() {
        return invites;
    }

    public Map<UUID, Long> offlineSince() {
        return offlineSince;
    }

    public String state() {
        return state;
    }

    public void state(String state) {
        this.state = state;
    }

    public String dungeonId() {
        return dungeonId;
    }

    public void dungeonId(String dungeonId) {
        this.dungeonId = dungeonId;
    }

    public String runId() {
        return runId;
    }

    public void runId(String runId) {
        this.runId = runId;
    }
}
