package io.sakurammo.config;

import org.bukkit.Material;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 职业定义（classes/&lt;id&gt;.yml）。
 */
public final class ClassDefinition {

    private final String id;
    private final String displayName;
    private final String role;
    private final String description;
    private final Material icon;
    private final Map<String, Double> stats;
    private final Map<String, ResourceDefinition> resources;
    private final List<String> skills;
    private final List<String> allowedEquipment;
    private final List<String> forbiddenEquipment;

    public ClassDefinition(String id,
                           String displayName,
                           String role,
                           String description,
                           Material icon,
                           Map<String, Double> stats,
                           Map<String, ResourceDefinition> resources,
                           List<String> skills,
                           List<String> allowedEquipment,
                           List<String> forbiddenEquipment) {
        this.id = id;
        this.displayName = displayName;
        this.role = role;
        this.description = description;
        this.icon = icon;
        this.stats = Collections.unmodifiableMap(new LinkedHashMap<>(stats));
        this.resources = Collections.unmodifiableMap(new LinkedHashMap<>(resources));
        this.skills = List.copyOf(skills);
        this.allowedEquipment = List.copyOf(allowedEquipment);
        this.forbiddenEquipment = List.copyOf(forbiddenEquipment);
    }

    public String id() {
        return id;
    }

    public String displayName() {
        return displayName;
    }

    public String role() {
        return role;
    }

    public String description() {
        return description;
    }

    /** 菜单图标，null 表示用默认值。 */
    public Material icon() {
        return icon;
    }

    public Map<String, Double> stats() {
        return stats;
    }

    public Map<String, ResourceDefinition> resources() {
        return resources;
    }

    public ResourceDefinition resource(String resourceId) {
        return resources.get(resourceId);
    }

    /** 主资源 = 第一个非 reserved 的资源；没有则返回第一个。 */
    public ResourceDefinition primaryResource() {
        for (ResourceDefinition def : resources.values()) {
            if (!def.reserved()) {
                return def;
            }
        }
        return resources.isEmpty() ? null : resources.values().iterator().next();
    }

    public List<String> skills() {
        return skills;
    }

    public List<String> allowedEquipment() {
        return allowedEquipment;
    }

    public List<String> forbiddenEquipment() {
        return forbiddenEquipment;
    }
}
