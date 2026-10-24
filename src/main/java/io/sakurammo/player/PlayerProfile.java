package io.sakurammo.player;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 玩家运行时数据。持久化格式见任务书 §6。
 * <p>
 * level / experience / specialization / skillSnapshots 是 P1 预留字段：P0 只负责读写与保留，
 * 不实现任何成长或快照逻辑。
 */
public final class PlayerProfile {

    private final UUID uuid;
    private String name;

    private String classId;
    private long classSelectedAt;
    private long switchCooldownUntil;

    private final Map<String, Double> resources = new LinkedHashMap<>();
    /** skillId -> 冷却结束时间（epoch millis） */
    private final Map<String, Long> cooldowns = new LinkedHashMap<>();
    /** 快捷栏槽位 -> 绑定（P0.5 旧模型：仅保留读取兼容，不再写入） */
    private final Map<Integer, SkillBinding> boundSkills = new LinkedHashMap<>();
    /** P0.6：技能按键槽位 Q/E/R/T -> skillId（不跟物品绑定） */
    private final Map<String, String> skillBinds = new LinkedHashMap<>();
    /** P0.6：bound-skills → skill-binds 迁移标记（仅一次） */
    private boolean migratedFromBoundSkills = false;
    /** 按键布局版本：1 = 旧 Q/E/R/T，2 = R/T/F/G，3 = 现 Shift+1~4。新档案直接是 3。 */
    private int bindLayoutVersion = 3;

    private int level = 1;
    private long experience = 0L;
    private String specialization = null;
    /** P1：切换职业时保存 AuraSkills 技能等级 */
    private final Map<String, Integer> skillSnapshots = new LinkedHashMap<>();

    // ---------------- 阶段二：副本数据 ----------------

    /** 进本前的主世界背包快照（Base64，41 格：36 背包 + 4 盔甲 + 1 副手）。出本恢复。 */
    private String inventorySnapshot;
    /** 正在参与的副本 run-id（null = 不在副本中）。断线/宕机后据此恢复或结算。 */
    private String activeRun;
    /** 已领取奖励的 run-id 集合（幂等防重复领取）。 */
    private final List<String> claimedRewards = new ArrayList<>();
    /** 奖励邮箱（Base64 物品），出本恢复背包时一并发放——保证「先持久化后发物品」。 */
    private final List<String> rewardMailbox = new ArrayList<>();

    // ---------------- 阶段三：天赋 ----------------

    /** 未分配的天赋点（升级获得，洗点后重算为已获总额）。 */
    private int talentPoints = 0;
    /** 已投天赋：节点 id → 等级。 */
    private final Map<String, Integer> talents = new LinkedHashMap<>();

    private volatile boolean dirty = false;

    public PlayerProfile(UUID uuid, String name) {
        this.uuid = uuid;
        this.name = name;
    }

    public UUID uuid() {
        return uuid;
    }

    public String name() {
        return name;
    }

    public void name(String name) {
        if (name != null && !name.equals(this.name)) {
            this.name = name;
            markDirty();
        }
    }

    public String classId() {
        return classId;
    }

    public void classId(String classId) {
        this.classId = classId;
        markDirty();
    }

    public boolean hasClass() {
        return classId != null && !classId.isBlank();
    }

    public long classSelectedAt() {
        return classSelectedAt;
    }

    public void classSelectedAt(long value) {
        this.classSelectedAt = value;
        markDirty();
    }

    public long switchCooldownUntil() {
        return switchCooldownUntil;
    }

    public void switchCooldownUntil(long value) {
        this.switchCooldownUntil = value;
        markDirty();
    }

    public Map<String, Double> resources() {
        return resources;
    }

    public double resource(String id) {
        return resources.getOrDefault(id, 0.0D);
    }

    public void resource(String id, double value) {
        resources.put(id, value);
        markDirty();
    }

    public void clearResources() {
        resources.clear();
        markDirty();
    }

    public Map<String, Long> cooldowns() {
        return cooldowns;
    }

    public long cooldownUntil(String skillId) {
        return cooldowns.getOrDefault(skillId, 0L);
    }

    public void cooldownUntil(String skillId, long epochMillis) {
        cooldowns.put(skillId, epochMillis);
        markDirty();
    }

    public void clearCooldowns() {
        cooldowns.clear();
        markDirty();
    }

    public Map<Integer, SkillBinding> boundSkills() {
        return boundSkills;
    }

    public Map<String, String> skillBinds() {
        return skillBinds;
    }

    public String skillBind(String key) {
        return skillBinds.get(key);
    }

    public void skillBind(String key, String skillId) {
        skillBinds.put(key, skillId);
        markDirty();
    }

    public boolean migratedFromBoundSkills() {
        return migratedFromBoundSkills;
    }

    public void migratedFromBoundSkills(boolean value) {
        this.migratedFromBoundSkills = value;
        markDirty();
    }

    public int bindLayoutVersion() {
        return bindLayoutVersion;
    }

    public void bindLayoutVersion(int value) {
        this.bindLayoutVersion = value;
    }

    /**
     * 四个技能按键槽（v3 布局）：Shift+1/2/3/4（潜行 + 快捷栏 1-4，原版客户端原生可触发）。
     * 菜单键为 F（副手交换）与 V（桥接模组）。
     * v1（Q/E/R/T）与 v2（R/T/F/G）布局在 PlayerDataManager.load 里按槽位顺序迁移。
     */
    public static final List<String> SKILL_KEYS = List.of("1", "2", "3", "4");
    /** 旧按键布局（v1 / v2），迁移用。 */
    public static final List<String> LEGACY_KEYS_V1 = List.of("Q", "E", "R", "T");
    public static final List<String> LEGACY_KEYS_V2 = List.of("R", "T", "F", "G");

    public int level() {
        return level;
    }

    public void level(int level) {
        this.level = level;
        markDirty();
    }

    public long experience() {
        return experience;
    }

    public void experience(long value) {
        this.experience = value;
        markDirty();
    }

    public String specialization() {
        return specialization;
    }

    public void specialization(String value) {
        this.specialization = value;
        markDirty();
    }

    public Map<String, Integer> skillSnapshots() {
        return skillSnapshots;
    }

    public String inventorySnapshot() {
        return inventorySnapshot;
    }

    public void inventorySnapshot(String value) {
        this.inventorySnapshot = value;
        markDirty();
    }

    public String activeRun() {
        return activeRun;
    }

    public void activeRun(String value) {
        this.activeRun = value;
        markDirty();
    }

    public List<String> claimedRewards() {
        return claimedRewards;
    }

    public List<String> rewardMailbox() {
        return rewardMailbox;
    }

    public int talentPoints() {
        return talentPoints;
    }

    public void talentPoints(int value) {
        this.talentPoints = Math.max(0, value);
        markDirty();
    }

    public Map<String, Integer> talents() {
        return talents;
    }

    /** 已投入的天赋点总数。 */
    public int investedTalentPoints() {
        int sum = 0;
        for (int value : talents.values()) {
            sum += value;
        }
        return sum;
    }

    public boolean dirty() {
        return dirty;
    }

    public void markDirty() {
        this.dirty = true;
    }

    public void clean() {
        this.dirty = false;
    }
}
