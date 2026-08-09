# 自由练习 Web 与桌面端统一审计及重构计划

> 状态：✅ 2026-08-07 回归复修完成。`030bac9b` 建立的共享 raw scene/controller 与 toolbar
> descriptor 继续作为架构基线；本轮已恢复改造前的桌面时间轴视觉、pointer stream 连续性和
> 滚轮缩放，并用代表性 Desktop golden、JVM/JS trace 与真实 Playwright 路径重新验收。

## 0. `web-alignment` 提交复审（2026-08-07）

本次复审以 `master` 的 `52b6aa92` 为桌面行为基线，以改造前截图中的刻度、调性线、和弦卡片、
选中/锁定/枢纽状态、边界手柄、惯用进行括号和末端插入区为视觉基线。共享引擎和薄 adapter 的
架构方向保留，但“共享”不授权重设计桌面视觉或删减既有展示信息。

已确认的回归：

| 范围 | 证据 | 根因/缺口 |
|---|---|---|
| 桌面视觉 | 新增的 `free-practice-timeline-win32.png` 只显示被裁切的调性标签、单个纯蓝槽和 `+`，与改造前截图明显不同 | raw scene 只投影单个 `symbol`，遗漏多调性读法、音级、刻度/小节号、旧配色、锁定/枢纽/选中层次和手柄视觉；错误输出又被直接写成 golden |
| 桌面拖动 | `SharedHarmonicTimeline` 的 `pointerInput(scene.generation, gesture)` 在 `DOWN` 更新 gesture 后重启事件协程 | 正在进行的 pointer stream 被取消，后续 `MOVE/UP` 可能丢失；纯 controller trace 绕过了 Compose adapter，无法发现该问题 |
| 验收覆盖 | controller 单测直接顺序调用 `DOWN/MOVE/UP`；桌面 golden fixture 只有极简初始 workspace | 缺少 adapter 级“同一 pointer stream 直到单次 commit”的测试，也没有覆盖多和弦、空槽、惯用进行、调性线和选中态的代表性截图 |
| 契约表述 | 文档把 raw scene/controller 落地等同于桌面/Web 完成对齐 | 架构等价、行为等价和视觉兼容是三道独立门禁，不能互相替代 |

修复约束：

1. 保留 `features/free-practice/commonMain` 的 geometry、hit-test、量化和 edit 权威；不得把旧的
   `HarmonyWorkspaceEditor` 手势 reducer 搬回 Compose。
2. 共享 typed view/scene 必须补齐旧桌面卡片所需的展示数据和 draw objects；Desktop/Web 仍只重放，
   但桌面结果必须对齐改造前视觉，而不是以当前错误 golden 为准。
3. 桌面 pointer 接收协程在一次按下—移动—抬起期间必须保持稳定；scene/frame 可更新，但不得用
   gesture 或瞬态 generation 作为会取消当前 stream 的 `pointerInput` key。
4. golden 使用代表性 fixture，并同时做关键 draw-object/语义断言。只有人工确认新图与改造前基线一致后
   才能重刷；禁止用“测试生成了什么就提交什么”关闭视觉回归。
5. raw trace 继续证明 JVM/JS controller 等价，另补 Desktop adapter 事件连续性与真实 Web pointer
   commit/reload 门禁；两类测试都通过才可恢复完成状态。

复修结果：

- `PracticeTimelineView` 重新携带绝对/相对音名、多调性读法和 chord-owned 派生调性区间；共享 scene
  恢复刻度、小节号、旧配色层次、手柄、括号、空隙与末端插入区，Desktop/Web 只负责重放；
- Compose adapter 改用稳定的 `pointerInput(Unit)`，在协程内部读取最新 scene/request，接受的
  `DOWN/MOVE/UP` 全部消费，避免重组取消当前 pointer stream 或被横向滚动抢走；
- 恢复桌面“和声时间轴”标题、帮助、删除入口和滚轮拍宽缩放；代表性 golden 覆盖五槽、空槽、
  选中、锁定、枢纽、多调性读法、手柄、派生调性线和惯用进行；
- `practice-trace.json` 验证 presentation-ready typed view，raw trace 同时验证 append onset/duration；
  JVM 全套、78 项 Node/JVM-JS 测试、Desktop golden/回读及 Playwright 16 项（15 通过、1 项环境跳过）
  已通过。

## 1. 首轮对齐前的审计结论（历史）

下表记录共享 raw scene/controller 落地前确认的三类问题，供解释本方案的架构取舍；当前状态以
第 0 节复修结果和第 6 节门禁为准。

| 范围 | 当前实现 | 结论 |
|---|---|---|
| 时间轴/谱面布局 | 时间轴有独立 `margin`，Canvas 又在带 padding 的滚动区居中；两者没有共享内容原点和视口宽度 | 左端不能稳定对齐，短谱面的五线谱 surface 也不会占满主区 |
| 两层工具栏 | Web 顶栏只有新建/打开/撤销/重做/导出；谱面栏混入桌面 palette 没有的剪贴板、移调、位置输入、MIDI 等控件 | 只是“拆成两条”，内容、分组、尺寸和换行均未与桌面一致 |
| 和声时间轴 | React 自行插值、分 lane、命中、量化、构造 edit、维护 ghost 和 pointer capture | 与 Compose 的第二套实现已发生漂移；拖动提交、点选和末端 `＋` 仍不可靠 |

本次缺陷不是再补几条 CSS 或 E2E 即可关闭。目标改为：**共享引擎输出完整时间轴 raw scene、
hit objects 与交互结果，Desktop/Web 都只重放绘制命令并转发原始输入事件。**

## 2. 已确认根因

### 2.1 存在三个互不相同的横向坐标原点

- `.harmony-timeline` 使用 `margin: 0 20px`，其绝对 x 从 margin 内侧起算；
- `.canvas-scroll` 使用 `padding: 20px`，Canvas 又以 `margin: 0 auto` 居中；当 frozen surface
  小于视口时，居中会额外产生未公开的左偏移；
- `WebResolvedTimeAxis` 只返回 anchor x、`contentEndX` 和 `surfaceWidth`，没有明确的
  `contentOriginX`、viewport、scroll extent 或“谱面内容应从何处开始”的契约。

`HarmonyTimeline.jsx` 再把 raw x 转成百分比和 `viewBox=1000` 坐标，实际 DOM 命中层又使用
CSS 百分比。即使某个 anchor 数值相同，时间轴、SVG、DOM 和 Canvas 仍可能落在不同屏幕 x。

此外 `renderFreePracticeFrameForWidthJson` 当前保留了 viewport 参数但排版不使用它。固定时值宽度
避免了随窗口拉伸，却没有解决“surface 至少铺满可见主区”和“多表面共享内容原点”。

### 2.2 时间轴在两端各有一套 geometry 与 gesture reducer

桌面 `FreePracticeEditorPanel.kt` 的 `HarmonicTimeline` 自行完成：

- timeline end/gap、刻度、调性行、惯用进行 lane、和弦高度与末端插入区几何；
- x↔音乐时间换算、吸附、左右/共享边界命中和 Ctrl 连同后续；
- gesture start/draft/edit、预览、取消、提交、选择及删除；
- 颜色、圆角、手柄、锁定/枢纽/选中态和最右侧悬浮 `＋`。

Web `HarmonyTimeline.jsx` 又实现了一遍 `fractionValue/interpolate/snappedFraction`、
`optimisticTimeline`、lane 分配、range 百分比、pointer capture 和 edit 构造。共享 session 只在
预览/提交末端校验，无法保证两端在“点到什么、拖了多少、何时提交”上等价。

“拖动看似移动但没有保存”必须在重构前用日志区分：未收到 pointer-up、edit 为 null、队列未发送、
stale/invalid 被拒绝，还是权威 frame 回写错误。不能再用“revision 增加”代替对 committed onset/duration
和重新打开文件后的值进行断言。

### 2.3 工具栏没有共享规格，Bravura 只解决了字体而非等价

桌面自由练习顶栏的权威顺序来自 `Toolbar.kt`：

1. `FileActions(showNew=false, showExport=false)`：打开、保存；
2. 撤销、重做；
3. 自动求解/自由练习模式；
4. 重写、换结果、自动写作、回溯和弦、回放个数、BPM、声部数、上谱表声部、吸附单位、
   默认和弦拍数、调性；
5. 从头播放、播放/暂停、从选择播放、速度、音频设置；
6. 最右侧应用设置。

Web 当前顶栏只有文件、历史、导出和 revision 状态。浏览器文件动作可以用文件选择器/下载实现，
但按钮槽位、标签、顺序、enabled 与分隔必须仍以桌面规格为准；“新建练习”等 Web 独有动作应放到
空状态或桌面也具备的统一入口，不能挤入这条权威工具栏。

桌面谱面工具栏的权威组件是
`HorizontalNotePalette(showScoreElementTool=false, voiceNumbers=1..4)`，顺序为：

1. 选择、框选、音符 palette 开关；
2. 声部 1–4；
3. 常用时值、休止模式、单双附点、非常用时值展开；
4. 升/降/还原/重升/重降；
5. 延音线、圆滑线；
6. 倚音、短倚音、小音符；
7. 建议连音组、自定义连音组、确认/清除；
8. 独立音符、左右/单侧符杠、组成符杠组；
9. 奏法展开与奏法字形。

Web profile 目前包含选择剪贴、上下移调、输入位置/音高、插入和弦、键盘步进和 MIDI 等额外组，
且缺少桌面工具模式、完整倚音/符杠/奏法展开语义。SMuFL 码位集中化是必要条件，不是完成条件。

## 3. 目标架构：共享时间轴 scene 与交互控制器

### 3.1 单一数据流

```text
FreePracticeSession + resolved score time axis + viewport preferences
                              |
              FreePracticeTimelineController (commonMain)
                  | raw input          | commit/selection
                  v                    v
          transient gesture       FreePracticeIntent
                  |
                  v
          PracticeTimelineScene
       draw objects + hit objects + a11y objects
          /                              \
Desktop Canvas/semantics            Web Canvas/SVG/DOM semantics
```

平台层只允许：

- 把 surface-local pointer/keyboard/wheel 原样转发给共享 controller；
- 按 scene 顺序重放 raw draw objects；
- 按 a11y objects 建立语义节点并把激活动作转发给 controller；
- 执行 pointer capture、系统 cursor、滚动容器与焦点 API。

平台层禁止：自行插值 time↔x、自行量化、判断共享边界、分配 idiom lane、推导锁定态、构造
`PracticeTimelineEdit`、生成末端 `＋`、维护业务 ghost 或决定点击选择哪个对象。

### 3.2 Raw scene 最小契约

拟在 `features/free-practice/commonMain` 增加平台无关 DTO 与控制器：

```text
PracticeTimelineScene
  generation / revision / axisRevision
  viewportWidth / contentOriginX / contentWidth / contentHeight / scrollExtent
  drawObjects[]        rect / roundRect / line / text / bracket，含完整 paint 与 z-order
  hitObjects[]         stable id / bounds / cursor / supported pointer+keyboard actions
  hoverTargets[]       按命中优先级降序排好的 hit id / bounds / cursor / overlay draw objects
  accessibility[]      role / label / selected / disabled / bounds / action ids
  contentAnchors       scoreOriginX / timeZeroX / contentEndX / appendX
  gestureState         pointer id / target / mode / accepted / reasonKey
```

所有坐标使用 surface-local raw pixel，并带 scene generation；平台不得再次缩放 x。文本对象包含字体族、
字号、字重、对齐和最大边界；颜色、描边、圆角和手柄宽度也由 scene 给出，避免两端各自维护主题常量。
Web 可选择 Canvas 或 SVG 重放，但不得改变几何；DOM 只承载语义和焦点，不再负责业务命中。

### 3.3 输入与提交契约

共享 controller 接收 `DOWN/MOVE/UP/CANCEL/WHEEL/KEY`，输入包含 scene generation、pointer id、
surface-local x/y、按钮和 Ctrl/Meta/Shift。返回：

- 最新 scene 或无变化；
- capture/release/cursor/focus 等平台 effect；
- preview accepted/reason；
- 必要时由 controller 调用 session 提交的单个 intent/result。

`UP` 后只有权威 session frame 能成为 committed scene。保存门禁必须比较 commit 前后 document、
重新加载后的 workspace 及 undo/redo，不接受只看 ghost 或 revision。

### 3.4 悬停反馈契约

指针悬停不经过 session：哪些元素响应悬停、认领什么 cursor、高亮画成什么样，全部由 projector 写进
`PracticeTimelineScene.hoverTargets`，平台只做「按列表顺序取第一个 bounds 命中的目标」和「把它的
`overlay` 叠在 base draw objects 之上」。列表已按 `DOWN` 使用的同一张命中优先级表降序排好，
所以悬停高亮与按下真正命中的对象永远一致（共享边界压过两侧和弦与其手柄）。

- Desktop 调用 `FreePracticeTimelineController.hoverTarget(scene, x, y)`，用 `pointerHoverIcon`
  映射 cursor，拖动进行中不再重算悬停；
- Web 在主线程内联同一条 first-hit 规则并把 `cursor` 写到 surface style，**不得**为悬停向 Worker
  发消息——那会让每次 mousemove 排队到引擎串行队列后面；
- 平台不得自建优先级表、cursor 常量或高亮配色。`timeline-raw-input-trace.json` 的 `hover` 用例
  在 JVM 与 Kotlin/JS 两侧比较 `hoverHitId / hoverCursor / hoverOverlayIds`。

## 4. 布局和工具栏统一契约

### 4.1 时间轴与五线谱

- 工作台主区只定义一个 `contentOriginX`；时间轴 time-zero、谱面 axis time-zero 和滚动偏移以它为准；
- score surface 的可见背景/Canvas 宽度至少等于主区 viewport，左对齐且不再 `margin:auto`；
- 固定 144 px/四分音符只约束音乐 anchor 间距。为铺满 viewport 增加的是 trailing surface，
  不能拉伸已有 anchor 或和弦框；
- scene 明确给出 intrinsic content width、viewport width 和 scroll extent。Desktop/Web 都不能从 DOM/
  Compose constraints 猜测；
- 最右侧 `＋` 是 scene 中的 draw/hit/a11y object：内容尾端可见时画在 append range，尾端滚出视口时
  由共享 scene 生成吸附在 viewport 右侧的 affordance，两端行为一致。

### 4.2 两层工具栏

- 顶栏以桌面 `Toolbar` 探索模式组合为唯一内容/顺序基线；Web 独有 revision 文本移到非工具栏状态区；
- 谱面栏以桌面 `HorizontalNotePalette` 为唯一分组与状态基线；不得用 Web `FULL_SCORE_EDITOR_TOOLBAR`
  的子集近似；
- 增加共享 toolbar descriptor/状态快照，Desktop/Web 从相同 stable control id 列表渲染；
- 按桌面 64dp 顶栏、28dp palette button、分隔线、group gap、padding 和 FlowRow 规则建立视觉 token；
- 所有可用 SMuFL 字形继续使用 Bravura；没有合适字形的选择/框选等使用同义图标，不得用文字按钮
  改变尺寸；
- 做 descriptor 快照测试，逐项比较 control id、顺序、group、enabled/selected/expanded；再做
  Desktop/Web 截图差异门禁。

## 5. 施工顺序

### P0：诊断与金标准

1. 为当前 Web 手势增加仅测试环境使用的 down/move/up、edit、queue、session effect 追踪，复现拖动未保存；
2. 固定桌面窗口、Web viewport、字体和测试 document，产出两层工具栏与时间轴金标准截图；
3. 增加失败用例：左端偏差、score surface 宽度、槽点选、拖动后 reload、尾端 `＋`。

### P1：共享 timeline scene/interaction engine

1. 定义 raw scene、input、platform effect 和无障碍 DTO；
2. 把桌面的 timeline geometry、lane、hit-test、gesture reducer 和 append affordance 迁入 commonMain；
3. controller 复用 `FreePracticeSession.previewTimelineEdit/dispatch`，不形成第二份历史；
4. JVM/JS 重放同一 raw input trace，逐帧比较 scene、effect、commit 和最终 document。

### P2：两端改为薄重放层

1. Desktop `HarmonicTimeline` 删除布局/手势 reducer，只保留 Compose draw/semantics/input adapter；
2. Web `HarmonyTimeline.jsx` 删除 fraction/interpolate/optimistic/edit 构造，只保留 scene replay；
3. Worker channel 支持合并 MOVE，但不得丢 DOWN/UP/CANCEL；scene generation stale 时显式拒绝；
4. 接入共享 origin/viewport contract，让 Canvas 铺满主区并与时间轴共同左对齐。

### P3：工具栏完全对齐

1. 建立共享 toolbar descriptors 与视觉 token；
2. 顶栏按桌面探索模式逐组补齐/移除；
3. 谱面栏按 `HorizontalNotePalette` 逐项补齐并删除额外组；
4. 补齐缺失的 Bravura/CSS 复合图标、expanded、tooltip、快捷键和无障碍状态。

### P4：门禁与文档收口

1. JVM/JS practice trace + raw pointer trace 覆盖选择、移动、双端、共享边界、Ctrl 后续、取消、删除、
   调性线、惯用进行和 append；
2. Playwright 断言 committed onset/duration、reload、undo/redo、横向滚动与不同 viewport；
3. Desktop/Web 同 fixture 截图比较时间轴、两层工具栏、左端原点和 surface 宽度；
4. 浏览器导出 `.mecon` 后由桌面回读；更新能力矩阵、扩展指南、Web 开发指南和 CHANGELOG。

## 6. 完成定义

- [x] 桌面时间轴的刻度、调性线、多行和弦读法、音级、卡片层次、手柄、惯用进行括号和末端插入区
  与改造前基线一致；代表性 golden 已经人工复核；
- [x] 桌面同一 pointer stream 在 `DOWN/MOVE/UP` 间不因重组取消，并只提交一个历史项；
- [x] 时间轴、谱面 time-zero 左端误差 ≤1 px，score surface 在无横向溢出时占满主区；
- [x] Desktop/Web 不再各自计算时间轴几何、hit-test、量化、lane、gesture 或 edit；
- [x] 点击任一普通和弦槽都选择相同稳定 ID，锁定/惯用进行选择语义一致；
- [x] 所有拖动在 pointer-up 后写入 document，reload 与桌面回读保持，undo/redo 为单历史项；
- [x] 最右侧 `＋` 始终由共享 scene 提供，可点击/键盘激活且滚动行为与桌面一致；
- [x] 顶栏与谱面栏的 control id、顺序、分组、状态、尺寸及图标与桌面金标准一致；
- [x] raw input JVM/JS trace、真实浏览器 E2E、Desktop adapter/UI/screenshot 与文件回读全部通过；
- [x] 能力矩阵只在全部门禁通过后恢复为“桌面对齐完成”。
