# 自由练习自动写作改造

> 状态：✅ 2026-08-12 已实施；音符/声部锁定与锁定旋律配和弦已接入，窗口外的通用
> 右端边界匹配和公开 `SolverApi.refine` 仍按本文界定留待后续。
> 求解器细节与 `refine` 边界见
> [自由练习窗口写作与 refine 基础](../theory/free-practice-window-voicing.md)，持久化模型见
> [自由练习数据模型](../data_model/free-practice.md)。

## 1. 结论与范围

自动写作不再选择“下一个和弦”，而是把用户已经选定的和弦音响/解释落实为具体声部音高。
现有启发式续写 UI、桌面字符串候选和未接线的 continuation 门面删除；和弦时间轴仍是用户意图的
唯一真相，`RuntimeScore` 仍是音符的唯一真相。

本次实施范围：

1. 选中或修改和弦时按设置自动写作；修改中间和弦默认不动其后材料。
2. 支持向左回溯、自动补齐连续“已选但未写”的前置和弦。
3. 支持乐谱选段后“重新写作”，以及对最后一次范围“换一个结果”。
4. 和弦选择与其自动写作作为一个撤销项；手动重写、换结果各自作为一个撤销项。
5. 自动回放以最后写作和弦为终点，可从前面若干和弦开始，并使用练习专属 BPM。
6. 所有练习设置移入 Exploration 顶部工具栏的“练习设置”弹层。

本次不做：窗口外右边界匹配、公开 `SolverApi.refine`、独立节奏生成与和弦外音装饰。
这些能力不应阻塞本轮，但本轮新增的范围、边界帧、pin 和结果指纹必须能被其复用。

## 2. 顶部工具栏与交互

自由练习激活时，Exploration 顶部工具栏为：

```text
[打开/保存] [撤销/重做] | [练习设置 ▾] | [重新写作] [换一个结果] | [写作状态] | [播放控制]
```

- “练习设置”接管右栏现有的复音上限、上下谱表通道、初始调性，并增加自动写作、回溯数、
  回放数和 BPM；右栏保留和声选择、调性布局、惯用进行、详情与检查。
- 改复音上限或初始调性仍属于文档重建操作，弹层必须明确提示会重建材料；上下谱表通道调整
  继续走保留音符的迁移事务。自动写作/回放设置是非破坏性文档偏好，不进入撤销栈。
- “重新写作”只在乐谱选区能解析为至少一个完整和弦槽、且范围内每槽均已选和弦时启用。
- “换一个结果”忽略当前选区，严格复用最后一次成功写作的稳定槽范围；任何其他材料或和声
  编辑会使旧候选失效。
- 写作期间显示范围和进度，可取消；会改变工作区或谱面的操作暂时禁用，浏览、滚动不禁用。
- 播放控制复用主界面的从头播放、播放/暂停、从选区播放、速度倍率和音频设置入口；播放位置
  同时驱动自由练习五线谱与钢琴卷轴的播放线，两种视图继续共享横向时间投影。工具栏播放
  会把练习 BPM 写入本次 MIDI 帧，主界面的全局速度倍率仍可叠加且不会被练习设置覆盖。
- 五线谱的点击、音高拖动、录入和单事件属性编辑复用主编辑器的短试听入口；自动写作运行中或
  片段回放期间不触发手动短试听，避免与写作完成后的自动回放叠音。
- 编辑回放由 `FreePracticeSession` 发布 typed `PracticeEditPlayback`：单事件选择/编辑发布 MIDI
  短试听，和弦与惯用进行写作发布精确 `PracticeReplayRange`；Desktop/Web 只负责音频重放，
  不按 effect 名称或本地选区重新推导范围。编辑回放不进入正式 transport，也不移动播放线。

顶部工具栏不能直接读取 Workbench 内部 Compose 变量。新增稳定的
`FreePracticeToolbarController`，由 Workbench 发布单个不可变 state，并由 `App` 交给 `TopBar`。
控制器只暴露自由练习命令，不把这些领域动作塞进通用 `EditableScoreHost`。

## 3. 设置与持久化

schema v6 拟新增：

```kotlin
@Serializable
data class FreePracticeWritingSettings(
    val autoWritingEnabled: Boolean,
    val backtrackChordCount: Int = 0,
    val replayChordCount: Int = 1,
    val playbackTempoBpm: Int = 120,
)

@Serializable
data class FreePracticeSettings(
    // 既有字段……
    val writing: FreePracticeWritingSettings,
)
```

约束：回溯 `0..16`；回放 `0..16`，其中 `0` 表示写作后不自动回放；BPM `30..240`。
新建练习默认开启自动写作；v1–v5 文件迁移时默认关闭，避免旧工作流在下一次选和弦时
突然改变。迁移时 BPM 从关联乐谱起点的有效速度取整并裁剪，无法读取才回退 120；此后它是
独立的练习预听偏好，不与谱面 tempo 双向同步。factory 与 v6 migrator 必须显式表达新旧默认差异。

候选、最后写作范围、busy 状态、seed、诊断、选区和撤销栈均为瞬态，不写入 `.mecon`。

## 4. 稳定写作范围

`EventSection` 会随重写失效，不能作为工具栏或“换一个结果”的范围标识。统一使用：

```kotlin
data class PracticeWritingScope(
    val slotIds: List<WorkspaceSlotId>,
    val triggerSlotId: WorkspaceSlotId?,
    val leftBoundarySlotId: WorkspaceSlotId?,
)

enum class PracticeWritingTrigger {
    CHORD_SELECTION, SELECTION_REWRITE, ALTERNATE_RESULT
}
```

`slotIds` 必须非空、按当前时间轴连续且每槽已有 `WorkspaceChordChoice`。求解和提交前都重新按
id 投影并校验；任一槽消失、换调性或换和弦时，旧请求按 `STALE` 丢弃。

乐谱选段状态从 `FreePracticeEditorPanel` 提升到 Workbench。公共谱面选区增加 opt-in 的时间范围
回调，使空谱/休止区也能框选；事件选区则用事件 onset 投影到槽。最终范围吸附到首尾和弦边界，
取首个到最后一个命中槽的连续区间。中间存在未选和弦时拒绝并提示，不暗自跨越或拆成多次撤销。
Web 壳从共享 `scoreTargets` 恢复事件 onset，再用 renderer 的绝对时间锚点投影到连续 `slotIds`；
不得退化为仅发送当前时间轴焦点槽。“换一个结果”仍只读取 session 保存的 `lastScope`，不重新读取选区。

## 5. 自动范围算法

设用户修改槽 `i`，配置回溯数为 `N`：

```text
end = i
start = i 向左最多 N 个连续“已选和弦”
while start 前一槽已选、且完全没有音符材料:
    start 再向左扩一槽
leftBoundary = start 前一槽（仅当它有完整的结束边界声部帧）
```

- 遇到未选槽立即停止；若 `i-1` 未选，则当前和弦独立安排。
- 覆盖状态从 `RuntimeScore` 派生，不读已废弃的 `workspace.notes`：`EMPTY` 表示完全没有发声音符；
  在槽结束左极限处能取得 `N` 条分析声部音高则为 `BOUNDARY_READY`；其余为
  `PARTIAL_OR_COMPLEX`。槽内允许有较短音或装饰，边界只取最后纵向。
- 只有连续 `EMPTY` 前置槽会被强制并入范围，即使超过配置 `N`，满足“先把前面写好”；部分
  手工材料不会因自动扩展被静默覆盖。若用户配置的回溯范围明确包含它，则视为授权重写。
- 例外是仅含已锁定旋律、尚未形成完整和声的前置槽：它属于“待配和弦”材料，会与后选槽一起
  向左并入；未锁定的部分手工材料仍不会被自动覆盖。
- 范围左侧为 `PARTIAL_OR_COMPLEX` 时不作为边界，当前窗口独立安排并给出信息诊断。
- 左边界只提供首个连接的实际音高与和声上下文，不重新接受音域、完整性或教材硬规则检查；
  因而用户先前的不合理排列不会让新和弦必然无解。
- 修改中间槽时 `end` 永远为 `i`；右侧槽、音符及完整位于范围后的 EventId 保持不变。
- 手工选段精确使用选段范围，不叠加回溯设置；只额外读取范围前一个可用左边界。

自动触发以提交后的 target fingerprint 为准：插入/替换 `WorkspaceChordChoice`、锁定/解除具体解释，
或直接改变该槽所采用的调性读法且使 target 域变化时触发。惯用进行的整组插入/替换只发起一次
覆盖其新增槽的复合写作；移动/缩放时间轴、选区变化、枢纽标记、finding 导航、手工音符编辑与
undo/redo 不触发。自动写作关闭时这些命令只提交原编辑，用户仍可显式“重新写作”。

## 6. 写作流水线

```text
Workspace command / selected score range
  -> scope resolver + Runtime voicing projection
  -> fixed selected-chord target domains
  -> FreeHarmony window solve (Top-K)
  -> range materializer
  -> computeScore
  -> one HarmonyPracticeTransaction
  -> bounded playback
```

每槽目标域只包含与 `WorkspaceChordChoice.pitchClasses` 一致的解释：锁定解释为单元素域；自由解释
保留所有兼容解释作为互斥分支。为此 `FreeHarmonyRequest` 增加逐槽
`allowedTargetIdentityKeysBySlot`，并用可选 `slotSpecs` 携带稳定 solver id、真实 onset/duration 和
source anchor；编译器统一与既有 singleton fixed target 取交集并生成 `ConstraintSlot`，不在桌面
重写解释过滤逻辑，也不并存两份 `SlotDomain`。

求解仅覆盖 `leftBoundary + scope` 所需上下文，不扫描整谱。交互首解使用
`candidateLimit=12 / prefix frontier=8 / maxResults=1`，得到第一候选即可提交；随后用相同 fingerprint
在缓存 worker 使用 `candidateLimit=24 / prefix frontier=24 / maxResults=4` 的 seeded restart，只填充
“换一个”候选而不再改谱。后台 pass 不把刚提交的首解作为强制相似度 baseline；多样性门槛已经
负责避免近重复，音乐软规则分数才用于优化质量。阶段 A 只生成一个种子解，剩余预算交给定向
重启；变异槽优先选择“高扣分和弦的前一和弦”。输出必须区分 `SOLVED / NO_SOLUTION /
BUDGET_EXHAUSTED / CANCELLED / INVALID`，不能把预算耗尽伪装成无解。

2026-08-03 用 8 槽 `I-ii-I64-V-I-IV-V-I` 自由古典四声部程序校准（同机 JVM，时间仅作量级参考）：

| prefix frontier | 访问节点 | 首解耗时 | 首解分数 |
|---:|---:|---:|---:|
| 32 | 19,953 | 3.7–5.4 s | 60.95 |
| 24 | 11,751 | 1.8–2.2 s | **53.80** |
| 16 | 5,404 | 0.62–0.88 s | 68.30 |
| 8 | 1,369 | 0.12–0.17 s | 82.45 |

宽度并非越大越好：候选 exploit/explore 配额会改变前缀组成。`8 -> 24` 两阶段既把首个可用解提前，
又比旧 32 宽度更快得到更低分候选；24 宽度的多样化 pass 访问 11,793 节点，得到的四个结果为
`53.80 / 62.45 / 90.45 / 92.80`。因此参数以后台优化为主，不再继续扩大初搜。

逻辑候选先与探索自动求解共用 `VoicingEventPlanner`，统一生成 slot / voice / onset / duration /
pitch event cells。物化器再按 `[scope.start, scope.end)` 原子替换全部声部骨架；本轮没有锁定语义，所以范围内手工
修改也会被明确重写，不新增“自动/手工音符”第二份真相。完全位于范围外的事件和 ID 不变；
跨边界事件在边界切分并保留范围外的可听部分，清理指向被替换事件的 tie/slur/选择引用。
新增音符按稳定逻辑 voice id 写回对应记谱通道，每个工作区和弦槽、每个未锁定声部只写一个
持续骨架音。锁定旋律造成的求解 segment 只提高约束采样分辨率，不得让伴奏随每个旋律音重复落音。

## 7. 规则放宽与有效剪枝

自动写作使用自由古典 profile：平行五/八、交叉、倾向音、七音解决、普通跳进等全部是软偏好，
不因用户给定和弦不理想而判无解。和弦音响/锁定解释、`VoicePlan` 音域、MIDI 可表示范围、
复音容量与显式 pin 仍是硬材料约束；可信左边界本身不接受这些范围复检。

通用写作卫生的软成本高于教材章节形式：外/内声部交错和平五/八优先规避，章节的标准预备、
解决、重复音和连接形式只作较低权重建议。四声部新工作区使用标准 SATB 人声音域；任意声部数的
通用范围公式只用于非四声部配置。

另设不可由 UI 调整的 `SearchFeasibilityPolicy`，用同一谓词同时生成 prune trace 与 finding：

- 非低音相邻分析声部间距不超过 12 半音；最低外声部与其上方声部不超过 19 半音；
- 一次内部生成连接中，超过八度跳进的声部最多 1 个；
- 左边界到首个生成和弦的上述跳进规则先参与正常搜索；若它是唯一死因，只把该边界规则降为
  软偏好重试一次，内部生成连接和纵向间距不放宽。

阈值是首版基线，实施时用 3–6 声部与极端用户边界基准校准。求解器须按失败槽、prune rule、
访问节点与是否耗尽预算返回诊断；不得只提高预算或扩大候选池后宣称无解问题已修复。

## 8. 异步、撤销与 stale 结果

`HarmonyPracticeScoreHost` 新增复合 intent，在后台串行执行 reducer、求解、物化与 compute。
和弦先在 Workbench 显示为 optimistic draft，但 host 只在完整结果准备好后执行一次
`HarmonyPracticeTransaction.commit`：

- 成功：新 workspace + 新 Runtime/Computed 一次入栈；
- 无解/预算耗尽：用户和弦选择仍成功，作为同一个和弦选择操作提交；谱面保持原样、标为写作
  过期并显示诊断，不自动回放可能仍对应旧和弦的音符；
- 取消：恢复提交前 draft，不产生历史项；
- 后台引擎崩溃：见 §8.1；
- undo/redo 只恢复历史帧，绝不再次触发自动写作。

CPU 搜索增加协作取消检查；worker 使用 operation id、基础 document version、scope/target fingerprint
校验结果。写作未完成时禁用冲突编辑与 history 动作，关闭工作台会取消 worker。完整
Runtime/Computed 发布前继续显示旧谱面帧，遵守大乐谱交互保护约束。

### 8.1 后台崩溃必须走共享失败通道

后台 channel 抛异常、worker 死亡或引擎 chunk 加载失败时，**平台不得只把错误打到状态栏**：
请求仍挂在 `FreePracticeSession.activeRequest` 上，工作台会永远停在
`PracticeWritingPhase.RUNNING`、显示尚未提交的 workspace 且拒绝后续 intent（用户只能刷新页面）。

- 协议：`PracticeBackgroundFailure(requestId, reason)`。只带 requestId——其余属性 session 自己
  持有，崩掉的 worker 不可信。
- 入口：`applyBackgroundFailure` / `applyTeachingCatalogFailure` / `applyFindingFailure`；
  requestId 与当前 active request 不符时按 `STALE_BACKGROUND_RESULT` 丢弃。
- 首解崩溃 = 回退：`requestWritingForWorkspace` 只把待提交 workspace 挂在请求上、从不预先提交，
  因此**丢弃请求本身就是回退到最后一次提交的 workspace 与谱面**。禁止在这里提交请求自带的
  快照——崩溃后恰恰是那份 pending 状态不可信。结果为 `PracticeWritingOutcome.Failed(reason)`
  加 `INVALID` + `freePractice.writing.failed`，与「无解」区分开：无解是教学结论，崩溃是缺陷。
- 备选（`OPTIMIZE_CANDIDATES`）崩溃只解锁，不回滚已应用的首解。
- finding / teaching catalog 崩溃要把 fingerprint 记为「已尝试」再清空 pending 请求：只清空会让
  下一次 `result()` 立刻重发同一请求，在必现崩溃上空转；只标 stale 又会永远卡住。
- 桌面：`EditableScoreHost.solveInBackground` 捕获 `Throwable`（`CancellationException` 透传）；
  Web：`engine-worker.js` 为每个 search worker 挂 `onerror` 与 error 消息路由，并在 apply 结果
  自身抛错时补发 failure。两端共用同一 session 语义，不各写一套回退。

## 9. 换一个结果与回放

```kotlin
data class LastWritingSession(
    val scope: PracticeWritingScope,
    val inputFingerprint: String,
    val candidates: List<PracticeVoicingCandidate>,
    val usedDiversityKeys: Set<String>,
    val nextSeed: Long,
    val committedDocumentVersion: Long,
)
```

“换一个”优先应用同批尚未使用的候选；耗尽后以新 seed 重启并排除已用 diversity key。
不能每次用 `maxResults=1` 只换 seed，因为当前多样化搜索的第一解固定不随机。每次换结果单独入栈，
但范围与和弦目标不变；成功后的普通编辑使 session 失效。

自动回放区间以写作范围最后一槽结束为终点，从其前 `replayChordCount - 1` 个可用槽开始，
可包含范围左侧已有和弦。新增 `PracticePlaybackExcerptBuilder`，在后台只查询该半开区间的事件，
生成局部 `MidiScore`：tick 0 注入设置 BPM、裁剪跨边界 note、在终点补齐 note-off。提交完整谱面帧
后调用 `PlaybackController.playExcerpt(excerpt)`，由短片自然结束；不能复用会继续播放后文的
`playFromSelection`，也不能临时改主编辑器的全局 tempo multiplier。

惯用进行插入/替换保留整组听觉语义：当惯用进行 scope 长于 `replayChordCount` 时，回放起点扩展到
该惯用进行第一槽；不长于设置值时仍沿用普通“最近 N 个和弦”规则。该判断随后台 request 的
`replayWholeScope` 进入共享会话，平台不得按 UI 中的实例长度再次判断。

## 10. 实施顺序

1. schema v6、迁移、设置弹层与 typed toolbar controller。
2. Runtime 声部边界投影、稳定 scope resolver、空区时间选段。
3. 求解状态/取消、逐槽 target domain、左边界、可行性谓词与 Top-K 会话。
4. 范围物化器、跨边界事件与引用清理、增量 hint/compute。
5. host 复合事务、自动触发、手动重写、换结果与 stale 防护。
6. 有界回放与 BPM 覆盖。
7. 删除 continuation 代码/UI，更新跨平台和求解器文档。

## 11. 验收

- 默认回溯 0 只改当前槽；改中间槽不改后续可听材料。
- 前置未选时独立写作；前置连续已选但未写时自动扩展并一次提交。
- 极端左边界仍能写出当前和弦；内部过宽间距和两个以上大跳分支被可解释地剪掉。
- 选段重写覆盖精确稳定槽；换结果始终复用最后范围并产生不同 diversity key。
- 和弦选择 + 自动写作一次 undo/redo；失败只留下和弦选择且仍只有一个历史项。
- stale/cancelled 结果不覆盖新文档；求解、物化、compute 不在 Compose 主线程。
- 回放恰好在目标槽尾停止，0/1/N、谱首截断及 BPM 均正确。
- 3–6 声部、自由/锁定解释、跨调槽、跨小节时值和 schema v1–v6 均有回归测试。
