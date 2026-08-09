# 自由练习工作台主交互重构设计

> 基线：2026-07-30；状态：✅ 主交互与 R1–R4 通用和弦详情已实施。
> 顶栏设置、自动声部写作及删除“续写”的 v6 改造已实施，以
> [自由练习自动写作改造](free-practice-auto-writing.md) 为准；本文保留 v5 布局基线。
>
> 本文只设计自由练习工作台的主交互、统一时间轴和写作声部模型。视觉继续使用现有
> `MeconColors`、`WorkbenchPanel`、五度圈与调性线样式；原型
> `chord-prototype/和弦写作工作台.dc.html` 仅提供信息层级与布局参考。

## 1. 结论与范围

本轮采用以下目标：

1. 页面改为“单卡片写作区 + 右侧检查器”，撤销现有计划 / 编辑 / 反馈三栏；和声选择进入右栏。
2. 和声时间轴仍是唯一主轴；拖动、单边缩放、公共边界、Ctrl 组平移、空隙插入和调性线
   交互保持不变。
3. 已插入的惯用进行以跨区间括号和名称标示在时间轴下方；选中后在右侧显示来源、变体与
   整体替换 / 删除操作。单个受控和弦仍不可独立修改。
4. 和弦序列、五线谱与钢琴卷轴在同一卡片中紧凑上下排列，共享横向投影、滚动和缩放。
5. 写作材料允许和弦化声部和自由换声部，只限制任意时刻的发声音符总数不超过 `N`。
6. 钢琴卷轴始终自动选择记谱声部；用户只在五线谱中修正声部。
7. 五线谱固定为大谱表两行；`N` 个默认记谱声部按用户设置分到上下谱表。
8. 分析时把写作材料重新分离为 `N` 条单音分析声部，不把记谱声部直接当作分析真相。

本轮不做：

- 不换色板、字体、圆角样式或调性选择布局。
- 不重写时间轴手势与 `HarmonyWorkspaceCommand` 语义。
- 不填充完整和弦百科、推荐去向等右侧新内容；只建立选中对象与右侧检查器的状态契约。
- 不把自由练习专用约束写死进主编辑器；通用能力必须显式 opt-in。

## 2. 目标布局

```text
┌──────────────────────────── 主工作区 ────────────────────────────┬──── 右侧检查器 ────┐
│ 和声与调性时间轴（原交互）                                      │ 详情                 │
│ 已插入惯用进行： └──────── 名称 / 来源 ────────┘                 │ 设置                 │
│ ───────────────────── 紧凑分隔线 ───────────────────────────── │ 和声选择             │
├─────────────────────────────────────────────────────────────────┤ 检查（v5 基线）       │
│ 对照 / 五线谱 / 钢琴卷轴                                        │                      │
│ ┌────────────────── 两行五线谱 ───────────────────────────────┐ │                      │
│ └─────────────────────────────────────────────────────────────┘ │                      │
│ ┌────────────────── 钢琴卷轴 ─────────────────────────────────┐ │                      │
│ └─────────────────────────────────────────────────────────────┘ │                      │
│                    共用横向滚动条                                │                      │
└─────────────────────────────────────────────────────────────────┴──────────────────────┘
```

### 2.1 主工作区

- 时间轴、五线谱和钢琴卷轴共用一个外层卡片，只以紧凑分隔线区分；可折叠其中一个预览。
- 两视图上下排列，纵向高度可调；分隔条拖动时只移动预览手柄，松开后一次提交 15%–85% 的
  分割比例，避免卷轴跟手偏慢和连续重排。折叠只影响可见性，不清空选区、输入笔或滚动位置。
- 时间轴、惯用进行标示、五线谱和卷轴使用同一个 `AlignedTimeViewportState`。
- 只保留一个横向滚动条。时间轴滚轮继续调整拍宽；卷轴滚轮只调整音高行高。
- 五线谱禁止独立横向缩放 / 平移；其纵向视口仍可单独调整。卷轴键盘与谱面工具栏属于固定
  gutter，不参与时间滚动。

### 2.2 惯用进行标示

- `WorkspaceIdiomInstance.slotIds` 投影为 `[首槽 onset, 末槽 end)`，在和弦卡片下绘制跨区间括号。
- 标签显示教材名称；窄区间先省略来源，不缩小到不可读字号。
- 点击括号选择整个实例并把右侧切到详情；时间轴卡片仍显示锁定状态。
- 标示只读，不新增一套拖拽语义。变体切换、整体删除继续走现有实例级命令。
- 若未来允许重叠实例，标示使用通用区间分行算法向下堆叠；本轮一行即可。

### 2.3 右侧检查器

右栏合并当前 `PracticePlanPanel` 与 `PracticeFeedbackPanel`，默认宽度 392 dp，可在 240–720 dp
内调整；拖动期间只移动预览手柄，松开后才提交宽度并重新布局：

- **详情**：作为与“和声选择”相邻、默认折叠的独立面板；“惯用进行”紧随其后，用户选完和弦
  可直接选进行。展开详情后按实际音响显示跨章节解释、构造路线、性质与来源；用户可锁定具体解释，
  也可保持自由解释。点击目录和弦会先以自由解释立即更新当前时间轴槽位；浏览线路不提交，确认
  线路后才锁定解释。具体状态机见
  [和弦音响与多重解释设计](../theory/chord-detail-and-vagrant-chords.md)。选中惯用进行时显示来源、
  当前变体和整体操作。
- **和声选择**：原时间轴下方的和弦目录、调性读法与枢纽和弦控件整体移入右栏。目录上方提供
  “低音”单选；选项为“任意”及当前和弦的全部结构音，并跟随目录的相对/绝对音高读法显示。
  新建练习和首槽重新选和弦时默认原位，其余手工和弦默认任意低音。
- **设置（v5 基线）**：复用现有初始调性、五度圈、调性布局控件；含“同时发声音符上限”和
  “默认上 / 下谱表声部数”。v6 设计把全部练习设置移到上方工具栏。
- **惯用进行**：复用章节自动发现的目录和变体列表；插入点仍取当前和弦槽。
- **检查**：保留 Hint 与警告；旧续写候选已在 v6 自动写作改造中删除。

右栏各区域统一使用主界面检查器的折叠标题样式；该样式由 `desktop-ui-kit` 的
`CollapsiblePanelItem` 提供，不在自由练习内复制。选择和弦不强制切 tab；右栏状态是 UI
瞬态，不写入文件。

## 3. 统一时间投影

### 3.1 为什么不能只同步滚动条

当前三套横向坐标彼此独立：

- 时间轴：`beatWidth × Fraction`；
- 五线谱：Renderer 的固有比例间距；
- 钢琴卷轴：`ticks × scaleX`。

同步 offset 只能让三者一起移动，不能让同一 `TimeCode` 落在同一 X。目标必须是共享一份
可正反查询的最终时间投影。

### 3.2 新契约

在 `renderer/layout` 增加通用、非自由练习专用的类型：

```kotlin
data class TimeAxisSegmentRequest(
    val start: TimeCode,
    val end: TimeCode,
    val preferredWidth: StaffSpace,
)

data class AlignedTimeAxisRequest(
    val segments: List<TimeAxisSegmentRequest>,
    val extraAnchors: Set<TimeCode>,
    val leadingInset: StaffSpace,
    val revision: Long,
)

data class ResolvedTimeAxis(
    val anchors: List<TimeAxisAnchor>,
    val contentEndX: StaffSpace,
) {
    fun xAt(time: TimeCode): StaffSpace
    fun timeAt(x: StaffSpace): TimeCode
}
```

- UI 按当前拍宽生成连续 segment；和弦、调性、惯用进行边界进入 `extraAnchors`。
- `ResolvedTimeAxis` 进入 `UnifiedLayoutResult` 与 `RenderResult`，时间轴和卷轴只消费最终结果，
  不再各自重算比例。
- `TimeCode ↔ 线性 Fraction` 统一抽成 `ScoreTimeMap`，替换宿主和卷轴里的重复换算。

### 3.3 排版算法

参考 `SystemBreaker` 的“先固有排版、后行内拉伸”，新增连续模式的约束投影 pass：

1. 原有事件收集、同槽多声部避让、注释测量和比例 X 求解不变，得到 intrinsic slot map。
2. 合并请求边界、固有时间槽、小节线和谱尾，形成有序锚点。
3. 建立从左到右的差分约束：
   - 相邻请求锚点的距离不得小于按时值计算的 `preferredWidth`；
   - 相邻固有槽的距离不得小于 intrinsic X 差；
   - 首尾必须容纳谱表头、临时记号、注释等固有 overhang。
4. 对该有向无环图求最长路，得到满足所有约束的最小 X。请求宽度是期望下限；内容过密时只
   扩张受影响区间，绝不压缩到符号碰撞。
5. 用 `ResolvedTimeAxis.xAt(slot.time)` 生成新的不可变 `UnifiedTimeSlotMap`。
6. 在新槽位上继续执行 beam、barline、annotation、tie/slur、附件和命中区等 X 相关步骤。
7. 五线谱发布完整 `RenderResult + ResolvedTimeAxis` 后，时间轴与卷轴原子切换到同一 revision。

该模式名为 `ALIGNED_CONTINUOUS`，首版与分页互斥；它借用分页的后置拉伸思想，但不产生 system
break。默认 `INTRINSIC` 路径完全不变，避免影响主编辑器与既有快照。

### 3.4 增量与性能

- `preBreakTimeSlotMap` 继续缓存固有坐标；另缓存 request hash 与 resolved axis。
- 仅拍宽 / viewport 改变时走 projection-only 路径，复用事件测量和 voice layouts，只重做
  X 投影及 X 相关几何。
- 乐谱编辑仍使用 `RenderHint` 的 affected measures；投影约束从受影响锚点向右增量传播，
  不能证明等价时回退该工作台的完整连续渲染。
- 请求和结果都用引用 identity 进入 Compose；后台串行 conflated worker 生成新帧，旧的三视图
  帧保留到完整结果发布，遵守大乐谱热路径约束。
- 编辑期间不得把对齐请求或已解析时间轴重置为 `null`。音符输入和区间拖动保留上一完整投影；
  和弦插入则立即发布 session 的 workspace 与稳定 `WorkspaceSlotId` 选择，旧轴覆盖的区间继续复用，
  新谱尾按当前拍宽临时线性延伸，禁止等待渲染或由失效索引回退首槽。新
  `RenderResult + ResolvedTimeAxis` 到达后替换临时谱尾投影。
- 最终锚点对齐误差以屏幕 100% 缩放不超过 1 px 为验收标准。

## 4. 写作声部与复音上限

### 4.1 三个概念必须分开

| 概念 | 含义 | 是否是音乐分析真相 |
|---|---|---|
| 复音上限 `N` | 任意时刻最多同时发声的音符数 | 是约束 |
| 记谱声部 | 两行谱表中承载事件的编辑 lane，可含和弦 | 否，只是写作组织与分析提示 |
| 分析声部 | 检查 / 求解前生成的 `N` 条单音线 | 是一次分析帧 |

`RuntimeScore` 仍是唯一音符真相；工作区不新增第二份 notes。

### 4.2 通用配置

将现有一声部一谱表的 `VoiceNotationPlan` 替换为可复用计划：

```kotlin
data class GrandStaffVoiceLayout(
    val upperVoiceCount: Int,
    val lowerVoiceCount: Int,
) {
    val capacity: Int get() = upperVoiceCount + lowerVoiceCount
}
```

- 默认四声部为 `2 + 2`；三声部 `2 + 1`，五声部 `3 + 2`，六声部 `3 + 3`。
- 始终只装配 treble / bass 两个 staff；每个 staff 内 voice number 从 1 递增。
- voice range、次序和上下边界继续来自 `VoicePlan`，但写作时只作为自动分配代价，不作为硬拒绝。
- 修改上下分配必须通过原子 track-plan 迁移，保留事件、pitch track 和选择 ID；禁止像当前
  `rebuild()` 一样清空材料。

### 4.3 编辑约束

在 core 增加 opt-in 的 `PolyphonyLimitValidator`，校验候选 `RuntimeScore`：

- 把每个非休止 `RuntimeVoiceEvent` 的每个 pitch 视为一个发声音符；
- 使用半开区间 `[onset, end)` 扫描，和弦按 pitch 数计数，边界相接不重复计数；
- 插入、延长、粘贴、批量输入均在完整候选结果上校验；
- 超限返回结构化错误 `PolyphonyOverflow(time, actual, limit)`，不 compute、不提交、不产生 undo；
- 主编辑器默认不安装该 validator，原有和弦写作行为不变。

自由练习改用 `NoteInsertionPolicy.CHORDAL`。同声部同起点可形成和弦；移动单个音头沿用
`VoiceNoteSection` 粒度。

### 4.4 钢琴卷轴自动分配

- 删除“全部 / V1…VN”输入声部和 `selectedVoice`；卷轴每次输入都调用
  `AutomaticNotationAssigner`。
- 分配器先验证复音上限，再在未被持续事件占用的记谱 lane 中选择代价最低者：优先音域、
  上下谱表配置、前一音运动和平行顺序；确定性 tie-break 使用 voice order / id。
- 分配只决定新输入落点，不重排既有材料。用户在五线谱做过的声部修正不会被下一次卷轴输入
  改回。
- 卷轴仍负责时间 / MIDI 命中；量化、拼写、lane 选择和事务继续在共享宿主完成。

### 4.5 五线谱声部调整

- 两个 staff 各显示其可用 voice chips；活动 voice 只决定下一笔的目标，不再把其他声部全局
  灰掉。
- 选中整事件或单个音头后点击目标 voice，调用扩展后的通用 `moveVoices`：
  `targetStaffId + targetVoiceNumber + pitchIndices`。
- 跨 staff 移动与同 staff 移动同样支持拆分 / 合并和弦，并在一次事务中更新选择。
- 不再运行“手动事件固定、自动事件重新配声”的全谱回流；现有
  `voiceAssignmentSources` 仅作旧 schema 读取，后续不写。

### 4.6 分析声部分离

在 `theory` 增加通用 `AnalyticalVoiceSeparator`：

1. 将每个和弦事件展开为带来源 `NoteRef` 的 pitch atom。
2. 保持仍在发声的 atom，按 onset 对新 atom 做有序最小成本匹配。
3. 记谱 voice / staff 是软提示；音域、运动、交叉和持续性共同计分。一个记谱和弦会自动拆到
   多条分析声部。
4. 输出不可变 `SeparatedVoiceFrame`，包含 source `NoteRef → analysis voice id`、诊断与可选
   歧义信息；不回写 `RuntimeScore`。
5. 若旧文件本身超过 `N`，返回明确的容量诊断，不截音、不静默增加声部。

检查、figuration reduction、和弦完整性和窗口化自动写作统一消费该帧。这一入口也可供分析 / 创作
功能复用，禁止各功能再写一套 SATB 拆分。

## 5. 文档与迁移

本轮基线曾把自由练习 module 升到 schema v3；当前已继续迁至 schema v5。v6 写作设置见
[自由练习数据模型](../data_model/free-practice.md)。v5 的核心设置为：

```kotlin
data class FreePracticeSettings(
    val polyphonyLimit: Int,
    val staffVoices: GrandStaffVoiceLayout,
    val initialKey: KeySpec,
)
```

- 要求 `staffVoices.capacity == polyphonyLimit`，首版仍支持 3–6。
- v1/v2 的 `voiceCount` 迁为 `polyphonyLimit`，按上述默认规则生成上下分配。
- 迁移同时涉及 payload 与关联 score，新增 `FreePracticeSnapshotMigrator`；不能只在 JSON codec
  中改字段。
- 旧的 N 个单声部 staff 合并为两个 staff，保留 voice / pitch track / event id，并重写
  staff membership 与 voice number。
- 保存仍只保存 `RuntimeScore + HarmonyWorkspaceState + settings`；投影、分析帧、右栏 tab、
  折叠和 scroll 均为可重建 / 瞬态状态。

数据模型说明与 v1/v2 → v3 兼容测试已同步更新。

## 6. 代码落点

| 范围 | 主要调整 |
|---|---|
| `renderer/layout` | `TimeAxisLayout`、约束投影 pass、`ResolvedTimeAxis`、默认路径隔离 |
| `renderer/render` | 把 resolved axis 发布到 `RenderResult`，补投影模式增量缓存 |
| `core/engine/edit` | `PolyphonyLimitValidator`；`moveVoices` 支持跨 staff 的单音头移动 |
| `theory/.../writing` | `GrandStaffVoiceLayout`、`AutomaticNotationAssigner`、`AnalyticalVoiceSeparator` |
| `exploration` | 两行谱表装配、schema v5、确切和弦选择与 snapshot 迁移 |
| `apps/desktop/ui/views` | Score / PianoRoll 接受外部时间轴与共享 viewport；默认调用不变 |
| `apps/desktop/ui/exploration` | 两栏 composition、惯用进行标示、右侧检查器、移除卷轴 voice 选择 |
| `apps/desktop/service` | 候选编辑校验、自动记谱分配、分析帧后台发布与原子事务 |

`FreePracticeEditorPanel.kt` 仍集中承载 Canvas、手势与预览接线。本轮为降低原时间轴手势
回归风险未做机械拆文件；后续可按 `FreePracticeTimeline`、`IdiomAnnotationLane`、
`AlignedPracticeEditors` 拆分，但拆分不得改变现有命令与手势语义。

## 7. 实施顺序

1. **契约与迁移**：先更新数据模型文档；实现 v3、两行记谱计划及旧文件 fixture。
2. **通用写作内核**：复音 validator、自动记谱分配、跨 staff 音头移动、分析声部分离及测试。
3. **Renderer 投影**：实现 aligned continuous、正反查询、默认路径快照与增量 / 性能测试。
4. **视图接线**：Score / PianoRoll 外部时间轴、共享 scroll/zoom、上下对照布局。
5. **工作台重排**：合并右栏、增加惯用进行括号与选择路由；复用现有配色和调性组件。
6. **清理**：删除自由练习的 MONODIC、卷轴 voice chips、自动全谱回流和一声部一谱表文档。

## 8. 验收

- 原时间轴拖拽、边界、Ctrl、空隙、删除、调性线操作的回归测试全部不改语义。
- 任意小节线、和弦边界、调性边界和惯用进行端点在三视图中对齐，误差 ≤ 1 px。
- 内容拥挤时三视图共同扩宽同一区间，无符号碰撞、无局部错位。
- 默认只渲染两行 staff；3–6 声部上下分配正确且调整不丢音。
- 同 voice 可写和弦；跨 voice / staff 移动单音头可撤销，且一次操作只有一个历史项。
- 卷轴无声部选择控件，连续输入不会改动用户已在谱面修正的声部。
- 任意时刻第 `N+1` 个发声音符被原子拒绝；主编辑器仍允许普通和弦输入。
- 分析输出恰有 `N` 条单音声部，和弦音均有 source `NoteRef`，原谱不被改写。
- 默认 Renderer、分页、主编辑器 PianoRoll 和大乐谱增量测试无回退。
