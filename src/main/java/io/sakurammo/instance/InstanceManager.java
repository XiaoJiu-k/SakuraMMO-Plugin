package io.sakurammo.instance;

import io.sakurammo.SakuraMMOPlugin;
import io.sakurammo.party.Party;
import io.sakurammo.player.PlayerProfile;
import io.sakurammo.util.Msg;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Directional;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Zombie;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 副本实例管理：开本（模板复制 → 加载 → 隔离传送 → 布置 Boss/门）、
 * 进行中（Boss 死亡链 / 双阀 / 转阶段 / 奖励箱）、结束（撤离 → 卸载 → 异步删目录）。
 * <p>
 * Boss 识别：spawn 时记录生成点附近的实体 UUID 差集（MythicMobs 命令生成后回查），
 * 死亡判定走原版 {@link org.bukkit.event.entity.EntityDeathEvent}——比反射监听
 * MythicMobDeathEvent 更稳（MythicMobs 5 的 API 包名多代并存），识别等价。
 */
public final class InstanceManager {

    private final SakuraMMOPlugin plugin;
    private final WorldHelper worlds;
    /** worldName → 运行中的实例。 */
    private final Map<String, DungeonInstance> byWorld = new ConcurrentHashMap<>();
    /** runId → 实例。 */
    private final Map<String, DungeonInstance> byRun = new ConcurrentHashMap<>();

    public InstanceManager(SakuraMMOPlugin plugin) {
        this.plugin = plugin;
        this.worlds = new WorldHelper(plugin);
    }

    public WorldHelper worlds() {
        return worlds;
    }

    public DungeonInstance byWorld(String worldName) {
        return worldName == null ? null : byWorld.get(worldName);
    }

    public DungeonInstance byRun(String runId) {
        return runId == null ? null : byRun.get(runId);
    }

    /** 玩家所在队伍的运行中实例（不看玩家位置）。 */
    public DungeonInstance ofParty(Party party) {
        return party == null || party.runId() == null ? null : byRun.get(party.runId());
    }

    public List<String> availableDungeons() {
        File dir = new File(plugin.getDataFolder(), "dungeons");
        List<String> ids = new ArrayList<>();
        File[] files = dir.listFiles((d, name) -> name.endsWith(".yml"));
        if (files != null) {
            for (File file : files) {
                ids.add(file.getName().substring(0, file.getName().length() - 4));
            }
        }
        return ids;
    }

    // ---------------- 开本 ----------------

    public void start(Player leader, String dungeonId) {
        DungeonDefinition def = DungeonDefinition.load(plugin, dungeonId);
        if (def == null) {
            Msg.send(leader, "dungeon.unknown", "id", dungeonId);
            return;
        }
        Party party = plugin.parties().partyOf(leader.getUniqueId());
        if (party == null) {
            // 单人开本：min-members=1 的副本自动创建一人队伍（队内语义复用，run-id 跟随队伍）
            if (def.minMembers > 1) {
                Msg.send(leader, "party.not-in");
                return;
            }
            plugin.parties().create(leader);
            party = plugin.parties().partyOf(leader.getUniqueId());
            if (party == null) {
                Msg.send(leader, "party.not-in");
                return;
            }
            Msg.send(leader, "dungeon.solo-party");
        }
        if (!party.isLeader(leader.getUniqueId())) {
            Msg.send(leader, "party.not-leader");
            return;
        }
        if (!"IDLE".equals(party.state())) {
            Msg.send(leader, "dungeon.party-busy");
            return;
        }
        List<Player> online = onlineMembers(party);
        if (online.size() < def.minMembers) {
            Msg.send(leader, "dungeon.need-members",
                    "min", String.valueOf(def.minMembers),
                    "current", String.valueOf(online.size()));
            return;
        }
        if (party.members().size() > def.maxMembers) {
            Msg.send(leader, "dungeon.too-many", "max", String.valueOf(def.maxMembers));
            return;
        }
        if (!plugin.mythic().available() && !plugin.mythic().pluginPresent()) {
            Msg.send(leader, "dungeon.no-mythic");
            return;
        }

        // 模板就绪（不存在则引导生成并按实测坐标重写副本配置）
        if (!worlds.templateExists(def.templateWorld)) {
            if (!plugin.config().main().dungeonAutoTemplate()) {
                Msg.send(leader, "dungeon.no-template", "world", def.templateWorld);
                return;
            }
            int baseY = worlds.bootstrapTemplate(def.templateWorld);
            if (baseY == WorldHelper.BOOTSTRAP_FAILED) {
                Msg.send(leader, "dungeon.template-fail");
                return;
            }
            worlds.rewriteDungeonConfig(dungeonId, def.templateWorld, baseY);
            def = DungeonDefinition.load(plugin, dungeonId);
            if (def == null) {
                Msg.send(leader, "dungeon.template-fail");
                return;
            }
        }

        String runId = UUID.randomUUID().toString();
        String worldName = "instance_" + party.id().substring(0, 8);
        if (Bukkit.getWorld(worldName) != null || byWorld.containsKey(worldName)) {
            Msg.send(leader, "dungeon.already-running");
            return;
        }
        final DungeonDefinition defFinal = def;
        final Party partyFinal = party;

        party.state("RUNNING");
        party.dungeonId(def.id);
        party.runId(runId);
        plugin.parties().saveAsync();
        broadcast(party, "dungeon.starting", "dungeon", def.displayName);

        // 世界复制是重 IO：异步复制 → 回主线程加载
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            boolean copied = worlds.copyWorld(defFinal.templateWorld, worldName);
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (!copied) {
                    partyFinal.state("IDLE");
                    partyFinal.runId(null);
                    plugin.parties().saveAsync();
                    broadcast(partyFinal, "dungeon.copy-fail");
                    return;
                }
                World world = WorldCreator.name(worldName).createWorld();
                if (world == null) {
                    partyFinal.state("IDLE");
                    partyFinal.runId(null);
                    plugin.parties().saveAsync();
                    broadcast(partyFinal, "dungeon.copy-fail");
                    return;
                }
                enterDungeon(partyFinal, defFinal, world, runId, worldName, online);
            });
        });
    }

    private List<Player> onlineMembers(Party party) {
        List<Player> online = new ArrayList<>();
        for (UUID uuid : party.members()) {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null && player.isOnline()) {
                online.add(player);
            }
        }
        return online;
    }

    /** 世界加载完成：隔离背包 → 传送 → 关门 → 布置三个 Boss。 */
    private void enterDungeon(Party party, DungeonDefinition def, World world,
                              String runId, String worldName, List<Player> online) {
        DungeonInstance instance = new DungeonInstance(party.id(), runId, def.id, worldName,
                def.boss2.valves().size(), def.boss3.phases().size());
        byWorld.put(worldName, instance);
        byRun.put(runId, instance);

        for (Player player : online) {
            if (!plugin.loot().snapshotAndClear(player)) {
                plugin.getLogger().warning("背包快照失败，" + player.getName() + " 不进本（数据保护）。");
                continue;
            }
            PlayerProfile profile = plugin.playerData().getOrLoad(player);
            profile.activeRun(runId);
            plugin.playerData().save(profile);
            player.teleport(def.spawnLoc(world));
            player.setHealth(Math.min(20.0D, player.getHealth() + 20.0D));
            player.setFoodLevel(20);
            instance.inside.put(player.getUniqueId(), true);
        }
        closeDoor(world, def);
        spawnBosses(instance, def, world);
        broadcast(party, "dungeon.entered", "dungeon", def.displayName);
    }

    /** 关门：门洞填铁栏杆（Boss1 死亡后打通为空气）。 */
    private void closeDoor(World world, DungeonDefinition def) {
        if (def.boss1.doorMin() == null || def.boss1.doorMax() == null) {
            return;
        }
        fill(world, def.boss1.doorMin(), def.boss1.doorMax(), Material.IRON_BARS);
    }

    private void openDoor(World world, DungeonDefinition def) {
        if (def.boss1.doorMin() == null || def.boss1.doorMax() == null) {
            return;
        }
        fill(world, def.boss1.doorMin(), def.boss1.doorMax(), Material.AIR);
    }

    private void fill(World world, int[] min, int[] max, Material material) {
        for (int x = Math.min(min[0], max[0]); x <= Math.max(min[0], max[0]); x++) {
            for (int y = Math.min(min[1], max[1]); y <= Math.max(min[1], max[1]); y++) {
                for (int z = Math.min(min[2], max[2]); z <= Math.max(min[2], max[2]); z++) {
                    world.getBlockAt(x, y, z).setType(material);
                }
            }
        }
    }

    /**
     * 布置 Boss：记录生成点周边实体 UUID → 控制台 mm spawn → 1 秒后差集识别并登记。
     * MythicMobs 生成失败时退化为原版僵尸（保住流程可测）。
     */
    private void spawnBosses(DungeonInstance instance, DungeonDefinition def, World world) {
        spawnBoss(instance, def, world, 1, def.boss1);
        spawnBoss(instance, def, world, 2, def.boss2);
        spawnBoss(instance, def, world, 3, def.boss3);
    }

    private void spawnBoss(DungeonInstance instance, DungeonDefinition def, World world,
                           int index, DungeonDefinition.BossDef boss) {
        Location at = new Location(world, boss.x(), boss.y(), boss.z());
        Set<UUID> before = nearbyUuids(world, at, 8);
        String command = "mm mobs spawn " + boss.mob() + " 1 " + world.getName()
                + "," + boss.x() + "," + boss.y() + "," + boss.z();
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command);
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            LivingEntity found = null;
            for (Entity entity : world.getNearbyEntities(at, 8, 8, 8)) {
                if (entity instanceof LivingEntity living && !before.contains(entity.getUniqueId())) {
                    found = living;
                    break;
                }
            }
            if (found == null) {
                // 退化：MythicMobs 没生成（mob 未定义等）→ 原版僵尸顶着名字上
                plugin.getLogger().warning("MythicMobs 未生成 " + boss.mob()
                        + "，副本 Boss " + index + " 退化为原版僵尸。");
                Zombie fallback = world.spawn(at, Zombie.class);
                fallback.setCustomName("§4黑铁Boss" + index);
                fallback.setCustomNameVisible(true);
                fallback.setMaxHealth(20.0D + index * 10.0D);
                fallback.setHealth(fallback.getMaxHealth());
                found = fallback;
            }
            if (index >= 2) {
                found.setInvulnerable(true); // Boss2/3 先无敌，前置 Boss 死亡后解锁
            }
            instance.bosses.put(found.getUniqueId(), index);
            plugin.getLogger().info("[dungeon] run=" + instance.runId + " Boss" + index + " 已就位："
                    + found.getType() + " " + found.getUniqueId());
        }, 20L);
    }

    private Set<UUID> nearbyUuids(World world, Location at, double radius) {
        Set<UUID> uuids = new HashSet<>();
        for (Entity entity : world.getNearbyEntities(at, radius, radius, radius)) {
            uuids.add(entity.getUniqueId());
        }
        return uuids;
    }

    // ---------------- 出本 / 结束 ----------------

    /** 单人离场（/sm dungeon leave 或被踢/被移出队伍）。 */
    public void leave(Player player) {
        Party party = plugin.parties().partyOf(player.getUniqueId());
        DungeonInstance instance = ofParty(party);
        if (instance == null) {
            Msg.send(player, "dungeon.not-in");
            return;
        }
        exitPlayer(instance, player, true);
        checkEmpty(instance);
    }

    /** PartyManager 在成员离队/被踢时回调（离线成员不能动背包，等其上线恢复）。 */
    public void memberLeaving(Party party, UUID uuid, boolean voluntary) {
        DungeonInstance instance = ofParty(party);
        if (instance == null) {
            return;
        }
        Player online = Bukkit.getPlayer(uuid);
        if (online != null && online.isOnline()) {
            exitPlayer(instance, online, voluntary);
        } else {
            instance.inside.remove(uuid); // 标记离场；档案里 activeRun 保留，上线时恢复背包
        }
        checkEmpty(instance);
    }

    /** 把一个在线玩家传出副本并恢复背包。 */
    private void exitPlayer(DungeonInstance instance, Player player, boolean voluntary) {
        DungeonDefinition def = DungeonDefinition.load(plugin, instance.dungeonId);
        instance.inside.remove(player.getUniqueId());
        plugin.loot().restore(player);
        if (def != null) {
            player.teleport(def.exitLoc());
        }
        Msg.send(player, voluntary ? "dungeon.left" : "dungeon.forced-out");
    }

    /** 实例内已无在线玩家 → 结束实例（撤离离线者推迟到其上线恢复）。 */
    private void checkEmpty(DungeonInstance instance) {
        if (instance.ending || instance.hasOnlineInside()) {
            return;
        }
        endInstance(instance, "empty");
    }

    /** 强制结束（解散队伍 / 管理员 stop / 全员离线）。 */
    public void forceEnd(Party party, String reason) {
        DungeonInstance instance = ofParty(party);
        if (instance != null) {
            endInstance(instance, reason);
        }
    }

    /** 结束流程：撤离在线成员 → 队伍回 IDLE → 延迟卸载并异步删目录。 */
    private void endInstance(DungeonInstance instance, String reason) {
        if (instance.ending) {
            return;
        }
        instance.ending = true;
        plugin.getLogger().info("[dungeon] run=" + instance.runId + " 结束（" + reason + "），开始撤离。");
        Party party = plugin.parties().byId(instance.partyId);
        for (UUID uuid : instance.insidePlayers()) {
            Player online = Bukkit.getPlayer(uuid);
            if (online != null && online.isOnline()) {
                exitPlayer(instance, online, false);
            }
        }
        if (party != null) {
            party.state("IDLE");
            party.runId(null);
            party.dungeonId(null);
            // 单人队伍用完即散，避免残留（多人队伍保留）
            if (party.members().size() <= 1) {
                plugin.parties().disbandById(party.id());
            } else {
                plugin.parties().saveAsync();
            }
        }
        long delay = Math.max(1, plugin.config().main().dungeonCleanupDelaySeconds()) * 20L;
        Bukkit.getScheduler().runTaskLater(plugin, () -> unloadAndDelete(instance), delay);
    }

    /** 卸载世界（主线程）→ 异步删除目录 → 注销记录。仍有玩家在内则重试一次。 */
    private void unloadAndDelete(DungeonInstance instance) {
        World world = Bukkit.getWorld(instance.worldName);
        if (world != null) {
            int players = world.getPlayers().size();
            if (players > 0) {
                for (Player player : List.copyOf(world.getPlayers())) {
                    DungeonDefinition def = DungeonDefinition.load(plugin, instance.dungeonId);
                    if (def != null) {
                        player.teleport(def.exitLoc());
                    }
                    plugin.loot().restore(player);
                }
            }
            if (!Bukkit.unloadWorld(world, false)) {
                plugin.getLogger().warning("[dungeon] 卸载世界 " + instance.worldName
                        + " 失败（可能仍有玩家/句柄占用），10s 后重试。");
                Bukkit.getScheduler().runTaskLater(plugin, () -> unloadAndDelete(instance), 200L);
                return;
            }
        }
        worlds.deleteFolderAsync(worlds.worldFolder(instance.worldName));
        byWorld.remove(instance.worldName);
        byRun.remove(instance.runId);
        plugin.getLogger().info("[dungeon] 世界 " + instance.worldName + " 已卸载并删除。");
    }

    // ---------------- 在线状态钩子 ----------------

    /** 玩家断线：副本内标记离场；实例内已无在线玩家 → 结束实例。 */
    public void handleQuit(Player player) {
        DungeonInstance instance = byWorld(player.getWorld().getName());
        if (instance == null) {
            return;
        }
        instance.inside.put(player.getUniqueId(), false);
        checkEmpty(instance);
    }

    /**
     * 玩家上线：档案里有 activeRun → 实例还在就传送回本；不在了就恢复背包回主城。
     * 邮箱里还有奖励（上次没出本就断线）→ 一并发放。
     */
    public void handleRejoin(Player player) {
        PlayerProfile profile = plugin.playerData().getOrLoad(player);
        String runId = profile.activeRun();
        if (runId == null) {
            if (!profile.rewardMailbox().isEmpty()) {
                plugin.loot().restore(player); // 只发邮箱（快照为空时仅清一次背包无副作用）
            }
            return;
        }
        DungeonInstance instance = byRun.get(runId);
        DungeonDefinition def = instance == null ? null
                : DungeonDefinition.load(plugin, instance.dungeonId);
        boolean goingBack = instance != null && !instance.ending && def != null
                && plugin.parties().byId(instance.partyId) != null;
        if (goingBack) {
            player.teleport(def.spawnLoc(Bukkit.getWorld(instance.worldName)));
            instance.inside.put(player.getUniqueId(), true);
            Msg.send(player, "dungeon.rejoined");
        } else {
            plugin.loot().restore(player);
            if (def != null) {
                player.teleport(def.exitLoc());
            } else {
                player.teleport(Bukkit.getWorlds().get(0).getSpawnLocation());
            }
            Msg.send(player, "dungeon.ended-while-offline");
        }
    }

    // ---------------- 战斗事件（DungeonListener 回调） ----------------

    /** Boss 死亡链：1→开门+解锁2；2→解锁3；3→放奖励箱+广播通关。 */
    public void handleEntityDeath(LivingEntity entity) {
        DungeonInstance instance = byWorld(entity.getWorld().getName());
        if (instance == null) {
            return;
        }
        Integer index = instance.bosses.remove(entity.getUniqueId());
        if (index == null) {
            return;
        }
        DungeonDefinition def = DungeonDefinition.load(plugin, instance.dungeonId);
        if (def == null) {
            return;
        }
        Party party = plugin.parties().byId(instance.partyId);
        switch (index) {
            case 1 -> {
                instance.phase = 2;
                openDoor(entity.getWorld(), def);
                // Boss2 的无敌只由双阀解除（模块 D 机制）；这里只开门
                broadcast(party, "dungeon.boss1-dead");
                rewardBoss(party, entity.getWorld(), entity.getLocation(), 1);
            }
            case 2 -> {
                instance.phase = 3;
                LivingEntity boss3 = bossEntity(instance, 3);
                if (boss3 != null) {
                    boss3.setInvulnerable(false);
                }
                broadcast(party, "dungeon.boss2-dead");
                rewardBoss(party, entity.getWorld(), entity.getLocation(), 2);
            }
            case 3 -> {
                instance.phase = 4;
                placeRewardChest(instance, def, entity.getWorld());
                instance.chestPlaced = true;
                if (party != null) {
                    party.state("CLEARED");
                    plugin.parties().saveAsync();
                }
                broadcast(party, "dungeon.cleared", "dungeon", def.displayName);
                Bukkit.broadcastMessage(Msg.prefix() + Msg.raw("dungeon.broadcast-cleared",
                        "dungeon", def.displayName));
                rewardBoss(party, entity.getWorld(), entity.getLocation(), 3);
            }
            default -> {
            }
        }
    }

    /**
     * Boss 掉落（阶段三制造底材）+ 经验/金币（在线成员各一份）。
     * 底材直接掉在 Boss 位置；经验给天赋系统，金币进经济。
     */
    private void rewardBoss(Party party, World world, Location at, int bossIndex) {
        String materialId = switch (bossIndex) {
            case 1 -> "blackiron_ingot";
            case 2 -> "mana_crystal";
            default -> "dark_core";
        };
        int amount = 2 + java.util.concurrent.ThreadLocalRandom.current().nextInt(2); // 2-3
        ItemStack drop = plugin.crafting().createMaterial(materialId, amount);
        if (drop != null) {
            world.dropItemNaturally(at, drop);
        }
        if (bossIndex == 3) {
            ItemStack crystal = plugin.crafting().createMaterial("mana_crystal", 2);
            if (crystal != null) {
                world.dropItemNaturally(at, crystal);
            }
        }
        if (party == null) {
            return;
        }
        int xp = switch (bossIndex) {
            case 1 -> 120;
            case 2 -> 180;
            default -> 300;
        };
        double factor = bossIndex == 3 ? 1.5D : bossIndex == 2 ? 1.2D : 1.0D;
        double moneyMin = plugin.economy().bossMoneyMin() * factor;
        double moneyMax = plugin.economy().bossMoneyMax() * factor;
        for (UUID uuid : party.members()) {
            Player online = Bukkit.getPlayer(uuid);
            if (online == null || !online.isOnline()) {
                continue;
            }
            plugin.talents().addExperience(online, xp);
            double money = moneyMin + java.util.concurrent.ThreadLocalRandom.current().nextDouble()
                    * (moneyMax - moneyMin);
            plugin.economy().deposit(online, Math.round(money * 100.0D) / 100.0D, "economy.boss-gain");
        }
    }

    private LivingEntity bossEntity(DungeonInstance instance, int index) {
        World world = Bukkit.getWorld(instance.worldName);
        if (world == null) {
            return null;
        }
        for (Map.Entry<UUID, Integer> entry : instance.bosses.entrySet()) {
            if (entry.getValue() == index) {
                Entity entity = Bukkit.getEntity(entry.getKey());
                if (entity instanceof LivingEntity living && !entity.isDead()) {
                    return living;
                }
            }
        }
        return null;
    }

    /** Boss3 转阶段（70%/35%）：广播 + 狂暴增益。DungeonListener 在伤害后回调。 */
    public void handleBossDamage(LivingEntity entity) {
        DungeonInstance instance = byWorld(entity.getWorld().getName());
        if (instance == null) {
            return;
        }
        Integer index = instance.bosses.get(entity.getUniqueId());
        if (index == null || index != 3) {
            return;
        }
        DungeonDefinition def = DungeonDefinition.load(plugin, instance.dungeonId);
        if (def == null) {
            return;
        }
        double ratio = entity.getHealth() / Math.max(1.0D, entity.getMaxHealth());
        List<Double> phases = def.boss3.phases();
        for (int i = 0; i < phases.size(); i++) {
            double threshold = phases.get(i);
            if (!instance.phasesDone[i] && ratio <= threshold) {
                instance.phasesDone[i] = true;
                entity.addPotionEffect(new PotionEffect(PotionEffectType.SPEED,
                        20 * 20, 1, false, true));
                entity.addPotionEffect(new PotionEffect(PotionEffectType.STRENGTH,
                        20 * 20, 0, false, true));
                plugin.parties().all().stream()
                        .filter(p -> p.id().equals(instance.partyId))
                        .findFirst()
                        .ifPresent(party -> broadcast(party, "dungeon.phase",
                                "percent", String.valueOf((int) (threshold * 100))));
            }
        }
    }

    /** 玩家交互：双阀（Boss2 解锁）与奖励箱（幂等领取）。 */
    public void handleInteract(Player player, Block block) {
        DungeonInstance instance = byWorld(block.getWorld().getName());
        if (instance == null) {
            return;
        }
        DungeonDefinition def = DungeonDefinition.load(plugin, instance.dungeonId);
        if (def == null) {
            return;
        }
        if (plugin.config().main().debug()) {
            plugin.getLogger().info("[debug] 副本交互 " + player.getName() + " "
                    + block.getType() + " (" + block.getX() + "," + block.getY() + "," + block.getZ()
                    + ") phase=" + instance.phase);
        }
        // 双阀
        if (instance.phase == 2 && !instance.boss2Unlocked) {
            List<int[]> valves = def.boss2.valves();
            for (int i = 0; i < valves.size(); i++) {
                int[] at = valves.get(i);
                if (block.getX() == at[0] && block.getY() == at[1] && block.getZ() == at[2]) {
                    openValve(instance, def, i, player);
                    return;
                }
            }
            if (block.getType() == Material.LEVER) {
                plugin.getLogger().warning("[dungeon] 拉杆交互未匹配配置阀门：点击("
                        + block.getX() + "," + block.getY() + "," + block.getZ() + ") 配置"
                        + valvesToString(valves));
            }
        }
        // 奖励箱
        if (instance.chestPlaced && def.chest != null
                && block.getX() == def.chest[0] && block.getY() == def.chest[1]
                && block.getZ() == def.chest[2]) {
            plugin.loot().claimChest(player, instance, def);
            return;
        }
        if (instance.chestPlaced && block.getType() == Material.CHEST) {
            plugin.getLogger().warning("[dungeon] 箱子交互未匹配配置奖励箱：点击("
                    + block.getX() + "," + block.getY() + "," + block.getZ() + ") 配置("
                    + (def.chest == null ? "无" : def.chest[0] + "," + def.chest[1] + "," + def.chest[2]) + ")");
        }
    }

    /** 开启第 index 个阀门（0 起）；全开时解锁 Boss2。方块交互与 /sm dungeon valve 共用。 */
    public boolean openValve(DungeonInstance instance, DungeonDefinition def, int index, Player feedback) {
        if (instance.phase != 2 || instance.boss2Unlocked
                || index < 0 || index >= instance.valvesOpen.length || instance.valvesOpen[index]) {
            return false;
        }
        instance.valvesOpen[index] = true;
        if (feedback != null) {
            Msg.send(feedback, "dungeon.valve-opened", "n", String.valueOf(index + 1));
        }
        plugin.getLogger().info("[dungeon] run=" + instance.runId + " 阀门 " + (index + 1)
                + " 已开启" + (feedback == null ? "（命令）" : "（" + feedback.getName() + "）"));
        boolean all = true;
        for (boolean open : instance.valvesOpen) {
            all = all && open;
        }
        if (all) {
            instance.boss2Unlocked = true;
            LivingEntity boss2 = bossEntity(instance, 2);
            if (boss2 != null) {
                boss2.setInvulnerable(false);
            }
            Party party = plugin.parties().byId(instance.partyId);
            broadcast(party, "dungeon.boss2-unlocked");
        }
        return true;
    }

    private static String valvesToString(List<int[]> valves) {
        StringBuilder sb = new StringBuilder("[");
        for (int[] at : valves) {
            sb.append(at[0]).append(",").append(at[1]).append(",").append(at[2]).append(" ");
        }
        return sb.append("]").toString();
    }

    private void placeRewardChest(DungeonInstance instance, DungeonDefinition def, World world) {
        Block block = world.getBlockAt(def.chest[0], def.chest[1], def.chest[2]);
        block.setType(Material.CHEST);
        if (block.getBlockData() instanceof Directional directional) {
            directional.setFacing(BlockFace.SOUTH);
            block.setBlockData(directional);
        }
        world.getBlockAt(def.chest[0], def.chest[1] - 1, def.chest[2]).setType(Material.IRON_BLOCK);
    }

    /** 副本内死亡的复活点 = 副本出生点（防止复活到主世界绕过隔离）。 */
    public Location respawnLocation(Player player) {
        DungeonInstance instance = byWorld(player.getWorld().getName());
        if (instance == null) {
            return null;
        }
        DungeonDefinition def = DungeonDefinition.load(plugin, instance.dungeonId);
        World world = Bukkit.getWorld(instance.worldName);
        return def == null || world == null ? null : def.spawnLoc(world);
    }

    // ---------------- 停服兜底 ----------------

    /** onDisable：把还在线的副本玩家全部恢复背包（世界随服务端一起卸载，目录留给启动清理）。 */
    public void shutdownRestore() {
        for (DungeonInstance instance : List.copyOf(byWorld.values())) {
            for (UUID uuid : instance.insidePlayers()) {
                Player online = Bukkit.getPlayer(uuid);
                if (online != null && online.isOnline()) {
                    plugin.loot().restore(online);
                }
            }
        }
    }

    public void startupCleanup() {
        worlds.cleanupOrphanInstances();
    }

    private void broadcast(Party party, String key, String... kv) {
        if (party == null) {
            return;
        }
        String message = Msg.prefix() + Msg.raw(key, kv);
        for (UUID uuid : party.members()) {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null) {
                player.sendMessage(message);
            }
        }
    }
}
