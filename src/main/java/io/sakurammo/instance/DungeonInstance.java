package io.sakurammo.instance;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 一个运行中的副本实例（一个队伍同时最多一个）。
 * <p>
 * bossIndex 1/2/3 对应 DungeonDefinition 的 bosses.1/2/3；
 * phase = 已进入的阶段（1 未打门卫，2 门卫亡，3 双阀 Boss 亡，4 通关）。
 */
public final class DungeonInstance {

    public final String partyId;
    public final String runId;
    public final String dungeonId;
    public final String worldName;

    /** 实体 UUID → boss 序号（1/2/3）。 */
    public final Map<UUID, Integer> bosses = new ConcurrentHashMap<>();
    /** 每个阀门（下标）是否已开启。 */
    public final boolean[] valvesOpen;
    /** Boss3 转阶段（0.7/0.35）是否已触发。 */
    public final boolean[] phasesDone;
    /** 在副本世界内的玩家（在线）。 */
    public final Map<UUID, Boolean> inside = new ConcurrentHashMap<>();

    /** 团本模式（Boss 同场全灭通关，无门/无敌链）。 */
    public volatile boolean raidMode = false;

    public volatile int phase = 1;
    public volatile boolean boss2Unlocked = false;
    public volatile boolean boss3Unlocked = false;
    public volatile boolean chestPlaced = false;
    public volatile boolean ending = false;

    public DungeonInstance(String partyId, String runId, String dungeonId, String worldName,
                           int valveCount, int phaseCount) {
        this.partyId = partyId;
        this.runId = runId;
        this.dungeonId = dungeonId;
        this.worldName = worldName;
        this.valvesOpen = new boolean[valveCount];
        this.phasesDone = new boolean[phaseCount];
    }

    /** 是否还有在线玩家在副本里。 */
    public boolean hasOnlineInside() {
        for (Boolean online : inside.values()) {
            if (online) {
                return true;
            }
        }
        return false;
    }

    public List<UUID> insidePlayers() {
        return List.copyOf(inside.keySet());
    }
}
