# 自由练习 Web 工作台评审结论（2026-08）

> 评审范围：`54862247 feat(free-practice): complete web workbench`、`0a7fb70d docs(web)`、
> `c612bec4 sync CLAUDE.md` 中与 **Web 自由练习** 相关的部分（`web/`、`bridge/web-engine/`、
> `features/free-practice/`、`features/score-editing/` 及配套门禁）。桌面 adapter 改动
> （`EditableScoreHost` / `FreePracticeWorkbench`）不在本文范围。
>
> 本文只记录评审发现的问题与修复方向，不复述已实现的架构；架构基线见
> [free-practice-web-workbench-completion-plan.md](free-practice-web-workbench-completion-plan.md)
> 与 [free-practice-extension-guide.md](free-practice-extension-guide.md)。

## 结论摘要

| # | 问题 | 类别 | 严重度 |
|---|------|------|--------|
| 1 | `scoreChanged` 在共享 session 里粘滞为 true，Web 每次选择/后台结果都全量重排 | 性能热路径 | 🔴 |
| 2 | 每个 finding / catalog 请求新建并终止一个 Worker，逐次重载 Kotlin/JS 引擎 | 性能热路径 | 🔴 |
| 3 | Worker 抛错后 `practiceIntentInFlightRef` 永不清空，工作台静默冻结 | 正确性 | 🔴 |
| 4 | 浏览器导出→桌面回读门禁实为空跑，仓库内无处设置其系统属性 | 门禁 | 🔴 |
| 5 | 4 个新增时间轴 intent 未进 `practice-trace.json`，也无成功路径单测 | 门禁 | 🟠 |
| 6 | `PracticeTimelineEdit` 与 `FreePracticeIntent` 重复定义，映射逻辑写两遍 | 架构边界 | 🟠 |
| 7 | 每次 dispatch 计算两遍 `frame()`，并对全谱做一次 `toStorage` + JSON | 性能 | 🟠 |
| 8 | `optimisticTimeline` 在 React 复制了 session 的时值再分配语义 | 架构边界 | 🟠 |
| 9 | 时间轴拖动每个 pointermove 都发预览请求，无节流/去重 | 性能 | 🟠 |
| 10 | 自动恢复策略以 effect 名单硬编码在壳层，漏 commit 过的 `INVALID` 帧 | 正确性 | 🟠 |
| 11 | Worker `open` 先建后弃一个 ScoreEditingSession；后台结果绕过串行队列 | 工程质量 | 🟡 |
| 12 | Web 引擎与 E2E 门禁仅能在 Windows + Edge 运行 | 可移植性 | 🟠 |
| 13 | `FreePracticePerformanceTest` 阈值 5s、12 采样、无预热，无法拦截回归 | 门禁 | 🟡 |
| 14 | Service Worker 策略只有字符串位置断言，E2E 又 `serviceWorkers: "block"` | 门禁 | 🟡 |
| 15 | `WebResolvedTimeAxis.revision` 恒为 0；重排判据未包含 timeline | 潜在缺陷 | 🟡 |

---

## 一、性能热路径

### 1. `scoreChanged` 粘滞为 true（🔴）

`ScoreEditingSession.frame()` 用 `state !== dispatchBaseState` 判定 `scoreChanged`
（`ScoreEditingSession.kt:132`），而 `dispatchBaseState` **只在 `dispatch()` 开头刷新**
（`:174`）。自由练习的 workspace / 写作提交走 `transaction.commit` + `notifyExternalCommit()`
（如 `FreePracticeSession.kt:405/440/516/539`），该方法只更新 `observedState` 与 revision
（`ScoreEditingSession.kt:161-167`），不更新 `dispatchBaseState`。

后果：第一次 workspace 提交之后，任何**不经过 `scoreSession.dispatch` 的**帧
（`selectSlot`、`selectIdiom`、`setCatalogFilter`、catalog/finding 结果回填）在
`update()` 里取 `scoreSession.initialUpdate()`（`FreePracticeSession.kt:1229`）时都会得到
`scoreChanged = true`，直到下一次谱面 intent 才复位。

Web Worker 正是以此为唯一判据跳过排版（`engine-worker.js:112`，且注释明确要求"永远信任
session 的 scoreChanged"）。于是**写作跑过一次后，每一次点击和弦槽都会触发一次整谱重排 +
一次完整 bundle 回传 + 一次 canvas 重绘**；finding 结果回填再重复一次。这正是
`docs/performance/large-score-editing.md` 与 CLAUDE.md 要禁止的"无谓全量排版"。

修复方向：让 `notifyExternalCommit()` 同步刷新 `dispatchBaseState`（或让 `frame()` 以
"上一次发布的 state"为基准），并补回归：*一次写作提交后连续两次 `selectSlot`，第二帧
`score.scoreChanged == false`*。注意同时处理 #15。

### 2. 每个后台请求新建 Worker（🔴）

`runFindings` / `runTeachingCatalog` / `runBackground`（`engine-worker.js:130-169`）用
"terminate 旧 Worker + `new Worker(search-worker.js)`"作为取消手段，而
`search-worker.js:3` 在模块顶层 `createMeconFreePracticeExecutor()`——即**每个请求都要重新
加载并初始化整个 Kotlin/JS 引擎 bundle**。

而 `ensureFindingRequest()` 的 fingerprint 就是 `revision.toString()`
（`FreePracticeSession.kt:1291-1293`），选择类 intent 也会 `revision++`
（`:679/:692/:708`）。因此**每点一次和弦槽 = 一次引擎冷启动**。

修复方向：finding / catalog 用常驻 Worker，靠 session 已有的 `requestId / baseRevision /
fingerprint` 丢弃过期结果（session 侧校验已经完备，见 `applyFindingResult`）；terminate
只保留给真正不可协作取消的长 CPU 求解（`runBackground`）。可选：让 `ensureFindingRequest`
的 fingerprint 反映真实输入（document + score）而非裸 revision，纯选择变化就不再触发重算。

### 7. 每次 dispatch 计算两遍 `frame()`（🟠）

`result()` 先构造 `frame = frame()`（`FreePracticeSession.kt:1204`），Web 侧随后
`toWireUpdate()` → `update()` 又调一次 `frame()`（`:1224`）。`frame()` 内部包含
`catalog()`（`ChordSelectionCatalog.choices`）、timeline / plan 投影与 `document()` 组装
（`:120-158`）。此外 `update()` 在 `scoreUpdate == null` 时调用
`scoreSession.initialUpdate()`，其中 `frame.runtimeScore.toStorage()` 把**整份乐谱转存储层
并序列化**，即使这一帧只是 finding 回填。

修复方向：让 `toWireUpdate` 复用 `result.frame`（`FreePracticeDispatchResult` 已持有它），
并在 `scoreChanged == false` 时避免重复 `toStorage`。

### 9. 时间轴拖动的预览风暴（🟠）

`HarmonyTimeline.updateDrag`（`HarmonyTimeline.jsx:204-258`）在每个 pointermove 上
`++requestCounterRef`、`setActiveRequestId`、`setOptimisticView` 并 `onPreview(...)` 发一条
Worker 消息——即使 snap 后的 `delta` 与上一帧完全相同。Worker 侧 `drainTimelinePreviews`
只做"保留最新"合并，不能省掉主线程的三次 setState 与 postMessage。

修复方向：`delta`/`edit` 未变化时直接返回；必要时按 rAF 合并。

---

## 二、正确性

### 3. Worker 报错后 intent 队列永久阻塞（🔴）

`App.jsx` 的 `practiceIntentInFlightRef` 只在收到**匹配 `clientRequestId` 的
`freePracticeFrame`** 时清空（`App.jsx:87-90`）；`data.type === "error"` 分支只写 status 并
`return`（`:61-66`）。而 Worker 对 `handle()` 抛出的任何异常都只回一条 `error`
（`engine-worker.js:24-26`），不回帧。

后果：一次 codec/session 异常后，`pumpPracticeIntent` 因 `practiceIntentInFlightRef != null`
永远不再发送任何 intent（`:253-257`）——时间轴、计划面板、撤销全部静默失效，只有刷新页面
能恢复；状态栏仅显示一行错误文本。

修复方向：Worker 的 `error` 消息带上 `clientRequestId`，App 收到后清空在飞标记并继续 pump；
或在 App 侧加超时兜底。建议补一条 Playwright/单测：注入一次失败 dispatch 后仍能继续编辑。

### 10. 自动恢复策略在壳层硬编码（🟠）

`recovery.js:5-14` 用 `PERSISTENT_FREE_PRACTICE_EFFECTS` 白名单（`APPLIED`/`WRITING_APPLIED`/
`UNDONE`/`REDONE`/`PRACTICE_REBUILT`）判断是否落盘。但 `applyBackgroundResult` 在
"无解 / 未求出"分支里**先 `transaction.commit(...)` 再返回 `INVALID`**
（`FreePracticeSession.kt:513-529`）——文档确实变了，却不会被自动恢复写入。

这也是一处壳层复制内核语义：新增 effect kind 时必须同步改 JS 名单，且名单无法表达
"提交了但结果无效"。修复方向：由 session 权威地给出 `documentChanged`（或复用修好后的
`scoreChanged`），JS 只读该布尔值。

附带两点：
- 页面关闭时未 flush。`recoveryWriter.cancel()` 只在 React 卸载时执行（`App.jsx:107-109`），
  真实关标签页时 300ms 去抖窗口内的最后一次编辑直接丢失；建议在 `pagehide` /
  `visibilitychange` 上强制 flush。
- `saveRecovery` / `loadRecovery` 每次调用都 `indexedDB.open` 且从不 `close()`
  （`recovery.js:73-86`），长会话会累积连接并阻塞将来的版本升级。

### 15. 时间轴 revision 恒为 0，重排判据未含 timeline（🟡）

`MeconWebEngine.toAlignedTimeAxis` 硬编码 `revision = 0`，桌面则用
`documentVersion * 31 + beatWidth` 作为轴代次（`FreePracticeEditorPanel.kt:322`）。因此
`WebResolvedTimeAxis.revision` 是一个恒零的死字段，Web 无法判断收到的轴是否与当前 practice
帧同代。

更值得注意的是：Worker 的排版输入是 `(score, timeline)`
（`layoutFreePracticeFrame`），跳过重排的判据却只有 `scoreChanged`
（`engine-worker.js:112`）。目前被 #1 的过度上报掩盖；一旦按 #1 修好 `scoreChanged`，
**纯时间轴编辑将复用旧轴**，槽位与谱面对不齐。修复 #1 时必须同时把 timeline 代次纳入判据
（或让 session 报出 `timelineChanged`）。

---

## 三、门禁与测试

### 4. 浏览器导出→桌面回读门禁是空跑（🔴）

`FreePracticeBrowserExportTest.kt:18` 以
`System.getProperty("freepractice.browser.export.path") ?: return@runBlocking` 开头——属性缺失
时**测试直接通过**。全仓库检索该属性名只有这一处：没有 Gradle 任务、没有 CI、
`docs/web-development.md` 也只在清单里写"`.mecon` 变化通过浏览器导出→桌面回读"，未给出命令。

而 E2E 已经把导出文件写到了 `web/build/e2e/browser-free-practice-export.mecon`
（`f1-free-writing.spec.js:211-216`）。缺的只是把它喂给桌面测试的那一步。

修复方向：加一个 Gradle 任务（或在文档给出
`-Dfreepractice.browser.export.path=...` 的确切命令），并在属性缺失时 `skip` 而非静默 pass，
使"空跑"在报告中可见。

### 5. 新时间轴 intent 未进跨端 trace（🟠）

本次新增的 `translateChordRange`、`moveSharedBoundary`、`moveBoundaryWithFollowing`、
`setTonalLayoutBounds`（`FreePracticeProtocol.kt:173-205`）正是 Web 拖动提交的四个 intent，
但 `features/free-practice/testdata/practice-trace.json` 的 42 步里一个都没有（只有
`placeChordRange` / `insertChordRange` / `removeChordRange`）。`FreePracticeSessionTest`
里 `TranslateChordRange` 也只出现在 stale/missing 的**拒绝**用例中（`:304-333`），成功路径、
共享边界再分配、`includeFollowing` 均无 session 级断言。

这直接违反 CLAUDE.md"新能力必须追加 practice-trace.json 并让 JVM/Kotlin-JS 重放同一流程"。
修复方向：按 4 类编辑各追加 trace 步骤（含一次 preview→commit 的一致性断言），先在 JVM 校验
再让 `web/packages/web-renderer/test/free-practice-trace.test.js` 重放。

### 13. 性能测试阈值形同虚设（🟡）

`FreePracticePerformanceTest` 的 `MAX_P95 = 5s`、`SAMPLE_COUNT = 12`、无预热。32/64 槽的
preview/commit 实际耗时远低于该阈值，任何量级的回归都不会被拦下；JIT 噪声也足以主导 p95。
建议要么按实测量级收紧阈值（并预热），要么明确降级为 smoke 测试、不再在文档中当作性能门禁。

### 14. Service Worker 只有文本断言（🟡）

`architecture.test.js:57-64` 用 `indexOf` 的相对位置断言 sw.js 是 network-first；同时
`playwright.config.js:15` 设 `serviceWorkers: "block"`，E2E 从不执行 SW。离线壳的真实行为
（导航更新、缓存 key）没有任何运行时覆盖。建议至少加一条不 block SW 的 production preview 用例。

---

## 四、架构边界

### 6. `PracticeTimelineEdit` 与 `FreePracticeIntent` 双份定义（🟠）

`PracticeTimelineEdit`（`FreePracticeProtocol.kt:436-475`）的 5 个成员与
`FreePracticeIntent` 对应成员（`:156-212`）字段、`@SerialName` 完全一致，只差
`expectedRevision`。到 `HarmonyWorkspaceCommand` 的映射也写了两遍：预览走
`timelineCommand`（`FreePracticeSession.kt:723-741`），提交走 `dispatch` 的 when 分支
（`:328-353`）。

任何一侧改动漂移，都会表现为"预览一个样、提交另一个样"——恰恰是共享 session 要消灭的
故障类型，而 #5 又使其中 4 个没有回归保护。修复方向：以 `PracticeTimelineEdit` 为唯一定义，
新增 `FreePracticeIntent.TimelineEdit(expectedRevision, edit)` 包裹，预览与提交共用一份映射。

### 8. React 复制了时值再分配语义（🟠）

`optimisticTimeline`（`HarmonyTimeline.jsx:47-84`）在 JS 里实现了 1/16 最小时值钳制、
`includeFollowing` 的后继整体平移、共享边界两侧的时长再分配、调性线 start/end 互相约束。
虽然它只作用于异步预览到达前的瞬时显示（CLAUDE.md 允许"瞬时 preview"），但这已经是**第二份
乐理编辑语义**，与 `HarmonyWorkspaceEditor` 并行演化。

同一段代码还有两个具体问题：
- `slots.findIndex(sharedNextId)` 返回 -1 时，`view.slots[-1]` 为 `undefined`，
  紧接着的 `fractionValue(originalNext.onset)` 会抛 TypeError（`:72-81`）。
- 线性回退模式下 `axisExtent` 取自 `view.end`，而槽位来自 `displayed`
  （`:129-149`）；拖到超出原长度时百分比会溢出 100%。

修复方向：把乐观视图退化为纯 x 位移（不改 onset/duration 数值），或直接以上一帧
`previewResult.timeline` 为准并接受一帧延迟；至少补上 `nextIndex < 0` 的防御与
`displayed.end` 的一致取值。

### 11. Worker 生命周期细节（🟡）

- `handle("open")` 无条件 `createMeconScoreEditor({score})`，随后若带 module payload 立刻
  `close()` 并改建 free-practice session（`engine-worker.js:59-67`）——白白构造一次完整
  `ScoreEditingSession`（含全谱 compute）。按 `message.document` 分支即可。
- `runBackground` / `runTeachingCatalog` / `runFindings` 的 onmessage 直接调用 `handle(...)`，
  既绕过 `self.onmessage` 的串行 `queue`，返回的 Promise 也没有 `.catch`——异常会变成 Worker
  内未处理的 rejection，而不是回给 UI 的 `error` 消息。

---

## 五、可移植性（12，🟠）

开源后仓库要求 GPLv3 协作，但 Web 侧强制门禁目前只能在 Windows + Edge 上跑：

- `web/package.json` 的 `prepare:engine` 硬编码 `..\\gradlew.bat`；`test:engine` 依赖它，
  因此 macOS/Linux 贡献者无法运行 CLAUDE.md 要求的 Kotlin/JS 跨端 trace 门禁。
- `playwright.config.js:14` 固定 `channel: "msedge"`，`docs/web-development.md` 也直接让用户
  `npx playwright install msedge`。
- 文档命令全部是 PowerShell 形式。

另注：`0a7fb70d` 给 `prepare:engine` 加了 `-x kotlinStoreYarnLock -x kotlinNpmInstall`，
在规避 `EISDIR symlink` 的同时也永久跳过了依赖安装/锁文件校验，依赖漂移不再有人发现——
建议在文档中写明该跳过的代价与何时需要完整跑一次。

修复方向：`prepare:engine` 改为跨平台脚本（`node` 包装或 `gradlew`/`gradlew.bat` 择一），
Playwright channel 可配置并默认 chromium。

---

## 修复状态（2026-08-06）

全部 15 项已修复，下列为落点；细节以代码与文档为准。

| # | 落点 |
|---|------|
| 1 | `ScoreEditingSession.beginExternalOperation()`：复合 session 每次操作入口重置基准；`scoreChanged` 改按 `runtimeScore` 引用判定 |
| 2 | finding / catalog 改常驻 Worker（`residentSearchWorker`）；finding 与 catalog 结果按 fingerprint 而非 revision 校验，纯选择不再重算 |
| 3 | Worker `error` 携带 `clientRequestId`；队列提取为 `practice-intents.js` 并有单测覆盖失败后继续编辑 |
| 4 | 新增 `:apps:desktop:freePracticeBrowserExportTest`；属性缺失时 `require` 失败而非空跑；已从 `:apps:desktop:test` 排除 |
| 5 | trace 新增 `timelineEdit` 步骤（4 类编辑 + preview/commit 一致性断言 + undo/redo）与 slots/layouts 逐值断言 |
| 6 | 删除 5 个重复 intent，改用 `FreePracticeIntent.TimelineEdit(expectedRevision, edit)`；预览与提交共用 `timelineCommand` |
| 7 | `toWireUpdate` 复用 `result.frame`；`ScoreEditingSession.wireUpdate` 按 runtime 引用记忆化 `toStorage` |
| 8 | `optimisticTimeline` 退化为纯位移，补 `nextIndex < 0` 防御，`axisExtent` 取自 `displayed` |
| 9 | `requestPreview` 在 edit 未变化时直接返回 |
| 10 | 新增权威 `FreePracticeUpdate.documentChanged`；`recovery.js` 只读该布尔值；`pagehide`/`visibilitychange` flush；IndexedDB 单连接复用 |
| 11 | `open` 按 `message.document` 分支；后台 Worker 回调统一走串行 `enqueue` 并带 catch |
| 12 | `prepare:engine` 改 `web/scripts/prepare-engine.mjs`（按平台选 wrapper，另有 `prepare:engine:full`）；Playwright 默认 chromium，`MECON_E2E_CHANNEL` 可选 |
| 13 | 性能测试加预热（20）与 40 采样，阈值 5s → 250ms（实测 p95 2–7ms） |
| 14 | 新增 `e2e/offline-shell.spec.js`，`serviceWorkers: "allow"` 下真实校验安装、缓存与离线导航 |
| 15 | 轴代次由时间轴边界派生；Worker 以 `(scoreChanged, timeline)` 共同决定是否重排 |

## 建议处理顺序

1. #3（工作台冻结）与 #4（空跑门禁）——一个是用户可见故障，一个让"已验收"结论不成立。
2. #1 + #15 一起改（`scoreChanged` 语义 + timeline 代次），随后 #2、#7、#9 才有意义。
3. #5 + #6 一起改：先合并 `PracticeTimelineEdit` 与 intent，再按合并后的单一形状补 trace。
4. #8、#10、#11、#12 属于收尾；#13、#14 在下一轮门禁整理时处理。

## 复核命令

```powershell
.\gradlew.bat :features:free-practice:jvmTest :features:score-editing:jvmTest
cd web
npm run test:engine
npm run test:e2e
```

修 #1/#15 时额外确认：大谱普通编辑仍为 `spliced=true`，且一次写作后连续两次纯选择帧的
`score.scoreChanged` 为 `false`、时间轴与谱面 x 投影仍对齐（`data-axis-source="renderer"`
用例）。
