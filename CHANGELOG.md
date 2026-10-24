# SakuraMMO 插件 · 版本变更记录

## v4.0.4（2026-10-25）

- **掉落控制**：副本世界外生物死亡无掉落物/无经验球（玩家死亡保留）；副本内保留并对 RPGLoot 英文词条物品自动汉化（RpgLootTranslator：稀有度/词条/套装词映射；随机生成名保留）
- `/sm admin translatex`：手持物品 RPGLoot 词条汉化（测试/修正两用）
- 术士小鬼 AI：AITargetSelectors=attackers+monsters（打怪/反击，不打玩家）

## v4.0.3（2026-10-25）

- 菜单全中文化：Text.statCN/attrCN 映射（9 属性 + 6 词条），替换职业面板/职业选择//sm class info/升格继承/兑换预览/看板职业分布共 7 处英文 id 显示
- **messages 文件键级重建**：消除历史追加造成的重复顶层段（talent/craft/economy 各两份，后段覆盖前段吃键）；补 craft.goto-* 与 economy 两键；新增消息键完整性扫描（源码引用 vs 定义比对 = NONE）

## v4.0.2（2026-10-25）

- 主菜单加制造入口（四入口：职业/周常/制造/副本）：按钮显示金币余额与可制造配方数

## v4.0.1（2026-10-25）

- 主菜单加周常入口（职业 / 周常 / 副本三入口）：按钮显示可领取任务数，点击直达周常菜单

## v4.0.0（2026-10-24）—— 阶段四

- **团本**：`/sm raid`（复用队伍系统 20 人上限覆盖）、`raid_blackiron.yml`（mode: raid，三 Boss 同场全灭通关）、并发限制 `dungeon.max-concurrent: 3`、通关代币奖励
- **周常**：`weeklies.yml` 5 任务（击杀/通关/制造/开箱），**Cycle-ID 零遍历重置**（周一 04:00 界），`/sm weekly` GUI 领奖（金币+代币+底材）
- **追赶**：`upgrade.yml` 升格（旧装备词条全继承）/ 代币兑换保底装备；`/sm upgrade|redeem` GUI
- **运营**：`/sm admin` GUI（看板：在线/实例/TPS/职业分布/金币产出回收累计；全服发奖两步确认；挂起实例清理）、`/sm admin reset <player> <weekly|economy|dungeon>`
- **审计**：AuditLogger（logs/audit/ 按日滚动，异步批量刷盘）；经济 deposit/withdraw 全量审计 + 产出/回收计数器
- PAPI：`%sakurammo_tokens%`
- 修复：/sm reload 补周常/追赶/天赋/制造重载；messages 顶层段重建（22 段零重复）

> 按 `docs/03-阶段计划与验收.md` 的阶段推进；详细实测数据见 `../tests/测试记录.md`（S-001~S-024）。

## v3.0.4（2026-10-24）

- HUD `{skill_cd}` 段改为显示 **Shift+1~4 全部绑定技能**：冷却中 `⚔名字 X.Xs`（倒计时）、就绪 `⚔名字 ✓`；未绑定槽不显示

## v3.0.3（2026-10-24）

- 修复 HUD 颜色码未翻译（`&b&9法力` 字面显示）：renderOnce 发送前统一 `Text.color()`
- config format 去掉 `&b` 前缀（display 自带颜色，避免叠码）

## v3.0.2（2026-10-24）

- **HUD 两行改版（消除闪烁）**：ActionBar 单槽被施法提示与资源条交替抢占是闪烁根因；重构为单一写入者两行渲染
  - 行 1 施法行：施法成功/失败提示独立计时淡出（2s）
  - 行 2 资源行：`法力/怒气 | ❤生命 | ⚔法术CD`（新增 `{skill_cd}` 占位符，各段 `|` 分隔）
- castBound 改 `castMessage` + `recordSkillCast`；资源上限显示跟随天赋加成

## v3.0.1（2026-10-24）

- **副本底材随身带出**：背包隔离豁免带 PDC 底材标记的物品（Boss 掉落的黑铁锭/魔晶/暗核），出本随快照一起恢复；其余副本内物品仍隔离丢弃
- 出本传送点固定为 **0, 69, 0**（此前是主世界出生点）

## v3.0.0（2026-10-24）—— 阶段三

- **天赋树**：`talents.yml` 7 职业×2 分支×3 节点（42 节点）；角色等级 1-60（曲线 100+50×level）、Lv.10 起每 3 级 1 点（60 级 17 点）；`/sm talent` GUI 左键加点/右键减点/洗点（500 金币全额返还）；前置节点与下游依赖保护
- **天赋生效四通道**：stat→AuraSkills modifier（`talent_` 前缀，与 `class_` 互斥，聚合入口唯一）；cooldown/cost→施法折减；resource-max/regen→ResourceService 有效上限；skill-damage→施法窗口补差（**MythicMobs `ignorearmor` 技能事件 `setDamage` 无效**——MM 取消事件并原始直扣，MONITOR 补差 + 取消事件补偿 + 递归护栏）
- **经验来源**：击杀怪（15exp+2~6 金币）、副本 Boss（120/180/300exp + 金币）
- **制造**：`recipes.yml`（黑铁锭/魔晶/暗核底材 + 3 配方）；底材产自副本 Boss 掉落（PDC 标记）；成品带原版 attribute modifiers + 品质色名称 + PDC 防刷标记；CraftMenu 一键制造
- **经济**：**内置 SakuraEconomy**（Vault Economy provider，余额 economy.yml 原子写；外部经济存在时自动让位）；`/sm money(+set)`、`/sm sell`、`/sm addxp`、`/sm givemat`；防刷金：成品回收 25%、底材 0 回收
- PAPI 扩展：`%sakurammo_money%`、`%sakurammo_talent_points/level/cd/cost%`、`%sakurammo_talent_mult_<skill>%`

## v2.1.2（2026-10-24）

- 副本人数 1~5：`min-members` 1；无队伍单人开本**自动创建一人队伍**，结束自动解散

## v2.1.1（2026-10-24）

- **菜单重构**：主菜单只留 职业/副本 两个入口，详情点进去显示（与副本同级同交互）；语言文件重复 `menu:` 段覆盖修复（missing message）
- `/sm menu` 改开主菜单；SkillBindMenu/DungeonMenu 返回主菜单

## v2.1.0（2026-10-24）

- **按键布局 v3**：技能槽 R/T/F/G → **Shift+1/2/3/4**（潜行+快捷栏，原版原生触发，无需模组）；F 键退役恢复为菜单键；旧绑定（Q/E/R/T、R/T/F/G）按槽位顺序自动迁移（`bind-layout`）
- **菜单集成副本**：角色面板加副本入口(31) + 新增 DungeonMenu（状态/列表开本/离开副本/返回）

## v2.0.0（2026-10-24）—— 阶段二

- **队伍**：`/sm party`（create/invite/accept/leave/kick/disband/info），5 人上限、邀请 60s、离线保留 5 分钟（副本中豁免）、parties.yml 原子持久化
- **副本实例**：模板世界自动生成+配置重写；异步复制为 `instance_<队伍id>`；背包快照隔离；三 Boss 死亡链（Boss1 门→双阀解 Boss2 无敌→Boss3 70%/35% 转阶段→奖励箱）；撤离→卸载→异步删目录；启动清理 `instance_*` 残留 + 崩溃队伍重置 IDLE
- **防复制红线**：奖励 run-id 幂等（标记→落盘→发放顺序）；强杀重启实测不重复领取、无残留世界、背包精确恢复

## v1.1.0（2026-10-24）

- 按键布局 v2（R/T/F/G）+ InputEngine 桥接反射监听（两代 API 兼容）；修复 Q 空手不发包 / E/R/T 监听缺失的「按键无效」

## v1.0.0（2026-10-24）—— 阶段一 / P0

- 7 职业（LuckPerms 组 + AuraSkills modifier `class_<id>_<stat>`）、35 技能入口、资源系统（怒气/法力，事件驱动+回复）、五项施法校验、F 键角色面板、职业选择/切换 GUI、ActionBar 资源条、PlaceholderAPI 扩展、玩家数据持久化
