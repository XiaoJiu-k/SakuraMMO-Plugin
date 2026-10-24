package io.sakurammo.player;

import java.util.LinkedHashMap;
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
    /** 快捷栏槽位 -> 绑定 */
    private final Map<Integer, SkillBinding> boundSkills = new LinkedHashMap<>();

    private int level = 1;
    private long experience = 0L;
    private String specialization = null;
    /** P1：切换职业时保存 AuraSkills 技能等级 */
    private final Map<String, Integer> skillSnapshots = new LinkedHashMap<>();

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
