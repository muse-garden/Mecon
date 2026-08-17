# 自由练习完整工作台 Web 化实施计划

> 实施基线：`afd53740`（2026-08-06）；更新：2026-08-07；状态：✅ W2–W6、F2、D2 与桌面对齐已完成，钢琴卷轴按既定边界保留桌面实现。
>
> 首发已完成共享 session、Kotlin/JS facade、公共 `ScoreEditor`、typed 时间轴、规划面板、窄屏 tabs、
> 后台 channel 与浏览器/资源门禁。2026-08-07 二次复审发现的时间轴双实现、左端错位、短谱
> surface、拖动落盘/点选/末端 `＋` 和工具栏差异，已按
> [统一审计及重构计划](free-practice-web-desktop-parity-plan.md) 完成收口；本文仍只作为实施记录。
> 总体多端边界见 [自由练习多端方案](free-practice-multiplatform.md)，乐谱编辑硬约束见
> [乐谱编辑多端接入规范](../score-editing-multiplatform.md)，逐项状态见
> [Web 能力矩阵](free-practice-web-editor-capabilities.md)。后续功能调整不再向本文追加过程日志，统一按
> [自由练习功能扩展指南](free-practice-extension-guide.md) 接入。

## 1. 本轮目标与边界

本轮把桌面自由练习的完整工作台交互搬到 React，同时保持 Web 为轻量平台壳：所有持久化写入仍以
普通 JSON intent 进入 Worker，由 Kotlin/JS 中的 `FreePracticeSession` 和内层
`ScoreEditingSession` 执行；React 不直接修改 `StorageScore` 或 `HarmonyWorkspaceState`。

| 能力 | `8d94ce91` 现状 | 本轮完成定义 |
|---|---|---|
| 完整乐谱编辑 | 能力与公共交互控制器已接通；组合根迁为 `App.tsx`，属性状态与后台 Worker 已拆分 | 可复用公共 React 组件，默认完整 profile |
| 自由练习谱面工具栏 | 与全功能编辑器混在同一顶栏/右栏 | 使用基础音符编辑 profile，可自定义布局和显隐 |
| 和声时间轴 | 左栏只有槽位按钮 | 桌面等价的槽、边界、调性线、惯用进行交互 |
| 右侧面板 | 只有扁平和弦下拉与 finding key | 当前调性、和声选择/详情、惯用进行、反馈完整迁移 |
| 响应式工作台 | 简单三栏堆叠 | 宽屏双栏，窄屏 tabs/步骤视图，交互不缩水 |

普通编辑器仍以业务结果、选择、历史边界、文件和跨端 trace 等价为主；自由练习时间轴与两层工具栏
属于产品基线，须共享 geometry/descriptor 并通过像素和截图门禁，不能只验证 intent 等价。
钢琴卷轴、禁忌表和综合教学练习不在本轮范围；钢琴卷轴保留桌面实现，待时间轴与右栏稳定后另立
阶段接入。误调用本轮未装载的禁忌表能力时仍必须显式失败。

## 2. 状态所有权

| 层 | 持有内容 | 禁止事项 |
|---|---|---|
| `FreePracticeDocument` | 设置、和声 workspace、稳定 slot/layout/idiom id | 不保存 hover、scroll、面板宽度 |
| `FreePracticeSession` | revision、统一 selection、历史、写作、目录请求、typed view/effect | 不依赖 Compose、DOM、字体或文件系统 |
| `ScoreEditingSession` | 谱面选择、编辑命令、clipboard、`nextInputPosition`、render hint | 不感知 React 布局 |
| 共享 timeline controller | raw scene、hit objects、lane、量化、gesture、ghost、append 与 edit/selection 路由 | 不依赖 Compose、DOM 或平台字体 API |
| React/Compose | raw scene 重放、原始输入转发、pointer capture、cursor、焦点、scroll、折叠/tabs | 不插值时间、不命中/量化、不构造 timeline edit、不分 lane |
| 平台 adapter | Worker 生命周期、IndexedDB、`.mecon`、Web Audio、Web MIDI | 不推导和弦、调性、声部或时间轴交互 |

工具栏按钮显隐只控制展示，不是业务权限。按钮的 enabled、参数约束和最终接受/拒绝仍由共享 frame
与 session 决定。普通五线谱像素 adapter 继续遵守 score-editing 规范；自由练习时间轴的像素映射、
hit-test 与瞬时 ghost 必须进入共享 timeline controller。

## 3. 目标结构

```text
web/packages/web-renderer/editor/    公共、受控的 React 乐谱编辑入口
  ScoreEditor                        工具栏 + 画布 + 可选 inspector
  useScoreEditorController           平台工具、选择、pointer/keyboard/MIDI adapter
  ScoreEditorToolbar                 稳定 control id registry + profile/layout
  interaction/                       命中、框选、拖动几何与 preview（原 editing-actions.js）

web/apps/free-practice/
  FreePracticeWorkbench              文件/恢复/音频/Worker composition root
  HarmonyTimeline                    raw timeline scene 重放 + DOM 语义 adapter
  PracticePlanPanel                  只渲染 typed plan view
  PracticeFeedbackPanel              只渲染 typed finding view

features/free-practice/commonMain/
  FreePracticeSession                唯一工作台状态机
  FreePracticeProtocol               intent/frame/effect/request wire
  FreePracticeViewProjector          timeline/plan/toolbar/piano-roll 只读投影
  FreePracticeTimelineController     raw scene/hit/gesture/preview/commit
```

数据流固定为：

```text
raw input -> timeline controller -> FreePracticeSession
                |                         |
                |                         +-> ScoreEditingSession
                +-> raw scene             +-> document/history
                         \-> Desktop/Web replay
```

一次发布必须原子包含相互匹配的 document、score update、frozen bundle、geometry 与 time axis；不能先
发布新时间轴再显示旧谱面，也不能用流式首个页面冒充完整 render generation。

## 4. 公共乐谱编辑 React 组件

### 4.1 包边界

在现有包新增可选入口 `@mecon/web-renderer/editor` 与 `@mecon/web-renderer/editor/react`；包的主入口
继续保持 headless 引擎/facade，不把文件、恢复、音频或自由练习状态塞进去。这样无需再发布一个
npm 包，未 import editor 子路径的消费者也不会加载 React 编辑 UI。

公共组件采用受控接口，宿主负责外层 session 包装：

```ts
<ScoreEditor
  frame={{ update, bundle, geometry, timeAxis }}
  dispatchScoreIntent={dispatchInnerIntent}
  toolbar={FREE_PRACTICE_SCORE_TOOLBAR}
  interactionPolicy={FREE_PRACTICE_SCORE_INTERACTIONS}
  onAudition={platformAudition}
/>
```

普通乐谱宿主直接 dispatch `ScoreEditIntent`；自由练习宿主把同一 inner intent 包成
`FreePracticeIntent.Score`。公共组件只读取内层 score revision，不知道外层 document、写作或目录。

### 4.2 组件拆分

当前落地：`ScoreEditorSurface`、`ScoreEditorToolbar`、interaction helpers、输入/结构/表情三组
domain state hooks，以及 event/structure/expression command controller 已进入公共包。步进输入、倚音、
连音组、布局断点与谱表可见性 inspector 也已公共化，MIDI 只由宿主 callback 注入。表情、结构、
重复/导航与连线 inspector 仍需继续迁移，不能把现有 `App.tsx` 当成新宿主范式。本轮已先把
`PracticeTopToolbar`、`PracticeNoteProperties`、`AudioSettingsDialog` 与
`PracticeBackgroundWorkers`、`PracticePlaybackController` 拆为 typed 模块，并由架构测试扫描
`.ts/.tsx` 防止平台业务回流。浏览器音频图、异步 schedule generation 与播放光标动画均由后者
独立拥有，React 组合根只转发共享播放请求和 UI 设置。

1. 从 `App.tsx` 继续提取 worker 无关的 controller、画布、其余 inspector 和 interaction helper，
   第一阶段不改 intent 形状和交互结果；
2. 文件打开/导出、IndexedDB、Service Worker、搜索 worker、Web Audio 留在应用层并通过 callback 注入；
3. 把约 40 个局部表单状态按能力域拆到 hooks，避免公共组件重新形成单文件巨石；
4. 现有 `editing-actions.js` 迁到公共包并保留纯函数测试；应用不得复制一份；
5. `App.tsx` 最终只负责装配 `FreePracticeWorkbench`，不再包含具体乐谱命令按钮。

### 4.3 工具栏配置

每个控件使用稳定 `ScoreEditorControlId`。registry 集中定义 label key、图标/SMuFL 字形、快捷键、
可见条件、enabled 投影和 intent 构造；宿主只配置排列与显隐。

```ts
type ToolbarLayoutItem =
  | { type: "group"; id: string; items: ScoreEditorControlId[] }
  | { type: "separator" }
  | { type: "break" }
  | { type: "slot"; id: string };

type ScoreEditorToolbarConfig = {
  layout: ToolbarLayoutItem[];
  hidden?: ScoreEditorControlId[];
  overflow?: "wrap" | "scroll" | "menu";
};
```

内置两个不可变 profile：

- `FULL_SCORE_EDITOR_TOOLBAR`：覆盖现有能力矩阵全部能力族；
- `FREE_PRACTICE_SCORE_TOOLBAR`：选择/音符工具、按 frame 中实际谱表派生的声部、常用及折叠时值、
  休止、附点、变音记号、延音线/圆滑线、倚音/小音符、连音组、符杠和发音法；隐藏谱号/调号/拍号、
  小节结构、反复、导航、
  表情、布局和谱表可见性入口，与桌面 `HorizontalScoreEditor(showScoreElementTool=false)` 对齐。

自定义 `slot` 只能插入宿主控件，不能替换同名核心命令的业务实现。隐藏按钮后快捷键是否保留由独立
`shortcutPolicy` 明确配置，不能从 DOM 是否存在反推。

### 4.4 公共组件退出条件

- free-practice 应用和独立全功能 fixture 使用同一个 `ScoreEditor`；
- 默认 profile 的每个 control id 都映射到能力矩阵与至少一个 E2E；
- 基础 profile 快照证明结构/表情组不可见，音符编辑与快捷键仍走相同 inner intent；
- 组件不 import `.mecon` codec、FreePractice 类型、搜索 worker 或 Web Audio。

## 5. 先补齐复合 session 协议

当前 `FreePracticeSession.dispatchScore` 丢弃内层 dispatch result，外层对 inner no-op 也递增 revision，
`update()` 又重新取 `scoreSession.initialUpdate()`；在公共编辑器依赖它之前必须修正真实 inner effect、
base revision、`scoreChanged`、`nextInputPosition` 与 render hint 的透传。

新增或扩充以下契约（全部用稳定 ID，不传数组下标）：

| 契约 | 最低内容 |
|---|---|
| `FreePracticeSelection` | slot、tonal layout、idiom instance、内层 score selection |
| `PracticeToolbarView` | 设置、写作状态、canRewrite/canAlternate、选区范围 |
| `PracticeTimelineView` | 槽、锁定、读法、调性 span、派生 span、idiom marker、网格能力 |
| `PracticePlanView` | 当前调性、和弦分组/详情/低音、枢纽、惯用进行、disabled reason |
| `PracticeCatalogStatus` | request key、generation、loading/stale/error、typed contribution |
| `FreePracticeChangeFlags` | `documentChanged`、权威 `scoreChanged`、view/render generation |

`FreePracticeIntent` 至少补齐：槽区间放置/平移/左右端点/共享边界；调性布局增删改选；枢纽开关；
惯用进行插入/替换/删除；声部布局迁移；显式重建练习；统一选择。
这些 intent 在 session 内委托 `HarmonyWorkspaceEditor`、material projector、自动声部分配器和内层
score session，React 不构造 `HarmonyWorkspaceCommand`。

自由练习包裹的普通五线谱插入也必须在 session 内执行练习专属的复音上限、自动记谱声部和 workspace
同步校验；当前桌面 `HarmonyPracticeScoreHost` 中的特例不能只迁 Web 控件而继续留在平台层。

预览使用独立、无副作用的 `previewTimelineEdit(baseRevision, requestId, edit)` 查询：不改 revision、
document 或历史；结果带 requestId，前端只接收当前 gesture 最新结果。pointer-up 仍发送普通 commit
intent，session 独立重算和校验，不能信任 preview。

工作台提交使用单 in-flight FIFO：Worker 回传对应 `clientRequestId` 后才确认该 intent；若提交触发
自动写作，后续 intent 继续等待 `writing.phase != RUNNING` 的权威帧，再注入最新 workspace/score
revision。这样不会把 React 捕获的旧 revision 伪装成新提交，也不会在 session 尚持有候选 workspace
时把下一次编辑错误地施加到旧 workspace。任何提交都会令当前 preview requestId 失效，迟到 preview
不得覆盖权威帧。

教学目录与较重 finding 改为 `PracticeBackgroundRequest` 的独立 channel/kind，支持同帧多个 request；
writing、catalog、findings 各自 newest-wins，目录刷新不能 terminate 正在写作的搜索 Worker。Worker
不得再只执行 `requests[0]`。每个结果都校验 requestId、baseRevision 与 fingerprint，过期结果可见地
丢弃。wire schema 升级与旧字段兼容只改协议；若新增持久化字段，必须先更新 `docs/data_model/`。

历史规则：选择、过滤、preview 不进历史；一次时间轴 commit（含补小节与可选自动写作）只生成一个
历史项；stale/invalid/no-op 状态与历史都不变。声部数/初始调性的“重建练习”必须显式确认并返回
typed reset effect，不能伪装为普通滑块更新。

## 6. 和声时间轴：共享 raw scene

首发的 SVG + DOM 语义层保留为 Web 绘制后端，但不再拥有布局或交互逻辑。commonMain controller 输出
完整 draw/hit/a11y objects；Desktop Canvas 与 Web SVG/Canvas/DOM 只重放同一 scene。详细 DTO、
迁移顺序与红线见[统一审计及重构计划](free-practice-web-desktop-parity-plan.md)。

### 6.1 统一时间投影

把桌面 `freePracticeAlignedTimeAxisRequest` 的音乐投影和 viewport contract 移到共享层。scene 明确给出
`contentOriginX/contentWidth/viewportWidth/scrollExtent`，时间轴与五线谱共享 raw pixel anchors；
React/Compose 不再自行插值。新 bundle 完成前保留同 generation 的旧 bundle/axis/scene，随后原子切换。

### 6.2 手势

1. 平台把 surface-local `DOWN/MOVE/UP/CANCEL/KEY/WHEEL` 转给 controller；
2. controller 用 scene hit objects 确定稳定目标，完成量化、preview 与 ghost scene；
3. Worker 可合并 MOVE，但不可丢 DOWN/UP/CANCEL，过期 scene generation 显式拒绝；
4. pointer-up 由 controller 提交一个 intent；成功采用权威 frame，失败回弹并返回 typed reason；
5. Escape、pointer-cancel、丢失 capture 由 controller 清 gesture，不提交历史。

覆盖槽整体移动、单端伸缩、共享边界、连同后续平移、终点插入、删除；调性线创建/改调/改范围/删除；
惯用进行选中与删除。锁定槽和固定转位的可操作性由 timeline view 给出，session 再次校验。

## 7. 右侧面板与工作台工具栏

宽屏与桌面对齐为“主写作区 + 可调宽右栏”，不是当前临时三栏。主区上方是时间轴，下方是五线谱；
右栏按顺序渲染当前调性、和声选择、和弦详情、惯用进行、Hint 与警告。

| React 区域 | 只消费的共享投影 | 发送的 intent |
|---|---|---|
| 当前调性 | active/selected tonal layouts | select/insert/change/remove layout |
| 和声选择 | selected chord、readings、groups、bass options、lock | replace chord、bass、tonality、pivot |
| 和弦详情 | typed construction/detail rows | 无，或同一 selection intent |
| 惯用进行 | focused/default sections、variant、loading/error | filter、insert/replace/remove idiom |
| 反馈 | finding id、message key/args、severity、anchors | 选择/聚焦 finding |
| 顶部工作台工具栏 | `PracticeToolbarView` | 重写、换结果、写作设置、声部/调性配置 |

`ChordSelectionCatalog` 查询、`tonalityOptions`、和弦匹配、离调过滤、惯用进行去重/指导和目录发现全部
移到 common projector/session。React 只本地化 `messageKey + arguments`、管理折叠/弹层/面板宽度，
不得根据 pitch class 或字符串 symbol 重建这些规则。

窄屏使用“时间轴 / 五线谱 / 计划 / 反馈”tabs；切换 tab 不销毁 session、选区、播放或
未完成的 Worker 请求。面板宽度、split ratio、折叠和 label mode 是平台偏好，不进入 document。

## 8. 播放

播放区间和 MIDI excerpt 继续由 Worker 生成，AudioContext/AudioNode 调度留在浏览器；stop、seek、
组件卸载和 session close 都必须补齐 note-off 并释放节点。钢琴卷轴保持桌面专属，不参与本轮 Web
frame、time axis 或 E2E 退出条件。

## 9. 实施阶段

| 阶段 | 主要修改 | 退出条件 |
|---|---|---|
| ✅ W2a 公共编辑器提取 | 公共包以 `ScoreEditor` + `useScoreEditorController` 统一持有 surface、toolbar、完整 registry、domain hooks、命令/click/drag controller 与 inspector；完整 fixture 与自由练习使用同一 host，宿主仅用 render function 安排区域 | 已完成；架构测试禁止应用回退到低层拼装 |
| ✅ W2b 工具栏 profile | registry、layout/hidden/slot、完整/练习 profile 已通过 Node 单测与 W1 浏览器回归 | 已完成本阶段 |
| ✅ F2 复合协议 | wire v2 已覆盖 inner 精确透传、时间轴预览、稳定 ID 生命周期、显式 slot/layout/idiom/score selection、谱表分配、练习重建及 writing/catalog/findings 独立 channel；JVM/JS trace 到 revision 36，并验证选择、谱表分配与手工记谱来源随 undo/redo 恢复，以及复音上限失败原子性 | 已完成本阶段；后续新增 intent 继续追加同一 trace |
| ✅ D2 桌面收敛（钢琴卷轴除外） | 和弦、低音、调性、枢纽、惯用进行、槽/调性线与桌面时间轴最终命令均走 stable-ID session intent；workbench 的槽、调性线和惯用进行 reducer fallback 已删除；普通五线谱的插入/删除/移调/时值/休止符/变音/延音线/符杠/连音组/小音符/声部移动/连线/奏法均走内层 `ScoreEditingSession`。共享 commit policy 在历史提交前校验复音上限，并把手工事件来源放在同一 undo 边界 | 钢琴卷轴及其自动记谱 adapter 按本轮明确边界保留，另立阶段迁移；测试构造器使用的外部快照入口不属于生产 UI |
| ✅ W3 时间轴 | commonMain raw scene/controller 统一 geometry、lane、hit-test、量化、gesture、preview/commit 与末端 `＋`；Desktop/Web 为薄重放层 | JVM/JS raw trace、真实 pointer/keyboard、reload/undo/redo、64 槽滚动/viewport 与 Desktop 金标准通过 |
| ✅ W4 右栏/工具栏 | plan/feedback 已接通；`PracticePlanView` 提供 presentation-ready 导航、调性、和弦读法/锁定/离调 payload 与惯用进行；右栏不重复顶栏控件并支持 240–720px 拖宽；两层 toolbar 消费共享 descriptor 与 64/28dp token，Bravura 码位集中管理 | controller/去重架构测试、真实 pointer/键盘调宽与 control-id 快照通过 |
| ✅ W5 响应式/无障碍 | 窄屏四 tabs 不卸载视图；具备 tablist/tab/tabpanel、roving focus、方向键/Home/End、时间轴键盘语义层与可聚焦的五线谱 `img`/实时摘要。真实浏览器辅助技术树断言只暴露活动 panel，且焦点导航与摘要关联通过 | 已完成；回归由 Playwright 保持可重复 |
| ✅ W6 文件与质量门禁 | F1 浏览器导出→桌面回读验证活动自由练习、兄弟乐谱、未知 module payload 与 manifest workspace；恢复写入串行 newest-wins；JVM/JS 32/64 槽、64 槽浏览器对齐、Node 66/66、常规 Playwright 12/12、生产构建均通过；Service Worker 导航为 network-first + 离线壳回退 | 正式 `npm run test:e2e:soak` 运行 30.3 分钟通过，持续交互无 page error，heap 增长在 96 MB 门槛内 |

F2 与 W2 可并行，但 W3/W4/W5 不得在 F2 之前用 React reducer 临时补业务逻辑。D2 与 Web 组件可
并行消费同一协议；每新增 intent 必须同批接桌面 adapter 和 Web 入口。

## 10. 测试、文件与性能门禁

1. 扩写 `features/free-practice/testdata/practice-trace.json`；当前由开发者显式编辑步骤并先经 JVM
   `FreePracticeTraceTest` 校验，再由 Kotlin/JS 重放。`-Pfreepractice.trace.write=true` 尚无生成器，
   不作为重刷命令；JVM/JS 每步比较规范化 document、score、双 revision、
   selection、effect、requests、writing、catalog/finding generation、`scoreChanged`、
   `nextInputPosition` 和 render hint；
2. trace 覆盖全部时间轴、调性布局、惯用进行、设置重建、score inner no-op/stale、失败原子性、
   单历史项、undo/redo 选择恢复和 preview 不入历史；
3. 公共组件测试覆盖完整/基础/自定义 toolbar profile、隐藏与换行/overflow、pointer、键盘和 Web MIDI；
4. Playwright 用真实 pointer/keyboard 修改时间轴、右栏和谱面，再导出 `.mecon`；桌面
   `FreePracticeDocumentCodec` 回读并断言 workspace/score，同时保留 sibling score、未知 module 和
   manifest workspace；
5. 架构测试禁止 Web 源码 import/复制 `HarmonyWorkspaceEditor`、和弦/惯用进行白名单或直接写
   `StorageScore`；禁止 common feature import Compose/AWT/DOM；
6. selection、timeline preview 和 workspace-only 更新不得触发谱面 layout；普通音符编辑继续保留
   incremental render hint 与 splice 等价；
7. 4 声部 32 槽、6 声部 64 槽记录 preview/commit/cancel p50/p95，preview 队列不积压，React 主线程
   不运行 compute/solve/catalog/finding/MIDI 转换，时间轴与五线谱在所有 resolved anchors 的对齐
   误差 ≤1 px；
   当前内核通道实测：JVM 最差 p95 28.34 ms（4/32 preview），Kotlin/JS 最差 p95 34.15 ms
   （6/64 cancel）；两组 preview/commit/cancel 均已进入自动测试。既有 64 槽用例证明 renderer anchor
   与槽内部坐标误差 ≤1 px，但未覆盖 CSS margin/Canvas 居中后的屏幕原点，不能作为本次左端对齐门禁。
8. 自动恢复按持久化 generation 防抖，不能每个 selection/hover frame 都重打 ZIP；30 分钟操作后
   Worker、AudioNode、bundle 快照和 listener 数量不持续增长。自动门禁为 `npm run test:e2e:soak`；
   默认运行 30 分钟，也可用 `MECON_SOAK_MINUTES` 调整本地诊断时长。2026-08-06 正式运行
   30.3 分钟通过，64 槽窄屏持续切换/选择无页面错误且 late-vs-early heap 增长小于 96 MB。

## 11. 完成条件

- `App.tsx` 不再拥有具体乐谱编辑能力，公共组件同时服务完整编辑器与自由练习；
- 自由练习两层工具栏由共享 descriptor 驱动，control id、顺序、分组、状态和视觉 token 与桌面一致；
- 时间轴由共享 raw scene/controller 驱动，React/Compose 不再拥有 geometry、hit-test 或 gesture reducer；
- 桌面与 Web 的所有持久化动作都只经共享 session，React/Compose 不再直接调用业务 reducer；
- JVM/JS trace、真实浏览器交互、浏览器导出→桌面回读及性能门禁全部通过；
- 能力矩阵、总体多端方案和数据模型文档与实际状态一致。
