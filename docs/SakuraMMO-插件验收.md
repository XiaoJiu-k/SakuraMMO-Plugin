# SakuraMMO 插件验收清单

> 对应任务书 §7 的 13 条验收标准。
> **当前状态：代码完成、构建通过，运行时验收尚未执行**（验收需要部署到 25566 测试服，该服由另一条工作线占用）。

## 0. 环境

| 项 | 值 |
|---|---|
| 服务端 | Paper 1.21.4-232 |
| Java | 21.0.8+12-LTS-250 |
| 端口 | 25566 / RCON 25576 |
| 已装插件 | AuraSkills 2.4.0、RPGLoot 0.9.0、MythicMobs 5.13.0-aad3f1c9、LuckPerms 5.5.88、PlaceholderAPI 2.12.3、ViaVersion 5.12.1、Vault 1.7.3 |
| 本插件 | SakuraMMO 1.0.0（`dist/SakuraMMO-1.0.0.jar`） |

## 1. 部署步骤

```bash
# 1. 停服（不要强杀）
python tools/rcon.py stop

# 2. 放 jar
cp SakuraMMO-Plugin/dist/SakuraMMO-1.0.0.jar server/plugins/

# 3. 用仓库里的职业定义覆盖旧的零代码版本（旧的没有 resources 段）
cp SakuraMMO-Plugin/src/main/resources/classes/*.yml server/plugins/SakuraMMO/classes/

# 4. 起服（后台，不要加 &）
cd server && java -Xms1G -Xmx2G -XX:+UseG1GC -jar paper-1.21.4-232.jar --nogui > console-latest.log 2>&1
```

> ⚠️ 旧 `plugins/SakuraMMO/classes/*.yml` 是零代码方案留下的，**没有 `resources:` 段**。
> 不覆盖的话插件会 WARN「职业 X 没有定义任何 resources」，资源系统对该职业不可用。

### 部署时的可选清理

MythicMobs 的 7 个职业技能文件里仍带 `Cooldown: N`（与 `skills.yml` 同值）。
插件现在是冷却的唯一权威，两者同值不冲突，但**调 `skills.yml` 的冷却时 MythicMobs 会继续按旧值拦**。
建议把这 7 个文件里的 `Cooldown:` 行删掉，让冷却只由插件管：

```bash
cd server/plugins/MythicMobs/skills
cp ironwarden.yml ironwarden.yml.bak   # 先备份
sed -i '/^  Cooldown: /d' ironwarden.yml paladin.yml priest.yml assassin.yml ranger.yml elementalist.yml warlock.yml
```

## 2. 验收项

状态列：⬜ 未测 / ✅ 通过 / ❌ 不通过 / ⚠️ 部分

| # | 验收项 | 状态 | 实测记录 |
|---|---|---|---|
| 1 | 起服无 ERROR，`/plugins` 显示 SakuraMMO 绿色 | ⬜ | |
| 2 | `/sm class list` 显示 7 职业 | ⬜ | |
| 3 | 选择铁卫后 `/skills modifier list <p>` 能看到 `class_ironwarden_*` 3 个 modifier | ⬜ | 实际名为 `class_ironwarden_health/_toughness/_strength`（见 §3 说明） |
| 4 | 重登后 modifier 仍在（登录补发保证） | ⬜ | |
| 5 | `/sm resource` 显示 `怒气 0/100`；打训练假人怒气增加 | ⬜ | |
| 6 | 绑定盾击到主手，右键假人：怒气 -20、冷却 6s、假人掉血 | ⬜ | |
| 7 | 怒气不足 20 时右键：ActionBar 提示「怒气不足」，不触发、不扣冷却 | ⬜ | |
| 8 | 冷却中右键：提示「冷却中 Ns」 | ⬜ | |
| 9 | 切到牧师：`class_ironwarden_*` 被移除、`class_priest_*` 被添加；资源切为法力 | ⬜ | |
| 10 | 退出重进：职业、资源、冷却、绑定技能全部恢复 | ⬜ | |
| 11 | `/papi parse me %sakurammo_class%` 返回 `ironwarden` | ⬜ | |
| 12 | 服务器 `stop` 后重启：数据不丢、无复制 | ⬜ | |
| 13 | AuraSkills / MythicMobs / PlaceholderAPI 任一缺失时仍能启动并降级 WARN | ⬜ | |

## 3. 与任务书的两处已知偏差

### 3.1 修饰符命名 `class_<id>_<stat>`

任务书 §4.1 要求「modifier 名统一 `class_<id>`，一个职业的所有加成共用一个名字」。

**技术上不成立**：AuraSkills 的修饰符存在 `Map<String, StatModifier>` 里，名字就是键，
同名第二个属性会被拒绝（已在 `tests/测试记录.md` S-007 实测记录）。
所以实际命名是 `class_ironwarden_health` / `class_ironwarden_toughness` / `class_ironwarden_strength`，
移除时按前缀 `class_` 批量清 —— 效果等价于「切换时按名字移除」。

### 3.2 双资源职业的副资源

刺客 `combo`、元素法师 `resonance` 在 `classes/*.yml` 里写了 `reserved: true`：
字段存在、读写接口可用、但不参与自然恢复与 HUD。P1 填逻辑时只需去掉 `reserved` 并加业务规则。

## 4. 已完成的静态验证（不依赖服务端）

| 项 | 方法 | 结果 |
|---|---|---|
| 编译 | `gradle build`（JDK 21，paper-api 1.21.4-R0.1） | ✅ BUILD SUCCESSFUL，0 warning |
| 产物 | `dist/SakuraMMO-1.0.0.jar` | ✅ 92,861 字节，含 26 个 class + 11 个资源文件 |
| `plugin.yml` 版本替换 | 解包检查 | ✅ `version: '1.0.0'`（Gradle expand 生效） |
| AuraSkills API 签名 | `javap -cp AuraSkills-2.4.0.jar` | ✅ `AuraSkillsApi.get()` / `getUser(UUID)` / `SkillsUser.addStatModifier(StatModifier)` / `removeStatModifier(String)` / `getStatModifiers()` / `getStatLevel(Stat)` / `new StatModifier(String, Stat, double)` 全部存在 |
| AuraSkills 属性枚举 | `javap` | ✅ `Stats` 是 Enum，9 个常量与 `ConfigManager.VALID_STATS` 一致 |
| MythicMobs 施法入口 | `javap -cp MythicMobs-5.13.0.jar` | ✅ `MythicBukkit.inst()` / `getAPIHelper()` / `BukkitAPIHelper.castSkill(Entity, String) -> boolean` / `getSkillManager().getSkillNames()` 全部存在 |
| MythicMobs 技能 id | 对照 `server/plugins/MythicMobs/skills/*.yml` | ✅ 35 个 `mythic-skill` 与现有技能文件里的 key 完全一致 |
| 降级路径 | 代码走查 | ✅ 三个 hook 都在「插件不存在」时返回 no-op / WARN，主类不引用 Vault/PAPI 类型 |

## 5. 运行时验收时的关注点

1. **AuraSkills 用户就绪时机**：登录后插件每 10 tick 重试一次，最多 10 秒。
   若 10 秒后仍 WARN「补发超时」，说明 AuraSkills 侧 `getUser` 返回 null，需要查 AuraSkills 日志。
2. **`/skills modifier list` 的可见性**：反射添加的修饰符与命令添加的完全同源，列表里应能看到 `class_*`。
3. **资源事件频率**：受击/攻击各 +N，DoT 每跳都会算一次「攻击」，术士的 `soul_shards` 可能涨得比预期快。
4. **HUD 与其它 ActionBar 冲突**：本插件每 10 tick 覆盖一次 ActionBar，
   会盖掉其它插件的 ActionBar 提示（聊天栏消息不受影响）。
5. **绑定与物品材质**：绑定记的是「槽位 + 材质」，换成别的材质不会触发，换回来才恢复。
