# 大层级标注：曲式与调性布局（Form & Key Plan）🚧 设计

> 状态：**设计阶段，未实现**。总览见 [README.md](README.md)。
> 大层级分析**直接在总谱上画**——不提取片段，标注以时间区间挂在整份乐谱上。

## 1. 定位

曲式（乐句 / 乐段 / 部 / 乐章的嵌套分段）与调性布局（各段落的调性区、转调/离调）
是俯瞰全曲的分析层。交互形态是"选区 → 标记"，数据形态是**插件标注轨的 span 事件**，
渲染形态是乐谱上下方的横向括号与文字——全部复用
[custom-track](../plugin/custom-track.md) 配方，新增 span 类注释元素（§3）。

## 2. 数据模型（`mecon.form` 插件轨）

```kotlin
@Serializable @SerialName("mecon.form.section")
data class StorageFormSection(
    override val id: EventId,
    override val onset: TimeCode,
    val endOnset: TimeCode,              // 半开区间 [onset, endOnset)
    val label: String,                   // "A" / "呈示部" / "第一乐句"，自由文本
    val level: FormLevel,
) : StoragePluginEvent()

@Serializable
enum class FormLevel { MOVEMENT, PART, PERIOD, PHRASE }   // 由大到小，可嵌套

@Serializable @SerialName("mecon.form.keyArea")
data class StorageKeyArea(
    override val id: EventId,
    override val onset: TimeCode,
    val endOnset: TimeCode,
    val tonic: PitchClass,
    val mode: KeySignatureMode,          // MAJOR / MINOR（theory 既有类型的 spec 映射）
    val kind: KeyAreaKind,               // ESTABLISHED（成立的调性区）/ TONICIZATION（离调）
) : StoragePluginEvent()
```

- 嵌套树由 `level` + 区间包含关系推导，不存 parentId；写入时校验同 level 区间不重叠、
  低 level 区间不跨越高 level 边界（违反给出诊断，不静默修正）。
- `StorageKeyArea` 与调号事件独立：调号是记谱事实，调性区是分析判断
  （同一调号下可有多个调性区，离调不改调号）。
- 曲式标签不做受限词汇表——曲式术语流派差异大，v1 自由文本 + `level` 结构化；
  终止式类型等受限枚举由 `ConnectionType` / `WritingTarget.Cadence`（theory 既有预留）
  在检测层使用，不进标注存储。

## 3. 渲染：span 注释元素

现有 `AnnotationElement.Text` 只支持单时间点。新增：

```kotlin
AnnotationElement.Span(
    startTime: TimeCode, endTime: TimeCode,
    text: String,
    relativeY: Float,                    // level → 行分层（MOVEMENT 最外层）
    sourceEventId: EventId,
    style: SpanStyle,                    // 括号 / 底色带 / 虚线，按 level 与 kind 区分
)
```

- 布局由 `AnnotationStaffLayoutComputer` 统一落实坐标；跨行（system）span 的断开
  与延续记号复用 hairpin / 8va 的跨行处理经验。
- 曲式轨 anchor `AboveAllStaves`，调性布局轨在其下一行；两轨独立开关。
- 分页模式下 span 跨页与跨行同法处理。

## 4. 交互

- **标记**：选择时间范围（拖选或点击小节区间）→ 面板/右键"标记乐段"/"标记调性区"，
  填 label 与 level；边界拖动吸附小节线（PHRASE 允许吸附拍）。
- **撤销**：手动曲式标注是用户数据，应进撤销栈——但现有
  `updatePluginTrackState` 明确**不进**撤销栈（面向自动分析结果，见
  [state-management](../state-management.md)）。需要新增"用户编辑语义"的插件轨
  写入路径（进栈），自动检测建议仍走不进栈路径。这是本设计对状态管理的唯一扩展点，
  实施前先在 state-management.md 定稿。
- **导航**：曲式标注兼作大纲——面板列出分段树，点击跳转滚动位置；
  这是"复杂总谱俯瞰"的主要收益之一。

## 5. 辅助检测（建议 → 确认）

自动检测只产出 `confirmed = false` 的建议标注（灰显），用户确认后转正——与
[motive.md](motive.md) §5、ai/roadmap "建议轨道 + 用户确认"同一模式：

- **终止式识别**：管线 = 缩谱 → `FigurationAnalysis.reduce` 骨架 → 功能序列 →
  终止式（figuration §9 既定路线）。终止点是 PHRASE / PERIOD 边界的最强信号。
- **调性区建议**：滑窗统计音级集合 + `NaturalTriads.possibleKeys` + 调号/临时记号
  变化点，产出候选调性区；离调（TONICIZATION）由副属和弦检测触发。
- **动机线索**：新动机引入点、occurrence 密度变化辅助分段建议（消费
  [motive.md](motive.md) occurrence 数据，弱信号，只调权重不单独产出建议）。
- 检测范围默认全曲，也可限某段（对超长总谱分段跑）。检测依赖缩谱覆盖度：
  无缩谱的时间段跳过终止式检测，只给调性区建议，并在结果中说明覆盖缺口。

## 6. 开放问题

- span 跨行断开的视觉细节（延续侧是否重复 label）；
- 同 level 标注重叠的例外（过渡段横跨两部的分析观点）是否需要"软边界"表达；
- `FormLevel` 四级是否足够（超小节层 hypermeter 归 figuration §9 的 BeatWeight
  扩展，不进本轨）；
- 插件轨用户编辑语义（进撤销栈）落地方式：新 API 还是给 `updatePluginTrackState`
  加 flag。
