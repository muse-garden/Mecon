# 状态管理

> 路径：`api/src/commonMain/kotlin/com/mecon/api/state/`

## 1. 三个核心类

| 类 | 职责 |
|----|------|
| `ScoreState` | 三元组：`(RuntimeScore, ComputedScore, RenderHint?)` 快照 |
| `ScoreStateManager` | 维护撤销 / 重做历史（≤50 项），暴露 `StateFlow`，提供提交与插件 / 视图就地更新 |
| `GlobalScoreState` | 单例持有当前 `ScoreStateManager`，供无法逐层传参处（如交互逻辑）全局访问 |

## 2. ScoreState

```kotlin
data class ScoreState(
    val runtimeScore:  RuntimeScore,
    val computedScore: ComputedScore,
    val renderHint:    RenderHint? = null,   // 增量渲染提示（见 §6），非乐谱本体
)
```

编辑会话只快照 Runtime / Computed 两层；`StorageScore` 只存在于打开文件后的反序列化入口和保存文件前的序列化出口，不参与编辑热路径或撤销历史。`renderHint` 是**关于「产生本状态的那次转换」的瞬态渲染元数据**——记录编辑前的 `ComputedScore` 与 `ComputeChangeSet`，让渲染层能增量更新而非全量重渲（§6）。非增量转换（打开文档、撤销重做、插件 / 视图偏好编辑）下为 `null`。

## 3. ScoreStateManager

```kotlin
class ScoreStateManager(
    initialRuntimeScore: RuntimeScore,
    initialComputedScore: ComputedScore,
) {
    val currentState: ScoreState
    val currentStateFlow: StateFlow<ScoreState>           // 订阅以驱动 UI 重组

    fun commitNewState(storage, runtime, computed, renderHint: RenderHint? = null)  // 推入历史栈（可撤销）
    fun updatePluginTrackState(trackId, transform)        // 就地更新当前项（不入撤销栈）
    fun addPluginTrack(storageTrack, runtimeTrack, computedTrack)  // 就地添加轨道
    fun updateViewPreferences(transform)                  // 就地更新视图偏好（不入撤销栈）

    fun canUndo(): Boolean; fun canRedo(): Boolean
    fun undo(); fun redo()
}
```

历史栈最多 50 项，超出时移除最旧项（FIFO）；撤销后再提交会截断后续历史（标准 undo tree 行为）。

### 提交流程

编辑分两类，落到不同的重算路径：

**A. 音符编辑（增量）** —— 见 [ui/score-editing.md](ui/score-editing.md)、[data_model/incremental-update.md](data_model/incremental-update.md)：

```
用户落子 → NoteEditEngine.insert(runtime, insertion)         // 纯函数，得到新 RuntimeScore + editInterval
        → computeScoreIncremental(prevComputed, newRuntime, editInterval)   // 局部重算，复用旧 computed 子树
        → commitNewState(storage, newRuntime, computed, RenderHint(prevComputed, changeSet))
```

**B. 结构 / 存储编辑（全量）** —— 如页面布局、插件事件经存储层写入：

```
改 RuntimeScore（不可变更新）
        → computeScore(newRuntime)        // 全量重算
        → commitNewState(storage, runtime, computed)        // 无 renderHint
```

桌面端由 `ScoreSession`（`apps/desktop/.../service/ScoreSession.kt`）持有 manager，把 `currentStateFlow` 收集进 Compose snapshot state；读派生 getter 的 composable 在状态变化时自动重组。

### 插件 / 视图就地更新

```kotlin
manager.updatePluginTrackState(TrackId("chord")) { old -> old.withEvents(newEvents) }
```

`updatePluginTrackState` / `addPluginTrack` / `updateViewPreferences` 改的是历史栈**当前项**（in-place），不推新项——所以撤销不会回退插件分析结果或视图偏好，这是期望行为。就地更新会把 `renderHint` 置空（这类变更不是增量音符编辑，应整体重渲）。

## 4. GlobalScoreState

```kotlin
object GlobalScoreState {
    fun initialize(storage, runtime, computed)            // 启动 / 加载文件时调用
    val activeManager: ScoreStateManager                  // 未初始化则抛错
    val currentState: ScoreState
}
```

## 5. Undo / Redo

```kotlin
manager.undo()  // 指针前移
manager.redo()  // 指针后移
```

UI 层快捷键（`Ctrl+Z / Ctrl+Y`）在 `App.kt` 的键盘处理中调用，经 `ScoreSession.undo()/redo()` 转发。音符录入已实现（见 [ui/score-editing.md](ui/score-editing.md)），多级撤销可用。

## 6. 与渲染的关系

```
ScoreState.computedScore (+ renderHint)
    ↓  RenderEngine.renderIncremental  /  render
ComposeScoreRenderer
```

每次状态变化触发 Compose 重组并重新渲染。路径选择：

- **音符编辑** → 增量 compute + 增量 render：`commitNewState` 携带 `RenderHint`，主视图据此调 `RenderEngine.renderIncremental` 只重排受影响小节（见 [data_model/incremental-update.md](data_model/incremental-update.md) 与 [renderer/incremental-rendering.md](renderer/incremental-rendering.md)）。
- **撤销 / 重做、快速连击被合并跳帧** → 仍走增量：`renderHint` 缺失或与显示帧不符时，渲染端的**谱系守卫**用持久化 B+ 树结构化 diff 现场算出「显示帧 → 新 computed」的真实 `ComputeChangeSet`（`computeChangeSetBetween`），据此增量更新；遇记号 / 结构变化才安全退回全量。详见 [data_model/computed-event-store.md](data_model/computed-event-store.md) §7a。
- **打开文档 / 缩略图 / 参考视图** → 全量 `render`。
