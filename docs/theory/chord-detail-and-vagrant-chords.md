# 和弦音响、多重解释与详情选择设计

> 状态：✅ R4A、R4B 已实施。
>
> Roadmap：[chord-detail-roadmap.md](chord-detail-roadmap.md)  
> 数据模型：[../data_model/chord-construction-and-interpretation.md](../data_model/chord-construction-and-interpretation.md)  
> 自由练习存储：[../data_model/free-practice.md](../data_model/free-practice.md)

## 1. 决策摘要

1. 和弦列举仍由 `ChordSelectionCatalog` 按现有门类、符号和家族投影生成；减七仍可压缩为三个选择卡。
2. 用户点击任何和弦后，立即以自由解释写入时间轴，并按实际可听 pitch-class 集合查询全部章节解释；
   普通和弦与游移和弦不再分流。
3. 选中门类对应的解释排在最前，其余解释按统一章节顺序排列；UI 不维护第二份顺序常量。
4. 章节显式声明 `ChordExplanationId`。同一类解释只显示一次，其下展开多个精确构造线路。
5. 用户可以锁定一条线路，也可以只固定音响而不锁定解释；两者都能应用到时间轴。
6. pitch-class 相同只建立“可互查”关系，不合并拼写敏感的 `SonorityId`、解释、规则或章节身份。
7. 求解时每个候选解释独立展开，禁止把多个解释的规则取并集。
8. “替代某功能”只来自显式 treatment 关系；进行规则可以校验或提供去向，不能反向生成百科结论。
9. 构造文字与五线谱消费同一 typed tone 列表；首版实现减七的“完整属降九减去根音”。

## 2. R4A 实施复核

R4A 已统一音响发现、解释归并、自由写入和线路锁定，但详情内容链仍有四个断点：

- `ChordDetailDefinition.function/voiceLeading` 在 `ChordKnowledgeCatalog` 归并时未进入
  `ChordExplanationDefinition`，因此 mapper 无法展示基础功能与替代关系。
- `ConstructionOperation` 的 tone/memberCount 参数存在，但 mapper 只按子类型输出固定操作名；
  `ImpliedToneDetail` 也未参与构造句，无法形成“哪个基础和弦省略哪个成员”。
- `ROOTLESS_DOMINANT_NINTH.substitutesFor = DIATONIC_DOMINANT` 已是明确语义，却只用于规则组合，
  详情没有解析它。当前测试只断言 section 非空，没有守护具体功能与构造内容。
- 拿坡里虽已标为 `PREDOMINANT`，并有进入终止四六/直属和弦的规则，但尚无知识贡献和显式
  “替代属前” treatment；若从后继规则反推，会把语境性连接误当成无条件身份。

R4B 只补详情语义和呈现，不改变 R4A 的发现、排序、持久化与求解分支。

## 3. 四条身份轴

```kotlin
@JvmInline value class AudibleSonorityKey(val value: String)
@JvmInline value class ChordExplanationId(val value: String)
@JvmInline value class ConstructionRouteId(val value: String)
@JvmInline value class ChordCatalogCategoryId(val value: String)

data class ChordSelectionOriginRef(
    val categoryId: ChordCatalogCategoryId,
    val choiceId: ChordSelectionId,
)

data class ChordInterpretationRef(
    val sonorityId: SonorityId,
    val interpretationId: InterpretationId,
)
```

| 身份 | 比较内容 | 用途 |
|------|----------|------|
| `ChordSelectionOriginRef` | 用户点击的门类和卡片 | 首选解释排序、恢复选择器显示 |
| `AudibleSonorityKey` | 排序后的不重复 pitch classes | 跨拼写、跨章节发现同音响解释 |
| `ChordExplanationId` | 章节声明的解释类别 | 把同类说明归并为一张解释卡 |
| `ConstructionRouteId` | 具体来源、目标音级和构造操作 | 解释卡内区分多条路线 |
| `ChordInterpretationRef` | 拼写音响 + 精确功能解释 | 锁定规则、求解和线路结果 |

`AudibleSonorityKey` 忽略根音概念、等音拼写、转位和重复音，只用于发现与未锁定选择。它不能替代
`SpelledToneSetKey`；`ChordCatalogCollector` 继续按拼写归集。德国增六与属七可以互相出现在解释列表，
但仍是两个独立 `SonorityId` 和两套章节语义。

现有 `VagrantChordFamilyId + SoundingClassId` 继续负责“选择器内部如何压缩卡片”。它不再承担详情
查询身份，也不限制哪些和弦能够发现多重解释。

## 4. 统一目录快照与数据流

```text
chapter recipe / vocabulary
        │
        ├─► ChordSelectionCatalog ─► 原有门类与选择卡
        │                 │
        │                 └─ selected origin + AudibleSonorityKey
        │                                      │
        ├─► ChordInterpretationDiscoveryIndex ◄┘
        │          └─ 同音响的全部精确解释引用与章节来源
        │
        └─► ChordKnowledgeCatalog
                   └─ 按 ChordExplanationId 归并详情与路线
                                      │
                                      ▼
                              ChordDetailModel
                                      │
                     ┌────────────────┴───────────────┐
                     ▼                                ▼
          后续锁定具体路线                  点击时默认写入自由解释
```

同一调性上下文应构建一个不可变 `ChordCatalogSnapshot`，一次性承载选择分组、音响反向索引和知识索引，
避免三个目录重复执行章节构造。快照在后台生成，Compose 只按引用 identity 消费。

`ChordInterpretationDiscoveryIndex` 从全部 `ChordCatalogContribution` 的构造结果建立，而不是从
“已有详情”反推；尚未补完百科内容的合法解释也必须能被发现。知识目录只负责丰富说明和分组。

## 5. 查询、归并与排序

### 5.1 查询

```kotlin
data class SoundingInterpretationQuery(
    val audibleKey: AudibleSonorityKey,
    val selectedOrigin: ChordSelectionOriginRef?,
    val pinnedInterpretationRef: ChordInterpretationRef? = null,
)
```

查询步骤：

1. 从用户点击的 choice 取得 `AudibleSonorityKey`，不使用显示符号反查。
2. 在全局反向索引中取得当前调性上下文里所有同 pitch-class 集合的解释。
3. 对重复贡献的同一个 `ChordInterpretationRef` 去重，同时保留它出现过的 category/chapter 集合。
4. 用知识目录将解释映射到显式 `ChordExplanationId`；缺少知识时返回可见 fallback，不静默丢弃。
5. 对解释组和组内线路做稳定排序。

### 5.2 排序

章节必须共享单一 `ChordChapterDescriptor(id, order)`；选择 category 只保存章节引用和章内顺序。
排序键为：

```text
是否属于 selectedOrigin.categoryId（是者优先）
→ chapter.order
→ category.orderWithinChapter
→ explanation.orderWithinChapter
→ explanationId
```

同一解释组中，用户所选门类产生的路线优先，其余按章节声明的 `routeOrder` 和稳定 route id 排列。
“排到最前”只表达用户入口，不代表系统已锁定该解释。

## 6. 同类解释与多路线

公共说明的所有者从精确 interpretation 上移到解释组；基础功能也必须随组保留，具体替代目标属于线路：

```kotlin
data class ChordExplanationDefinition(
    val id: ChordExplanationId,
    val chapter: ChordChapterDescriptor,
    val sourceCategoryIds: Set<ChordCatalogCategoryId>,
    val orderWithinChapter: Int,
    val summary: ChordSummary,
    val structure: ChordStructureDetail,
    val function: ChordFunctionDetail,
    val routes: List<ConstructionRoute>,
    val sourceRefs: List<TheorySourceRef>,
)

data class ConstructionRoute(
    val id: ConstructionRouteId,
    val interpretationRef: ChordInterpretationRef,
    val routeOrder: Int,
    val functionRelations: List<ChordFunctionRelation>,
    val construction: ChordConstructionDetail,
    val voiceLeading: ChordVoiceLeadingDetail,
)
```

不得用 quality、显示名称或“首条解释”推断分组；`ChordExplanationId` 由章节显式声明。目录校验同 id 的
公共说明与基础功能一致、route id 全局唯一、每条 route 的精确解释与音响查询键相符。关系校验另要求
`SubstitutesFor.targetTreatmentId` 出现在该线路 treatment 解析后的 `substitutionTargets` 中。

减七示例：同一个 pitch-class 集合只显示一张“省略根音的属降九”解释卡；不同省略根音、目标音级、
局部导音和进入领域成为卡片内的路线。若未来章节提供真正不同的理论解释，则使用另一个
`ChordExplanationId`，而不是塞进同一组。

C 大调中指向 I 的线路声明完整公式 `G–B–D–F–A♭`，其相对音级为 `5-7-2-4-b6`，并把 G/5 标为
`OMITTED`；实际 sonority 必须恰为其余四音。属九基础定义先拥有名称与 `V9`，减七 route 引用后由
mapper 生成“属九和弦V9 (…) 省略根音 (5)”
和谱例，禁止章节再提供一份拼好的显示字符串。

后续 variant 按理论来源分别建模：中古调式派生携带临时主音、调式、上/下行路径、完整七音级与
和弦音标记；小下属关系携带同主音/下属音自然小调来源、调号、当前大调共同功能参照与借入和弦：
同主音小调显示本调属和弦，下属小调显示本调主和弦。
同一借入和弦若同时属于两套自然小调，保留两条构造线路。增六使用
`AugmentedSixthDerivation` 携带构造前音列、增六音列、增六两端、和弦类别与共同解决音；mapper 从同一
DTO 生成公式与单谱表事件。`ø+6` 与普通半减七共享可听音响，但解释、路线和选择身份分离。
未实现的 variant 不返回占位 DTO，也不降级成 `OmittedFromFormula` 或 `LegacyTrace` 的展示字符串。

## 7. 应用意图与自由解释

自由练习当前槽位不再把“选择音响”等同于“锁定解释”：

```kotlin
@Serializable
data class WorkspaceChordChoice(
    val pitchClasses: List<Int>,
    val origin: ChordSelectionOriginRef? = null,
    val pinnedInterpretationRef: ChordInterpretationRef? = null,
)
```

- `pitchClasses` 是排序、去重且位于 0..11 的存储源字段；运行时由它计算 `AudibleSonorityKey`。
- **锁定路线**：写入 `pinnedInterpretationRef`；拼写、功能角色和规则都由该解释决定。
- **自由应用**：该字段为 `null`；时间轴仍保存并显示所选音响，候选解释由当前目录快照解析。
- `origin` 是用户意图和显示排序，不是音乐规则身份；迁移文件无法恢复时可以为空。
- 若锁定引用的 pitch classes 与存储音响不同，命令必须拒绝。

“自由”不等于把所有规则关闭或合并。求解器把候选解释分别展开为独立搜索分支，最终计算帧携带本次
采用的 `effectiveInterpretationRef`；分析界面可以同时报告其他可行读法。系统不得为了提交成功选择
列表第一项，也不得把互相冲突的解释规则取并集。

## 8. 统一交互

所有选择卡走同一状态机：

```text
选择门类中的和弦
  → 立即以 pinnedInterpretationRef = null 写入当前时间轴槽位
  → 同时展示按实际音响发现的解释组
  → 可浏览路线而不修改时间轴
  → [应用所选解释] 时以 pinnedInterpretationRef 替换同一槽位
```

- 点击任何和弦都会立即产生一次可撤销的自由解释插入/替换，满足快速试验进行的需要。
- 面板首项是用户所选门类的读法；副属七和弦可以同时看到等音的德国增六解释。
- 每张解释卡只展示一次公共性质；全部 route chip 保持可见，但只展开 `focusedRouteId` 的详细内容，
  避免在窄栏重复多份谱例。首次聚焦排序第一条线路不等于锁定。
- 浏览或高亮 route 仍是 UI 瞬态；只有“应用所选解释”才创建第二次可撤销替换。
- 自由解释显示为默认选项；已锁定解释可通过“恢复自由解释”解除锁定，这同样是一次可撤销替换。
- 再次点击任一和弦卡（包括当前卡）按新选择重新写入自由解释，不沿用旧线路锁定。
- 缺少章节详情时仍允许自由应用，并显示“该解释尚无章节详情”；UI 不硬编码百科内容。

线路展开区沿用 prototype 的信息节奏，并把新内容放在倾向音之前：

```text
[解释名称] [基础功能]   [线路：指向 I｜指向 V…]
功能关系   属功能；替代属和弦 / 指向 x 级的副属和弦
构造线路   属九和弦V9 (5-7-2-4-b6) 省略根音 (5)
           [单谱表结构示意：省略音灰色并带“省略”图例]
倾向音 / 常见去向 / 来源
```

mapper 把 `ChordConstructionTone` 转成中性的谱例 DTO，文字和谱例仍来自同一 tone 列表。
`ChordDetailPanel` 位于 `apps/desktop-ui-kit`，只提供宿主 composable slot；desktop 宿主建立单和弦
`StorageScore → RuntimeScore`，交给调号按钮同源的 `SimpleScoreView/ScoreRenderer`。mapper 按公式顺序展开
为上行音列，不改拼写或成员顺序；`OMITTED` 音通过 `VoiceNoteSection` 样式将符头与变音记号降为灰色。
双和弦关系谱例联合选择两个和弦的八度：先限制额外加线，再缩小两者音区距离，最后偏向谱表中央；
禁止分别选取各自最居中的八度而造成上下错层。
UI 与 renderer 都不判断减七、拿坡里、属七或增六，文字图例同时说明灰色语义。

## 9. 章节与宿主职责

- **章节**：构造本体、精确解释、`ChordExplanationId`、route、功能关系、typed rules 和来源。
- **选择目录**：保留现有门类列举与家族内卡片投影，额外输出 origin 和 audible key。
- **发现索引**：只做跨贡献 pitch-class 查找和精确引用去重，不生成理论结论。
- **treatment registry**：展开引用/替代闭包，为 route 声明提供授权校验，不负责生成显示文字。
- **知识目录**：校验解释组、替代目标和构造音集合，组装公共详情、线路详情与缺失知识诊断。
- **工作区**：保存 `WorkspaceChordChoice`，执行原子命令与撤销，不保存教材正文。
- **求解/分析**：把未锁定音响展开为互斥解释分支，发布有效解释，不修改存储意图。
- **UI**：本地化并排版 DTO，不解析 raw trace、rule 文案、符号或 quality 决定语义。

增六章节接入后，德国增六与属七通过 `AudibleSonorityKey` 互相可见；两者仍由各自章节贡献不同
解释组、拼写、倾向音和连接规则。增三、减七继续保留选择器内的对称音响压缩。

## 10. R4B 实施边界

R4A 已完成 schema v5；R4B 的新字段都由章节知识在运行时生成，不进入 `WorkspaceChordChoice`，因此不升
schema。已按 common model/registry 校验 → 减七 route/golden → catalog 聚合 → mapper/UI DTO → renderer
谱例 → 拿坡里专属 treatment/知识贡献完成；固定的无参数构造操作文案不再作为 R4B 构造说明。

已实现 `OmittedFromFormula`、`ModalScaleDegrees`、`MinorSubdominantRelation` 与
`AugmentedSixthDerivation`；禁止为了“通用”而把新构造伪装成其他 variant。谱例建立一次性的单和弦 RuntimeScore，先选择
加线最少的连续八度再走 Renderer；它不写入工作区、不参与四部排列，Renderer 也不生成和声语义。

## 11. 验收不变量

1. 现有和弦列举的门类、卡片数量和显示顺序不回归；减七仍为三个音响卡。
2. 任意 choice 都能按 audible key 发现跨章节、等音异写解释。
3. 用户门类优先，其余严格按共享章节顺序；provider 发现顺序不影响结果。
4. 同一 `ChordExplanationId` 只显示一次，路线集合完整且无重复。
5. 点击 choice 立即以自由解释更新时轴；锁定/解除锁定线路均能保存、读取、撤销和重做。
6. 未锁定求解逐解释分支，结果与逐个锁定后求解的并集等价，不出现规则并集污染。
7. `ChordCatalogCollector`、原有 typed rules、综合练习和禁忌表语义不因发现索引改变。
8. 减七每条线路的替代目标均通过 treatment registry 校验；主属与副属文案由 target degree 动态变化。
9. C 大调指向 I 的减七标题与谱例共享 `5-7-2-4-b6`，仅 5/G 为 `OMITTED`，其余四音等于 sonority。
10. 拿坡里只在专属 treatment 注册后显示“替代属前”；其他 `MINOR_SUBDOMINANT` 解释不被连带标记。
11. route 聚焦不修改工作区；只有应用/解除锁定产生历史事务。
12. UI 源码无和弦家族分支；路线是无外层卡片嵌套的单层可选面，100%/150% 缩放和窄栏中谱例不越界。
