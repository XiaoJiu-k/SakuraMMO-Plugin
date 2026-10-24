# SakuraMMO 插件验收记录

> 对应任务书 §7 的 13 条验收标准 + 新增的「按 F 打开菜单」功能。
> 验收时间：2026-10-24 15:24 – 15:50
> 环境：Paper 1.21.4-232 / Java 21.0.8 / SakuraMMO 1.0.0

## 0. 结论

**13 条验收：12 条通过，1 条（插件缺失降级）只做代码审查未做运行时验证。**

菜单功能 6 项全部通过，包括真实的「按 F」封包验证。

## 1. 环境与部署

| 项 | 值 |
|---|---|
| 服务端 | Paper 1.21.4-232（端口 25566 / RCON 25576） |
| Java | 21.0.8+12-LTS-250 |
| 插件栈 | AuraSkills 2.4.0、RPGLoot 0.9.0、MythicMobs 5.13.0、LuckPerms 5.5.88、PlaceholderAPI 2.12.3、ViaVersion 5.12.1、Vault 1.7.3、CommandPanels |
| 本插件 | `server/plugins/SakuraMMO-1.0.0.jar`（113,494 字节） |
| 测试客户端 | mineflayer bot（`tools/bot/`），离线模式，用户名 `SakuraTester`（OP） |

### 部署时做的三件事

1. 覆盖 `server/plugins/SakuraMMO/classes/*.yml`（补 `icon:` 与 `resources:` 段）
2. 覆盖 `server/plugins/SakuraMMO/skills.yml`（补 35 个 `icon:`）
3. **删除** `server/plugins/MythicMobs/skills/{7 个职业}.yml` 里的 35 行 `Cooldown:` —— 冷却改由插件统一管
   - 备份在 `_backup-before-plugin/20261024-1523/`

### 启动日志（关键行）

```text
[SakuraMMO] 已加载 7 个职业：[assassin, elementalist, ironwarden, paladin, priest, ranger, warlock]
[SakuraMMO] 已加载 35 个技能入口定义。
[SakuraMMO] AuraSkills 挂钩就绪（API 反射），可用属性 [strength, health, regeneration, luck, wisdom, toughness, crit_chance, crit_damage, speed]。
[SakuraMMO] MythicMobs 挂钩就绪，已注册技能 38 个。
[SakuraMMO] 未检测到 Vault 经济实现，切换职业费用检查已跳过。
[SakuraMMO] SakuraMMO 已启用（102 ms）：7 职业 / 35 技能 / 挂钩 AuraSkills✓ MythicMobs✓ PAPI✓
```

## 2. 十三条验收

| # | 验收项 | 结果 | 实测证据 |
|---|---|---|---|
| 1 | 起服无 ERROR，`/plugins` 显示 SakuraMMO | ✅ | `plugins` 输出里 `SakuraMMO` 在 Bukkit Plugins 且为绿色；全程 `Could not pass` / `io.sakurammo.*Exception` 计数 **0** |
| 2 | `/sm class list` 显示 7 职业 | ✅ | 输出 7 行：ironwarden 铁卫(主坦克) / paladin 圣骑士 / priest 牧师 / assassin 刺客 / ranger 游侠 / elementalist 元素法师 / warlock 术士 |
| 3 | 选铁卫后 `modifier list` 有 3 个 class_ironwarden_* | ✅ | `class_ironwarden_health - 生命值 等级 +6` / `class_ironwarden_strength - 力量 等级 +3` / `class_ironwarden_toughness - 韧性 等级 +4` |
| 4 | 重登后 modifier 仍在 | ✅ | 每次登录控制台都有 `[debug] <玩家> 应用职业修饰符 ironwarden × 3 项`；重登后 `modifier list` 仍是 3 条 |
| 5 | `/sm resource` 显示怒气；攻击假人怒气增加 | ✅ | 初始 `怒气 0/100`；7 次铁剑攻击后 `31/100`（每次 +3，另有 2 次受击 +5×2） |
| 6 | 绑定盾击右键假人：怒气 -20、冷却 6s、假人掉血 | ✅ | 见 §3 数值明细 |
| 7 | 怒气不足时提示且不触发 | ✅ | 怒气设 5 后右键：`怒气 5/100` 不变、`冷却中：无`、假人血量不变 |
| 8 | 冷却中右键提示且不触发 | ✅ | 首次施放后 600ms 内再右键：怒气 `36/100` 不变，`冷却中：ironwarden_shield_bash 5s` 保持 |
| 9 | 切牧师：旧 modifier 移除、新 modifier 添加、资源切法力 | ✅ | `class_priest_wisdom/regeneration/health`，`/sm resource` → `法力 1000/1000`；切回铁卫恢复 3 条 ironwarden 修饰符与 `怒气 0/100` |
| 10 | 退出重进：职业/资源/冷却/绑定恢复 | ✅ | 施放 ground_slam 后退出（冷却 24s、rage 16）→ 重进：`rage=16`、`冷却中：ironwarden_ground_slam 13s`、`绑定：0→ironwarden_shield_bash` |
| 11 | `/papi parse me %sakurammo_class%` 返回 ironwarden | ✅ | `class=ironwarden display=铁卫 rage=16/100`（另验证 `_max` = 100） |
| 12 | 服务器 stop 后重启数据不丢 | ✅ | 验收期间重启 3 次，职业 / 怒气 / 绑定 / 冷却全部保留（`playerdata/79408cdc-….yml`） |
| 13 | 任一挂钩缺失仍能启动并降级 WARN | ⚠️ | **代码审查通过、未运行时验证**（移除 AuraSkills/MythicMobs 会影响另一条工作线）。三个 hook 都在插件不存在时返回 no-op + WARN，主类不引用 Vault/PAPI 类型（`VaultBootstrap` / `PlaceholderBootstrap` 隔离） |

## 3. 技能数值明细

测试目标：`TrainingDummy`（1000 HP，NoAI），铁卫 `ironwarden_shield_bash`（cost 20，cooldown 6s）

| 步骤 | 操作 | 观察 |
|---|---|---|
| 1 | `/sm resource set SakuraTester rage 50` | `怒气 50/100` |
| 2 | 右键训练假人 | `怒气 36/100`；`冷却中：ironwarden_shield_bash 5s` |
| 3 | 600ms 后再次右键 | `怒气 36/100`（被冷却拦下，无副作用） |
| 4 | 假人血量 | 1000 → 987.86（**场上有 2 只假人**，每只 -6.07） |
| 5 | `/sm cast ironwarden_ground_slam` | `怒气 16`（50 - 40 + 6 来自两只假人的 on-attack）；冷却 24s |

> **怒气 50 → 36 而不是 30 的原因**：施放瞬间 MythicMobs 的 `damage` 机制命中 2 只假人，
> 触发 2 次 `EntityDamageByEntityEvent` → `on-attack: 3` × 2 = +6，所以净值是 `50 + 6 - 20 = 36`。
> 单只假人时为 `50 + 3 - 20 = 33`。行为正确，不是重复扣费。

## 4. 菜单功能验收

| 项 | 结果 | 证据 |
|---|---|---|
| 按 **F** 打开角色面板 | ✅ | 客户端发 `block_dig{status:6}`（原版副手交换）→ 服务端 `windowOpen`，窗口 **90 槽**（54 菜单 + 36 背包） |
| `/sm menu` 打开 | ✅ | 同上，标题「SakuraMMO · 角色面板」 |
| 点「切换职业」进职业列表 | ✅ | 点槽位 40 → 新窗口 **63 槽**，标题「SakuraMMO · 选择职业」 |
| 点当前职业 | ✅ | 提示「你已经是 铁卫 了。」并留在列表 |
| 点其他职业进二次确认 | ✅ | 标题「SakuraMMO · 确认切换」，槽位 11 绿色确认 / 15 红色取消 |
| 图标与物品 | ✅ | 槽 4 = `shield`（职业）、13 = `experience_bottle`（资源）、19 = `shield`（盾击）、40 = `nether_star`（切换）、49 = `barrier`（关闭） |
| 菜单防拿取 | ✅ | 所有 `InventoryClickEvent` / `InventoryDragEvent` 被取消 |

**潜行 + F** 保留原版副手交换（`menu.shift-bypass: true`）。

## 5. 期间修掉的问题

| 问题 | 现象 | 修复 |
|---|---|---|
| 右键**实体**不触发技能 | 打怪 / 点假人时技能没反应 | 原版右键实体走 `PlayerInteractEntityEvent`，不触发 `PlayerInteractEvent`。补了第二个监听器 |
| 重登后技能全部「未解锁」 | `PermissionAttachment` 不跨会话 | `scheduleApplyOnJoin` 里先补发 `sakurammo.skill.<id>` 权限 |
| 绑了技能的弓无法蓄力 | 空气右键被 `setCancelled` | 只在 `RIGHT_CLICK_BLOCK` 时取消事件 |
| 语言文件升级不生效 | 新增消息显示 `<missing message: xxx>` | `ConfigManager#mergeMessageDefaults`：启动时把 jar 内默认文件里缺失的键补进用户文件（本次补了 39 条） |
| 技能图标全是纸 | `skills.yml` 首启已生成，`saveResource` 不覆盖 | 手动同步；已写进 README 已知限制 |

## 6. 遗留与建议

1. **ActionBar 与 AuraSkills 抢通道**（未修，需你决定）
   AuraSkills 的 `action_bar` 每 5 tick 刷新血量/法力，本插件每 10 tick 刷新资源条，实测资源条基本看不到。
   二选一：关 AuraSkills 的 `action_bar.enabled`，或把本插件 `hud.enabled` 设 false。
2. **`%sakurammo_resource_*%` 在玩家离线时返回空**：`PlaceholderHook` 只读内存缓存，玩家退出后缓存已清。
   若需要在离线场景用（如网页排行榜），需要改成读 `playerdata/*.yml`。
3. **被动技能（5 个 `PASSIVE`）没有驱动逻辑**：仍由 MythicMobs 自己的 `Conditions` 触发，插件不管。
4. **装备限制未拦截**：`equipment.allowed/forbidden` 只是文档约定。
5. **`/sm cast` 是给调试用的**：它绕过绑定校验，但仍走职业/权限/资源/冷却四项检查。
6. **测试机器人看不到自定义名**：`TrainingDummy` 在 mineflayer 里 `customName` 为空，只能用 `nearest` 定位。
   这是 bot 侧限制，不影响真人客户端。
