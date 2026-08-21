# 桌面端 UI

> 模块：`apps/desktop/src/main/kotlin/com/mecon/desktop/`
>
> 框架：Compose for Desktop (JVM)

## 1. 顶层布局

```
App.kt (根 Composable；工作区委托给 AppLeftToolbar / AppCenterWorkspace / AppMainScoreView)
├── TopBar              — 文件操作 / 播放控制
├── LeftToolbar         — 工具列（选择 / 音符笔 / 乐谱元素）+ 独立音符调板与乐谱元素调板
├── ScoreView           — 五线谱渲染（主视图）
├── RightPanel          — 折叠侧边栏外壳（内置检查器 + 插件面板）
├── BottomPanel         — 可折叠、可停靠到下方或右侧的钢琴卷轴
└── Dialogs             — 文件打开 / 保存对话框
```

所有状态由 `App.kt` 持有并以 Compose `remember` + `mutableStateOf` 管理。跨组件边界不再逐项透传
几十个值与 lambda：顶栏、左右工具面板分别使用 `TopBarUiState/TopBarActions`、
`LeftToolbarSelectionState/LeftToolbarActions`、`RightPanelUiState/RightPanelActions`。
右侧栏的 `RightPanelUiState` 只含宽度、折叠等容器状态；选中项属性由
`ui/components/inspector/` 下按功能组织的贡献者消费统一只读上下文。每个贡献者自行负责
适用性判断、属性投影和该功能的窄动作接口，禁止把所有元素字段重新并入一个巨型
`SelectionPropertiesState/Actions`。编辑提交由应用层选择编辑协调器转给 `ScoreSession`，
检查器不直接执行乐谱编辑规则。插件面板继续直接消费 `PluginPanelContext`。

## 2. 核心状态 (App.kt)

```kotlin
var scoreManager:              ScoreStateManager? by remember { ... }   // 撤销/重做历史
val scoreState:                ScoreState?        by produceState(...)   // 当前三层状态
val runtimeScore              = scoreState?.runtimeScore
var eventSelection:            EventSection?      by remember { ... }   // 选中的音符/谱线区段
var selectedAnnotationEventId: EventId?           by remember { ... }   // 选中的插件注释元素
```

`scoreManager` 在加载文件后创建，持有最多 50 步的 `ScoreState`（Runtime / Computed 两层快照）历史记录。`produceState` 将 `currentStateFlow` 转换为 Compose 状态。两类选中互斥：选中注释时清除 `eventSelection`，选中音符时清除 `selectedAnnotationEventId`。

保存文件时在序列化边界使用 `runtimeScore.toStorage()`；编辑会话不保留第二份 `StorageScore`。插件 Runtime track 必须完整写回，避免保存时丢失。

> 文件：`apps/desktop/src/main/kotlin/com/mecon/desktop/App.kt`

## 2a. 撤销 / 重做

- **快捷键**：Ctrl+Z（撤销）、Ctrl+Y 或 Ctrl+Shift+Z（重做），由根 `Column` 的 `onKeyEvent` 处理
- **按钮**：TopBar 工具栏中的 Undo / Redo 按钮，不可用时变灰
- **触发**：`scoreManager?.undo()` / `scoreManager?.redo()`
- **可用性**：由 `scoreState` 的变化驱动重组，每次重组后读 `scoreManager.canUndo()` / `canRedo()`

## 2b. 插件事件编辑

`App.kt` 向 `RightPanel` / `PluginPanelContext` 暴露三个回调：

| 回调 | 说明 |
|------|------|
| `onAddPluginEvent(trackType, event)` | 添加事件到指定类型的 track（不存在则新建 track） |
| `onUpdatePluginEvent(trackType, oldId, newEvent)` | 原子替换事件（单步撤销） |
| `onDeletePluginEvent(trackType, eventId)` | 删除事件 |

每次操作都会调用 `commitNewState(storage, runtime, computed)` 生成新的历史快照，支持撤销。

`targetTimeCode` 由当前选中音符的 `event.onset` 推导，作为新增事件的时间锚点。

## 3. ScoreView（五线谱视图）

> `ui/views/RenderedScoreView.kt`

```
RuntimeScore
    └── ScoreLayoutEntry.computeLayoutWithComputed()    context(BravuraFont)
    └── RenderEngine.renderUnified()
    └── ComposeScoreRenderer.render(DrawScope, ...)
```

点击拾取（双路命中）：

```
detectTapGestures { offset →
    ├── HitTestService.hitTest(point)：命中音符/谱线 → onSelectEvent(EventSection)
    └── 未命中 → 扫描 TEXT_ANNOTATION RenderElement.hitBox
            ├── 命中 → onSelectAnnotationEvent(element.eventId)   // 选中插件注释
            └── 未命中 → 两者均清空
}
```

音符选中态通过 `StyleOverrideManager` 的声明式 Track 驱动（见 [../renderer/interaction.md](../renderer/interaction.md)）。注释元素选中用半透明蓝色叠加层直接绘制在 Canvas 上。

分页模式播放时，`RenderedScoreView` 通过 `globalToDesign` 把播放线的顶部和底部映射到 UI 页面网格，再分别检查 X 与 Y 是否仍在视口内。播放线及其当前谱表行仍可见时保持用户视口；X 越界时把播放线放到视口约三分之一处，Y 越界时把当前谱表行垂直居中。两个轴独立判断，因此放大页面后会随播放线继续横向跟随，换行或换页时也会定位到实际播放行。播放位置索引在后台批量换算 tick，播放线按音符位置逐步前进。Renderer 仍只输出 page-local 页面与坐标，不负责屏幕视口。播放中拖动画布仅在手势期间暂停跟随，松开或取消后先保持当前位置，待播放线再次越界才重新对齐。

谱号 / 调号 / 拍号输入走独立的乐谱元素调板：`LeftToolbar.kt` 展示谱号、调号、拍号三段；调号选择复用 `KeySignaturePicker.kt`，新建乐谱对话框与左侧调板共用同一套预览按钮，按钮自动换行并按 `C`、升号从少到多、降号从少到多排列完整大调调号。`RenderedScoreView.kt` 分别调 `RenderEngine.computeClefGhost()`、`computeKeySignatureGhost()`、`computeTimeSignatureGhost()` 显示竖线+元素 ghost，并提交到 `ScoreSession.applyClefEdit()`、`applyKeySignatureEdit()`、`applyTimeSignatureEdit()`。第 1 小节拍首的谱号笔由共享 core 规范化为初始谱号状态，不写入冗余 `clefChanges`；后续变谱号进入 `StaffPitchContext.Timeline`，音符 pointer 先确定 onset，再按该时刻的中央 C 位置反推音高，并在未显式选择临时记号时采用调号默认音。单选 `ClefSection` / `KeySignatureSection` / `TimeSignatureSection` 时调板按钮高亮，点击其他值会原位修改；未选中时点击调板进入对应笔，在乐谱小节上点击写入/替换该小节起的元素。所有插入笔仍以根 `NoteToolState.tool` 全局互斥，但具体默认值由 `toolstate/` 下的 note、notation、expression、structure 四个子状态分别持有；按钮高亮只反映当前激活工具（保存的默认值不单独常亮）。点选择 / 框选工具或按 Esc 会取消当前插入笔但保留下次使用的默认值。以后新增乐谱元素工具必须加入对应功能子状态，不得继续向根状态堆放无关字段。若选到分页/分行的行首重述谱号或调号，则编辑写入本行起始小节的变更，不回溯修改前一个真实事件或乐谱初始状态。行首重述谱号与调号虽显示当前有效状态，但 hit element 注册为本行起点的独立 `ClefSection` / `KeySignatureSection`，保证每行可单独选中、高亮不联动。

需要把编辑控件放在谱面上方的工作台复用 `HorizontalScoreEditor`。它与 `LeftToolbar`
共享 `NoteToolState`、按钮语义和 Bravura 字形，但用 `FlowRow` 横向铺平并在宽度不足时自动
换行。默认时值只展开全音符至三十二分音符；二全音符、六十四分音符及更少用时值收在同一
折叠组，演奏法也默认折叠。工具栏不显示分组标题，以竖向分隔线区分功能组。功能工作台可
把谱表内部的 voice number 作为编号按钮放入同一工具栏。该编号对每个谱表分别生效：鼠标
所在 staff 决定目标谱表，工具栏编号决定该谱表内的 voice；不使用跨整谱 `VoiceFocus`，也
不使用缩谱模式的谱表行首选择器。横竖工具栏共用同一个声部按钮行为：`NOTE` 输入模式下
即使刚插入的音符仍被选中，切换编号也只改变下一次输入；只有选择/框选模式下的可编辑选区
才移动到同谱表的目标 voice number。
选区到编辑请求的投影及调板/快捷键动作由公共 `ScoreSelectionEditor` 提供，宿主通过
`EditableNoteHost` 提交；嵌入式编辑器禁止复制主页面的时值、升降号、连音线、符杠等逻辑。
`panEnabled=false` 只关闭空白画布平移，不得关闭音符移调或休止符位置拖动。
工具栏与谱面使用同一左边界，不为已移除的左侧工具栏保留空列。嵌入式工作台可通过
`RenderedScoreDisplayConfig.showViewLabel/showZoomIndicator` 隐藏主视图名称和缩放角标；
通过 `firstSystemIndent` 缩短首系统留白；全屏主乐谱仍使用默认缩进并保留两个角标。

速度记号调板位于八度记号下方，点选 BPM、`più/meno mosso`、`a tempo`、`Tempo I`、等拍、关键帧后在谱面时间槽落点；`accel.` / `rit.` 采用拖拽范围输入。所有速度条目都是全局关键帧，但显示样式与播放参数分离：隐藏关键帧仅在编辑模式显示蓝色圆点，预览模式不画；选中任一速度记号会显示到时间槽的虚线引导，除乐曲开头关键帧外可水平拖动时间，渐变记号可改两端。普通速度记号只在所属 TimeCode 渲染一次，不在后续分行开头重述；只有渐变记号会跨行拆成续段。右侧选中项属性按内容自适应高度，可编辑有效四分音符 BPM、显示/隐藏，以及到下一关键帧的阶跃/线性/缓动方式。速度记号命中区由最终文字/SMuFL 绘制边界合并得到。数据和引用联动规则见 [../data_model/tempo.md](../data_model/tempo.md)。

桌面端标准文本输入统一使用 `desktop-ui-kit` 的 `MeconTextField`，共享深色背景、边框、文字、标签与光标颜色；调用方仅通过布局 modifier 决定字段宽度（例如属性面板中的 BPM 使用紧凑宽度）。新建乐谱对话框和选中项属性均使用该组件，新增输入框应继续复用。

自由练习的全局工具栏使用 `CompactNumberInput(dense = true)` 展示 BPM、回溯/回放数量、声部数和和弦默认拍数；紧凑样式保持 12sp 数字字号，仅收窄高度与步进按钮，并由工具栏设置组统一保留标签和选项间距。

顶部默认打开“文件”页；原“插入”能力合并进“编辑”页。“视图”页只保留互斥的编辑模式 / 预览模式，
该状态属于 Compose 临时视图状态，不写文件也不进入撤销栈。预览模式仅隐藏 `EDITOR_MARKER` 编辑入口；
Renderer 结果和分页 Picture 缓存保持不变。强制分行 / 分页记号可直接选中，选中态仍由统一
`StyleOverrideManager` 驱动，点击、Delete 与右侧“选中项属性”删除使用同一个 `LayoutBreakSection`。

## 4. PianoRollView（钢琴卷轴）

> `ui/views/PianoRollView.kt`

用 Compose `Canvas` 直接绘制：

- X 轴 = 时间（按 `Duration.ticks` 等比）
- Y 轴 = MIDI 音高（低音在下）
- 每个事件绘制为色块，颜色随选中状态变化
- 播放位置的 50ms 音频刷新先做线性帧间插值，再由同一个平滑位置同时计算时间轴偏移和播放线，使播放头稳定在可用视口约三分之一处

不复用渲染引擎，是独立的 Canvas 绘制路径。

## 5. 面板交互

可折叠面板用 `AnimatedVisibility` + 拖拽分隔线实现。分隔线拖拽使用 Swing 级别的光标设置（`window.cursor = Cursor.E_RESIZE_CURSOR`），避免 Compose 重组导致光标闪烁。

右侧检查器的乐理分析功能由 `:plugins:theory-analysis:desktop` 贡献。当前插件内含固定声部分析面板：用户可在 `高2/低2` 与 `高3/低1` 两种键盘缩谱分配之间切换。面板每次根据当前 `RuntimeScore` 调用 `theory.FixedVoiceScore.validate/load` 与教材规则 API，只展示状态、诊断、声部映射、选中音上下文和关联规则，不在 UI 层重写声部规则，也不把临时选择写入乐谱文件。

乐理分析同时注册全谱计算型 `AnnotationStaffProvider` 与 `NoteStyleProvider`。annotation staff 在每个含规则 finding 的 `TimeCode` 下方显示规则数量，点击后右侧面板列出该时间点所有规则；选中音符时面板列出与该音符关联的规则；鼠标悬停规则行会通过 `onRequestNoteStyleRecompute` 刷新谱面样式，高亮 finding 的主锚点与相关锚点。

## 6. 文件操作

> `service/ScoreFileService.kt`

```kotlin
suspend fun loadAuto(file: File): Result<StorageScore>
suspend fun saveAuto(score: StorageScore, file: File): Result<Unit>
```

按扩展名自动分派：`.mecon/.yaml` → YAML，`.xml/.musicxml` → `MusicXmlConverter`。

对话框：`ui/dialogs/` 下封装 `JFileChooser`（原生文件选择器）。

打开或新建文档后，主乐谱区显示“正在载入乐谱并准备交互”遮罩。该状态从文件解析开始，覆盖
Runtime/Computed 构建、完整 RenderResult 发布以及 Canvas 的首轮 Picture 缓存录制；分页乐谱会在遮罩期间
预录全部页面缓存，避免页面首次进入视口时把录制成本推迟到第一次拖动画布。只有新 `documentVersion` 的
settled frame 实际绘制后遮罩才消失，旧文档帧或流式锚点页不能提前结束本次加载。

## 7. 国际化 (i18n)

> 详见 [i18n.md](i18n.md)

当前支持中文 / 英文。`apps/desktop-ui-kit` 提供 `I18nRegistry` + `i18n(key)`；主壳 `BuiltinStrings` 与各插件的 Strings 均在启动时调用 `I18nRegistry.register(...)`。

## 8. 插件桌面 SPI

`RightPanel.kt` 只负责侧栏容器和面板顺序：内置 `SelectionInspector`、独立的
`RangeCheckerPanel`，随后遍历 `PluginRegistry.panelDescriptors()`。每个 `PluginPanel` 作为
一个 `ResizablePanelItem` 嵌入，标题由 `i18n(panel.titleKey)` 解析。和弦分析与乐理分析都走
这条插件面板路径；插件属性仍通过插件面板表达，不与主壳内置选中项贡献者相互引用。

`PluginPanelContext` 向插件暴露当前选中的注释元素 ID、运行时乐谱、目标时间点和编辑回调，插件面板可据此在 `runtimeScore.pluginTracks` 中查找对应事件并展示详情，通过回调提交修改。详见 [../plugin/plugin-framework.md](../plugin/plugin-framework.md)。

## 9. 在 UI 中使用 SMuFL 字符 ⚠️

左侧调色板、和弦插件等处直接用 Bravura 字体渲染 SMuFL 字形（音符、变音记号、休止符等）。这类字符有几个坑，新增/修改时务必注意：

1. **码位在 Unicode 私用区（PUA，U+E000–U+F8FF）**：在编辑器、终端、`Read` 工具以及任何非音乐字体中**都显示为空白/方框**。不要靠"肉眼在源码里看到字形"来确认，应对照 `apps/desktop/src/main/resources/bravura/glyphnames.json` 的 `codepoint` 字段核对。
2. **改含 PUA 字符的源码要小心**：字符串字面量里可能藏着不可见字符，基于文本匹配的工具（含 `Edit`）可能匹配不到看似为 `""` 的串。优先用字节/码点核验（如 PowerShell `[int][char]`），不要盲目整体覆写常量（会丢字形）。建议把码位集中成命名常量（见 `LeftToolbar.kt` 的 `Smufl` object）并用 `\uXXXX` 转义书写。
3. **必须用 Bravura 渲染**：通过 `rememberBravuraFont()`（`com.mecon.desktop.ui` 或 `uikit`）取得 `FontFamily`；缺字体时 PUA 码位会变成豆腐块，可在适当处提供 `♯`/`♭` 等 Unicode 兜底。
4. **居中要测量、不要靠 `Text` 居中**：Compose `Text` 按字体整行框居中，而 Bravura 的行框留白很大，会让音符等字形显得偏下。正确做法是用 `rememberTextMeasurer` 测量后在 `Canvas` 上 `drawText`，按基线定位：

   ```kotlin
   y = size.height / 2f - layout.firstBaseline + emPx * bias
   ```

   `bias`（基线相对 em 的偏移）按字形类别取值：带符干音符 ≈ 0.45、符头（全/二全/longa/maxima）≈ 0.12、变音记号 ≈ 0。参考 `LeftToolbar.kt` 的 `MusicGlyph` / `glyphBias`，以及 `plugins/chord-analysis/.../TonnetzCanvas.kt` 的画法。
5. **某些字形没有对应码位**：如 longa/maxima 在"个体音符"表里没有带符干的单字形，需退化到 mensural 符头（`mensuralNoteheadLongaBlack` 等）；连音线没有合适字形，直接用 `Canvas` 路径手绘。

## 10. 输入与待实现项

- 音符输入：鼠标 + 调色板、电脑键盘与 MIDI 的步进/实时录入均已实现，见
  [note-input.md](note-input.md)
- 复制 / 粘贴
- 多窗口 / 多文档
- 打印 🚧
- 导出 PDF ✅：File 工具栏「导出 PDF」按钮，矢量后端见 [renderer/pdf-export.md](../renderer/pdf-export.md)


## 10. 新建乐谱与编制

NewScoreDialog.kt 使用接近全屏的四栏布局：原有的类别→预制两层文本列表、乐器/括号编辑、实时乐谱预览、基础信息。四栏使用统一背景和简单边框分隔，不嵌套异色卡片。NewScorePresets.kt 覆盖单谱表、室内乐与交响乐的 11 种编制；所有颜色只引用 MeconColors 的语义角色。

基础信息编辑器使用 `NewScoreMetadataDraft`，再按 credits / notation / page 三块不可变子状态
执行 `copy` 更新；不得重新扩张为 value/callback 成对的长参数列表。

乐器编辑行由乐器目录下拉框、可编辑谱表展示名、统一样式的谱表数/人数数字输入、
独奏/合奏下拉框和谱表分配入口组成，因此 Violin I / Violin II 可以选择同一 `violin`
乐器但保留不同名称。展开分配入口后每条谱表为一行，演奏者编号可在行间拖拽；普通乐器
连续分配，圆号使用交错分配。新建页与“配器与演奏者”弹窗复用同一编辑组件，
“新建缩谱”复用相同数字输入与下拉字段。下拉目录按木管 / 铜管 / 弦乐 / 打击乐 /
键盘折叠展示，条目同时显示当前 i18n 名称与英文名并支持搜索。钢琴、管风琴等多谱表
乐器只生成一个 StorageInstrument、共享播放目标，并自动生成大括号。

括号轨道直接排列在乐器行左侧：为便于编辑，跨度大的外层在左、跨度小的内层在右；正式乐谱按传统视觉关系反向排列，小分组在左、大分组靠近谱表。端点按乐器行拖拽，类型可切换为方括号 / 大括号 / 子括号。范围允许包含或分离，但禁止交叉或完全重合。谱表数既可用步进按钮，也可直接输入 1–8。预览显示实际乐器展示名，以固定最大比例垂直居中；大型编制只缩小、不放大。预览和最终创建都调用 `StorageScore.create(StorageScore.CreationOptions(...))`。

古典主义和浪漫主义交响乐预制不添加跨越全谱的外层括号，只保留木管、铜管、弦乐方括号。浪漫编制中同类分部使用一个多谱表 `InstrumentTemplate`（如 Flutes 下含 Flute I / II），由创建器自动在该乐器内部添加大括号，不拆成多个播放乐器。

## 11. 组合与会话模块边界

- 通用选择查询、复制/粘贴目标和 palette 聚合分文件组织；检查器投影留在对应
  `ui/components/inspector/<feature>/` 功能目录。`AppDialogs.kt` 统一对话框状态和宿主。
- `TopBar`、`LeftToolbar`、`RightPanel` 以及 exploration 编辑器接收领域 state/actions，
  不再平铺十余个 value/callback。
- `RightPanel.kt` 不识别具体 `EventSection`；`SelectionInspector` 只遍历内置贡献者。
  速度、装饰音、演奏记号、小节线、谱表可见性和曲线方向分别维护自己的属性 UI。
  Delete 键和检查器删除按钮共同委托应用层选择编辑协调器，避免两套元素分派。
- `ScoreSession` 的文档生命周期、插件轨道编辑、文档显示设置与选择解析分别位于同包模块；
  通用 storage 提交、render geometry 提交与 expression 提交也分别位于
  `ScoreSessionStorageEditing.kt`、`ScoreSessionGeometryEditing.kt`、
  `ScoreSessionExpressionEditing.kt`；session 本体保留乐谱状态和需要共享私有事务的编辑流程。
- `RenderedScoreView` 的入口为 `RenderedScoreViewConfig + Modifier`。配置按 source、selection、
  display、edit（notation / expression / movement）和 lifecycle 分组，定义在
  `RenderedScoreViewConfig.kt`；配置不再暴露把这些分组重新摊平的转发 getter。
- 画布的 viewport、拖拽预览和插入 ghost 状态由 `RenderedScoreInteractionState.kt` 分开持有；
  拖拽动作按选择、音符移动、表情移动和结构移动分组。Canvas 请求契约位于
  `RenderedScoreCanvasContract.kt`，绘制实现不再同时声明宿主契约。
- Canvas 绘制、统一拖拽、点击选择和插入工具手势分别位于
  `RenderedScoreCanvasDraw.kt`、`views/drag/`（见下条）、
  `RenderedScoreSelectionGestures.kt`、`RenderedScoreInsertionGestures.kt`。命中优先级位于
  `ScoreSectionPriority.kt`，像素→`TrackId`/`TimeCode`/锚点解析位于 `ScoreAnchorResolution.kt`
  （P/S 放置与 H 手柄共用），分页坐标支持位于 `RenderedScoreViewSupport.kt`。
- **拖拽按[交互家族](score-interaction-taxonomy.md)分文件**：`views/drag/ScoreDragGestures.kt`
  只做仲裁——解析一次命中（`ScoreDragPick`）、选出认领该手势的 handler、转发 move/release/cancel；
  每种行为各占一个文件：`ViewportPanDrag`（视口，非编辑）、`MarqueeSelectDrag`（N）、
  `NoteHandleDrag`（H，移调/移休止）、`BeamHandleDrag`、`AttachmentHandleDrag`、`CurveHandleDrag`、
  `VoltaHandleDrag`、`NavigationHandleDrag`（均为 H）、`AnnotationRangeDrag`（分析域区间）。
  新增拖拽行为应新增一个 handler 文件并在仲裁链中排序，不要回到单个巨型 `onDragStart`。
  handler 只写 transient 预览状态，抬起时最多派发一次编辑（一个历史项）。
- 拖拽的两条配套通道也在同一包：`ScoreDragOverlayDraw.kt` 画所有进行中拖拽的 Canvas 覆盖层
  （编辑器 chrome，不产生 `RenderElement`，因此不影响排版边界/分页/命中索引）；
  `ScoreDragCommitHold.kt` 统一"抬起 → 提交帧上屏"的交接：按 identity 比较 `commitBaseline`，
  在完整新帧显示前保留预览并阻断交互，并带超时兜底。各拖拽类型不再各写一遍这套 effect。
- 探索输入页的不可变 state/actions 契约位于 `ExplorationEditorContract.kt`，具体编辑器只消费
  对应领域契约，避免视图实现同时承担跨模式状态定义。
