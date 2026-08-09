# 乐谱编辑交互

演奏法、力度、hairpin、文字渐强渐弱与 8va/8vb 的编辑见
[expression-editing.md](expression-editing.md)。

记录"音符录入"工具的端到端交互：左侧工具栏 → 画布悬浮/点击 → `NoteEditEngine` 编辑规则 → `commitNewState` 提交。编辑管线在计算层的接入见 [data_model/incremental-update.md](../data_model/incremental-update.md)。

## 工具栏（左侧两列）

`apps/desktop/.../ui/components/LeftToolbar.kt` + 状态 `NoteToolState.kt`。

- **工具列**（左）：三个工具，状态机为 `EditTool`（同一时刻仅一个决定画布点击行为）。
  - **选择**（鼠标指针）：保留既有的选中 / 平移 / 缩放交互，不改动乐谱。
  - **框选**（虚线方框）：指针类工具，**单击行为与选择工具完全一致**；**拖动**拉出橡皮筋选框，与框相交的元素被选中（见下「框选与多选」）。
  - **音符**（四分音符笔）：**纯调板开关**（`togglePalette()`，只翻 `paletteExpanded`），**不动 `tool`**。高亮跟随 `paletteExpanded`（**不是** `tool == NOTE`），故只要调板开着它就亮，点 选择/框选 不改变它。调板**默认展开**（`paletteExpanded = true`）。
- **进入音符录入（NOTE）模式**：**不再由音符笔按钮触发**，而是**在调板上选了一个值（或按对应快捷键）且当前无可编辑选区**时，经 `NoteToolState.enterNoteEntry()` 进入（`tool = NOTE` 并保证调板展开）。Esc / 点选择工具退出。
- **调板与工具解耦**：调板显隐由 `paletteExpanded` 单独控制，**不绑定 `tool`**。`NotePalette` 内分两个条件（与键盘 `SelectionEditor.active` 一致，保证调板点击与键盘行为相同）：
  - `reflectSelection = tool != NOTE` —— 决定**高亮来源**：SELECT/MARQUEE 时来自 `PaletteSelectionInfo`（**无选区 → 空 → 无按钮按下**）；NOTE 模式来自 `NoteToolState` 默认值。
  - `editingSelection = reflectSelection && selectionInfo.editable` —— 决定**点击行为**：有可编辑选区时编辑选区（`onEdit*` 回调）；否则设默认值并 `enterNoteEntry()` **开始录入**。
- **调板列**（右，`paletteExpanded` 时显示）：每行三个按钮。
  - 声部：`1 / 2 / 空` · `3 / 4 / 空`。NOTE 模式下设置下一次录入的声部；选择/框选且有可编辑选区时，高亮显示选中音符的公共声部，点击则把选中音符移到目标声部。乐谱中的选择高亮按声部使用固定循环配色：1 蓝、2 绿、3 紫、4 土黄；声部按钮数字上移，并在下方显示对应颜色短横线。
  - 时值：二全 / 全 / 二分 · 四分 / 八分 / 十六 · 卅二 / 六四 / 休止(toggle) · 单点 / 双点 / 展开
  - 展开后（`uncommonDurationsExpanded`）：四全(longa) / 八全(maxima) / 128 / 256 🚧（256 无对应 `DurationBase`，按钮禁用）
  - 变音记号：♯ / ♭ / ♮ · x / ♭♭ / 展开 🚧（展开按钮暂无逻辑）
  - 连音线 / 圆滑线：同一行两列；左侧延音线仍可作为输入 toggle，右侧圆滑线仅在选择模式下启用。图标均以 Bravura 的 SMuFL 四分音符字形配合手绘弧线组成：延音线连接同音高，圆滑线连接不同音高
  - 装饰音 / 小音符：位于连音线 / 圆滑线下方。装饰音模式沿用当前时值并显示缩小的
    ghost note；点击普通时间锚点会新建/追加组，点击已有装饰音槽位会并入和弦。小音符按钮
    只在完整休止选区上启用，把选区一次性转换为占拍、隐藏括号的缩小多连音区域，按钮不保持
    选中。转换后继续使用普通音符工具；落点位于该区域时自动按小音符输入，区域外仍是普通音符。
  - 连音组：按当前总时值推荐常用 N 连音，另有最近使用与自定义 N；有选区时把单声部、单小节、完整覆盖的区间改成连音，录入时把当前时值视为连音组总跨度并插入第一个音。
  - **休止按钮仅作输入模式**（设定下一个插入元素），不是可编辑属性：只在 NOTE 模式高亮，点击它经 `enterNoteEntry()` 开始录入并翻 `restMode`。

`NoteToolState`（Compose snapshot state，由 `App` 持有，工具栏写、画布读）暴露：`tool`、`paletteExpanded`、`durationBase` + `dots`（合成 `duration`）、`restMode`、`accidental`、`tieMode`、`activeVoiceNumber`、`tupletCount`、`recentTupletCounts`。

- **改时值取消附点**：切换时值经 `pickDuration(base)`（调板按钮与键盘快捷键共用）——`base` 与当前不同时清零 `dots`（附点属于旧时值，沿用到新时值会悄悄改变它）；重复点同一时值则保留其附点。

## 键盘快捷键

音符录入也可用键盘：时值 `1/2/4/8/6/3`、休止 `0`、附点 `.`/`Shift+.`、变音 `s`/`f`/`n`（`Shift` 为重）、连音线 `-`、连音组 `Alt+2..9`、声部 `Ctrl+1/2/3/4`、撤销/重做 `Ctrl+Z`/`Ctrl+Y`。

- **NOTE 模式 / 无选区**：按键写的是同一份 `NoteToolState`，等价于点调板按钮（经 `activate()` → `enterNoteEntry()` 进入 NOTE 并展开调板）——设定下一个插入音符；`Ctrl+1..4` 设定下一次插入的声部。
- **SELECT/MARQUEE 且有选区**：时值 / 附点 / 变音 / 连音线 / 连音组 / 声部快捷键**改的是选中音符**（键盘镜像调板的编辑模式）。`handleEditingShortcut(event, session, noteTool, editor)` 多收一个 `SelectionEditor`：`editor.active`（= `tool != NOTE && 有可编辑选区`）为真时路由到选区编辑，否则照旧改默认值（并 `activate()` 切到音符笔 + 展开调板，使高亮镜像键盘）。设置/清除的 toggle 决策由 `App` 端按选区聚合 `PaletteSelectionInfo` 决定，两个入口（调板、键盘）一致。休止键始终只作输入模式。

快捷键架构（`KeyStroke` / `ShortcutAction` / `KeybindingStore` / `handleEditingShortcut`）、可在设置对话框里重绑定的交互、以及**如何为新功能定义快捷键**，统一见 [settings.md](settings.md)。

### Esc

`App.onKeyEvent` 直接拦截 `Key.Escape`（不经可重绑定的快捷键）：

- **选择 / 框选**：清空全部选择。
- **音符录入（NOTE）**：清空选择并切回 `EditTool.SELECT`（调板保持打开）。

## 画布交互（悬浮 + 点击）

`apps/desktop/.../ui/views/RenderedScoreView.kt`，由 `noteToolActive = noteTool?.tool == EditTool.NOTE` 驱动。

### 音符即时试听

- 录入或完成单个音符/和弦的属性编辑后播放一次短 preview；休止符与多事件批量编辑不播放。录入回调显式携带本次提交后的 `RuntimeScore`，不能读取仍待 Flow 同步的旧 `ScoreSession.state`，否则新事件无法解析所属乐器并会错误回退到钢琴。
- 单击音符头只播放该音，单击符杆播放其所属和弦；试听只读取该 `ComputedVoiceEvent`，不合并同一时刻的其他声部或谱表。
- Shift 多选仅在目标从未选中变为选中时播放；取消选择不播放。框选无论是否按 Shift 都不播放。
- 拖动音高时，每跨到一个新的实际音高播放一次该事件的完整结果和弦；部分和弦拖动只改变被拖音，未被拖音保持原音高。其他选中事件不加入试听，MIDI 边界夹取后实际音高未变化时也不重复触发。`PlaybackController.audition` 把 computed MIDI 音高与所属乐器的 bank/program 交给 `AudioEngine.audition`；JVM 后端首次使用 preset 时先 pin 其动态 SF3 样本，并用短生命周期的独立 note-on/off 播放，不加载乐谱、不移动播放头；已 pin preset 在本次音频会话中保持加载，切换乐器再返回不会让首音回退钢琴。乐谱允许保留 MIDI 0–127 之外的书写音，但所有音频出口统一通过 `audio/model/MidiNoteRange.kt` 过滤：越界音静默跳过，不夹到边界音；和弦内仍在范围内的音继续播放。
- 选择 / 框选共用的 tap 手势在 `noteToolActive` 时直接 `return`，与音符笔互不干扰。
- 音符笔的 `pointerInput`（连续与分页模式均支持）：
  - `Move / Enter` → `RenderEngine.computeGhost(...)` 计算并显示灰色虚影音符（真实排版几何，符杆与谱面对齐）。
  - `Exit` → 清除虚影。
  - `Press` → 用虚影的 `(voiceTrackId, onset, pitch)` 加上工具栏的 `duration / restMode / tieMode` 构造 `NoteEditEngine.Insertion`，调用 `onInsertNote`，并 `change.consume()`。
- 分页模式：先用 `designToGlobal` 把页内光标映回全局坐标再求虚影；绘制时虚影几何为全局坐标，用 `globalToDesign(anchor)` 求出所属页的平移量后整体平移绘制（同播放头）。

### 谱号笔虚影 `RenderEngine.computeClefGhost` → `GhostClefComputer`

`EditTool.CLEF` 激活时同样逐帧计算虚影：一枚谱号字形 + 一根竖线标示插入生效位置。竖线落在**插入 onset 右侧相邻音符的左缘**——即该时槽的最左元素边（`TimeCodePosition.leftX`）。落在小节开头（downbeat）时，最左元素是本小节的起始小节线（它位于更靠前的独立时槽），故 `leftX` 会把该小节线一并纳入，竖线与小节线重合；小节中间则贴住音符组左缘。`leftX` 由 `RenderResultAssembler.computeTimeCodePositions` 单趟计算：按时间序（系统内即 X 升序）累积「上一个音符槽之后到当前音符槽之间」所有槽的最左边，遇音符槽结算、跨系统重置。

### 框选与多选

选择模型为 `Set<EventSection>`（单选即大小 ≤ 1 的特例）：`App` 持 `eventSelection: Set<EventSection>`，只认单目标的下游（顶栏时间码、右侧面板）取 `lastOrNull()`（插入序即「最近所选」）。`RenderedScoreView` 暴露 `selection: Set<EventSection>` + `onSelectionChange`，并以可配置的 `marqueeSelectableTypes: Set<RenderElementType>`（默认 `{NOTEHEAD, REST}`）约束框选范围——不同功能可传不同集合。

分页换行处的可见小节线也属于选区：前一行行末的替代小节线登记为 `SYSTEM_END`，后一行行首线登记为同一逻辑边界的 `SYSTEM_START`。两者均可点击并高亮，同时保留位置语义供插入小节判断使用；结构渲染分支必须把对应 `BarlineSection` 登记到 `SectionIndex`。

- **Shift 跟踪**：tap / drag 手势回调拿不到修饰键，故用一个 `Initial`-pass 的 `pointerInput` 循环把 `event.keyboardModifiers.isShiftPressed` 镜像到 `shiftHeld`，供两个选择手势读取。
- **单击**（选择 / 框选共用）：命中元素经 `selectByPriority()` 取代表 section；无 Shift = 替换为单选，Shift = 在集合内 toggle（已选则取消）。空白处无 Shift 清空、Shift 保留。
- **框选拖动**（仅 `EditTool.MARQUEE`）：拖动时禁用平移（`panEnabled && !marqueeActive`），`detectDragGestures` 拉橡皮筋框并以原始指针坐标绘制（在 `graphicsLayer` 变换**之外**的覆盖 Canvas，故边框粗细不随缩放变化）。松开后把选框映射到全局坐标，**重叠判定下沉到 renderer 的空间索引** `RenderResult.hitTestRegion(rect, marqueeSelectableTypes)`（见 [renderer/spatial-index.md](../renderer/spatial-index.md) §4a），逐命中元素取 `sections.selectByPriority()` 收集；Shift 并入既有集合，否则替换。
- **分页**：选框可跨多页，逐页裁剪到页槽位、经 `designToGlobal` 映射成该页全局矩形分别 `hitTestRegion` 再并集。

### 拖动平移音高

在 `SELECT` 与 `MARQUEE` 工具下，于音符上**按下并拖动**即平移其音高（纵向），手势整合进既有的「平移画布」/「框选」两个 `detectDragGestures` 的 `onDragStart` 分支（避免新增竞争的 `pointerInput`），不改横向时间（onset）。

- **触发与移动目标**（`resolveMoveSections` + `buildTransposeTargets`）：
  - `SELECT`：拖动起点命中音符（`EventSection.movableEvent() != null`，排除休止符）即进入平移。被移目标：命中音符**已在选区** → 整个选区一起移；否则按住 **Shift** → 选区 + 命中音符；否则 → 仅命中音符（并将选区设为它）。
  - `MARQUEE`：仅当命中音符**已在选区**时拖动才平移（目标 = 整个选区），否则照常拉框选。
  - 目标聚合同 `buildDeletions`：`VoiceNoteSection` → 仅移该和弦音；`VoiceEventSection` → 整事件所有音。
- **音级增量**：起点与当前点经 `rawToAbsolutePoint`（去 pan/zoom/density，分页经 `designToGlobal`）→ `transformerSnapshot.toRelative` 取相对 Y，`stepDelta = round((startRelY − curRelY) × 2)`（每音级 = 0.5 谱距，上移为正）。
- **音高语义**（见下「编辑引擎 `transpose`」）：按 diatonic 音级移动，新音按所在小节**调号默认拼写**——丢弃临时升降号。
- **无重排预览**：拖动中**不重新排版**。`RenderEngine.computeTransposePreview(result, runtime, computed, targets, stepDelta)` → `TransposePreviewComputer`（结构同 `GhostNoteComputer`）在**原 X、新音高**处用真实制谱构建器**重绘整音符**（符头/符干/符尾/加线），原音符经一条 `StyleOverride(hidden = true)` 的 style track 隐藏整个 `VoiceEventSection`（其各子元素——符头/符干/符尾/加线/变音/附点——均注册了它）；`StyleSnapshot.isHidden` 跨该元素所有 section 判断，与按 `order` 取色解耦。
  - **带符杠的音符保留符杠**：纵向拖动不改 X，符杠位置不变——故**不隐藏也不重绘符杠**，只把被移音的符干**重连到原符杠**。预览从渲染结果里读该事件原 `STEM` 元素离符头较远的那个端点（即贴符杠的一端，`beamTipOf`），新符干从新符头吸附点画到这个点；不画符尾（已成组）。组内未移动的音符及其符干原样保留，符杠端点不变，故符杠始终连着。
  - **两层着色**：预览返回 `baseCommands`（黑）+ `movedCommands`（选区蓝，覆盖在上），UI 分两遍 `renderCommandsTinted` 绘制。**整事件移动**（含选中和弦全部音）→ 全部进 `movedCommands`（整音符蓝）；**和弦内部分移动**→ 只有被移音头（及其变音/附点）进 `movedCommands`，其余音头与符干/符尾/加线进 `baseCommands`（保持黑）。音头按 `NoteheadRenderInfo.pitchIndex` 是否属被移集合分流（重绘时不打乱 `pitchData` 顺序，故 pitchIndex 仍对应原下标）。故部分拖动时**符干仍在**、只有被拖音着色。
  - **越界夹取**：`DiatonicTranspose.clampDelta` 把 `stepDelta` 向 0 夹取，使被移音不超出 MIDI 0–127（`NoteEditEngine.transpose` 用同一函数夹取，预览与提交一致）。夹到 0（已在顶/底）则预览为 null。
  - **预览为空即不隐藏**：`stepDelta == 0` 或夹取后为 0 时预览 null，隐藏轨道按「有无预览」判断（keyed on `transposeDrag.preview != null`），故原音符保持可见、不消失。
- **松手提交**：`onDragEnd` 若 `stepDelta != 0` 调 `onTranspose(targets, stepDelta)` → `ScoreSession.applyNoteTranspose`，走真实增量 compute + render 重排（引擎再夹取一次 delta）。被移事件按**原粒度**保持选中：整事件 → `VoiceEventSection`，部分移动 → 仅被移音头的 `VoiceNoteSection`（依 `TransposeResult.movedEvents` 携带的移动后新下标解析，不会把整个和弦选中）。`onDragCancel` / 切回 `delta == 0` 清空预览。
- **提交期保持预览，不闪回原音 / 不留空帧**：提交是异步的（off-thread compute + render），其间 `rememberRenderResult` 仍保留旧帧。`onDragEnd` 置 `committing = true`保持预览，并在调用编辑回调前同步保存当前 `renderResult` 引用；`LaunchedEffect(committing)` 以该引用为基线等待新帧，避免快速渲染先于 effect 启动而被误认为旧帧。原音隐藏现是仅合并到本 Canvas 的临时 `displayStyleSnapshot`，不再创建引擎全局 `StyleTrack`；新帧到达后清空 `transposeDrag`，预览与临时隐藏在同一次重组中一起撤销，提交音同帧可见。期间覆盖 `Box` 临时禁止乐谱交互；更新提示不再使用从鼠标松开开始的拖动专用计时器，统一由 render-generation 状态驱动，与撤销/重做共用同一起止口径。`COMMIT_HOLD_TIMEOUT_MS`（5s）作为新帧等待兜底。
- **提交期保持预览，不闪回原音 / 不留空帧**：提交是异步的（off-thread compute + render），其间 `rememberRenderResult` 仍保留旧帧。`onDragEnd` 置 `committing = true`保持预览，并在调用编辑回调前同步保存当前 `renderResult` 引用；提交交接 effect 以 `renderResultIdentityKey` 为 key，新帧发布时直接触发清理，不再每 8ms 轮询。原音隐藏仅合并到本 Canvas 的临时 `displayStyleSnapshot`，新帧到达后清空 `transposeDrag`，预览与临时隐藏在同一次重组中一起撤销，提交音同帧可见。期间覆盖 `Box` 临时禁止乐谱交互；更新提示统一由 render-generation 状态驱动，与撤销/重做共用同一起止口径。独立 `LaunchedEffect(committing)` 仅保留 `COMMIT_HOLD_TIMEOUT_MS`（5s）失败兜底。
  - 交互遮罩不等待交接 effect 的状态清理尾巴；`commitInteractionBlocked = committing && !committedFrameDisplayed`，首次持有新 `RenderResult` 的组合即解锁，与提交帧可见时点一致。
  - 拖动提交在 `ScoreStateManager.commitNewState` 后立即将 `mgr.currentState` 同步发布到 `ScoreSession.state`，再执行 `onAfter` 更新选区；不等待 `StateFlow` collector 异步回传，避免 Compose 先以「新选区 + 旧乐谱」做一次无效重组。

### 拖动移动休止符

休止符不平移音高，而是**纵向拖动调整显示谱位**（多声部避让）。手势复用拖动平移的同一条链路（`onDragStart`/`onDrag`/`onDragEnd` 的 `TransposeDragState` 生命周期——隐藏原件、保持预览、提交挂起两阶段换帧都与音符一致），仅分支为 `DragMode.REST_MOVE`：

- **触发**：拖动起点命中休止符（`EventSection.restEvent() != null`，即 `VoiceEventSection` 且 `isRest`），按与音符相同的选区规则（`SELECT` 直接进入、`MARQUEE` 需已选中）。命中既有音符走平移、命中休止符走移动（`startTranspose` 不成则 `startRestMove`）。
- **谱位增量**：同音符 `stepDelta = round((startRelY − curRelY) × 2)`（每步半个 staff space，上移为正）。每个目标休止符的当前有效谱位 = `rendering.restStaffPosition ?: RestLayout.defaultRestStaffPosition(duration)`，新谱位 = 起始有效谱位 + `stepDelta`。
- **无重排预览**：`RenderEngine.computeRestMovePreview(result, computed, targets)` → `RestMovePreviewComputer`，用 `NoteBodyElementBuilder.buildRestElement(duration, 新谱位)` 在原 X、新 Y 处重绘休止符字形（无符干/和弦逻辑），原休止符经 `StyleOverride(hidden = true)` 隐藏。复用 `TransposePreview` 结构：`baseCommands` 空、字形入 `movedCommands`（选区蓝）。
- **松手提交**：`onDragEnd` 调 `onMoveRest(targets)` → `ScoreSession.applyRestMove` → `NoteEditEngine.moveRest`，把每个目标的绝对新谱位写入 `RenderingProps.restStaffPosition`；**拖回类型默认位置时归一为 `null`**（清除覆盖）。单个被触小节走增量重排。

### 虚影定位 `RenderEngine.computeGhost` → `GhostNoteComputer`

虚影计算下沉到 renderer（`renderer/.../render/edit/GhostNoteComputer.kt`），与 `SpatialIndex` 复用谱表/系统解析逻辑，并用**与真实音符相同的排版构建器**生成符头/符杆/符尾/加线——保证虚影符杆与落子后完全一致。纯函数、无状态，可在指针线程逐帧调用。输入为命中检测同一套绝对坐标（`result` 自带 `transformerSnapshot`）。

1. **选谱表**：`result.spatialIndex.staffAt(relPoint)` 复用层次空间索引的 `StaffRegion`（含 system Y 偏移），优先落在包含光标的谱表、否则取中线最近者——天然支持分行/分页。返回 `(systemIndex, staffIndex, centerY)`。
2. **Y → 音高**：`staffPos = round((centerY − relY) / 0.5)`（相对坐标下每半线间距一音级）；叠加谱号中线音级 `middleLineDiatonicSteps(clef)`。变音半音偏移按「工具栏显式变音 → 否则本小节同音级延续 → 否则还原」解析：工具栏未选变音时，`carriedAccidentalOffset(voice, onset, diatonicSteps)`（经 `voice.events.before(onset)`）取本小节、同谱位、最近一个音符的 `chromaticOffset` 继承之——符合记号在小节内延续的常规，落子即与前面已升降的同音匹配，而非默认还原为本位。`effectiveAccidental` 仍按工具栏选择，故延续而来的变音**不再重画字形**，与落子后经 `EffectiveAccidentalComputer` 的排版一致。
3. **X → 吸附 onset**：候选 `TimeCode` = 当前编辑声部所有 onset ∪ 各小节整拍点（`ts.beatUnit * i`）。用 `result.timeCodePositions` 精确查绝对 X；缺项时按**绝对全音符位置**（按各小节拍号求和，跨变拍号正确）线性插值。取与光标 X 最近者。旧谱面可能尚无目标声部轨道；此时预览先用同 staff 的现有声部提供吸附候选，提交时由 `NoteEditEngine` 懒创建目标声部。借用其他声部候选时只保留约分后分母为 `2^n` 的位置（如 `3/16`），禁止把三连音中点 `1/12` 等非二进制网格泄漏到新声部；目标声部自身已存在时仍保留其全部 onset，故其内部 tuplet 可继续编辑。
   - **限定本行**：候选先按 `staffAt` 命中谱表的绝对 Y 落在 `TimeCodePosition.topY..bottomY` 的 system 过滤（同播放头的逐行 Y 带）。分行模式每行 X 都从左边距重新起算，不过滤会把光标 X 误吸到别行的同 X 小节、落子串行。
   - **off-beat 可达**：候选取自**运行时声部事件**（含休止符，`pitches` 为空即休止），故八分附点后留下的 16 分休止（onset `3/16`）这类非整拍位置也能吸附——前提是编辑后 `orderedStaffs()` 返回的声部事件是最新的（见下「编辑引擎」`staffGroups` 同步）。
4. **几何 + 右端对齐**：用 `NoteBodyElementBuilder` + `VoiceEventLayoutBuilder` + `StemDirectionResolver`（按当前声部取默认符干方向）生成 `RenderCommand` 列表（绝对坐标），休止符则只画休止符字形。注意 `timeCodePositions.x` 是该 slot 内容的**右边缘**（比例排版约定，见 `ProportionalLayoutComputer.computeXPositions`：休止符右边缘恰贴 X，音符略偏左留尾随间距），故音符整体左移使**符头右边缘**落在吸附 onset 的 X（`drawOffset.x = snapRelX − 符头右伸量`）；休止符仍左端贴 onset。`anchor` 随同偏移（分页时定位所属页）。返回 `GhostNote(voiceTrackId, staffTrackId, voiceNumber, onset, pitch, commands, anchor)`。

> 几何沿用真实排版，符杆方向/长度/落点与落子后一致；吸附手感仍可按需微调。

### 空小节最小宽度 `padEmptyMeasures`

`RenderLayoutConfig.padEmptyMeasures`（默认 `false`，快照测试沿用紧凑间距）。开启时，`UnifiedLayoutComputer` 给「只含休止符」的小节补足到 `minimumMeasureWidth`，方便在空小节里悬停/逐拍落子；主编辑视图 `RenderedScoreView` 传入 `true`（缩略图 `SimpleScoreView` 仍用默认），未来自动分谱亦依赖它。补宽只推进小节内容右边界、不移动休止符时槽；增量 X 求解必须从下一根小节线反推出缓存右边界，禁止拿最后一个休止符时槽代替，否则每次拖动重算都会把最小宽度重复累加。

### 落子即选中

`NoteEditEngine.Result.insertedEventId` 返回新音符（或被并入的和弦）的 id；`ScoreSession.applyNoteEdit(insertion, onInserted)` 在提交后解析出 `VoiceEventSection` 回调；`App` 据此设置 `eventSelection`，使新插入的音符默认选中。

落子后 `App` 的 `onInsertNote` 还会 `noteTool.accidental = null`：变音记号是一次性选择，落到音符上即清除，避免下一个音符被意外升降（已升降的同音延续由上文虚影第 2 步的小节内继承负责，不依赖工具栏保留选择）。

### 选区随撤销栈（编辑器状态注册表）

选区（`eventSelection`）**不属于乐谱**，但需随撤销/重做一起回退——否则「多选 → 拖动平移 → 撤销 → 再拖动」会因选区仍指向平移后（已失效）的 section 而只命中单音。为此 `ScoreStateManager` 提供一组**可注册的编辑器状态**接口（`EditorStateController { capture(); restore(snapshot) }`）：每个历史条目并存一份编辑器状态快照（与 `historyStack` 平行的 `editorSnapshots`，**不改 `ScoreState` 结构**，便于后续新增更多状态）。`commitNewState` / `undo` / `redo` 在**离开**当前条目前 `capture` 当前状态、进入新条目后 `restore` 该条目记录的状态。`App` 把 `eventSelection` 注册为 `"selection"`（`session.registerEditorState`，经 `ScoreSession` 在换文档时对新 manager 重新注册）。撤销回到某状态时，恢复的 section 引用的正是该状态的 computed（撤销复原的是同一 `ScoreState` 实例），故与命中测试的 section 相等、多选拖动可继续整体平移。

### 选中项属性 + 删除

选中项详情显示在**右侧面板「选中项属性」**（`RightPanel.kt`，原乐谱左下角的浮层已移除）：单选时列出类型（音符 / 和弦 / 休止符）、位置、时值、音高；多选时显示「已选 N 项」。有选择时面板底部出现**删除按钮**。

删除入口有二，都走 `App.deleteSelection`：删除按钮，或 `Delete` 键（可在设置里重绑定，见 [settings.md](settings.md)，`ShortcutAction.DELETE`）。窗口预览键盘分发器在子控件获得焦点时仍会把 Delete / Copy / Cut / Paste 路由到当前乐谱选区，内容区 `onKeyEvent` 保留为后备。`buildDeletions(selection, runtime)` 把 `Set<EventSection>` 按所属事件分组（`VoiceNoteSection` → 删和弦音；`VoiceEventSection`（含休止符）→ 整体删；同一事件的整体删优先于按音删）映射为 `Deletion` 列表，删除后选区指向产生的休止符。

复制 / 剪切 / 粘贴入口有二：顶栏编辑按钮组，或 `Ctrl+C` / `Ctrl+X` / `Ctrl+V`（可重绑定）。`App.buildCopyTargets` 从选区生成 `CopyTarget`，并把 Computed 层的源符杠结果冻结成 `RenderingProps.beaming`，所以自动符杠复制后也保持源位置的分组；音高对象原样进入剪贴板，粘到不同谱号 / 调号位置也不重拼写。若某条圆滑线的首尾音符都在复制目标中，`NoteClipboard.slurs` 会记录其声部偏移与端点源 ID，粘贴在新音符生成后以新 ID 重建该 slur；只复制单个端点时不携带。粘贴目标取当前选区最近一个音符 / 休止符 onset；空小节上的隐式整小节休止符用 `originVoiceTrackId` 定位目标声部。剪切只删除成功写入音符剪贴板的音符 / 休止内容；即使选区是整小节，也不会复用“删除小节”按钮的结构删除语义。

### 编辑选中音符属性（调板 / 快捷键）

在 选择/框选 模式下，调板与快捷键就地修改选中音符的**时值、附点、变音记号、连音线、连音组、符杠、声部**（休止符不在此列，休止符仍只支持纵向显示位置调整；连音组可包含选区内休止符）。

圆滑线按钮要求至少一个声部选中两个非休止音符；点击后按声部分组，分别连接该声部时间最早与最晚的选中音符。圆滑线本身是 `VoiceSlurSection`，可用 `Delete` 或右侧“选中项属性”删除；单选圆滑线时，右侧属性还可把弧线切换到音符上方 / 下方，方向作为该稳定 slur ID 的 `SlurGeometry.above` 覆盖持久化。方向属性会将几何标记为 `directionOnly`，renderer 在目标侧重新选择和弦外缘音头并执行自动端点、避让与弧高排版，而不是只翻转曲率；该纯几何编辑不携带音乐内容的增量 `RenderHint`，避免拼接器复用旧 SLUR 图元。首次编辑 legacy 计数型 slur 时，`SlurEditEngine` 会先把同轨既有计数配对提升为显式 `RuntimeSlur`，再执行增删，避免旧圆滑线丢失。

- **粒度按属性分**：
  - **时值 / 附点**作用于**整个事件**（整和弦）——选了和弦上的部分音也调整整个和弦。`App.durationTargets()` 把选区收敛为去重的 `(TrackId, ComputedVoiceEvent)`。
  - **变音记号 / 连音线只改和弦中被选中的音**（不波及未选的同和弦音）。`App.pitchSelections(selection)` 按事件分组并记录被选音头下标（整事件选中 → 全部音）；`pitchTargets()` 产出 `(TrackId, EventId, 选中下标集 | null=整和弦)`，下传引擎的 `AccidentalEdit.pitchIndices` / `TieEdit.pitchIndices`。
  - **声部也只移动被选中的音**：整事件选中 → 整个音符/和弦移到目标声部；只选中和弦部分音 → 原声部移除这些音头，目标声部新增这些音头，未选音头留在原声部。
- **高亮聚合 `PaletteSelectionInfo`**（`paletteInfoFor`）：仅当**所有**相关目标在某属性上一致时该字段非 null，否则 null（按钮不高亮）。时值/附点按事件聚合；**变音记号 / 连音线只聚合被选中的音头**（与上面的 per-pitch 编辑一致）。变音记号用**显示记号** `effectiveAccidental`（调号拼出的音不误亮，抵消用的还原号会亮）；连音线看被选音是否向后系。
- **set/clear 决策在 `App` 端**：调板按钮与快捷键都把原始按钮值交给 `App` 的 `SelectionEditor`，由它按聚合值决定切换（点已高亮的变音 → 清除回调号默认；点附点 → 在 0↔n 间切换；连音线 → 取反）。
- **连音组选区约束**：只允许单声部、单小节、区间完整覆盖已有事件且原区间内没有嵌套连音。引擎按 `normal/count` 缩放事件 onset 与实际时值，再在尾部补连音休止符；例如八分休止+八分音符改三连音后各占一个三连音拍，四分音符改三连音后占前两个三连音拍。
- **编辑引擎**：见下 `editDurations` / `editAccidentals` / `editTies` / `applyTuplets` / `moveVoices`（复用 `replaceVoice`）。
- **符杠按边连接**：单音符的“向左 / 向右 / 左右都连”表示请求连接该音符对应一侧的边；任一端请求即可建立双向可见的符杠边。因此 MusicXML 导入为显式 `BeamingInfo.NONE` 的断开邻音，也能由所选音符的单侧操作重新连接；未被请求的另一侧仍保持断开。“将选中音符作为一个符杠组”则批量写入 `start / middle / end`。
- **时值冲突整批拒绝**：普通时间轴上，选中音符之间改长后相互重叠（如两相邻四分同改二分）会返回 `EditOutcome.Conflict`、**整批不改**；改长吞掉未选中的相邻音符 / 休止符仍允许。普通 tuplet 成员始终继承原 ratio，新实际终点超过 `TupletSpan.endTimeCode` 时同样整批拒绝，尤其禁止末音继续伸出组外。合法缩短只在成员释放的组内区间补同 ratio 的 tuplet 休止符，不走普通小节补休止。`App` 对冲突显示 `i18n("edit.durationConflict")`。
- **不能改刚插入的音符**：插入后该音处于选中且 `tool` 仍为 NOTE，而 NOTE 模式调板/快捷键只改**默认值**不碰选区——故刚插入的音不会被误改（用户通常是给下一个音设属性）。

## 编辑引擎 `NoteEditEngine`

`core/.../engine/edit/`（纯函数，输入/输出 `RuntimeScore`；单测 `NoteEditEngineTest`）。`NoteEditEngine.kt` 是 API 外壳——保留所有请求/结果类型与公开入口，按功能委托给同包内的 `NoteInsertion` / `NoteCopyPaste` / `NoteDeletion` / `NoteTranspose` / `NotePropertyEdits`（时值/变音/连音线/符杠/休止符位置）/ `VoiceMoveEngine`；这些又建立在共享原语 `EditGeometry`（坐标换算，含 `absolute`）、`StaffTrackOps`（声部/谱表解析与重建，`replaceStaffsInGroup` 与 `ClefEditEngine` 共用）、`VoiceSpanEditing`（`clearInterval` / `fillRange` / `fillGaps` 等区间物化）、`TupletSupport`（连音组配比与休止符）之上。坐标统一换算为**绝对全音符单位**（`absolute`）比较重叠。

`insert(runtime, insertion)` 流程：

1. **和弦判定**：同一 onset 上存在「非休止、且时值相同」的音符 → 把音高并入其和弦（按 `midiNumber` 稳定排序）。允许同音高重复和等音拼写，不按 MIDI 去重；同谱位且变音不同的音组由 Computed 层强制物化双方 `effectiveAccidental`（含调号本可省略的还原号），Renderer 将符头分置符杆两侧，并按从上到下、就近无碰撞列排列变音记号。
2. 否则：`clearInterval` 清出区间 → `fillRange` 物化新音符/休止符 → `fillGaps` 补齐小节空洞。
3. 返回新 `RuntimeScore` 与受影响的 `editInterval`（供未来增量计算）。

若 `Insertion.tupletCount` 非空，`duration` 表示连音组总跨度：引擎先选 `Tuplet(actual=count, normal)` 与 `beatUnit`（如四分三连音为 `3:2 + EIGHTH`、附点四分二连音为 `2:3 + EIGHTH`），再插入第一个连音音符/休止符并用连音休止符补满组；总跨度不得跨小节线。悬浮预览显示的是**单个成员的 `beatUnit` 符号**，其上附带只有起点钩、另一端开放的连音括号与数字，用来区分“即将创建一组”与普通长音。提交成功后 UI 退出连音组录入状态，并把当前时值切到 `beatUnit`，方便继续点入组内后续音。

若普通插入落在既有连音组内部，工具栏的 `duration` 按**显示时值**解释：例如当前时值为十六分、落点在 `3:2` 三连音内，引擎实际写入 `Duration(SIXTEENTH, tuplet = 3:2)`，实际长度为 `1/24` 全音符（`1/6` 拍）。清除被覆盖区间时，左右残片也先换回显示时值分解、再重新附上相同 tuplet ratio，不能交给普通二进制时值分解。删除整个连音成员则原位写回同显示时值、同 ratio 的休止符；首成员携带的 `TupletSpan` 保留。插入仍拒绝越出该连音组 span。

### 装饰音与小音符输入

`Insertion.grace != null` 进入零计量时间的装饰音编辑路径。锚点可在普通音符之间、拍首、
小节首或小节末尾；同一 `(measure, beat)` 的新成员始终追加在现有组尾，随后按音乐顺序重新
均分 `[-1,0)` grace 轴。
只有第一项保存 `GraceNoteInfo(totalDuration, stealFrom)`，删除第一项时元数据自动转移。
右侧选区属性可修改组总时值，以及占用之前音符（`PREVIOUS`）或之后主音
（`PRINCIPAL`）的时值；单个成员的显示时值仍由普通时值调板修改。

小音符通过 `createSmallNoteRegions` 把单声部、单小节、完整覆盖且全为休止的选区转换为
`TupletSpan(smallNotes=true, displayStyle=NONE)`。区域仍占用原休止时长，事件携带
`RenderingProps.scale=0.7`；区域内继续输入会继承同一连音比与缩放，选择的显示时值可任意
细分/覆盖区域，因此成员数量可随编辑变化。转换时按区域总长度和 tuplet beat unit 重新生成
恰好填满右开区间的占位，不保留源休止符的显示时值，禁止占位越过区域末端。尚未输入的容量
由 `hidden=true` 的内部休止片段
保持计量与吸附；区域起点保留一个专用颜色的休止符标记，其余内部占位不生成字形。普通音符
ghost 会根据吸附后的 onset 自动判断是否位于 `smallNotes` 区域：区域内生成更细的动态吸附
网格并以 0.7 比例预览、提交，区域外不缩放。用户无需切换“小音符输入模式”，可沿区域继续
向后追加。区域当前成员占满固定跨度后，末音与右端点之间的空白提供显式追加热区；编辑器
按组内所有成员各自选择的显示时值重新计算共用 tuplet ratio、更新 `TupletSpan.count` 并从
区域起点重新排布，使第五个及后续成员继续落在原区域内，而不会进入普通时间轴或触发普通
休止符填充。区域右端恰好是小节线时，动态网格按绝对时间推进并把边界规范化为下一小节
`beat=0`；禁止生成 `上一小节:超过小节长度` 的 onset。Core 插入入口也会把等价或越界的
拍内位置规范化后再做时值分解，避免产生负长度片段。组内已有音符占满区域后，最后一个
小音符符头右缘到区域末端之前成为追加热区；点击这段空白即可追加，ghost 显示在末音之后。
追加请求携带组首事件 ID，Core 不从时间点猜测目标；区域末端自身及其之后始终按普通音符区域
处理，命中后继正常音符时优先在该音符上输入和弦。普通插入清理区间会按声明的
`TupletSpan` 原样隔离已结束的小音符组，避免编辑相邻小节时重建前组或改变后一空小节休止符。
修改已输入小音符的时值时，只替换目标成员的显示 base/dots；其他成员的 ID、音高和显示时值
保持不变。引擎按新的显示时值总和重算公共 ratio 并在固定 span 内重新排布所有已输入成员，
同时删除内部容量占位，不调用普通 `fillGaps`，因此不会在成员之间或后一小节乱插休止符。

若 `Insertion` 携带 `staffTrackId + voiceNumber`，引擎会在该 staff 上解析目标声部；目标声部不存在时自动创建 `RuntimePitchTrack + RuntimeVoiceTrack` 并同步 `staffTracks` / `staffGroups`，旧谱面无需预先迁移到四声部结构。

`copyNotes(runtime, targets)` / `pasteNotes(runtime, clipboard, target)`：复制收集非休止音符，和弦可按 `pitchIndices` 复制部分音头，并附带首尾端点都入选的圆滑线；粘贴按目标声部为锚点保留多声部相对 `voiceNumber`，为新音符生成新 `EventId` / `PitchEventId`，先清目标跨度再写入，最后以端点新 ID 重建 slur。长音若在目标位置跨小节线，会按目标小节边界拆成多个 tied pieces，末段保留复制来的尾部延音；但复制内容含连音事件或连音组 span 时，目标位置若使事件或组跨小节线则整次粘贴拒绝。当前不复制表情符号等其他独立标记，后续可扩展 clipboard 数据结构。

> ⚠️ **`replaceVoice` 必须同步 `staffGroups`**：`RuntimeScore` 的谱表数据**双份存储**——既在 `staffTracks` map，也内嵌在 `staffGroups` 各 `RuntimeStaffGroupMember.Staff` 里，而 `orderedStaffs()` 读的是后者。`replaceVoice` 在更新 map 的同时用 `replaceStaffsInGroup(...)` 递归把内嵌谱表指回重建后的实例，否则编辑后 `orderedStaffs()` 仍返回编辑前（常为空）的声部事件——渲染读 map 看似正常，但经 `orderedStaffs()` 的消费方（如虚影吸附）会拿到陈旧数据。

### clearInterval（左闭右开 `[start, end)`）

逐事件与清除区间比较：

- 无重叠 → 原样保留（保持事件 identity）。
- 完全被覆盖 → 删除。
- 跨越边界 → 裁剪为左/右残段。
- **完全包含**区间 → 拆成左右两个音符（保留原音高与演奏记号）。

残段经 `fillRange` 重新表达为时值（音符会按需加延音线）。

### fillRange（逐小节物化 + 跨小节连音）

按小节推进，每段：

- **音符**：`DurationDecomposer.decompose`（最少件数、可附点）；相邻件之间加延音线，因此跨小节会自然渲染为两个相连音符；`trailingTie` 额外给最后一件加尾延音。
- **休止符**：`restDurations`（见下），不加延音线。

### 休止符按拍对齐 `restDurations`

小节内每一步取**「能放下且对齐」的最大普通时值**（`largestAlignedRest`）：时值 `d` 须满足 `d ≤ 剩余` 且当前位置（自小节起算的全音符数）是 `d` 的整数倍——即休止符不能从比自身更强的拍点中间开始。`DurationBase.entries` 本就由大到小排列，第一个命中即为答案。

效果就是一条对齐曲线：起点不在强拍时，对齐约束**强制短→长**填到那个强拍；上到强拍后尽量合并成大休止符，再向末端**长→短**收尾。**不再一拍一个**——拍点对齐的跨拍段会合并（半休止、全休止等）。

示例（4/4）：

| 区间 | 结果 |
|------|------|
| beat 0 半音符跨度 `[0, 1/2)` | 一个**半休止符** |
| 整小节 `[0, 1)` | 一个**全休止符** |
| beat 0 四分音符后余量 `[1/4, 1)` | 四分休止符（beat 2）+ 半休止符（beat 3-4） |
| beat 0 卅二分音符后余量 | 卅二分 + 十六分 + 八分（短→长补到 beat 1）+ 四分 + 半休止符 |
| beat 2 起的半音符跨度 `[1/4, 3/4)` | 两个四分休止符（跨小节中点，半休止符非法） |

### fillGaps（小节补全）

插入后把受影响小节 `[start.measure, endMeasure]` 内的空洞（音符前、音符后、末小节余量）用休止符补满，使小节始终配平。落在小节线上的结束位置不会误填下一小节。

### delete（删除音符 / 休止符）

`delete(runtime, deletion)`，`Deletion(voiceTrackId, eventId, pitchIndices: Set<Int>?)`。当前仅支持音符与休止符，其他元素（谱号、小节线等）后续再加。两种情形：

- **和弦内删音**：`pitchIndices` 为和弦音高的**真子集** → 仅移除这些音头，事件保留；存活音高上的延音线按新下标重排，被删音高上的延音线丢弃。
- **整体删除**（整音符、和弦全部音高、或休止符）：把事件的 `[onset, end)` 区间用休止符**重新填充**（`fillRange(isRest = true)`），随后对受影响小节跑 `consolidateRests` **合并相邻休止符**。这一步是关键：单次 `delete` 只填自己那一小段，若不合并，「删掉一串 16 分音符的中间段」会留下一串 16 分休止符。`consolidateRests` 把同一小节内连续的休止符段按 `restDurations` 重排，故那段会合并成 `16分+8分+四分+四分+8分+16分`。合并幂等、不跨小节、只动 `[start.measure, lastMeasure]` 内的休止符（音符与其他小节按 identity 原样保留）。
- 因合并可能改写删除区间**之外**的相邻休止符，`Result.editInterval` 扩展到受影响的整小节，保证增量重算覆盖到。

返回 `Result`，`insertedEventId` 为合并后覆盖原 onset 的休止符 id，供删除后重选。未找到事件（如空小节的隐式整小节休止符无后端 runtime 事件）→ 返回 `null`（no-op）。

### transpose（拖动平移音高）

`transpose(runtime, targets: List<TransposeTarget>, stepDelta)`，`TransposeTarget(voiceTrackId, eventId, pitchIndices: Set<Int>?)`（`pitchIndices` 为 null = 整事件所有音）。只改音高、不动时值与 onset，故无需清区间/补洞，远比 `insert`/`delete` 简单：

- **MIDI 越界夹取**：先用 `DiatonicTranspose.clampDelta(被移音+调号, stepDelta)` 把 delta 向 0 夹取，使所有被移音落在 MIDI 0–127；夹到 0 → no-op（`null`）。预览计算器用同一函数，保证拖动预览与提交结果一致。
- 按 voice 分组，逐事件 `map`：被移音 `newPitch = DiatonicTranspose.spell(key, 原 diatonic + delta)`——按所在小节**调号默认拼写**，丢弃原音的临时升降号（「平移后默认删去临时升降号，音高按无临时记号来」）。和弦按 `midiNumber` 重排；被移音上的延音线丢弃，留存音上的按新下标重映射。
- 复用 `replaceVoice`（同步 `staffTracks` 与 `staffGroups`，见上 ⚠️）。
- 返回 `TransposeResult(score, intervals, eventIds)`：每个被触小节一个 widened 整小节区间（临时记号会影响本小节后续音的有效记号，故扩到整小节）。`stepDelta == 0` / 无目标 / 全是休止符或未知事件 → `null`（no-op）。

### 就地属性编辑 `editDurations` / `editAccidentals` / `editTies` / `applyTuplets`

这些方法统一返回 `EditOutcome`（`Changed(score, intervals, resultEventIds)` / `Conflict` / `NoOp`）——比 `Result` 多一个 `Conflict`，让 UI 区分「被拒绝」与「无变化」。

- **`editDurations(runtime, edits: List<DurationEdit>)`**：保留 onset、音高、演奏记号与「是否向后系延音线」，只换时值。
  - **冲突预检（在原始时间轴、按 voice）**：某被改音**改长**且其新末端越过**另一个同在批次里的音**的 onset → 整批 `Conflict`（过滤 `it.id in editedIds`）。缩短 / 同值恒合法；改长吞掉**未选中**的相邻音符 / 休止符也合法（被吞者交由后续 `clearIntervalEvents` 消除）。
  - 逐 `edit` 经 `replaceEventDuration`：删原事件 → `clearIntervalEvents` 清新区间（吞后续音符 / 休止）→ `fillRange` 在原 onset 物化新音（改长自动跨小节连音，缩短干净重排）→ `fillGaps` 把受影响小节补满。受触区间扩到整小节。
- **`editAccidentals(runtime, edits: List<AccidentalEdit>)`**：只改 `pitchIndices` 指定的音（null = 整和弦）的记号；其余和弦音原样保留。`accidental` 为 null = 清回该小节**调号默认拼写**（`DiatonicTranspose.spell(key, diatonicSteps)`），否则按 `chromaticOffset = accidental.offset` 保留音名改半音。改后和弦按 `midiNumber` 重排、延音线按新下标重映射（不丢，区别于 transpose）。不动时值故无冲突。
- **`editTies(runtime, edits: List<TieEdit>)`**：按 `tieOut` 给 `pitchIndices` 指定的音（null = 整和弦）加/去尾延音线——加时与既有 ties 取并集、去时只移除目标下标，其余音的连音线不动。连音线连向后继（可能在下一小节），故受触区间报本小节 + 下一小节。
- **`applyTuplets(runtime, edits: List<TupletEdit>)`**：把选区作为一个连音组总跨度。只接受单声部、单小节、完整覆盖的区间；已有连音或嵌套连音返回 `Conflict`。原事件按 `normal/count` 映射到连音时间轴，选区不足 N 拍时尾部补连音休止符。
- **`clearInterval` 重构出 `clearIntervalEvents(runtime, events, start, end)` 列表版**，供已自行增删事件的就地编辑（如改时值）直接清区间，无需先重建 `RuntimeVoiceTrack`。

### moveVoices（调整声部）

`moveVoices(runtime, targets: List<VoiceMoveTarget>)`，`VoiceMoveTarget(voiceTrackId, eventId, targetVoiceNumber, pitchIndices)`。只在同 staff 内移动音符，不移动休止符：

- 源声部严格走 `delete`：整事件移动会把原跨度补成休止符并合并相邻休止符；和弦真子集移动只删除被选音头，未选音留在原声部。
- 目标声部严格走 `insert`：目标声部不存在时由 `Insertion.staffTrackId + voiceNumber` 懒创建；若同 onset/duration 已有音符则并入和弦；若目标同位置存在不同时值音符，则按插入逻辑 `clearInterval` 清出新区间并保留剩余片段。
- 移动结果按插入后的事件 id 与音头下标回报，用于提交后重选。

## 延音线只认显式 tie

延音线**仅**由连音线工具（显式 `RuntimeTieInfo`）或 MusicXML `<tied>` 导入产生。`ComputeEngine` 调用 `TieTargetComputer.computePerPitchTies(..., useHeuristicFallback = false)`，关闭了"按结束时刻匹配同音高自动连音"的启发式——避免连续放置两个同音高音符被静默连起来。

## 提交 `commitNewState`（增量 compute + 增量 render）

`apps/desktop/.../service/ScoreSession.kt` 的 `applyNoteEdit(insertion)`：在 UI 线程跑引擎，`Dispatchers.Default` 上做 **`computeScoreIncremental(previousComputed, newRuntime, editInterval)`**（围绕编辑区间的局部重算，按引用复用旧 `ComputedScore`，黄金法则保证与全量 `computeScore` 等价，见 [incremental-update.md](../data_model/incremental-update.md)），再 `manager.commitNewState(runtime, computed, RenderHint(previousComputed, changeSet))` 入历史栈。Storage 转换只在保存边界执行；撤销/重做见 [state-management.md](../state-management.md)。

删除走 `applyNoteDeletes(deletions, onAfter)`：逐个 `Deletion` 折叠到 runtime（未受影响事件保持 identity，故批量中后续删除仍能定位目标），单个删除走增量重算 + `RenderHint`（同上），多选删除回退全量 `computeScore`。提交后把产生的休止符 / 改后和弦事件经 `onAfter` 回报，由 `App` 重置选区。

粘贴走 `applyNotePaste(clipboard, target, onAfter)`：调用 `NoteEditEngine.pasteNotes`，单个受触区间走增量 + `RenderHint`，多个区间回退全量；新事件经 `onAfter` 选中，供连续复制 / 属性编辑继续使用。

平移走 `applyNoteTranspose(targets, stepDelta, onAfter)`：调 `NoteEditEngine.transpose`，**单个**被触小节走增量 + `RenderHint`、**多个**回退全量；被移事件 id 经 `onAfter` 解析为 `VoiceEventSection` 回报，使平移后仍选中。

移动休止符走 `applyRestMove(targets, onAfter)`：调 `NoteEditEngine.moveRest`，把 `RestMoveTarget(voiceTrackId, eventId, staffPosition: Int?)` 的绝对谱位（`null` = 清回默认）写入 `RenderingProps.restStaffPosition`，复用 `commitEdit`（单个受触小节走增量 + `RenderHint`）。

声部调整走 `applyVoiceMove(targets, onAfter)`：调 `NoteEditEngine.moveVoices`，单个被触小节走增量 + `RenderHint`、多个回退全量；结果按整事件或部分和弦音头粒度重选。

就地属性编辑走 `applyDurationEdits(edits, onConflict, onAfter)` / `applyAccidentalEdit(edits, onAfter)` / `applyTieEdit(edits, onAfter)`：调对应引擎方法，共享 `commitEdit`——`EditOutcome.Changed` 时单个受触区间走增量 + `RenderHint`、多个回退全量，`resultEventIds` 经 `onAfter` 重选；`applyDurationEdits` 的 `Conflict` 触发 `onConflict`（`App` 弹顶部提示），`NoOp` 静默。

连音组应用走 `applyTupletEdit(edits, onConflict, onAfter)`：调 `NoteEditEngine.applyTuplets`，成功后同样共享 `commitEdit`；冲突（跨小节、跨声部、非完整区间或嵌套连音）触发顶部提示。

**渲染走增量路径**：`RenderHint`（`api/.../state/ScoreState.kt`，携带「编辑前的 `ComputedScore` + `ComputeChangeSet`」）随 `ScoreState` 经 state flow 流到 UI。主编辑视图 `RenderedScoreView` 把 `session.computedScore` / `session.renderHint` 透传给 `rememberRenderResult`，后者调用 `RenderEngine.renderIncremental(computed, changeSet, expectedPrevious)`——只重排受影响小节、平移其余、可拼接时元素级复用（见 [renderer/incremental-rendering.md](../renderer/incremental-rendering.md)）。

- **谱系守卫 + diff 回退（lineage guard）**：增量布局复用「上一帧」的 slot X，仅当上一帧正是本次编辑的直接前驱时才能直接信任 `changeSet`。`renderIncremental` 用 `expectedPrevious === lastRenderedComputed()`（引用相等）校验：
  - **命中**（直接前驱）→ 直接用编辑时算好的 `changeSet`，零额外开销。
  - **未命中**（连续快速落子被 `produceState` 合并跳帧、或**撤销/重做**跳历史）但有缓存帧 → 用 **B+ 树结构化 diff** 现场算出「显示帧 → 新 computed」的真实 `ComputeChangeSet`（`computeChangeSetBetween`，见 [data_model/computed-event-store.md](../data_model/computed-event-store.md) §结构化 diff），据此**仍走增量**——而非退回全量。持久化事件树共享结构，diff 为 O(变化量·log N)。撤销/重做因此天然增量化，无需为其单独接线（历史 `ScoreState` 上的旧 `RenderHint` 会因 `previousComputed` 不符被忽略，转走 diff）。
  - **无缓存帧**（首帧）→ 整谱全量。
  - 若 diff 发现记号 / 小节结构变化（`structureReflow`）→ `allowsIncrementalLayout` 为假 → 全量布局，安全兜底。
- **非编辑视图仍全量**：缩略图 `SimpleScoreView`、分屏参考视图不传 `computed`，`rememberRenderResult` 走经典 `RenderEngine.render(runtime)`（各自独立 engine，与主视图谱系无关）。插件轨 / 视图偏好的就地更新（`ScoreStateManager`）把 `renderHint` 置空。

## 相关文件

| 关注点 | 文件 |
|--------|------|
| 工具栏 / 状态 | `ui/components/LeftToolbar.kt`、`NoteToolState.kt` |
| 快捷键 / 设置 | `input/{KeyStroke,ShortcutAction,KeybindingStore,ShortcutDispatcher}.kt`、`ui/dialogs/SettingsDialog.kt`、`AppSettings.kt` |
| 画布交互 / 虚影绘制 | `ui/views/RenderedScoreView.kt`、`ComposeScoreRenderer.renderCommandsTinted` |
| 虚影计算 | `renderer/.../render/edit/GhostNoteComputer.kt`、`RenderEngine.computeGhost`、`HierarchicalSpatialIndex.staffAt` |
| 谱号笔虚影 / 插入竖线定位 | `renderer/.../render/edit/GhostClefComputer.kt`、`RenderEngine.computeClefGhost`、`TimeCodePosition.leftX`（`RenderResultAssembler.computeTimeCodePositions`） |
| 拖动平移预览 / 隐藏原音 | `renderer/.../render/edit/TransposePreviewComputer.kt`（两层着色）、`RenderEngine.computeTransposePreview`、`StyleOverride.hidden` / `StyleSnapshot.isHidden`、`ComposeScoreRenderer.renderElement` |
| 拖动移动休止符 | `renderer/.../render/edit/RestMovePreviewComputer.kt`、`RenderEngine.computeRestMovePreview`、`RestLayout.defaultRestStaffPosition`、`RenderedScoreView`（`DragMode.REST_MOVE` / `buildRestMoveInfo`） |
| 平移音高拼写 / MIDI 夹取 | `api/.../primitive/DiatonicTranspose.kt`（`spell` / `clampDelta`，引擎与预览共用） |
| slot X 语义（右边缘） | `renderer/.../layout/ProportionalLayoutComputer.kt`（`computeXPositions`） |
| 空小节宽度 | `RenderLayoutConfig.padEmptyMeasures`、`UnifiedLayoutComputer` |
| 编辑引擎 | `core/.../engine/edit/NoteEditEngine.kt`（API 外壳：`insert` / `delete` / `copyNotes` / `pasteNotes` / `transpose` / `moveVoices` / `moveRest` / `editDurations` / `editAccidentals` / `editTies` / `EditOutcome`，按功能委托给同包 `NoteInsertion` / `NoteCopyPaste` / `NoteDeletion` / `NoteTranspose` / `NotePropertyEdits` / `VoiceMoveEngine`）、`DurationDecomposer.kt` |
| 提交接线 / 选中 | `desktop/.../service/ScoreSession.kt`（`applyNoteEdit` / `applyNoteDeletes` / `applyNotePaste` / `applyNoteTranspose` / `applyVoiceMove` / `applyRestMove` / `applyDurationEdits` / `applyAccidentalEdit` / `applyTieEdit`）、`App.kt`（`buildDeletions` / `buildCopyTargets` / `buildPasteTarget` / `deleteSelection` / `paletteInfoFor` / `selectionEditor` / Esc） |
| 选区属性编辑 / 冲突提示 | `App.kt`（`PaletteSelectionInfo` 聚合、`SelectionEditor`、`editMessage` 顶部横幅）、`input/ShortcutDispatcher.kt`（`SelectionEditor` 路由）、`ui/components/NoteToolState.kt`（`PaletteSelectionInfo`） |
| 选中项属性 / 删除按钮 | `ui/components/RightPanel.kt` |
| 增量 compute / render hint | `core/.../engine/IncrementalComputeEngine.kt`、`api/.../state/ScoreState.kt`（`RenderHint`）、`api/.../state/ScoreStateManager.kt`（`commitNewState`） |
| 选区随撤销 / 编辑器状态注册表 | `api/.../state/ScoreStateManager.kt`（`EditorStateController` / `registerEditorState`）、`desktop/.../service/ScoreSession.kt`、`App.kt`（注册 `"selection"`） |
| 增量 render 接线 / 谱系守卫 | `desktop/.../ui/views/ScoreRenderPipeline.kt`（`rememberRenderResult`）、`renderer/.../render/RenderEngine.kt`（`renderIncremental(expectedPrevious)`、`lastRenderedComputed()`） |
| 延音解析 | `core/.../engine/Computers.kt`（`TieTargetComputer`） |
