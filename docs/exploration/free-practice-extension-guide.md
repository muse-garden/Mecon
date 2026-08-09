# 自由练习功能调整与新能力接入指南

本文是自由练习当前实现的开发入口。已完成范围与证据见
[Web 能力矩阵](free-practice-web-editor-capabilities.md)，历史方案与取舍见
[多端方案](free-practice-multiplatform.md)和[完整工作台实施计划](free-practice-web-workbench-completion-plan.md)。

## 1. 不可绕过的边界

```text
Desktop adapter ───────────────┐
React → engine Worker → facade ├─→ FreePracticeSession
Future mobile adapter ─────────┘       ├─ HarmonyWorkspaceEditor
                                       ├─ inner ScoreEditingSession
                                       └─ background request/result
```

- `features/free-practice/commonMain` 是自由练习 document、workspace、选择、历史、写作和投影的唯一业务入口；
- 普通记谱能力由内层 `ScoreEditingSession` 负责，使用 `FreePracticeIntent.Score` 包裹；
- React/Compose 只能维护平台 UI 状态并重放共享输出。普通谱面的像素 adapter 依 score-editing 规范；
  自由练习时间轴的像素映射、命中和瞬时 preview 由共享 timeline controller 负责；
- intent 只使用稳定 slot/layout/idiom/event/track ID 与音乐时间，不传数组下标、像素或帧对象；
- UI 禁用态不是校验。session 必须重验 revision、目标存在性、参数、复音及结构约束；
- 钢琴卷轴仍是明确后置范围，不应借新功能改动偷偷复制或扩张它的桌面旁路。

## 2. 先判断改动属于哪一类

| 类型 | 典型例子 | 起点 |
|---|---|---|
| 纯展示/布局 | 面板折叠、标签、响应式排列 | React/Compose adapter；不改 document |
| 现有投影的新展示 | 展示 frame 已有的 chord detail/finding | 对应平台组件，不重新推导音乐信息 |
| 新自由练习状态变化 | 新槽操作、设置、教学选择 | `FreePracticeIntent` + `FreePracticeSession` |
| 新普通乐谱编辑 | 新记谱元素或音符命令 | `ScoreEditIntent`，再由自由练习包裹 |
| 新持久化能力 | workspace/settings 新字段 | 先改 `docs/data_model/free-practice.md` 和 schema/migration |
| 新重 CPU 工作 | 求解、目录、整谱 finding | serializable background channel + 可终止 Worker |
| 新时间轴手势 | 区间、端点、吸附 | commonMain timeline scene/controller + 单次 commit intent |

若领域结果需要 React 根据 pitch class、和弦符号或历史状态计算，分类通常选错了：应先增加共享投影。

## 3. 当前代码地图

| 职责 | 文件/目录 |
|---|---|
| 持久化 document/settings 与 v1–v8 迁移 | `exploration/.../FreePracticeDocument.kt` |
| workspace 实体与不可变命令 | `theory/.../freepractice/HarmonyWorkspace.kt` |
| intent/effect/frame/background wire | `features/free-practice/.../FreePracticeProtocol.kt` |
| revision、事务、历史、校验与调度 | `features/free-practice/.../FreePracticeSession.kt` |
| timeline/plan/catalog/selection 投影 | `FreePracticeViewProjector.kt` |
| timeline raw scene/输入控制器 | `PracticeTimelineController.kt`；JVM/JS 共用 `timeline-raw-input-trace.json` |
| 写作、目录、finding 执行 | `FreePracticeWriting.kt`、`PracticeTeachingCatalog.kt`、`PracticeFindingExecutor.kt` |
| Kotlin/JS 字符串 facade | `bridge/web-engine/.../MeconFreePractice.kt` |
| 串行引擎/排版 Worker | `web/apps/free-practice/src/engine-worker.js` |
| 可终止后台 Worker | `web/apps/free-practice/src/search-worker.js` |
| React composition root | `web/apps/free-practice/src/App.jsx` |
| 时间轴重放/右栏 | `HarmonyTimeline.jsx`、`PracticePlanPanel.jsx`、`PracticeFeedbackPanel.jsx` |
| 公共乐谱编辑器 | `web/packages/web-renderer/editor/` |
| 桌面 adapter | `FreePracticeWorkbench.kt`、`FreePracticeEditorPanel.kt`、`EditableScoreHost.kt` |

共享 raw scene 只收敛 authority，不改变既有桌面视觉基线。修改 scene 时必须同时核对：

- typed timeline view 是否仍包含桌面已展示的多调性读法、音级、锁定/枢纽/选中状态；
- draw objects 是否覆盖刻度、小节号、调性线、边界手柄、惯用进行括号和插入空隙；
- Desktop `pointerInput`/Web pointer capture 是否在完整 `DOWN → MOVE* → UP/CANCEL` 期间保持同一事件流；
- controller trace、平台 adapter 测试和代表性截图是否分别通过。纯 controller trace 不能替代平台手势测试，
  新截图也必须人工对照批准的基线，不能直接把当前输出重刷成 golden。

## 4. 调整已有纯 UI

如果 frame 已提供完整信息且操作不持久化，可只改平台层。例如面板宽度、tab、展开状态和 label mode
都不进入 document，也不进 undo 历史。谱面拖动 ghost 虽是瞬时状态，其乐谱元素命令仍须由共享
renderer preview computer 生成；JS 只重放命令，不自行拼装或平移乐谱元素作为降级实现。

右栏统一消费 `PracticePlanView`：选中槽与导航、活动调性线、和弦读法/锁定能力、离调候选的
ready-to-dispatch payload、覆盖的惯用进行均由 `FreePracticeViewProjector` 给出。React 不得再从
`timeline` 或 document workspace 查找并拼出这些关系。面板拖宽是平台布局状态；已由顶栏 descriptor
承载的重建、写作、播放控件不得在右栏重复。

右栏的可见动态文案也属于共享投影契约：调性名称/范围、和弦读法与音名、低音候选、离调说明行、
惯用进行及变体标签均由 `PracticePlanView` 提供，平台不得自行格式化。静态文案由其中的
`PracticePlanStrings` 统一给出。Desktop/Web 对同一数据采用相同组件语义：桌面平铺项在 Web 仍为
平铺按钮/行，桌面顶部的调性选择在 Web 仍使用同语义的下拉面板；当前调性编辑与“插入调性”
使用 `@mecon/web-renderer/react` 导出的公共 `CircleOfFifthsPicker`，调性标签仍直接消费
`PracticePlanView.tonalKeyChoices`。tab、展开状态和相对/绝对音切换可保留为瞬时 UI 状态。
和弦目录的分组标题、说明以及相对/绝对音芯片标签由 `PracticePlanView.chordCatalogGroups` 提供；
两端均展示带内部滚动的分类平铺目录，不允许 Web 将其降级成单行原生 `select`。

自由练习当前有经典布局与分区布局。桌面由顶栏切换：经典布局继续同时纵排五线谱与钢琴卷轴；
分区布局的写作区一次只挂载一个表面，并在五线谱/钢琴卷轴之间切换，写作区下方以可调高度的
左右等分面板展示和声选择与惯用进行，右栏保留当前调性、默认展开的和弦详情及反馈。Web 因钢琴
卷轴仍属后置范围，默认使用只含五线谱的分区布局；`App.jsx` 保留经典组合分支，待钢琴卷轴接入后
再开放布局切换。布局、表面选择、面板高度与展开状态均为平台瞬时状态，不进入 document/undo。
宽屏工作台的左右区域必须共同填满剩余视口高度，右栏单独纵向滚动；工作台滚动条统一使用和声
选择目录的深色细滚动条。工具栏下方只显示真实错误/告警，不将 `revision` 作为常驻状态栏文案。
教学目录合并时须保留 concrete variant 的 `relatedToFocus` / `availableByDefault` 归属；开启离调只扩展
当前和弦相关候选及目标调性筛选，不得把全目标调性的变体混入默认教材列表或同定义的非相关变体。

要求：

1. 组件只消费 typed view，不从 score/workspace 二次枚举规则；
2. 宽屏和窄屏共享同一已挂载 session/Worker，不因切 tab 重建；
3. 新交互保持键盘、焦点、ARIA 名称和活动 panel 可见性；
4. 至少补 Node 组件/架构测试；用户路径补 Playwright。

本地化只把 `messageKey + arguments` 映射为文案，不根据文案反推 effect 或业务状态。

## 5. 调整公共乐谱编辑器或工具栏

完整与自由练习必须继续共用 `ScoreEditor` + `useScoreEditorController`。自由练习默认使用
`FREE_PRACTICE_SCORE_TOOLBAR`，普通 fixture 使用 `FULL_SCORE_EDITOR_TOOLBAR`。

自由练习两层 toolbar 统一消费 `FreePracticeToolbarSpec` 的 stable control id、分组和视觉 token；
Desktop `Toolbar`/`HorizontalNotePalette` 与 Web `PracticeTopToolbar`/`ScoreEditorToolbar` 不再各维护一份
顺序。时值、休止、附点和变音按钮使用 `music-glyphs.js` 的命名 SMuFL 码位。回归要求见
[统一审计及重构计划](free-practice-web-desktop-parity-plan.md)。

自定义配置示例：

```jsx
const toolbar = {
  layout: [
    { type: "group", id: "history", items: ["history.undo", "history.redo"] },
    { type: "separator" },
    { type: "group", id: "notes", items: ["selection.duration", "selection.tie"] },
    { type: "slot", id: "practice-actions" },
  ],
  hidden: ["selection.tie"],
  overflow: "wrap",
};

<ScoreEditor controller={editor} toolbarConfig={toolbar}
  toolbarSlots={{ "practice-actions": <PracticeActions /> }} />
```

- 新核心按钮先加入 `ScoreEditorControlId`/registry，再加入适用 profile；
- slot 只容纳宿主控件，不得替换同名核心命令的业务实现；
- 隐藏按钮与快捷键策略分开判断，不从 DOM 是否存在反推；
- `App.jsx` 只组合公共区域，禁止重新拆回 surface/controller/inspector 的应用内副本；
- 同步 `toolbar.test.js`、公共 controller 测试及 W1 Playwright。

## 6. 新增自由练习持久化操作

### 6.1 模型和命令

1. 若增加字段，先更新 `docs/data_model/free-practice.md`；
2. 在 Storage/workspace 类型中使用 `val`、稳定 ID 和 `@Serializable`；
3. 提升 `FREE_PRACTICE_SCHEMA_VERSION`，补旧版本默认值、迁移和未来字段拒绝/保留测试；
4. 在 `FreePracticeIntent` 增加有 `@SerialName` 的变体，携带 `expectedRevision`；
5. workspace 变换放在 `HarmonyWorkspaceEditor` 或最相关的 common policy，不写在 session 的 UI 分支里。

不新增持久化字段时，不应为了传 UI 状态提升 document schema。Wire schema 与 document schema 是两件事。

### 6.2 Session、effect 与历史

在 `FreePracticeSession.dispatch` 中：

1. 先验证外层 revision，再验证稳定目标和参数；
2. 构造完整候选 document/workspace/score；
3. 一次用户动作只 commit 一次；失败不得留下部分 score 或 workspace；
4. 明确 `APPLIED`、`SELECTION_CHANGED`、`INVALID`、`STALE_*`、`NO_OP` 等 effect；
5. 选择/过滤/preview 不进历史；持久化设置是否进历史必须在协议中明确并测试；
6. 若同时修改谱面，使用 session 的事务/commit policy，让复音校验、手工事件来源和历史处于同一边界；
7. undo/redo 必须恢复 document、score、统一 selection 和相关来源映射。

不要直接调用 `HarmonyWorkspaceEditor` 后再让 UI 另行提交 score；这会产生两个 revision 和两个历史项。

### 6.3 Frame 投影

React 需要的数据加入 `PracticeTimelineView`、`PracticePlanView`、`PracticeToolbarView`、`PracticeFindingsView` 或专门的 typed view，并在 `FreePracticeViewProjector` 集中生成。

- 投影可包含 label key、disabled reason、稳定 anchors 和可操作能力；
- 不把内部对象引用、求解器类型图或 Kotlin collection 暴露给 JS；
- 不让 React 根据 symbol/pitch classes 重建和弦候选、惯用进行或规则；
- 非 score 变化应保持 `scoreChanged=false`，避免无谓排版。复合 session 在 `scoreSession.dispatch`
  之外提交时，每个操作入口都要先调 `ScoreEditingSession.beginExternalOperation()`，否则首次提交后
  `scoreChanged` 会一直粘滞为 true；
- 是否需要落盘由 session 的 `documentChanged` 表达（谱面 / workspace / settings 任一变化），
  平台不得用 effect 名单近似——写作无解时会先提交文档再报 `INVALID`；编辑发声同理只消费 session 的
  `PracticeEditPlayback`，不得在平台按 effect/选区/惯用进行长度重算范围；编辑回放不移动播放线，长惯用进行整段回放由共享策略决定。
## 7. 新增普通记谱能力

先完整执行[乐谱编辑多端接入规范](../score-editing-multiplatform.md)：core immutable edit →
`ScoreEditIntent`/session → Computed/renderer/hit box/splice → desktop/Web adapter → shared trace/E2E。

自由练习只做两件额外工作：

1. Web/桌面把内层 intent 包为 `FreePracticeIntent.Score`；
2. `FreePracticeSession` 在提交前执行练习专属复音上限和 workspace 来源同步。

React 调用公共 `ScoreEditor` 的 `dispatch`，不要新增自由练习专属音符 reducer。外层 FIFO 会在发送时注入
最新 workspace revision，并给 inner intent 注入最新 score revision；调用组件不要捕获旧 revision。

## 8. 新增后台能力

只有 CPU 重、可过期或需要取消的工作才进入后台 channel。

- 写作类使用 `PracticeBackgroundRequest` 与明确 `kind`；同 kind newest-wins；
- 教学目录和 finding 保持独立 request/result/generation，不挤占写作 Worker；
- request 带 `requestId`、`baseRevision` 和 fingerprint；result 返回相同身份；
- session 应用结果时再次校验三者，过期结果返回 typed stale effect；
- Web 用可终止 `search-worker.js` 执行；Kotlin/JS 忙时取消依靠 `terminate()`，不能假设消息可抢占；
- `engine-worker.js` 必须处理同帧全部 request，不得只取 `requests[0]`；
- 主线程只显示 loading/stale/error 和最新不可变结果。

如果增加一种独立 channel，同批扩展 protocol、session generation、facade executor、Worker 路由、关闭清理和
竞态测试；不能把结果直接发布进 React 绕过 session。

## 9. 新增时间轴能力

时间轴必须先扩展 commonMain 的 raw scene/controller，再接两端重放层：

1. scene projector 生成 draw/hit/hover/a11y objects、共享内容原点、extent、lane、锁定与 append affordance；
   新增可交互元素时必须同时给出它的 `hoverTargets` 条目（cursor + overlay），否则该元素在两端都没有指针反馈；
2. controller 接收带 scene generation 的 surface-local `DOWN/MOVE/UP/CANCEL/KEY/WHEEL`，平台不先命中；
3. controller 固定 gesture id、base revision、稳定目标和原始音乐边界，完成 time↔x、量化与 preview；
   MOVE/UP/CANCEL 可能已在 scene 因 preview、滚动或 resize 重投影前进入平台队列，只要 pointerId
   仍匹配活动 gesture 就继续处理；需要重新命中的 DOWN/ACTIVATE 仍严格校验当前 generation；
4. `previewTimelineEdit` 无副作用，不改 revision/document/history；MOVE 可 newest-wins，edit 未变化不重算；
5. UP 把 controller 生成的**同一个 `PracticeTimelineEdit`** 交给 `FreePracticeIntent.TimelineEdit`，
   session 重新校验并只提交一次；
6. commit、Escape、cancel 或失去 capture 使旧 generation/requestId 失效；
7. Desktop/Web 重放相同 scene；JVM/JS raw-input trace 逐帧比较 scene/effect/commit，Playwright 再覆盖
   pointer、键盘、横向滚动、reload 与 32/64 槽。

Web 语义按钮只消费 controller 拥有的 Enter/Space、左右方向键、Delete/Backspace 与 Escape；
`Ctrl/Cmd+Z` 等工作台快捷键必须继续冒泡到应用级 handler，不能作为 timeline `KEY` 发往 Worker。

量化、坐标插值、裁剪、重叠、锁定、lane、可移动性、点选和末端 `＋` 均不得进入
`HarmonyTimeline.jsx` 或桌面 Compose composable。平台只负责绘制、系统 capture/cursor/focus/scroll。

scene 使用与浏览器 CSS 像素等价的**密度无关单位**：projector 的行高、手柄宽、字号都是设计单位。
桌面按 dp 送入（`freePracticeAxisSceneUnits`、`beatWidth.value`、scrollLeft 转 dp），绘制时统一
`scale(density)`、指针坐标除以 density；不得把设备像素喂给 scene，否则高分屏下只有轴驱动的宽度按
缩放变大，行高与和弦框会缩水。唯一的例外是文字基线补偿常量：它按设备像素标定，须除以 density 再
转成 scene 单位，随缩放一起放大会让高分屏下所有标签整体偏上。

轴锚点的退化区段由 `TimeScale` 统一处理：末尾小节线锚点会把整小节时值压进几像素。**这类区段既不能
用于外推，也不能用于插值**——按它外推会让指针每移动一点跳过多个小节；落在其**内部**的和弦则相反，
拖动一个网格步只移动约 2px，看起来就是"拖动没有预览"。凡密度低于请求 `pixelsPerWhole` 四分之一的
区段一律丢弃，其后的时间改用比例外推（不低于全轴平均密度），使 `x` 与 `time` 在两侧互为逆映射。
`contentWidth` 也必须能超出记谱表面宽度，拖到谱尾之外的和弦才不会被裁掉。

平台保存的是**预览编辑**而非投影结果：controller 只在量化编辑变化时下发 `previewEdit`，指针停留在
同一网格内时什么都不发，因此把投影钉在生成它的 base 上会让任何 workspace 重新发布（后台写作结果、
撤销）在该次拖动剩余过程中抹掉预览。base 变化时按保存的编辑重新投影；只有已提交的手势才冻结投影
结果（提交后会话基准已含该编辑，重投影会重复应用一次）。

平台保留 preview 直到提交结果回到自己手上。桌面的 workspace 由副作用回写，提交后的下一帧仍是旧
值，因此 `commitTimelineEdit` 返回是否被会话接受：接受则继续显示 preview，拒绝才立即丢弃。

自动写作运行期间，触发它的编辑还没提交、只存在于后台请求里，而各视图显示的正是它。因此 session
的**编辑基准是可见 workspace**（`editBase`）而非最后提交的 workspace：预览不再因“写作中”被整体
拒绝，新的提交也会顶替待处理请求而不是把它回退。新增 workspace 编辑一律经 `editBase` 解析。

**平台渲染的 workspace 必须与 `editBase` 同源。** 桌面从 `EditableScoreHost.practiceWorkspace`
（会话可见 workspace）取值，禁止渲染仅含已提交状态的 `workspace` 并靠"无后台任务时才同步"来遮掩
差异：写作之后还有优化任务，`hasPendingWorkspaceCommit` 几乎持续为真，其间撤销会让时间轴继续画出
会话已丢弃的和弦，下一次拖动的预览落在旧位置上，看起来就是"拖不动、没有预览"。Web 直接渲染 session
frame，本身满足该约束。

谱面是和弦时间轴的投影：`VoicePlanScoreAssembler.ensureTimelineMeasures` 既补齐也**裁剪**——时间
轴变短（向左拖动、删除和弦）时同步删掉末尾小节及其中的孤立音符，否则空小节会堆在谱尾。

悬停同理：`hoverTargets` 已按命中优先级降序排好，平台的全部规则是「取第一个 bounds 命中的目标，
叠加它的 overlay，套用它的 cursor」。命中优先级表、cursor 名称和高亮配色不得在平台侧复制；
Web 也不得为悬停向引擎 Worker 发消息（详见
[free-practice-web-desktop-parity-plan.md](free-practice-web-desktop-parity-plan.md) §3.4）。

## 10. 跨端 trace 与测试

`features/free-practice/testdata/practice-trace.json` 是自由练习 JVM/JS 等价依据。当前 fixture 由开发者
显式追加步骤，先用 JVM `FreePracticeTraceTest` 校验，再由 Node 的 Kotlin/JS replay 校验；
`-Pfreepractice.trace.write=true` 当前没有生成器，不要把它当作重刷命令。

新能力至少覆盖：成功、stale/no-op、失败原子性、单历史项、undo/redo selection 恢复；涉及 score 时还要
比较双 revision、`scoreChanged`、`nextInputPosition` 和 render hint。时间轴手势用 `timelineEdit` 步骤
（`edit` + 按下标寻址的 `slotIndex`/`layoutIndex`），两端都会先 preview 再 commit 并断言两者投影相等。

推荐门禁：

```powershell
.\gradlew.bat :features:free-practice:jvmTest :features:score-editing:jvmTest
.\gradlew.bat :theory:jvmTest :core:jvmTest :apps:desktop:test
cd web
npm run test:engine
npm run build:free-practice
npm run test:e2e
```

性能/资源相关改动另跑 32/64 槽 performance test 与 `npm run test:e2e:soak`。文件改动必须从浏览器导出
`.mecon`，再由桌面 codec 验证活动模块、兄弟乐谱、未知 payload 与 manifest workspace。

## 11. 完成定义

- [ ] commonMain 单独表达业务语义，平台删除后测试仍成立；
- [ ] Desktop 与 Web 都经同一 intent/session，无 reducer 或核心引擎旁路；
- [ ] frame 提供 typed 数据，React/Compose 未复制规则、常量或状态变换；
- [ ] revision、失败原子性、历史、选择和后台 stale 语义有测试；
- [ ] JVM/JS practice trace 已追加并通过；普通记谱能力也追加 score-editing trace；
- [ ] pointer/keyboard/无障碍和必要的文件回读 E2E 已覆盖；
- [ ] 数据模型、能力矩阵、本文和相关 renderer/UI 文档已同步；
- [ ] 明确不接入的平台能力在矩阵记录范围与原因。
