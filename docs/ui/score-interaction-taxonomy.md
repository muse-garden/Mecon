# 打谱交互分类与跨设备接口

> 审计日期：2026-08-19；状态：🚧 评审决定已记录，M0 共享交互基础已开始实现。
> 本文只定义交互层。任何持久化修改仍以 `ScoreEditIntent` 进入 `ScoreEditingSession`。

## 1. 审计范围与结论

审计以这些实现为准，而不是只看工具栏按钮：

- `features/score-editing/.../ScoreEditProtocol.kt`：57 个持久化 intent；
- `features/score-editing/.../ScoreSelection.kt`：14 种稳定选区目标；
- `ScoreEditingSession.kt`：校验、历史、effect、selection 与 render hint；
- Desktop 的 `NoteToolState`、左右调板、画布选择/插入/拖动与 `ScoreSession` adapter；
- Web `web-renderer/editor` 的 command/drag controller、toolbar descriptor 与 Playwright 路径；
- [音符编辑](score-editing.md)、[键盘/MIDI](note-input.md)、[表情编辑](expression-editing.md)。

结论：领域协议已经足以作为移动端唯一提交入口，但现有按钮、手势与 intent 之间缺少统一的
“交互家族”描述。Desktop 的部分 expression/geometry 路径仍直接构造 engine result，实时 take 也不在
`ScoreEditIntent` 内；它们必须在移动实现前收敛或明确后置，移动端不得复制这些过渡路径。

现有 14 种稳定选择身份为 `Event`、`Clef`、`KeySignature`、`TimeSignature`、`Barline`、
`VoltaEnding`、`NavigationMark`、`Slur`、`Tie`、`Beam`、`Articulation`、`Attachment`、
`LayoutBreak`、`StaffVisibility`；新交互优先复用它们，不用渲染元素下标代替领域身份。

钢琴卷轴、缩谱、配器、插件面板不是普通打谱交互；除非另立共享协议，不纳入本轮移动打谱。文件兼容、
显示与选择仍必须保留，不能因手机没有编辑入口而丢失元素。

## 2. 九个交互家族

每个命令只有一个**主要家族**，可以同时声明次要入口。例如 hairpin 主要属于“区间放置”，创建后可再用
“语义手柄”和“属性表单”；这不是三套业务实现。

| ID | 家族 | 目标拓扑 | 统一交互语言 | 现有代表能力 |
|----|------|----------|--------------|--------------|
| N | 导航、选择与历史 | 0..N 个稳定对象 | 点选/框选/追加；不改谱的状态不入历史 | 选择、全选、撤销、重做 |
| E | 连续记谱 | 插入光标 + 输入流 | 先定 `staff/time/voice`，输入一次提交一次并跟随 `nextInputPosition` | 音符、休止、和弦、装饰音输入、空连音范围 |
| P | 单点放置 | 一个事件/谱表时间/边界 | 选类型 → 候选 ghost → 确认一点 → 单次提交 | 谱号、调号、拍号、力度、速度、停顿、单点装饰音 |
| S | 区间放置 | 有序起点 + 终点 | 选类型 → 定起点 → 拉到终点 → 完整区间 ghost → 单次提交 | slur、hairpin、8va、渐变速度、区间 trill |
| G | 组选区构造 | 同声部/小节内的事件组 | 显式选区 → 显示结构预览与参数 → 确认一次 | 已有材料转 tuplet、装饰音组、小音符区域 |
| T | 选区变换 | 现有选区 | 操作只作用于当前稳定选择；按钮、快捷键与表单等价 | 删除、剪贴板、移调、时值、变音、tie、符杠、声部、演奏法、琶音 |
| B | 结构边界与范围 | 小节边界或 measure range | 只吸附逻辑边界；先显示受影响范围，再确认结构重排 | 增删小节、小节线、房子、导航、换行、谱表可见性 |
| H | 语义手柄 | 已有对象的 body/start/end/control | 选中后才出现大手柄；拖动只预览，抬起一次提交；必须有 nudge | 音高/休止、slur/tie/beam、附件、房子、导航 |
| F | 属性表单 | 一个或同类多选对象 | 本地草稿；Enter/完成/失焦校验后一次提交；中间文本不改谱 | ornament、tempo、fermata/breath 时长、重复次数、几何数值 |

### 2.1 57 个 intent 的主要家族归属

| 家族 | `ScoreEditIntent` |
|------|-------------------|
| N | `SetSelection`, `Undo`, `Redo` |
| E | `InsertNote`, `InsertChord`, `CreateTupletRegion`, `PasteNotes` |
| P | `SetClef`, `SetKeySignature`, `SetTimeSignature`, `AddDynamic`, `AddTempoMark`, `AddFermata`, `AddBreathMark`, `AddOrnament` |
| S | `AddSlurs`, `AddHairpin`, `AddOctaveShift`, `AddGradualTempo` |
| G | `ApplyTuplets`, `SetGraceGroups`, `CreateSmallNoteRegions` |
| T | `CopyNotes`, `CutNotes`, `MoveVoices`, `DeleteNotes`, `TransposeNotes`, `SetDurations`, `SetAccidentals`, `SetTies`, `SetBeaming`, `ToggleArticulation`, `SetArpeggio`, `MoveRests`, `DeleteSlurs`, `DeleteExpressions` |
| B | `InsertMeasures`, `DeleteMeasures`, `SetBarline`, `SetBarlineRepeatCount`, `ToggleVoltaPair`, `DeleteVolta`, `ToggleNavigationMark`, `DeleteNavigationMark`, `SetLayoutBreak`, `SetStaffVisibility` |
| H | `SetSlurGeometry`, `SetTieGeometry`, `SetBeamGeometry`, `SetArticulationGeometry`, `MoveAttachment`, `ResizeSecondVolta`, `ResizeFirstVoltaStart`, `MoveNavigationMark` |
| F | `UpdateOrnament`, `UpdateTempo`, `UpdatePerformanceMark` |

`InsertNote` 的 rest/tie/voice/beaming/articulation/grace/small-note 字段是连续记谱修饰符，不各自新开
交互家族。Desktop 兼容保留 `InsertNote.tupletCount` 的“范围 + 首音”旧入口；移动端使用
`CreateTupletRegion(count, totalDuration)` 先原子建立全休止范围，再切到 E 从范围起点逐音填入。
`AddOrnament(endOnset != null)` 的可见入口按 S 呈现，业务仍复用同一 intent。

## 3. 统一设计语言

所有家族共享以下状态和反馈，平台只能换排版与 pointer 解释，不能换语义：

1. **可见模式**：当前活动、家族、命令与关键参数始终可见；隐藏面板不等于退出命令。
2. **稳定目标**：像素先在 adapter 中解析为 event ID、track ID、`TimeCode` 或 measure boundary；
   像素、数组下标、帧内对象引用不进入 intent。
3. **同一种预览**：新增用中性 ghost；修改时隐藏旧对象并显示候选结果；同时显示音高、拍位、边界或范围读数。
4. **一次提交**：pointer move、hover、钢琴按住与表单输入只更新草稿；完成动作最多产生一个普通 intent、
   一个 history item。批量操作禁止循环 dispatch。
5. **明确取消**：第二 pointer、系统手势、切后台、活动切换、Esc、authoritative revision 变化均取消草稿，
   不隐式提交。失败/stale 保留权威谱面并给出可重试状态。
6. **完整帧交接**：提交后保留 ghost/隐藏旧对象，直到完整新 `RenderResult` 发布；流式首屏不算完成。
7. **确定性替代**：每个 drag 必须有 nudge 或属性表单；每个 hover 路径必须有 tap/键盘/辅助技术路径。
8. **候选不猜测**：命中接近时显示候选 callout/sheet；用户确认后才进入共享控制器。

统一生命周期：

```text
Idle -> Armed -> Targeting -> Previewing -> CommitPending -> Settled
  ^        |           |            |              |
  +--------+-----------+------------+--------------+-- cancel/stale
```

N/T/F 可跳过 `Targeting`。Phone 底部常驻一排工具组；E 在一次 `Settled` 后保持 armed 并自动移动到
`nextInputPosition`，同时提供上一/下一音符、上一/下一小节的语义光标按钮。P/S 成功后也保持当前工具组，
但清空本次 anchor，每次新增必须重新选择单点或区间位置。sticky 与 cursor policy 是 command descriptor
字段，不由各平台自行决定。

## 4. UI 无关的共享接口

实现位于 `features/score-editing/.../ScoreInteraction.kt`：可序列化描述与纯状态机不持有像素，也不直接写
`StorageScore`。Web 通过 Kotlin/JS facade 消费同一 descriptor，Desktop/Mobile 直接依赖。

```kotlin
enum class ScoreInteractionFamily { N, E, P, S, G, T, B, H, F }

data class ScoreInteractionSpec(
    val commandId: String,
    val family: ScoreInteractionFamily,
    val topology: ScoreInteractionTopology,
    val toolGroup: ScoreToolGroup,
    val successPolicy: ScoreInteractionSuccessPolicy,
)

sealed interface ScoreInteractionAnchor {
    data class Selection(val targets: List<ScoreSelectionTarget>) : ScoreInteractionAnchor
    data class StaffTime(val staffTrackId: TrackId, val time: TimeCode) : ScoreInteractionAnchor
    data class VoiceTime(val voiceTrackId: TrackId, val time: TimeCode) : ScoreInteractionAnchor
    data class Boundary(val boundaryMeasure: Int) : ScoreInteractionAnchor
    data class EventRange(val startEventId: EventId, val endEventId: EventId) : ScoreInteractionAnchor
    data class MeasureRange(val startMeasure: Int, val endMeasure: Int) : ScoreInteractionAnchor
}

class ScoreInteractionController {
    val state: StateFlow<ScoreInteractionState>
    fun begin(commandId: String, cursor: ScoreEntryCursor?)
    fun target(anchors: List<ScoreInteractionAnchor>, preview: Boolean)
    fun markCommitPending()
    fun accept(intent: ScoreEditIntent, result: ScoreEditDispatchResult)
    fun cancelRun()
    fun moveEntryCursor(runtime: RuntimeScore, action: ScoreEntryCursorAction)
}
```

`ScoreInteractionCatalog.commandId(intent)` 对 sealed intent 做穷尽映射；新增 intent 若未归类会在编译期失败。
controller 只管理 descriptor、语义锚点和交接策略，intent 仍由各产品 workflow 组装；
`ScoreEditingSession.dispatch` 独立验证 revision、目标存在性、结构约束和失败原子性。

平台接口只处理感知与呈现：

```kotlin
interface ScoreInteractionAdapter<PlatformProbe> {
    val capabilities: StateFlow<ScoreInputCapabilities>
    fun candidates(probe: PlatformProbe, request: TargetRequest): List<SemanticCandidate>
    fun present(state: ScoreInteractionState)
    fun feedback(event: InteractionFeedback)
}
```

`PlatformProbe` 可含 pointer 像素、pressure、tilt、键盘/MIDI 事件，但在 `candidates` 之后即被丢弃。
`InteractionPresentation` 只描述模式、ghost、handles、候选、读数和可用 action，不带 Compose/React 类型。

## 5. 设备 adapter 规则

| 家族 | 手机触控 | Pad 触控 | 触控笔 | 键鼠/键盘/MIDI |
|------|----------|----------|--------|----------------|
| N/T/F | 第一次点选；底部 action/sheet；显式多选 | 大选区与停靠 inspector | 精确点选，可直接进入手柄 | 现有快捷键、Shift 与 inspector |
| E | 聚焦谱段 + 底部钢琴；自动前进并有前后音符/小节按钮 | 可停靠钢琴/指板/调板 | hover ghost，抬笔插入 | 光标、步进键盘、MIDI 共用 intent |
| P | 选命令后点目标；候选 sheet | 调板 + 画布 ghost | hover 候选、抬笔提交 | hover/click 或表单位置 |
| S/G/B | 两步锚点或选区后确认；复杂项可横屏 | 直接拖动 + 可见参数区 | 直接画范围/边界 | drag、快捷键、数值边界 |
| H | 选中后第二次拖外扩手柄；同时给 nudge | 选中即显示语义手柄 | 精确直接拖，仍可取消 | 现有 drag + 属性数值 |

pointer 类型逐事件判断；“设备支持触控笔”不能让手指钢琴失效。Phone 在活动栏上方常驻当前活动的工具组
横排（音符、力度、区间、结构等），选择某组后才展开其上下文面板。手机默认一次只开展记录/编辑/分析/试听
中的一个活动，Pad 可并列工具轨、谱面与 inspector，但两者消费相同 spec/state。移动端尤其 Phone 不承担
精细 engraving；H 提供语义手柄、nudge 与必要属性即可，不设置“必须横屏精修”的完成门槛。

## 6. 新功能准入规则

新增打谱能力必须在设计/PR 中回答：

1. 主要交互家族与可选次要入口是什么？若九类均不适用，说明为何需要新拓扑，而不是只因按钮长得不同。
2. 语义目标、参数 schema、repeat/preview/commit/cancel 策略是什么？
3. 最终生成哪个新增/既有 `ScoreEditIntent`，是否追加 JVM/JS intent trace？
4. Phone、Pad、Pencil、键鼠和辅助技术如何可达；drag 的确定性替代是什么？
5. Computed/renderer metadata、hit box、continuous/paginated splice 与完整帧交接是否覆盖？

禁止把平台组件、手势名称或像素坐标写入 `NotationInteractionSpec`；禁止由平台 adapter 计算 tuplet、
变音拼写、符杠、跨小节拆分等音乐规则。

## 7. 移动开发前置门禁

进入某个家族的移动 UI 实现前必须；跨家族的 common 基础可先行，但不得替未决家族决定手势：

- 完成本分类对应的[移动打谱原型](mobile-score-prototypes.md)评审并记录结论；
- 将 Desktop expression/geometry 的普通记谱过渡入口收敛为 shared dispatch，或在能力矩阵注明后置原因；
- 为屏幕和弦输入补共享 MIDI chord 拼写协议；实时 take 若进入首版，先定义原子 intent/trace；
- 建立 descriptor 覆盖测试：57 个 intent 均有家族，所有暴露 command ID 均有平台入口或明确 capability gap；
- 追加移动 adapter 测试：候选歧义、两步拖动、cancel、stale、单历史项、完整 render 交接与无障碍 action。
