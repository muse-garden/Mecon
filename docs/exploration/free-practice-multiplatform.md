# 自由练习工作台 Web 首发与移动复用方案

> 基线：2026-08-05；更新：2026-08-07；状态：✅ W0–W6、F2 与桌面业务入口收敛完成。
> 公共编辑入口、共享 timeline raw scene/controller、调性/和弦/教学惯用进行右栏、独立目录 Worker、
> 窄屏 tabs、统一 toolbar descriptor 与 renderer origin/viewport contract 已接入；真实浏览器手势/截图、
> Desktop 离屏金标准及浏览器导出→桌面回读门禁已通过。除明确后置的钢琴卷轴外桌面持久化旁路已收敛；
> 钢琴卷轴本轮后置。
> 本轮按产品范围不接入勋伯格禁忌表；教学练习与禁忌相邻进行仍留在 JVM 侧。
>
> 本文细化 [多端移植设计](../multiplatform-porting.md) 中的 Web 探索路径，只讨论
> “探索—自由练习工作台”。领域与交互现状见
> [勋伯格自由练习](../theory/schoenberg/free-practice.md)；写作流水线语义以
> [自动声部写作改造](free-practice-auto-writing.md) 为准；五线谱编辑的共享协议与门禁不在本文
> 重复，见 [乐谱编辑多端接入规范](../score-editing-multiplatform.md)。完整界面后续实施以
> [自由练习完整工作台 Web 化计划](free-practice-web-workbench-completion-plan.md) 为准。

## 1. 决策

首发采用：**React/TypeScript Web 壳 + Kotlin/JS Worker 中的共享工作台 session + 现有
`FrozenScoreBundle` Canvas 后端**。移动版随后让 Android/iOS 在进程内调用同一个 Kotlin session，
UI 用 Compose Multiplatform 自适应。复用的是状态机、编辑事务、规则、检查、自动声部写作、谱面
装配与播放模型；各端只保留布局、指针/触控、Canvas/Compose 绘制、音频和文件桥接。

不采用：把 `FreePractice*.kt` 编译为 Compose Web（Beta + AWT/桌面专用布局假设）；在 React 中重写
工作台逻辑（立刻出现第二份路线/pattern/finding/写作实现）；所有操作请求 JVM 服务（延迟、成本、
离线不可用，只作重求解降级）；Kotlin/JS 直接操作整页 DOM（不能复用移动 UI）。

钢琴卷轴本轮不移植，不作为 W2–W6 的退出条件；时间轴与右栏已稳定，卷轴仍保留桌面实现并须另立阶段。

## 2. 历史基线与剩余债务（2026-08-05 核对）

✅ 已完成：`features/score-editing` 稳定 intent/frame/revision 协议、桌面 adapter、Worker/Canvas、
完整五线谱命令、Web↔Desktop `.mecon` 动态回读，均已过门禁（能力矩阵见
[Web 五线谱编辑能力矩阵](free-practice-web-editor-capabilities.md)）。领域侧
`HarmonyWorkspace`、`FreePracticeWindowVoicer`、`PracticeWritingScopePlanner`、
`FreePracticeMaterialProjector`、`FreePracticeSearchPolicy`、`AutomaticVoiceAssigner`、
`VoiceNotationPlan`、`VoicePlanScoreAssembler`、`FreePracticeDocument`（schema v8 + v1–v7 迁移）
已在 `commonMain`，且不依赖 JVM API。

下表保留 F0/F1 开始前的债务快照，不再作为最新状态表。该债务已由 W2–W6/F2 收敛；当前扩展规则以
[功能扩展指南](free-practice-extension-guide.md) 和[能力矩阵](free-practice-web-editor-capabilities.md) 为准。

| # | 债务 | 位置 |
|---|---|---|
| 1 | 工作台业务状态由 ~20 个 Compose `remember` 持有（路线、选中槽、调性布局、教学目录、设置、错误） | `FreePracticeWorkbench.kt:143-208` |
| 2 | 槽选择用列表下标且 `coerceAtLeast(0)`：槽消失时静默改指第 0 槽，而不是报 stale | `FreePracticeWorkbench.kt:168-169` |
| 3 | `HarmonyPracticeScoreHost` 用 Compose `mutableStateOf` 承载写作状态，并直接调 `NoteEditEngine`/`computeScore`，绕过 `ScoreEditingSession` | `service/EditableScoreHost.kt:182-1093` |
| 4 | 写作结果只有中文 `message`，调用方用 `"完成" in it` / `"已换用" in it` 反推成败 | `FreePracticeWorkbench.kt:405,757,763` |
| 5 | `FreePracticeVoicingMaterializer` 是写作流水线唯一还在桌面的一环 | `service/FreePracticeVoicingMaterializer.kt` |
| 6 | `HarmonyWorkspaceCommand` 多数命令以 `index: Int` 定位槽，不能直接上 wire | `HarmonyWorkspace.kt:472-640` |
| 7 | finding 聚合与文案在桌面，且自带一套中文标题 | `FreePracticeAnalysis.kt:17` |
| 8 | 初始工作区 / 声部 preset / `S,A,T,B` 标签是 desktop 私有逻辑 | `FreePracticeModels.kt:89-155` |
| 9 | 教学目录请求身份、缓存与过期丢弃写在 composable 里 | `FreePracticeWorkbench.kt:206-262` |
| 10 | 回放区间在 UI 线程算 `ScoreTimeMap.from` + 整谱 `ScoreToMidiConverter.convert` | `FreePracticeWorkbench.kt:376-384`、`PlaybackController.kt:139` |
| 11 | `theory`/`exploration`/`plugins:chord-analysis:core`/`audio` 只有 jvm target | 各 `build.gradle.kts` |
| 12 | 两处目录发现依赖 classpath 反射，只有 JVM actual | `ChordSelectionCatalog.kt:105`、`ChordKnowledge.kt:400` |
| 13 | 禁忌表 4 个 `.txt` 合计 ~1.4 MB 放在 `jvmMain/resources`，JS 无 classpath；缺失时**静默退化为空表**、枚举不剪枝 | `SchoenbergForbiddenTransitions.kt:69-78` |
| 14 | `PolyphonicConstraintSolution` 非 `@Serializable`，含 `ScoreBreakdown` 等内部类型，不能跨 worker | `constraint/ConstraintProgramSolver.kt:37` |

第 3 条与 [乐谱编辑多端接入规范](../score-editing-multiplatform.md) §1.1 是同一笔债：重复的不是乐理，
而是 session 层语义（selection、effect 分类、revision 单调、一次操作一个历史项）。

## 3. 目标模块与依赖

```text
apps/desktop ───────────────┐
apps/mobile ────────────────┼─→ features/free-practice → features/score-editing
bridge/web-engine ──────────┘             ├─→ exploration → theory → api
                                          └─→ core → api
bridge/web-engine → renderer → core
web/apps/free-practice → Worker facade + @mecon/frozen-score
```

`features/free-practice/commonMain` 负责：版本化 `FreePracticeDocument` 读写；`FreePracticeSession`、
不可变 `FreePracticeFrame`、intent/effect；工作台 preset、调性布局、惯用进行编排、检查聚合与
窗口化自动写作编排；组合 score-editing session 使 `RuntimeScore + HarmonyWorkspaceState` 形成
原子事务；`messageKey + typed arguments`，不产出成品文案。

它不依赖 Compose、React、浏览器、AWT、文件系统、Web Audio 或平台字体；`renderer` 不进入 session
本体，各端在 frame 发布后自行生成几何。

**JS target 状态**：`features/free-practice` 的 `js(IR)` target 已启用并通过 JVM/JS 同 trace 门禁。
commonMain 继续遵守 JS 约束（无 `java.*`、无反射、无同步 classpath 资源）；平台资源只能经明确的
expect/actual 或 facade 注入，不能把业务逻辑搬回 JS adapter。

## 4. 共享文档与状态契约

自由练习在 `.mecon` 中使用 `type = "exploration.free-practice"` 模块载荷，桌面当前 schema v8，
v1–v7 兼容读取（v6 增 `writing`，v7 typed chord choice + 可见迁移诊断，v8 无来源逐和弦临时调性）。
Web 复用同一 `FreePracticeDocumentCodec`，不另造浏览器专用格式。

- `MeconModuleEntry.scoreId` 是乐谱引用，`schemaVersion` 是载荷版本；payload 不重复保存二者；
- 音符、休止、连线只在模块引用的 `StorageScore`；和声时间轴、稳定 slot id、可重叠调性布局、
  逐槽调性读法、枢纽标记与惯用进行实例在 `workspace`；不保存第二份音符或配声来源；
- finding、写作候选/最后范围、教学目录索引、渲染结果与撤销栈是可重建缓存，不是文档真相；
- hover、面板折叠、split ratio、Canvas transform 是平台瞬态；active slot/voice 进入 frame 但不落盘；
- Web 自动恢复用 IndexedDB 存同一 document JSON + score JSON，导入导出仍是 `.mecon`；仅持久化
  effect/score change 触发归档，写入按 300 ms generation 防抖并串行化，selection、目录与 finding
  帧不会反复重打 ZIP。

数据模型细节见 [自由练习数据模型](../data_model/free-practice.md)；改动先更新该文档再改 `@Serializable`。

## 5. F0：自由练习共享 session

目标：**桌面自由练习的业务语义不再由 UI 文件定义**。F0 不写任何 Web 代码，但产出的协议就是 F1 的
wire 协议。

### 5.1 迁移清单

| 现状 | F0 去向 | 判据 |
|---|---|---|
| `FreePracticeWorkbench` 的 `remember` 业务状态 | `FreePracticeSession` 私有状态 + `FreePracticeFrame` | Workbench 只剩布局与瞬态 UI |
| `HarmonyPracticeScoreHost` 事务/写作/重写/换结果 | `FreePracticeSession`（Compose-free） | feature 模块不 import Compose |
| `FreePracticeVoicingMaterializer` | `features/free-practice/commonMain` | 写作流水线全部在 common |
| `initialWorkspace` / `initialTonalRoute` / `resolveExactChordChoices` | feature preset 与 theory | 桌面无自由练习领域函数 |
| `workspaceFindings` + `PracticeFinding` 中文标题 | `PracticeFindingComputer` + `messageKey` | feature 无中文字面量 |
| 教学目录 `produceState` + `teachingIndexCache` | session 的目录编排（请求身份、缓存、过期丢弃） | 目录请求可在后台执行并按身份丢弃 |
| `FreePracticeToolbarController` | `frame.toolbar` 视图状态 | 桌面只做 state→Compose 映射 |
| `PracticeWritingState.message` 字符串判定 | typed `PracticeWritingOutcome` | 代码中不存在 `contains("完成")` |

### 5.2 会话组合与撤销边界

```text
ScoreStateManager
 ├─ ScoreEditingSession.open(manager)          // 音符 / 记谱 intent（已存在此重载）
 └─ HarmonyPracticeTransaction(manager, ctrl)  // workspace 作为 companion state
FreePracticeSession 持有二者，并且是唯一 revision 权威
```

- 撤销边界由 `ScoreStateManager` 的 companion state 机制保证：任一路径提交都同时快照 workspace，
  一次用户操作只产生一个历史项；
- 自由练习自行提交（工作区命令、写作物化）后必须**显式**通知内层 score-editing session，不得依赖
  `ScoreEditingSession.kt:1098` 的引用嗅探 `synchronizeExternalState()`；F0 为其加显式提交入口，
  桌面收敛完成后删除嗅探（与 score-editing 规范 §1.1 同批清理）；
- 练习专属校验（`PolyphonyLimitValidator`、记谱通道分配）在**委托之前**完成，产出
  `voiceTrackId/staffTrackId/voiceNumber` 后交给 `ScoreEditIntent.InsertNote`；不在自由练习重写
  `NoteEditEngine`，也不新增第二条提交通道。

### 5.3 intent：稳定 id 与手势相位

```kotlin
@Serializable
sealed interface FreePracticeIntent {
    val expectedRevision: Long
    // Score(inner: ScoreEditIntent) / SelectSlot / ReplaceChord / SetChordBass / SetChordTonality
    // / InsertChordRange / TimelineGesture / TonalLayout* / Idiom* / Writing* / Settings* / Undo / Redo
}
```

- 一律使用 `WorkspaceSlotId` / `WorkspaceTonalLayoutId` / `WorkspaceIdiomInstanceId` / `EventId`；
  session 在应用前投影为 `HarmonyWorkspaceCommand` 所需 index。**投影失败返回 `STALE_TARGET`
  effect，禁止 clamp 到第 0 槽**（消灭债务 #2）；
- 记谱编辑用 `FreePracticeIntent.Score(inner)` 包一层复用现有 56 个 `ScoreEditIntent`，不复制协议；
- 时间轴与调性布局拖动携带 `gestureId + phase(PREVIEW/COMMIT/CANCEL)`；PREVIEW 不入历史，
  一次 COMMIT 恰好一个撤销项，CANCEL 恢复手势前状态；
- 写作类：`RunWriting`、`RewriteSelection(slotIds)`、`AlternateWriting`、`CancelWriting`。
  **自动写作是否触发由 session 按提交后的 target fingerprint 判定**（见自动写作文档 §5），
  平台不自己决定时机；
- 设置类（复音上限、上下谱表通道、初始调性、写作/回放偏好）也是 intent；其中重建材料的操作
  必须返回可提示的 `REBUILD_REQUIRED` effect，由平台决定确认交互。

### 5.4 frame 与派生视图

```kotlin
data class FreePracticeFrame(
    val revision: Long,
    val document: FreePracticeDocument,      // settings + workspace，落盘真相
    val score: ScoreEditingFrame,            // 复用，不再复制 runtime/computed 引用
    val selection: FreePracticeSelection,    // slot / tonalLayout / idiomInstance / 谱面选区
    val findings: PracticeFindingsView,      // 带 generation，未完成时保留上一批
    val writing: PracticeWritingStatus,
    val catalog: TeachingCatalogView,        // 带请求身份，过期结果不得展示
    val toolbar: PracticeToolbarView,
)
```

- 大不可变对象在 Kotlin 内部按引用发布，桌面用 `rememberIdentityKey` / `rememberReferentialUpdatedState`，
  禁止在 UI 线程做结构相等比较（见 CLAUDE.md 大乐谱热路径约束）；
- findings、catalog、写作候选都是可重建缓存：新结果就绪前保留旧结果并标 `stale`，不闪空白；
- wire update 只在对应 revision 变化时携带 `scoreJson` / `geometryJson`，首版允许小练习全量发送。

### 5.5 类型化结果与文案

```kotlin
sealed interface PracticeWritingOutcome {
    data class Solved(val scope: List<WorkspaceSlotId>, val replayRange: PracticeReplayRange?) : ...
    data object NoSolution; data object BudgetExhausted; data object Cancelled
    data class Invalid(val diagnostics: List<PracticeDiagnostic>) : ...
}
```

- 所有用户可见文本用 `messageKey + arguments`；桌面与 Web 各自本地化。**任何基于中文子串的成败
  判定都是缺陷**（债务 #4）；
- 自动回放由 `Solved.replayRange`（稳定 slotId 区间 + 练习 BPM）驱动，平台不再回算 `ScoreTimeMap`；
- 迁移诊断（`FreePracticeMigrationDiagnostic`）同样以 typed 形式进入 frame，不在打开文件时拼中文串。

### 5.6 异步编排：session 不自己起协程

session 是纯串行状态机，与 `ScoreEditingSession` 一致，不 `launch`、不持有 `CoroutineScope`：

```kotlin
data class FreePracticeDispatchResult(
    val frame: FreePracticeFrame,
    val effect: FreePracticeEffect,
    val requests: List<PracticeBackgroundRequest>,   // 求解 / 优化候选 / 教学目录
)
fun applyBackgroundResult(result: PracticeBackgroundResult): FreePracticeDispatchResult
```

- `PracticeBackgroundRequest` 不可变且可序列化：`baseRevision`、`scopeFingerprint`、`kind`
  （`FIRST_SOLVE` / `OPTIMIZE_CANDIDATES` / `TEACHING_CATALOG`）、workspace、score、fallbackKey、
  `SearchConfig`；
- `applyBackgroundResult` 先校验 `baseRevision` + fingerprint，过期直接丢弃；`Solved` 才走一次
  `HarmonyPracticeTransaction.commit`；
- **F0 新增可序列化 `PracticeVoicingCandidate`**（逐槽 `pitchesByVoiceId` + `diversityGroupKey` +
  分数 + 诊断摘要），materializer 改为消费它而不是 `PolyphonicConstraintSolution`（债务 #14）。
  这同时让“换一个结果”的候选集能跨进程保存，并成为 F1 worker 的唯一结果形态；
- 桌面 adapter 在 `Dispatchers.Default` 执行 request，用 `SearchCancellation` 协作取消；
  Web 用可终止 worker。两端取消语义相同：**过期结果按 revision 丢弃，而不是靠停止时机保证正确**。

### 5.7 桌面 adapter

`FreePracticeWorkbench` 变成 composition root：创建 session、把 frame 映射为 Compose 状态、把
request 交给 `Dispatchers.Default`、把结果回灌。`FreePracticeEditorPanel` 只保留像素布局、hover/cursor
与 pointer 采样，量化与边界约束由 session 决定。`FreePracticeToolbarController` 由 frame 直接构造。

### 5.8 F0 之后新增自由练习能力的接入路径

自由练习细节仍在演进，**新增能力从第一天就按下列顺序接入**，不得因为“桌面先做一版”而新增旁路：

1. 改存储字段先更新 `docs/data_model/free-practice.md`，再动 schema 并补迁移与 fixture；
2. 领域算法进 `theory`/`exploration` 的 `commonMain`；
3. 协议进 `FreePracticeIntent` / `FreePracticeFrame`（稳定 id、typed 结果、messageKey）；
4. session dispatch 接入并定义 no-op / stale / 失败原子性 / 历史边界；
5. 桌面 adapter 只加控件与 pointer 映射；
6. 往 `features/free-practice/testdata/practice-trace.json` 追加步骤；
7. 更新能力矩阵与相关文档。

F0 落地前新增的小功能，仍应把逻辑写在 `theory`/`exploration` 的 common 侧、UI 只做调用，
以把迁移成本控制在“搬运 + 换 id”。

### 5.9 F0 退出条件

- 桌面自由练习没有任何 `remember` 持有业务状态；桌面代码不再直接调用 `HarmonyWorkspaceEditor`、
  `FreePracticeWindowVoicer`、`FreePracticeVoicingMaterializer` 或 `computeScore`；
- 所有提交经 `FreePracticeSession`，`synchronizeExternalState()` 的引用嗅探被显式通知取代；
- 架构测试：`features/free-practice` 不依赖 Compose/AWT/DOM，且模块内无中文字面量；
- `practice-trace.json` golden trace 在 JVM 通过（F1 接同一份到 JS）；
- common tests 覆盖：3–6 声部、空槽三值检查、typed chord ref、时间轴 preview/commit/cancel、
  自动范围左扩、无解/预算耗尽/取消、stale target、单历史项、undo/redo 不重新触发写作。

## 6. F1：Kotlin/JS 与 Web 特色闭环

### 6.1 依赖模块的 JS 前置改造

给 `theory`、`exploration`、`plugins:chord-analysis:core`、`audio` 增加 `js(IR)` target，并解决三处
`expect`：

- **目录发现**（债务 #12）：`discoverChordCatalogChapters` / `discoverChordKnowledgeChapters` 的
  classpath 反射实现，替换为**在唯一 provider 声明处生成的编译期 registry**，JVM 与 JS 共用同一
  actual；插件贡献由环境注入。禁止 JS 侧另写 provider 白名单；
- **禁忌表**（债务 #13）：`.txt` 继续作为人类可读源数据，构建期生成平台无关索引资源；common 侧改为
  **显式装载 + `ready` 状态**，JS 在 worker 启动时 fetch。当前“资源缺失即空表、枚举不剪枝”的静默
  退化在 JS 上会让两端 enumerate 结果不同，**必须改为未装载时拒绝求解并报错**，不得静默放宽；
- **audio**：JNA/FluidSynth 已在 `jvmMain`，`commonMain` 的 `ScoreToMidiConverter` 与 MIDI 模型可直接
  编 JS；Web 只实现 Web Audio backend。

### 6.2 包体与加载预算

早期分模块估算曾预期 theory + exploration 会额外增加约 3 MB；当前 production Vite 构建把
Kotlin/JS bridge 产物输出为约 3.1 MB 的独立 asset。后续以构建产物实测为准，不继续引用早期源码
行数比例估算。因此：

- 首屏只加载 React 壳、字体与冻结 viewer；**进入工作台才动态 import theory chunk**；
- 禁忌表与目录索引走 `fetch` + Cache Storage，**不编进 bundle**；
- 持续记录首屏与工作台 chunk 的 gzip 体积、engine 首次加载时延，并纳入预算门禁。

### 6.3 Worker 拓扑与 bridge facade

```text
主线程 React ──intent JSON──► 工作台 Worker（FreePracticeSession + render + frozen geometry）
                                   │ PracticeBackgroundRequest JSON
                                   ▼
                              搜索 Worker（可 terminate；始终预热 1 个备用）
```

```text
openFreePracticeJson(documentJson, scoreJson) -> updateJson
dispatchFreePracticeJson(intentJson)          -> updateJson   // 含 requests
applyBackgroundResultJson(resultJson)         -> updateJson
renderCurrentJson(viewportJson)               -> frozenGeometryJson
buildPlaybackExcerptJson(rangeJson)           -> midiJson
close()
```

- Kotlin/JS 求解占满 worker 时无法响应 cancel 消息，取消一律用 `terminate()`；theory chunk 初始化
  不便宜，终止后立即预热替补，保持“1 忙 + 1 温”；
- 结果携带 `baseRevision + scopeFingerprint`，过期直接丢弃；
- 教学目录 `SchoenbergFreePracticeCatalog.buildIndex` 同样是重 CPU，走同一 request 通道
  （`TEACHING_CATALOG`），不占工作台 worker；
- 不向 TypeScript 暴露 Kotlin collection、Flow、`RuntimeScore` 或内部类图。

### 6.4 React UI

- 宽屏与桌面对齐为“主写作区 + 可调宽右栏”：主区含时间轴和五线谱；窄屏改为
  tabs 或步骤视图，不能把临时三栏简单压成纵向长页；
- `web/apps/free-practice/src/App.tsx` 是 TypeScript 平台装配；顶部工具栏、音符属性状态与面板、
  音频设置、播放控制器和后台 Worker 生命周期已拆到独立 typed 模块；`ScoreEditor`、状态 hooks、
  画布、toolbar controls、交互 helpers、命令/click-selection/drag controller 及完整 inspector 已进入
  公共包，完整 fixture 与自由练习共用同一 host；
- 桌面时间轴提交已从 whole-workspace reducer 改为 stable-ID `PracticeTimelineEdit` → host → session
  intent；拖动 preview 仍是本地瞬时状态。普通五线谱编辑已迁到内层 `ScoreEditingSession`，其
  commit policy 在提交历史前校验复音上限，并在同一 undo 边界维护手工事件来源；本轮明确不移植的
  钢琴卷轴/自动记谱 adapter 仍保留独立桌面提交路径；
- `PracticeStructureProjector` 与 `PracticeTimelineScoreSynchronizer` 是共享纯投影/同步边界；平台不得
  重新推导 pristine、选区可重写性、小节边界或尾部空小节裁剪。
- 顶栏 descriptor 只包含跨端会话能力；应用设置和探索导航仍由平台壳层拥有，不得在 Web 映射成
  重复的音频按钮。Web 新建/打开在 archive 解析前预留请求序号，启动恢复和迟到 Worker frame 均
  不得覆盖更新的用户文档请求或对应的未知 `.mecon` sidecar。
- 时间轴采用 SVG 图形 + DOM 语义层；pointer 只换算稳定 ID 与音乐坐标并绘制瞬时 ghost，量化网格、
  边界裁剪、重叠裁短及权威 preview/commit 由 session 决定；
- Web 工作台用 `clientRequestId` 串行自由练习提交；自动写作 `RUNNING` 期间继续保留后续 intent，待
  完成帧后才用最新双 revision 续发。提交会使旧 preview requestId 失效，避免迟到 ghost 覆盖权威帧；
- 和弦选择器、调性布局、惯用进行卡片只读 frame 的 `catalog` 视图；**禁止在 TS 维护和弦、进行或
  规则白名单**；
- 五线谱继续用 `FrozenScoreBundle` Canvas；DOM 语义层提供乐谱摘要、当前和弦与 finding 列表。

### 6.5 播放

- excerpt 在 worker 生成：整谱 `ScoreToMidiConverter.convert` 是 O(score)，禁止主线程执行；
- 区间由 session 的 `Solved.replayRange` 决定，BPM 用练习设置，不改全局 tempo multiplier；
- AudioContext 只由用户手势解锁；stop/seek 必须补齐 note-off；
- Web 播放线消费同一 render frame 从全部 renderer `timeCodePositions` 投影出的 `playbackAnchors`：
  按桌面端语义选择 `anchor.tick <= currentTick` 的最后一个逐音符锚点，在音符间离散跳进；禁止用较稀疏的
  和声 `timeAxis.anchors` 代替，否则一个和声槽内存在多个音符时播放线会落后。frozen `timePositions` 确定当前系统的谱表
  纵向范围。浏览器只用 AudioContext 时钟推进轻量 cursor store，
  但位置必须由 excerpt 的 `startTick/endTick/secondsPerTick` 换算为 renderer anchor 的 expanded MIDI
  tick；禁止用 `range` 乐谱时值与 excerpt 总秒数做线性比例推算，否则变拍号、反复或 hold 会形成累计比例误差。
  `ScoreEditorSurface` 绘制覆盖线，不重新排版或逐帧重放整张 Canvas；停止、结束、切换播放请求及组件卸载
  都清除节点、动画帧和播放线。暂停时可见 cursor 保留暂停瞬间的 tick，因此播放线停在当时所属的
  逐音符锚点；另行把内部恢复偏移吸附到当前音符起点，恢复时从该起点完整重播当前音符。从选中播放
  优先解析统一选区的 `scoreTargets`，没有谱面事件选区时才使用
  和声槽 onset，并持续到时间轴末端。

### 6.6 F1 退出条件

- 完成“选和弦—配声—检查—换结果—试听”闭环，并可离线恢复；
- JS 重放与 JVM **同一份** `practice-trace.json`，逐步比较 document、findings key、effect、revision、
  writing outcome；
- 断言两端禁忌表 key 集合与目录 registry 完全相同（防止 JS 静默空表）；
- 在 [能力矩阵文档](free-practice-web-editor-capabilities.md) 新增“自由练习工作台”表，逐族标注状态；
- Service Worker 缓存带 engine/rules/font 指纹，禁止混用不同版本资源。

### 6.7 2026-08-06 首轮自由写作落地记录

本轮完成范围是自由写作，不包括禁忌表与综合教学练习。`features/free-practice/commonMain` 已成为
桌面与 Web 的共同写作会话入口，持有 document/workspace/selected slot/revision、typed findings、
和弦目录、
写作请求与候选、回放区间及 undo/redo。和弦替换与低音变更后的自动写作由 session 判定；失败或取消
保留和弦选择，成功物化与 workspace 形成单一历史项。桌面旧 materializer、旧求解/候选旁路与桌面
finding 计算已删除。此段记录首轮状态；其后 F2 已继续删除时间轴/workbench reducer fallback，并把
教学目录、完整右栏投影和普通谱面编辑接入共享 session。

F1 已增加 Kotlin/JS facade、独立可终止 search worker、共享目录 registry、worker 侧 MIDI excerpt、
Web Audio 调度和带 engine/rules/font 指纹的 Service Worker。React 的和弦选择器只消费
`frame.catalog`，反馈面板只消费 typed finding key。浏览器可将同一 module payload 与 score 保存到
`.mecon`/IndexedDB。

跨端等价门禁使用同一份
`features/free-practice/testdata/practice-trace.json`：JVM 与生成的 Kotlin/JS 包逐步核对 revision、
effect、document 稳定 ID、typed outcome、finding key 形状与 score 是否写入；当前已覆盖写作、历史、
调性线、惯用进行、时间轴增删、写作设置、离调目录筛选、带专用 effect 的练习重建及独立 finding
channel 应用。另有真实 JS 求解器
测试证明自由写作不加载禁忌表也能完成。Playwright 覆盖“目录选和弦—自动配声—检查—换结果—试听—
刷新恢复”。

首发是无登录、可离线恢复的静态站点。云存档、分享链接与 JVM 远程重求解后置；远程服务若加入，
必须执行相同 document + request，返回相同 result schema，并校验 `rulesetVersion`。

## 7. 移动版复用

Web 稳定后给 `api/core/theory/exploration/features/*/audio/renderer` 增加 Android 与 iOS target。
移动端进程内直接持有 `FreePracticeSession`，不走 JSON。Desktop/Android/iOS 可共享 Compose 叶组件
（和弦目录、路线卡、finding 行、播放控件）；composition root 与导航不共享——手机分步、平板两栏、
桌面/宽 Web 双栏。触控把长按、拖动手柄与大命中区映射为同一 intent；相机、文件、分享与触控笔
只作为 capability 注入。Compose Web 转 Stable 且原型能减少总代码后，才重评估 Web UI 合并。

## 8. 实施顺序

| 阶段 | 工作 | 退出条件 |
|------|------|----------|
| ✅ E0/E1 | score-editing 协议、共享 codec、浏览器 ZIP、IndexedDB | 同一编辑协议 + `.mecon` round-trip |
| ✅ W0/W1 | Worker facade、React 壳、Canvas、完整五线谱能力 | 能力矩阵全部通过 Web E2E |
| ✅ F0a（自由写作子集） | materializer、preset、finding 与 typed outcome 迁到 common | JVM/JS 可执行同一写作流水线 |
| ✅ F0b（自由写作子集） | 首版 `FreePracticeSession`、intent/frame/effect、background request | 5 步 MVP trace 通过 |
| ✅ F0c/F2 | 完整 selection/timeline/inspector 协议；桌面 adapter 删除 reducer/编辑旁路 | §5.9 全部满足；钢琴卷轴按明确边界保留 |
| ✅ F1a（自由写作依赖） | 必需模块加 JS、共享 registry；禁忌表误调用显式失败 | MVP 可在真实 JS 求解 |
| ✅ F1b | bridge、独立后台 Worker、React 自由写作与播放 | 完整工作台闭环通过 |
| ✅ W2–W5 | 公共编辑器、toolbar profile、时间轴、右栏与响应式工作台 | [完整工作台计划](free-practice-web-workbench-completion-plan.md) 完成 |
| ✅ W6 | PWA、无障碍、错误恢复、性能/包体/内存与浏览器 E2E 收口 | 30.3 分钟 soak 与常规浏览器门禁通过 |
| M0 | Android/iOS target、Compose 自适应壳、原生音频与文件 | 同一 trace fixture 与代表交互通过 |

修改存储模型时先同步 `docs/data_model/`；F2 完成前禁止建立第二套 React reducer。

## 9. 测试与维护门禁

- **golden trace**：`features/free-practice/testdata/practice-trace.json` 是跨端唯一等价依据。
  求解结果由 **fixture 注入**（`applyBackgroundResult` 喂固定候选），保证确定性与速度；真实求解的
  回归留在 JVM 侧慢测试，不进 trace；
- common tests 覆盖 §5.9 所列语义，另加 schema v1–v8 读取与未知 payload 保留；
- Node contract tests 覆盖 bridge JSON、未知字段、上一 schema fixture 与 `close`；
- 浏览器 E2E 覆盖宽屏双栏/窄屏 tabs、时间轴 preview/commit、取消求解、换结果、播放 stop、离线恢复与
  `.mecon` round-trip（导出后由桌面 `MeconDocumentService` 回读）；
- 架构测试禁止 feature 模块导入 Compose/AWT/DOM，禁止 Web TS 维护和弦/进行/规则白名单；
- 基准至少含 4 声部 32 槽与 6 声部 64 槽：首解与优化 pass 的 p50/p95、取消到可再交互时延、
  worker 预热成本、30 分钟内存稳定性；主线程不得出现 compute/solve；
- 每个 session、render engine、worker 与 audio scheduler 必须显式 `close`，旧 revision 不得发布。

## 10. 选型依据

- Kotlin 官方[平台稳定性表](https://kotlinlang.org/docs/multiplatform/supported-platforms.html)：
  Kotlin/JS、Android、iOS 为 Stable；Kotlin/Wasm 与 Compose Web 仍为 Beta。
- Kotlin 官方[Web 方案选择](https://kotlinlang.org/docs/web-overview.html)：共享业务逻辑并使用
  React 等原生 Web UI 时选 Kotlin/JS；共享 Compose UI 才选 Kotlin/Wasm。
- 仓库内 [Web 渲染包](../renderer/web-renderer.md) 已确定 React/Canvas 与 JSON facade 接缝，
  本方案沿用该接缝，不新增另一套乐谱协议。
