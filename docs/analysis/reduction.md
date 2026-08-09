# 缩谱与音符映射（Reduction & NoteMapping）🚧 设计

> 状态：**A0/A1 与分层工作区第一阶段已实现，节奏改写与高级标记仍为 🚧**。总览见 [README.md](README.md)。
> 覆盖 [todos/polyphony.md](../../todos/polyphony.md) "智能缩谱"与"和弦与复调"两节。

## 1. 定位与需求

复调、四部和声、动机分析都要求**单音声部线条**，而总谱表面存在三类偏离：
`RuntimePitchTrack` 单事件多 pitch（复音）、同一旋律由多乐器轮流/齐奏/八度重复演奏、
和声被加密加厚。缩谱把总谱的一个时间段还原为若干单音声部，并通过**内容线记录逐音符映射关系**——
映射既服务分析（合并、还原、外音定位），也服务创作（对照、配器替换，见
[composition.md](composition.md)）。

需求要点（源自 polyphony todo）：

- 缩谱可先于总谱创作，timeCode 不必与总谱对应；
- 可建多份缩谱，时间段允许重叠，互相独立；
- 映射分两段：`缩谱 ↔ 内容线` 与 `内容线 ↔ 总谱书面音符`。演奏者和谱表变更只改
  内容线的 performance / assignment，不让缩谱直接依赖某条总谱谱表；
- 两段映射都可多对多：一条内容线可由多名演奏者演奏，也可在多个谱表形成书面表示；
- 映射后任一侧修改、或不完全重合的旋律，要在总谱上标出不一致与未映射音符。

## 2. 数据模型（Storage 层）

新顶层字段 `StorageScore.reductions`，遵循存储层原则（只含源字段与 ID 引用）：

```kotlin
@Serializable
data class StorageReduction(
    val id: ReductionId,                    // @JvmInline value class
    val title: String,
    val anchor: ReductionAnchor? = null,    // null = 独立缩谱（先创作、尚未挂到总谱）
    val scope: Set<TrackId> = emptySet(),   // legacy 兼容字段；新流程由 orchestration.lines 定位
    val template: ReductionTemplate,        // 声部约束模板
    val layers: List<StorageReductionLayer>,// 固定语义顺序的共享 TimeCode 层
    val materialTray: List<StorageScoreFragment> = emptyList(),
    val links: List<StorageNoteLink> = emptyList(),
)

@Serializable
data class ReductionAnchor(
    val sourceStart: TimeCode,              // 总谱侧起点；缩谱 0:0 对齐到这里
    val sourceEnd: TimeCode,                // 半开区间 [start, end)
)

@Serializable
enum class ReductionTemplate {
    MONOPHONIC_VOICES,   // 每声部单音（复调/动机分析前提，FixedVoiceScore 可加载）
    SATB,                // 大谱表 SATB（四部和声分析；亦满足单音约束）
    FREE,                // 无约束（自由缩谱/草稿）
}

@Serializable
data class StorageNoteLink(
    val source: NoteRef,                    // 主文档 orchestration 内容线侧
    val target: NoteRef,                    // 缩谱 NOTATION 层侧
    val octaveShift: Int = 0,               // source 实际音高 = target + 12 * octaveShift
)

@Serializable
data class NoteRef(val eventId: EventId, val pitchIndex: Int)
```

- `NOTATION` / `SKELETON` 层以 `StorageReductionLayer.score` 复用完整乐谱数据管线；
  曲式/和声使用同层的 `timelineItems`，配器层标签从顶层 orchestration 派生。
- `StorageScoreFragment.score` 使用片段局部 TimeCode；`sourceMetadata.originalRange` 仅供
  显示来源，不是作品中的放置锚点。第一阶段写入后复制为独立内容，不记录放置关系。
- `NoteRef.eventId` 用 **voice 事件 id + pitchIndex**，与 `VoiceNoteSection` /
  `NoteStyleProvider` 的键约定一致，着色与选中可直连。
- **多对多由链接集合自然表达**。`StorageReduction.links` 只负责内容线↔缩谱；
  `StorageOrchestration.links` 负责同一内容线↔总谱书面音符。两者以内容线 `NoteRef`
  串成完整来源链，避免缩谱直接绑死某位演奏者或某条谱表。
- `template` 在编辑提交时校验（`MONOPHONIC_VOICES` / `SATB` 复用 `FixedVoiceScore.load`
  的单声部单音校验）；违反时拒绝提交并提示，与现有编辑约束一致。
- 缩谱记谱层嵌套完整 `StorageScore` 会带来文件体积与 diff 问题——与 exploration
  document-model §7 同题，先接受，必要时共享事件池。

## 3. 时间对齐（v1）

- 挂载缩谱（`anchor != null`）时，映射时间轴为**线性平移**：缩谱 `0:0` ↔
  `anchor.sourceStart`，逐小节逐拍对应。创建缩谱时从总谱区间复制拍号/调号事件，
  v1 要求区间内拍号序列一致，不一致时拒绝挂载并提示。
- link 级检查不依赖全局对齐之外的假设，因此未来"分解和弦 → 柱式和弦"这类**节奏改写
  映射**只需放宽 onset 检查为窗口匹配（🚧 v2，polyphony todo 已列），数据模型不变。
- 独立缩谱（`anchor == null`）不做一致性检查，仅作为草稿存在；挂载操作补 anchor
  后进入正常检查（挂载与"实现到总谱"的交互见 [composition.md](composition.md) §3）。

## 4. 一致性与偏离

一致性是**派生数据**（Computed 层按需计算，不入存储），逐 link 与逐音符两级：

**link 级状态**：

| 状态 | 判定 |
|------|------|
| `OK` | 音高（模八度位移）、映射后 onset、时值均一致 |
| `PITCH_DIVERGED` | `pitch(source) != pitch(target) + 12 * octaveShift` |
| `TIME_DIVERGED` | onset（经 anchor 平移）或时值不一致 |
| `DANGLING` | 任一端事件/音高已被删除 |

**音符级状态**（在 anchor 范围与 scope 内扫描）：

| 状态 | 侧 | 含义 |
|------|-----|------|
| `UNMAPPED` | 内容线 | 内容线音符无缩谱 link——分析方向的"漏网之鱼" |
| `UNREALIZED` | 缩谱 | 缩谱音符无内容线 link——创作方向的"待绑定材料" |

实现要点：

- 计算方式沿用 `ChordToneAnalysis` 配方：`CalcBuilder` / `ReferenceAligner` 增量对齐 +
  `NoteStyleProvider` 着色（总谱侧：偏离橙色、未映射灰边；缩谱侧：UNREALIZED 虚框），
  编辑后只重算受影响区间。
- 偏离**不阻止编辑、不自动修复**——总谱可以有意偏离缩谱（装饰、变奏）。用户操作：
  "重新对齐 link"（按当前谱面更新 octaveShift / 删除失效 link）或"接受偏离"
  （标记 intentional 🚧 v2）。哲学与 exploration 的"输出过期 + 手动重跑"一致：
  不一致可见，解决时机由用户掌握。
- 不需要 fingerprint——逐音检查比整体指纹更细粒度，天然给出"哪里不一致"。

## 5. 提取交互（分析方向）

1. **创建缩谱**：创建时只选名称、固定行数和谱号。自动复制完整 TimeCode / 拍号 /
   调号结构，`links` 保持为空；创建步骤不询问演奏者、谱表或映射关系。
2. **缩谱到总谱**：初始可操作全部声部；需要隔离复音时，用每个系统谱表行开头的声部
   按钮确定唯一活动声部，再次点击该按钮可恢复全部声部；随后框选缩谱音符并点击
   缩谱上方两行标签中的演奏者编号。系统复用或建立隐藏内容线，写
   `StorageReduction.links`（内容线↔缩谱）、performance / assignment，随后立即生成
   书面音符和 `StorageOrchestration.links`，不再打开绑定表单。内容线同时作为同步组：
   选中组内任一音符后再次点击已有演奏者会取消，点击其他演奏者会加入；最后一位取消时
   删除内容线及链接，保留两侧可见音符。
3. **总谱到缩谱**：先用谱表前的演奏者按钮确定活动演奏者，框选总谱音符，再点击总谱
   上方两行标签中的目标缩谱声部。系统先提取/并入隐藏内容线，再把内容线映射到明确的
   `targetReductionStaffId + targetReductionVoiceId`。
   当前逐音保留所选事件的全部 pitch；更细的复音拆分后续提供三策略：
   - 一律取第 n 个 pitch（n 用户指定）；
   - 若各事件 pitch 数相同（均为 n），直接拆成 n 个声部；
   - 逐一指定（谱面上依次高亮待定事件，点选 pitch）。
   写入缩谱音符 + 逐音 link（一对一，octaveShift 取整体最近八度）。
4. **多对一合并**：先做一对一映射，再选中总谱另一段旋律 →"映射到已有内容"：
   程序按（模八度）音高与对齐 onset 逐音匹配缩谱声部，完全重合直接建 link；
   不完全重合也允许映射，未重合音自然落为 `PITCH_DIVERGED` / `UNMAPPED` 标记。
   另提供**自动检测**：扫描范围内未映射旋律中与缩谱已有声部完全重合者，批量建议。
5. **拆分同度**：对已映射音符再次映射到另一声部即形成第二条 link，无专门交互。

映射操作与缩谱音符编辑一样走主文档撤销栈（§6），可整段撤销。

同步组不对音符着色：同一和弦的符头背景合并为圆角色块，同组相邻事件在同一谱表行内由
窄 S 形色带连接；不同组稳定轮换低饱和青、蓝、琥珀、紫、珊瑚、绿等颜色。缩谱插入
音符后以默认钢琴试听。

## 6. 编辑宿主与撤销

缩谱是主文档的嵌套字段，编辑走 **lens 式提交**：缩谱编辑器持有
`ReductionScoreHost`，读取 `score.reductions[id].layers[active].score`，提交时包一层
`outer.copy(reductions = …)` 走主 `ScoreStateManager.commitNewState`——
**单一撤销栈**覆盖总谱、缩谱与映射操作（对比：探索模式 per-cell 独立栈，因其 cell
互相独立；缩谱与总谱强关联，跨层操作必须原子撤销）。

渲染沿用 exploration ui-interaction §4.3 的 per-score 管线：每份打开的缩谱一条
`compute → layout → render` 管线，缓存以 reduction 内容 + 宽度为键。交互作用目标
经 `ActiveScoreContext`（exploration E2 重构）切换，缩谱面板/对照视图即其第二个消费方。

## 7. 和弦与外音接入

- **和弦轨挂缩谱**：`mecon.chord_analysis` 插件轨随 `StorageScore` 存在，缩谱
  （本身是 StorageScore）天然可挂和弦轨。和弦音/外音着色（`ChordToneStyleProvider`）
  直接生效；经映射投影，总谱侧也可按缩谱和弦轨着色（八度位移后判定隶属）。
- **外音类型标注**：缩谱满足 `MONOPHONIC_VOICES` 时可执行
  `FigurationAnalysis.reduce`（figuration §6），产出骨架 + 每个外音的
  `NctClassification`。声部进行明确，分类歧义显著低于表面直接分析——这正是
  缩谱作为中间层的价值。分类结果以 INDICATION finding 呈现（p / n / s / app / …），
  锚点在缩谱音符，经 link 投影到总谱。
- **临时和弦**：乐理引擎遍历缩谱每个纵向时刻，在已标记和弦之间检测"外音与其他
  和弦音组成的临时和弦"（`ChordRecognizer` 对纵向音集识别，且与前后已标记和弦不同）。
  结果写入和弦轨的**次级和弦事件**（新字段 `provisional: Boolean` 或独立
  `@SerialName`），渲染加括号/灰色与已标记和弦区分。

## 8. 开放问题

- 缩谱内嵌 `StorageScore` 的体积与 diff（与 exploration 同题）；事件池共享的时机。
- 节奏改写映射（分解 → 柱式）的 onset 窗口语义与 UI（v2）。
- `UNMAPPED` 的豁免粒度：打击乐、持续音踏板等"有意不进缩谱"的内容，scope 按
  staffTrack 排除是否足够，还是需要音符级豁免标记。
- 映射建议的自动化程度：完全重合检测之外，八度重复/近似重合的模糊匹配阈值。
- 多份缩谱重叠时间段时，总谱侧着色以哪份为准（当前倾向：以"活动缩谱"为准，
  面板切换）。
