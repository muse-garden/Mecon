# 自适应渲染、试听与后台 Patch

> 状态：🚧 评审稿，未开始实现（2026-07-12）
> 范围：Mecon 舞台/混响状态、音符试听、preview/final 分级播放、缓存失效、后台任务与无缝 patch。
> Rust 侧设计：[空间混响与自适应渲染](../../../vst-experiment/docs/20-空间混响与自适应渲染.md)。
> 播放协议前置：[vst-integration.md](vst-integration.md)。

## 1. 产品目标

Mecon 必须同时满足三种体验：

1. **编辑即时反馈**：插入、拖动或选中音符后立刻听见，不等待整谱物理模拟。
2. **大型乐队可播放**：CPU 不足时使用同版本 engine 生成的 preview pack/既有缓存，不因一组弦乐的
   大量物理 voice 产生 dropout。
3. **最终声音可逐步升级**：后台按实际物理模型渲染，完成后无缝 patch 当前 timeline；继续编辑只失效
   受影响窗口，不重新计算整部作品。

所有模式消费同一 `PerformancePlan` 和 `ScenePlan`。preview 不是另一套乐谱解释器，也不能绕过力度、
奏法、控制轨或舞台布局。

## 2. Mecon 数据流

```text
RuntimeScore
  │ PerformanceCompiler + SceneCompiler
  ▼
PerformancePlan + ScenePlan
  │
  ├── AuditionEngine ───────────────► 即时音符试听
  ├── AdaptivePlaybackCoordinator
  │     ├── physical realtime voices
  │     ├── preview pack voices
  │     └── cached final chunks
  │
  └── BackgroundRenderCoordinator
        ├── dry physical render
        ├── spatial render
        └── verified RenderPatch
```

`AdaptivePlaybackCoordinator` 是唯一 transport 决策者；Compose UI 不自行决定某条轨道用采样还是物理，
也不直接拼接音频文件。

## 3. 存储与非存储状态

### 3.1 写入 .mecon

扩展 `StoragePlaybackConfig`：

```kotlin
data class StoragePlaybackConfig(
    val routes: List<StoragePlaybackRoute> = emptyList(),
    val scene: StorageSceneConfig = StorageSceneConfig(),
    val renderPolicy: StorageRenderPolicy = StorageRenderPolicy.AUTO,
)

data class StorageSceneConfig(
    val venuePresetId: String? = null,
    val seatingPresetId: String? = null,
    val mixPresetId: String? = null,
    val resolvedPresetVersion: String? = null,
    val sourceOverrides: List<StorageSceneSourceOverride> = emptyList(),
    val roomOverrides: Map<String, Float> = emptyMap(),
    val microphoneOverrides: List<StorageMicrophoneOverride> = emptyList(),
)
```

保存工程意图：route、preset、显式位置/参数 override 和用户选择的 render policy。所有新增字段有默认值。

### 3.2 不写入 .mecon

以下属于可再生或设备相关状态：

- preview pack 解码缓存；
- dry/spatial render chunks、waveform、checkpoint；
- 当前 CPU benchmark、实际质量层级、后台队列和进度；
- audition voice；
- 本机缓存路径/容量。

它们放全局内容寻址缓存或工程旁 sidecar。`.mecon` 移动到另一台机器仍可打开并重新渲染。

## 4. SceneCompiler

`SceneCompiler` 输入 ordered staffs、staff groups、playback routes、preset 与 override，输出纯 `ScenePlan`。
它不依赖 Renderer 的页面坐标。

映射规则：

- 一个独奏 route 默认一个 point source；
- string/brass/choir section 可生成 area source 或多个 seat source；
- 同一多谱表乐器的 staves 合并到同一 source；
- 未分配 route 不自动猜位置，生成 diagnostic；
- seating preset 只补齐没有 explicit override 的 source；
- source ID 由 route ID 稳定派生，编辑谱表名称不会丢位置。

## 5. 舞台与混响 UI

新增共享 `SpatialScenePanel`，嵌入桌面右侧面板或独立窗口。

### 5.1 舞台画布

- 俯视显示房间边界、舞台、听众/麦克风、section 区域和独奏图标；
- 默认按 section 聚合，大型弦乐显示区域与人数；双击展开 individual seats；
- 拖动、框选、对齐、旋转、镜像、锁定、复制布局；
- 切换听众/舞台视角；显示距离、early send、late send 热图；
- source 与 score route 双向选择：点谱表高亮舞台对象，点舞台对象定位对应谱表；
- 自动布局只处理新增/未定位 source，不能重排已有用户位置。

### 5.2 预设与参数

顶部提供三个独立选择器：

- 场馆：小录音棚、小/大音乐厅、教堂等；
- 座次：室内乐、古典双管、浪漫三管、管乐团等；
- 收音/混音：近场清晰、自然厅堂、观众席、电影化等。

常用参数直接显示 RT60、pre-delay、width、HF damping、dry/early/late；墙面吸声和 mic array 放高级区。
切换预设先预览差异，确认后作为一个 ScoreStateManager 提交；拖动过程只更新临时 audition state，松手
一次性写入撤销栈。

### 5.3 状态可视化

- 每个 source：mute/solo、dry/direct/early/late meter、当前 Physical/Preview/Cached 标识；
- 全局：实际 CPU、block budget、cache 命中、后台队列、当前 room 与输出峰值；
- timeline：灰色 Missing、蓝色 Preview、动画 FinalRendering、绿色 FinalReady、橙色 Stale、红色 Error；
- 用户始终能知道听到的是 preview 还是 final。

## 6. 即时音符试听

新增 `AuditionRequest`：

```kotlin
data class AuditionRequest(
    val routeId: PlaybackRouteId,
    val pitch: Pitch,
    val dynamic: Float,
    val articulation: ArticulationIntent,
    val durationHintMs: Int,
    val sourceId: SceneSourceId?,
)
```

触发来源包括插入音符、钢琴键盘、拖动音高和显式“试听”动作。规则：

- audition 使用独立 bus/voice，不改变播放头和主 transport；
- 优先 preview pack；目标 asset 已预热且 CPU 允许时可选 low-latency physical voice；
- 使用当前 source placement，但走短 audition spatial path，避免积累整厅长尾；
- 连续拖动复用当前 voice/asset；旧音快速 fade，不堆叠；
- commit 音符后 audition 结束，正式 PerformancePlan change 进入后台失效/渲染；
- audition 不写 Controller Track，不进入撤销栈。

目标延迟：已预取 preview 的 note-on 到设备输出不超过一个 audio block + 10 ms 调度余量。

## 7. RenderQualityPolicy

`RenderQualityPolicy` 包含 `AUTO / FORCE_REALTIME_PHYSICAL / HYBRID_PREVIEW / WAIT_FOR_FINAL /
PREVIEW_ONLY`。

`AUTO` 根据启动 benchmark 和运行时 underrun 风险选择。用户还可：

- pin 选中/solo route 为实时物理；
- 设置最大 CPU 百分比、后台 worker 数、缓存容量；
- 选择“立即播放”“从播放头前方优先渲染”“等待整段最终结果”；
- 正式导出强制 final，除非明确选择快速 preview export。

policy 是用户意图；实际每 route/chunk 的质量状态只在运行时，不能保存成“已完成”的工程事实。

## 8. 变更与失效

ScoreStateManager 每次提交生成 `PerformanceChangeSet`，记录变化的 pitch event、voice track、control lane、
playback route、scene source，以及 tempo 起点和结构时间范围。

失效图：

| 改动 | Dry cache | Spatial cache |
|---|---|---|
| 音高/时值/奏法/力度/控制曲线 | 对应 route + 依赖窗口 | 级联 |
| 乐器/preset/calibration/model | 对应 route 全部 | 级联 |
| source 位置/direct/early/late | 不失效 | 对应 source/window |
| venue/RT60/mic | 不失效 | scene 全部或从变更点起 |
| tempo | 从变化点起重编时间/必要 dry | 级联 |

依赖窗口由 slur/tie/transition、物理尾音、控制曲线和 room RT60 计算。不能只按当前小节硬截断。

## 9. 后台任务

`BackgroundRenderCoordinator` 使用有界优先队列：

1. 当前播放头前方；
2. 最近编辑且可见窗口；
3. loop range；
4. 其余时间线；
5. waveform/额外分析。

同一 chunk 新任务到来时取消旧 generation；结果提交前核对 score revision、plan digest 和 asset versions。
关闭文档取消任务但不破坏已完成内容寻址缓存。多个文档共享 worker pool，前台文档拥有更高配额，不能无限
启动线程或占满磁盘 IO。

### 9.1 chunk 状态

```kotlin
sealed interface RenderChunkState {
    data object Missing
    data class PreviewReady(val key: PreviewKey)
    data class FinalRendering(val generation: Long, val progress: Float)
    data class FinalReady(val key: RenderChunkKey)
    data class Stale(val oldKey: RenderChunkKey)
    data class Error(val diagnostic: RenderDiagnostic)
}
```

旧 final 在替代结果完成前保持可播放；`Stale` 不等于删除。

## 10. RenderPatch

`RenderPatch` 不是修改乐谱，而是更新当前 playback snapshot：

```kotlin
data class RenderPatch(
    val scoreRevision: Long,
    val startFrame: Long,
    val endFrameExclusive: Long,
    val dryChunkRefs: Map<PlaybackRouteId, RenderChunkKey>,
    val spatialChunkRef: RenderChunkKey?,
    val checkpointRef: RenderCheckpointKey?,
    val continuity: PatchContinuity,
)
```

应用条件：

- revision/digest/采样率完全匹配；
- chunk 已完成 NaN/peak/frame count/continuity 校验；
- patch 点在安全 block/sample frame；
- preview/旧 final 与新 final 做等功率 crossfade；
- 正在发音的长音优先延后到 overlap 充分的位置，不能硬切；
- 失败保留当前声音并显示 diagnostic。

UI 收到的是不可变 `PlaybackSnapshot` 新版本；不得在 audio callback 修改 Compose state。

## 11. 混响状态与 Patch

共享 late FDN 有历史，不能只替换一个湿声小节。采用：

- dry stem 小 chunk；
- spatial checkpoint 保存 direct/early 延迟与 FDN 状态；
- 周期 checkpoint + pre-roll/overlap 重渲染；
- 找不到兼容 checkpoint 时从最近静音/phrase boundary 向前算；
- venue topology 变化使用新旧 spatial engine 双跑 crossfade；
- 移动单个 source 只重算它的 direct/early 与 late send，但最终 late bus 从相邻 checkpoint 重建。

长 RT60 会扩大空间失效范围，这是正确代价；UI 应显示“干声已完成，空间尾音重算中”。

## 12. Preview Pack 管理

`PreviewPackManager`：

- 按 instrument/version/digest 发现已安装 pack；
- 启动后预取当前 score 起始区和编辑选中 route；
- 解码/IO 在后台，音频线程只读内存块；
- 缺 pack 时提示下载/构建，仍可选择低复音物理试听；
- pack 与 engine/calibration 不兼容时拒绝混用；
- 显示磁盘体积、最后使用、清理；清理不影响工程正确性。

两个项目开源不意味着大型 preview 二进制进入 Git；仓库保存生成器和 manifest，发布页/对象存储提供 pack。

## 13. 测试

- SceneCompiler：preset + override、route 增删、稳定 source ID、未分配 diagnostic。
- UI：section 聚合/展开、拖动撤销、自动布局不覆盖 override、score ↔ stage 选择联动。
- Audition：延迟、快速换音无 stuck voice、独立 transport、当前 scene 生效。
- Cache key：任何声音相关版本/控制变化都改 key，无关 UI 变化不改 key。
- Invalidation：slur/tie/tempo/长尾/RT60 扩窗，room 改动不失效 dry。
- Scheduler：CPU 压力降级顺序、pin/solo 优先、任务取消和跨文档公平。
- Patch：preview→final、stale→final、播放中替换、失败保留旧音、无 click/gap。
- Spatial continuity：checkpoint 恢复与连续全量渲染误差门槛、venue crossfade。
- E2E：大型弦乐 score 立即 preview，后台完成后逐段升级，正式导出全 final。

## 14. Mecon 实施顺序

| 阶段 | 内容 |
|---|---|
| A0 | Scene/quality/cache/patch 协议与 fixtures |
| A1 | 长笛 preview pack + AuditionEngine，先完成插入音符即时试听 |
| A2 | Rust spatial engine 接入所有 Mecon 声音路径 |
| A3 | SpatialScenePanel + venue/seating/mix preset |
| A4 | DryRenderCache、ChangeSet 失效与后台队列 |
| A5 | Hybrid playback 与 preview→final patch |
| A6 | 大型弦乐 section preview/压力测试 |
| A7 | 正式导出、缓存管理与诊断 UI |

A1/A2 是首个用户可感知闭环；第三方 DAW 不阻塞 A0–A7。

