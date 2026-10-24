# SakuraMMO 职业插件

Paper 1.21.4 服务器上的**职业 / 资源 / 技能入口层**插件。

它取代 `docs/06-阶段一-开发任务书.md` 里那套「LuckPerms 组 + `/skills modifier add`」的零代码临时方案，
成为职业层的单一权威。

```
Paper 1.21.4-232 / Java 21
   ├── AuraSkills 2.4.0     ← 属性聚合（本插件只通过它的 StatModifier 增减）
   ├── MythicMobs 5.13.0    ← 技能实际效果（本插件只负责入口校验后调用 castSkill）
   ├── RPGLoot 0.9.0        ← 装备词条（与本插件无耦合）
   ├── PlaceholderAPI       ← %sakurammo_xxx%
   ├── LuckPerms            ← P0 仅权限判断，不做组同步
   └── Vault                ← 可选，切换职业费用
```

## 职责边界

| 本插件负责 | 不负责（交给别人） |
|---|---|
| 职业定义、选择、切换、冷却、费用 | 技能效果 → MythicMobs |
| 7 种资源循环（怒气/圣能/法力/能量/集中值/灵魂碎片，含 2 个 P1 预留副资源） | 属性数值聚合与限幅 → AuraSkills |
| 技能入口：绑定主手、五项校验、冷却、扣资源 | 装备词条与掉落 → RPGLoot |
| 玩家数据持久化 `playerdata/<uuid>.yml` | 副本、经济、天赋树（不在 P0 范围） |

## 构建

```bash
# 需要 JDK 21
./gradlew build          # 或 gradle build
# 产物：build/libs/SakuraMMO-1.0.0.jar
```

依赖说明：

- `paper-api:1.21.4-R0.1-SNAPSHOT` 走 `repo.papermc.io`（唯一远程依赖）
- `libs/PlaceholderAPI-2.12.3.jar`、`libs/Vault-1.7.3.jar` 是本地 `compileOnly`，
  避免依赖第三方仓库可达性
- **AuraSkills / MythicMobs 不做编译期依赖**，全部走反射（原因见下）

## 安装

1. 把 `SakuraMMO-1.0.0.jar` 放进 `server/plugins/`
2. 起服。首次启动会在 `plugins/SakuraMMO/` 生成：
   ```
   config.yml
   messages_zh_CN.yml
   skills.yml
   classes/{ironwarden,paladin,priest,assassin,ranger,elementalist,warlock}.yml
   playerdata/            （玩家数据，运行时生成）
   ```
3. 控制台应看到：
   ```
   [SakuraMMO] AuraSkills 挂钩就绪（API 反射），可用属性 [...]
   [SakuraMMO] MythicMobs 挂钩就绪，已注册技能 N 个。
   [SakuraMMO] PlaceholderAPI 扩展已注册
   [SakuraMMO] SakuraMMO 已启用（… ms）：7 职业 / 35 技能 / 挂钩 AuraSkills✓ MythicMobs✓ PAPI✓
   ```

## 命令与权限

| 命令 | 权限 | 说明 |
|---|---|---|
| `/sm class list` | `sakurammo.use` | 列出 7 个职业，当前职业带 ◀ 标记 |
| `/sm class select <id>` | `sakurammo.use` | 首次选择（免费） |
| `/sm class switch <id>` | `sakurammo.use` | 切换（默认 24h 冷却 + 可选费用） |
| `/sm class info [player]` | `sakurammo.use` | 职业 / 属性 / 资源 / 技能一览 |
| `/sm resource` | `sakurammo.use` | 查看当前职业所有资源 |
| `/sm resource set <p> <res> <v>` | `sakurammo.admin` | 调试用 |
| `/sm bind <skillId>` | `sakurammo.use` | 绑定技能到**主手物品所在槽位** |
| `/sm unbind` | `sakurammo.use` | 解除当前槽位绑定 |
| `/sm reload` | `sakurammo.admin` | 只重载配置，不重载玩家数据 |
| `/sm debug` | `sakurammo.admin` | 职业 / 资源 / 冷却 / 修饰符 / 挂钩状态 |

别名：`/sakurammo`。Tab 补全覆盖全部子命令与职业/技能 id。

权限节点：

- `sakurammo.use`（默认所有人）
- `sakurammo.admin`（默认 OP）
- `sakurammo.class.<id>`（`require-class-permission: true` 时才检查）
- `sakurammo.skill.<skillId>`（**运行时由插件通过 PermissionAttachment 发给本职业玩家**，不需要手动配）

## 配置

### `config.yml`

```yaml
language: zh_CN
save-interval-seconds: 300        # 定时保存间隔
switch-cooldown-hours: 24         # 切换冷却，0 = 不限
switch-cost: 0                    # Vault 费用，0 = 免费
require-class-permission: false
hud:
  enabled: true
  type: ACTIONBAR
  update-interval-ticks: 10
  format: "&b{display} &f{current}&7/&f{max}"
resource:
  tick-interval-ticks: 10         # 0.5s 一次自然恢复
debug: false
```

HUD 占位符：`{display}` `{current}` `{max}` `{percent}` `{class}` `{class_display}` `{resource}`

### `classes/<id>.yml`

```yaml
id: ironwarden
display-name: 铁卫
role: 主坦克
description: 前排承伤，靠格挡与减伤建立仇恨。
stats:                 # 只允许 AuraSkills 真实存在的 9 项
  health: 6
  toughness: 4
  strength: 3
resources:
  rage:
    display-name: "&c怒气"
    max: 100
    initial: 0
    regen-per-second: 0
    on-damage-taken: 5
    on-attack: 3
skills: [ ... ]
equipment:
  allowed: [ SWORD, AXE, SHIELD, HEAVY_ARMOR ]
  forbidden: [ BOW, WAND ]
```

`resources.<id>` 支持的字段：`display-name` / `max` / `initial` / `regen-per-second` /
`on-damage-taken` / `on-attack` / `on-kill` / `reserved`。

`reserved: true` 表示 P1 预留资源：不自然恢复、不上 HUD，但读写接口可用（刺客 `combo`、元素法师 `resonance`）。

### `skills.yml`

```yaml
ironwarden_shield_bash:
  display-name: 盾击
  class: ironwarden
  mythic-skill: ironwarden_shield_bash   # 必须与 MythicMobs 里的 id 一致
  resource: rage
  cost: 20
  cooldown: 6
  trigger: RIGHT_CLICK                   # RIGHT_CLICK / RIGHT_CLICK_AIR / RIGHT_CLICK_BLOCK / LEFT_CLICK / PASSIVE
  unlock:
    permission: sakurammo.skill.ironwarden_shield_bash
```

启动时会校验每个 `mythic-skill` 是否存在于 MythicMobs，缺失只打 WARN 不崩溃。

### `messages_zh_CN.yml`

所有面向玩家的文本都在这里，Java 里没有硬编码中文。

## 数据模型

`plugins/SakuraMMO/playerdata/<uuid>.yml`：

```yaml
uuid: "..."
name: "PlayerName"
class: ironwarden
class-selected-at: 1730000000000
switch-cooldown-until: 0
resources:
  rage: 45.0
cooldowns:
  ironwarden_shield_bash: 1730000006000
bound-skills:
  "0":
    skill: ironwarden_shield_bash
    material: IRON_SWORD
level: 1
experience: 0
specialization: null
skill-snapshots: {}
```

写入是原子的：先写 `<uuid>.yml.tmp`，再 `Files.move(..., ATOMIC_MOVE)` 覆盖，
避免崩溃留下半截文件。

## 关键设计决策

### 1. AuraSkills / MythicMobs 走反射，不做编译期依赖

三个原因：

1. AuraSkills 的 API 包打在插件自己的 jar 里，不发布到公共 Maven 仓库；
2. MythicMobs 的 `repo.lumine.io` 在部署环境不可达；
3. 任务书要求 `softdepend`，任一缺失时插件必须能启动 —— 编译期依赖会让类加载直接失败。

反射走目标插件自己的 `ClassLoader`（`Class.forName(name, true, otherPlugin.getClassLoader())`），
绕开 Paper 的插件类隔离差异。反射失败时 AuraSkills 退化为「以玩家身份执行 `/skills modifier` 命令」，
MythicMobs 则直接降级（技能无法施放，打 WARN）。

`VaultHook` 与 `PlaceholderHook` 也做了隔离：主类只持有 `EconomyBridge` 接口和一个 `Object` 句柄，
分别由 `VaultBootstrap` / `PlaceholderBootstrap` 在确认插件存在后才加载，
这样 Vault / PAPI 缺失时不会 `NoClassDefFoundError`。

### 2. 修饰符命名是 `class_<id>_<stat>`，不是 `class_<id>`

AuraSkills 的修饰符存在 `Map<String, StatModifier>` 里，**名字就是键**，
一个名字只能挂一项属性（同名会被拒绝）。所以「一个职业共用一个名字」在技术上不成立。
实际命名 `class_ironwarden_health` / `class_ironwarden_toughness` / …，
移除时按前缀 `class_` 批量清。

> 这条已在 `tests/测试记录.md` 的 S-007 里记录过。

### 3. 「登录补发 + 切换时应用」，不赌 AuraSkills 是否持久化

每次应用都是「先清掉该玩家身上所有 `class_` 前缀的修饰符，再按当前职业重新挂」，
天然幂等。登录后每 10 tick 重试一次（最多 20 次 = 10 秒），等 AuraSkills 把玩家用户数据加载好。

### 4. 插件的 mana 与 AuraSkills 的 mana 是两套数值

牧师 / 元素法师的 `mana` 是插件自有资源（0–1000，自然恢复 5–6/s），
与 AuraSkills 的 mana（法力能力用）互不影响。P0 不做联动，这是已知限制。

### 5. 被动技能（PASSIVE）不绑定

每个职业第 5 个技能是触发型（低血护盾、DoT 死亡返资源等），
`trigger: PASSIVE`，`/sm bind` 会拒绝并提示。P0 不实现被动触发逻辑，留给 P1。

## 已知限制

| 项 | 说明 |
|---|---|
| 被动触发未实现 | 5 个 `PASSIVE` 技能目前只能由 MythicMobs 自己触发，插件不驱动 |
| 资源与 AuraSkills mana 分离 | 见上 |
| 双资源职业只有主资源可用 | 刺客 `combo`、元素法师 `resonance` 是 `reserved` 字段 |
| 装备限制只作文档约定 | `equipment.allowed/forbidden` 不拦截，P1 做 |
| 等级 / 经验 / 专精 | 字段已持久化，逻辑留 P1 |
| 法术攻击 / 治疗强度 / 护甲穿透 / 格挡 / 冷却缩减 / 抗暴 | AuraSkills 没有这些属性，`stats:` 里写会被 WARN 并忽略 |
| 绑定按槽位 + 材质 | 换武器不触发；同槽位换回该材质会恢复 |

## 目录结构

```
src/main/java/io/sakurammo/
├── SakuraMMOPlugin.java        插件主类 / 生命周期 / 访问器
├── classsystem/ClassManager    职业选择切换 / 修饰符应用 / 技能权限发放
├── command/SakuraCommand       /sm 全部子命令 + Tab 补全
├── config/                     config.yml / classes / skills 的强类型视图
├── event/                      ClassChangeEvent / ResourceGainEvent
├── hook/                       AuraSkillsHook / MythicMobsHook / Vault* / Placeholder*
├── hud/HudService              ActionBar 资源条
├── listener/                   玩家生命周期 / 右键施法 / 战斗资源增益
├── player/                     PlayerProfile / PlayerDataManager（原子写）
├── resource/ResourceService    资源唯一入口
├── skill/SkillService          绑定 / 五项校验 / 调 MythicMobs
└── util/                       Msg / Text / TimeFormat
```
