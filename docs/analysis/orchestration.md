# 配器与谱表分配（Orchestration & Staff Assignment）🚧 设计

> 状态：**O0/O2 基础闭环已实现**（模型、旧乐器迁移、演奏者/默认谱表编辑、
> 缩谱→内容线→演奏者→谱表绑定及写入）；标签、播放路由与分谱导出仍为 🚧。总览见 [README.md](README.md)，
> 数据基础与 [reduction.md](reduction.md) 共享 `StorageNoteLink / NoteRef`，
> 创作方向操作（实现/更换配器）见 [composition.md](composition.md) §3/§4——本篇是其配器维度的细化落地。

## 1. 定位与需求

交响总谱中"乐器 ↔ 谱表"不是固定关系：

- 三支长笛先合记一行，随后拆成 1&2 一行、3 另一行；
- 长笛 3 中途换短笛（doubling）；
- 独奏小提琴从小提琴声部中独立出一行谱表；
- 三支长笛齐奏同一旋律时谱表只写**一个**音符并标 tutti / a 3，而非三个重复声部；
- 三支长笛奏和声时，可记成一个声部的和弦、三个独立声部、或 1&2 一个声部 + 3 另一个声部，由用户配置；
- 同一段旋律先给"长笛+小提琴"、再改给"单簧管+圆号"试听配器效果；
- 每件乐器（每位演奏者）可导出自己的分谱。

现有模型 `Instrument → StaffTrack → VoiceTrack → PitchTrack` 是静态所有权链，无法表达上述时变关系。本设计把**内容**、**演奏者**、**谱表呈现**三者解耦。

## 2. 概念模型

```
内容线 Line       与乐器无关的完整单声部线（pitch + duration，可独立播放/导谱）
   │  StoragePerformance：线 → 演奏者们（时变；配器试听改这张表）
   ▼
演奏者 Player     持有若干乐器（可中途切换）；SINGLE（长笛1）或 SECTION（小提琴I组）
   │  StorageStaffAssignment：演奏者 → 谱表 + 声部分组（时变；谱表重组改这张表）
   ▼
谱表记谱          真实的 StorageVoiceTrack（可手动编辑的书面层，即现有编辑管线）
   ↕  StorageNoteLink：线音符 ↔ 书面音符（多对多 + pitchIndex，同 reduction）
```

关键取向（已敲定的设计决策）：

- **双层并存**：内容线与书面记谱都是持久数据，逐音 link 关联；两层可偏离，偏离可见（哲学同 reduction.md §4），但编辑时**无歧义处自动同步**（§5）。
- **内容线可共享**：tutti = 同一条线被多名 player 演奏（`StoragePerformance.playerIds` 多元素），而非复制内容。"三支长笛齐奏只写一个音符"由此自然成立：书面层一个音符、一条 link 指向线音符、标签 a 3 由演奏人数导出。
- **两张时变映射相互独立**：换配器（改 performance）不动谱表结构；谱表重组（改 assignment）不动内容与音色。粒度均为**任意 TimeCode**（div./unis./solo 常发生在小节中途）。
- **分配是意图，书面音符是事实**：改 assignment 不自动搬移已写音符（可在操作中勾选"同时迁移"，即 composition.md §4 的移谱操作）；分配用于指导后续实现/同步的落点、生成标签、以及暴露"写的位置与计划不符"。
- **谱表集合的增减**仍走既有机制：谱表是普通 `StorageStaffTrack`，中途出现/退场用 `hiddenRanges`（小节粒度）；assignment 决定谁的内容记在哪行。

## 3. 数据模型（Storage 层）

新顶层可空字段 `StorageScore.orchestration: StorageOrchestration? = null`——
`null` 时一切行为与现状完全一致（渐进采用）。

```kotlin
@Serializable
data class StorageOrchestration(
    val players: List<StoragePlayer> = emptyList(),
    /** 内容线：不挂任何谱表的 voiceTrack id（含其 1:1 pitchTrack），完整单声部线。 */
    val lines: List<TrackId> = emptyList(),
    val performances: List<StoragePerformance> = emptyList(),
    val staffAssignments: List<StorageStaffAssignment> = emptyList(),
    /** 线音符 ↔ 书面音符。NoteRef/StorageNoteLink 与 reduction.md §2 同型共用。 */
    val links: List<StorageNoteLink> = emptyList(),
)

@Serializable
data class StoragePlayer(
    val id: PlayerId,                              // @JvmInline value class
    val name: String,                              // "Flute 1" / "Violins I"
    val abbreviation: String? = null,
    val kind: PlayerKind = PlayerKind.SINGLE,      // SINGLE / SECTION
    val instruments: List<StoragePlayerInstrument>, // 至少一件；首件为默认
    val holds: List<InstrumentHold> = emptyList(),  // 换乐器切换点；空 = 全程持首件
)

@Serializable
data class StoragePlayerInstrument(
    val id: InstrumentId,
    val name: String,
    val abbreviation: String? = null,
    val transposition: TranspositionConfig? = null,
    val playback: InstrumentPlayback = InstrumentPlayback.PIANO,
)

@Serializable
data class InstrumentHold(val onset: TimeCode, val instrumentId: InstrumentId)

/** 这条线自 onset 起由谁演奏；同 lineId 的更晚 entry 覆盖。空 playerIds = 无人演奏。 */
@Serializable
data class StoragePerformance(
    val lineId: TrackId,
    val onset: TimeCode,
    val playerIds: List<PlayerId>,
)

/** 该 player（的某条线）自 onset 起记在哪个谱表；同键更晚 entry 覆盖。 */
@Serializable
data class StorageStaffAssignment(
    val playerId: PlayerId,
    val lineId: TrackId? = null,   // null = 该 player 的全部线；SECTION divisi 跨谱表时逐线指定
    val onset: TimeCode,
    val staffId: TrackId?,         // null = 此后不记谱（退场/tacet）
    val voiceHint: Int? = null,    // 呈现分组：期望落入的 voiceNumber；null = 自动
)
```

- 两张时变表的求值都是"按键取 `onset ≤ t` 的最后一条"，与 `clefChanges` 同式。
- **呈现分组**由共享谱表上各 player 的 `voiceHint` 表达：同谱表同 voice 的多条线在实现/同步时合并为和弦（音高重合则去重为单符头，即 tutti 单音符）；不同 voice 则分声部。"1&2 一个声部、3 另一个声部" = Fl.1/Fl.2 `voiceHint=1`、Fl.3 `voiceHint=2`。
- 内容线复用现有 `StoragePitchTrack + StorageVoiceTrack` 对（时值、连音组、装饰音、延音免费获得），仅"由 orchestration.lines 引用、不被任何 staff 引用"这一点与书面轨不同。实现时需审计假定"voiceTrack 必挂 staff"的消费方（如 `getAllVoiceEvents` 的全量遍历）。
- **与 `StorageInstrument` 的关系**：旧字段保留，供无 orchestration 的乐谱与简单场景继续使用。"启用配器管理"时一次性迁移：每个 `StorageInstrument` → 一名 SINGLE player（乐器、playback、移调照搬），其谱表生成整曲 assignment；内容线初始为空，经"提取"逐段建立（§7）。
- 谱表自身的 `transposition`/`clef` 仍是书面层的权威（同一谱表上的两支单簧管本就共用记谱移调）；player 当前所持乐器的移调与谱表不符时产生 warning（Computed 层），分谱导出按乐器移调重记。

## 4. 派生数据（Computed 层）

Computed 层决定生成何种标签与状态元素，Renderer 只排版（架构铁律不变）。

**谱表标签 `ComputedPlayerLabel(staffId, onset, text, kind)`**——在分配/演奏拓扑变化处生成：

| 情形（谱表某时段） | 标签 |
|--------------------|------|
| 谱表宿主多名同族 player，当前只有 1 号在奏 | `1.` |
| 两人各占一声部 | `1.` / `2.`（按声部上下） |
| k 人共奏同一书面声部（链接到同一条线） | `a 2` / `a 3` |
| SECTION 整组齐奏（此前有 solo/div.） | `tutti` / `unis.` |
| SECTION 的一条线由单人演奏 | `solo` |
| SECTION 同时奏多条线 | `div.` |
| `InstrumentHold` 切换 | `muta in Picc.`（切换点前的合适空隙，v1 记在切换 onset） |

v1 全自动生成（可整体隐藏）；逐处手动覆盖 🚧 v2。

**一致性状态**（复用 reduction §4 的计算配方：`CalcBuilder` 增量对齐 + `NoteStyleProvider` 着色）：

| 状态 | 侧 | 判定 |
|------|-----|------|
| `OK` / `PITCH_DIVERGED` / `TIME_DIVERGED` / `DANGLING` | link | 同 reduction §4 |
| `UNASSIGNED` | 书面 | 书面音符无 link（不知道谁演奏；同 UNMAPPED 机制，配器语境命名） |
| `UNREALIZED` | 线 | 线音符无 link（计划了但没写进总谱） |
| `MISPLACED` | link | link 指向的谱表 ≠ 当前 assignment 声明的谱表（写的位置与计划不符） |
| `TRANSPOSITION_MISMATCH` | 谱表段 | 所持乐器移调 ≠ 谱表移调 |

## 5. 同步规则（无歧义自动同步）

编辑任一层时，反向更新**无歧义则自动执行**（与原编辑同一撤销原子）；有歧义则不动
另一层，落偏离/待分配标记，由用户后续处理。歧义判据逐案列举：

| 编辑 | 行为 |
|------|------|
| 改书面音符音高/时值 | 所有 link 的线音符跟随（tutti 即全体跟随——语义本就是"这一个书面音改了"） |
| 删书面音符 | link 的线音符一并删除（含 tutti；撤销可整体回退） |
| 书面层加音符 | 无自动反写；标 `UNASSIGNED`，经"分配到 player/线"补 link（若该处 assignment 唯一且该线该 onset 空缺，提供一键/批量采纳） |
| 改单条线的音符 | 该线所有书面表示跟随；若某书面音符同时被**其他线**链接（合唱和弦中的一个 pitch 仅链此线则改该 pitch），无法整改的落 `PITCH_DIVERGED` 提示拆分 |
| 线上加音符 | 标 `UNREALIZED`；"实现"操作（§7）按 assignment + voiceHint 写入书面层并建 link |
| 删线音符 | 仅断 link，书面音符保留并转 `UNASSIGNED`（书面是事实，不连带删谱） |
| 改 performance（换人奏） | 纯路由数据，两层内容都不动；标签与播放即时变化 |
| 改 assignment（换谱表） | 默认只改意图（已写音符转 `MISPLACED`）；可选"同时迁移已写音符"= composition.md §4 移谱操作（音符+link 原子迁移，按目标谱表移调重记） |

## 6. 播放路由（配器试听）

**书面层发声，按分配路由音色**——所见即所听，偏离时不会播出陈旧内容：

- 对每个书面音符：`link → 线 → StoragePerformance(t) → players → InstrumentHold(t) → 乐器 playback`。
  k 名 player 共奏 → 同一书面音在 k 个通道各发一次（tutti 的厚度真实可闻）。
- 无 link 的书面音符按谱表默认音色发声一次（现状行为，兼容旧谱）。
- 同一条线在多个谱表都有书面表示时（罕见），以该 player 当前 assignment 指向的谱表为准，避免重复发声。
- 集成点：`ScoreToMidiConverter` 增加 per-player 通道分配；SECTION player v1 按一个通道（音量近似），按人头合成 🚧。
- **配器试听工作流**：选中线（或书面选区经 link 反查线）→ 面板改 performance（"这段给单簧管+圆号"）→ 播放；A/B 对比走撤销栈（composition.md §4 v1 策略），命名变体方案与 A6 合流 🚧。

## 7. 交互形式（apps/desktop）

- **演奏者/谱表**：位于顶部“分析/创作”页。按乐器显示独奏/合奏、人数，以及每条
  总谱谱表负责的演奏者编号；这是新建后调整默认分配的固定入口。
- **绑定/配器**：在任一侧选中音符后打开，按
  `缩谱 ↔ 内容线 → 演奏者 → 谱表` 显示当前选择；可从缩谱写入总谱，也可从总谱
  提取到缩谱；可新建/复用内容线、选择多名演奏者、分别指定 staff / voice。
- **提取成线**：选中书面旋律段（单谱表单声部连续事件）→"提取为内容线"→ 新建/并入某条线，建逐音 link；多 pitch 事件按 reduction §5 的三策略拆分。与缩谱提取共用交互骨架。
- **实现到谱表**：选中线段 →"实现"→ 按当前 assignment（可临时覆盖目标）写入书面层：同谱表同 voice 的多线合并和弦、音高重合去重（tutti 单符头），建 link。同 composition.md §3，目标从"staffTrack/voice"升级为"经 assignment 解析"。
- **谱面着色**：`UNASSIGNED` 灰边、偏离橙色、`UNREALIZED` 虚框（线编辑视图内）、`MISPLACED`/`TRANSPOSITION_MISMATCH` 提示条——全部复用 `NoteStyleProvider` 通道。
- **标签渲染**：`ComputedPlayerLabel` 以谱表上方小字排版（同 rehearsal/text 附着元素的避让通道）。
- **线编辑视图**：内容线可在独立面板打开编辑（per-score 渲染管线 + `ActiveScoreContext`，同缩谱面板基建）；也可只经总谱 + 同步规则间接维护。

## 8. 分谱导出（v1：派生快照）

"导出分谱"生成独立 `StorageScore`（可再导 PDF / MusicXML），不回链：

1. 收集该 player 全程演奏的线（performance 表）；按 holds 分段确定乐器与移调。
2. **书面细节优先**：每个线音符经 link 取书面表示（临时记号拼法、符杠、演奏法、
   和弦中该 player 的 pitch 由 `NoteRef.pitchIndex` 精确摘取）；`UNREALIZED` 音符
   直接取线层并入并在导出报告中列出。
3. 谱表附着符号（力度/渐强/8va）按书面谱表时间段 best-effort 复制（v1 整段复制，
   与该 player 无关的声部级附着的甄别 🚧）。
4. 乐器切换处生成 `muta in` 文本；拍号/调号/速度/反复从 global 复制；按乐器
   `transposition` 重记音高。多小节休止合并依赖渲染端 multirest 🚧。

## 9. 与 reduction / composition 的关系

- **同型不同轴**：缩谱层处理"谁在演奏 → 音乐材料"的**分析还原**（独立时间轴、嵌套
  score、anchor 平移）；内容线处理"材料 → 谁演奏、记在哪"的**配器实现**（与主谱同
  时间轴、平铺 track，无 anchor）。两者共用 `NoteRef / StorageNoteLink`、一致性计算
  配方与提取/实现交互骨架。
- **层级栈落位**：内容线插在缩谱层与表面层之间——缩谱声部"实现"的直接目标可以是
  内容线（先定材料与分工，再定谱表呈现），composition.md §3 的 realize 目标由
  "staffTrack/voice"泛化为"线 + assignment"。
- **A5/A6 的承接**：composition.md §4"更换配器与试听"的最终形态即本篇 §6/§7；
  A6 配器变体 = 命名的 performance 方案集。

## 10. 实施路线图

| 里程碑 | 内容 | 依赖 |
|--------|------|------|
| **O0** | 数据模型与序列化（orchestration 字段、PlayerId、两张时变表、NoteLink 共用定义）；StorageInstrument→Player 迁移；voiceTrack 无 staff 宿主的消费方审计 | 与 A0 共用 NoteRef/StorageNoteLink（先落地者定义） |
| **O1** | 一致性状态 + 着色 + `ComputedPlayerLabel`（1./a 2/solo/tutti/div./muta）增量计算与渲染 | O0 |
| **O2** | 交互：配器面板、提取成线、实现到谱表（voiceHint 分组/tutti 去重）、改分配（可选迁移）、换乐器；同步规则全表落地 | O1 |
| **O3** | 播放路由：`ScoreToMidiConverter` per-player 通道、配器试听 A/B 工作流 | O0 |
| **O4** | 分谱导出（快照 StorageScore + 导出报告） | O2 |
| **O5** 🚧 | divisi 深化（SECTION 按人头/多通道）、标签手动覆盖、命名 performance 变体、分谱活视图 | O2-O4 · A6 |

验证基线：test-scores 增交响片段（长笛组重组 + doubling + 弦乐 solo/div.）；
标签生成金标准测试；同步规则逐案单测；播放路由（tutti 多通道）审听样本；
分谱导出 golden file + MusicXML 往返。

## 11. 开放问题

- 谱表头名称/staffLabel 是否随 assignment 自动更新（v1 手动，仅生成行内标签）；
- 行中途 assignment 变化的标签排版避让（与力度/文字附着的碰撞）；
- SECTION 按人头播放与真实弦乐音量曲线；
- 声部级附着符号（仅对某 player 生效的力度）在书面层的表达与分谱甄别；
- MusicXML 互转：players/assignment 与 `<part-group>`/`<instrument>` 的映射保真度；
- 探索模式接缝：线作为 `MaterialRef` 来源（"这条旋律在探索模式里试配器"）。
