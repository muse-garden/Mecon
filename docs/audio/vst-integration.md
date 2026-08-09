# Rust 乐器引擎与演奏控制集成

> 状态：🚧 长期方案评审稿；基础音高/时值的可选 Rhody 过渡接入已实现（2026-08-04）
> 范围：Mecon 乐谱语义、控制轨、播放编译、Rust backend 与共享 Kotlin UI。
> Rust 侧总设计：[Rust VST 与宿主集成](../../../vst-experiment/docs/18-Rust-VST与宿主集成.md)、
> [控制标定与机器学习发布](../../../vst-experiment/docs/19-控制标定与机器学习发布.md)；
> 混响、preview 与后台 patch 见 [adaptive-rendering.md](adaptive-rendering.md)。

## 1. 现状审计

已有基础：

- `StorageScore.controllerTracks`、`ControllerScope` 和 `StorageControllerEvent` 已能保存力度切换与
  hairpin 起止意图；力度/hairpin attachment 留有 controller event ID。
- `StorageDynamicMark`、`StorageHairpin`、pitch articulation、slur/tie、tempo 和 transposition 已有
  存储或运行时表示，可作为演奏编译输入。
- `AudioEngine`、`ScoreToMidiConverter` 与 JVM sequencer 已完成基本音符/tempo 播放。
- JVM 可在运行时发现可选 `rhody_bridge`，由 `RhodyMidiReceiver` 按 GM program 分流基础
  note-on/note-off；Rhody 未实现的乐器与缺库/初始化失败继续使用 MS Basic。

必须补齐的缺口：

1. Controller event 只硬编码 `SET_DYNAMIC/RAMP_START/RAMP_END`，没有通用维度、数值、曲线、
   插值、来源、优先级和 note scope。
2. `RuntimeScore` 直接透传 storage controller；没有解析 scope、校验引用、区间查询或播放派生层。
3. `ScoreToMidiConverter` 固定 velocity，未消费 controller、articulation、slur/tie，也没有 per-note
   identity；MIDI CC 不能无损表达目标引擎控制。
4. attachment 与 controller 双向/双份意图容易失配；MusicXML import 当前只生成 attachment，
   不生成 controller event，UI 中也没有统一的原子双写入口。
5. 乐谱没有稳定的 playback instrument assignment；pitch track 顺序映射 16 个 MIDI channel，
   不足以支撑多谱表乐器、同音重叠、打击乐与超过 16 条轨道。
6. 当前过渡接入仍由 `AudioEngine` 绑定 RuntimeScore→MIDI、JVM synth 与逐 block JavaSound 输出，
   不适合作为带 sample-accurate 控制和共享空间声场的最终原生物理引擎抽象。

结论：保留“控制轨 + scope”方向，但必须把它从力度占位符扩展为通用语义 automation，并新增独立的
`PerformancePlan` 派生分支；不能把播放逻辑放进 Renderer 或复用渲染 `ComputedScore`。

## 2. 新的数据流

```text
StorageScore（记谱事实 + 显式控制 + playback routes）
    │ RuntimeScore.fromStorage：解析引用、建立时间索引
    ▼
RuntimeScore
    │ PerformanceCompiler（纯函数、可测试）
    ▼
PerformancePlan（播放派生层）
    │ backend prepare(sampleRate, blockSize)
    ▼
ScheduledPerformance（sample frame / block offset）
    ├── RustInstrumentBackend → Rust engine → audio device
    └── MidiPlaybackBackend   → 兼容/导出用的有损 MIDI 映射
```

`PerformancePlan` 与页面排版无关，也不写回乐谱。它承担播放语义中与 `ComputedScore` 类似的职责：
解析继承/作用域、补齐 hairpin 终点、识别 legato transition、生成稳定 note ID，并把来源 ID 留给播放高亮。

## 3. 模块划分

建议新增：

```text
performance/                         纯 KMP 播放语义和编译器，依赖 :api
├── model/                            PerformancePlan、routes、events、curves
├── compile/                          PerformanceCompiler、scope/merge/diagnostics
└── protocol/                         Rust 协议 adapter（生成类型隔离）

audio/                               transport facade + backend SPI
├── commonMain/.../PlaybackBackend   不再假定 MIDI
├── commonMain/.../MidiPlaybackBackend（保留兼容路径）
└── jvmMain/.../RustInstrumentBackend（JNI，批量命令）

apps/instrument-ui-kit/              共享 Compose 面板/ViewModel/controller 接口
apps/instrument-controller/          独立 Compose Desktop 应用（IPC adapter）
apps/desktop/                         嵌入同一面板（in-process adapter）
```

依赖方向：`apps → audio / instrument-ui-kit → performance → api`；Rust 生成协议类型不能进入 `api`
存储模型。`:renderer` 完全不依赖 performance/audio。

## 4. 存储模型扩展

### 4.1 控制轨

用通用 lane/point/segment 替代硬编码 event type：

```kotlin
@Serializable
data class StorageControllerTrack(
    override val id: TrackId,
    override val name: String,
    val scope: ControllerScope,
    val lanes: List<StorageControlLane>,
)

@Serializable
data class StorageControlLane(
    val id: ControlLaneId,
    val dimension: ControlDimension,
    val mergeMode: ControlMergeMode = SET,
    val events: List<StorageControlEvent>,
)

@Serializable
sealed interface StorageControlEvent {
    val id: EventId
    val onset: TimeCode
    val source: ControlSourceRef?
}

data class ControlPoint(..., val normalized: Float, val interpolation: Interpolation)
data class ControlSegment(..., val endOnset: TimeCode, val endValue: Float,
                          val shape: CurveShape)
data class ArticulationControl(..., val intent: ArticulationIntent)
```

约束：

- well-known dimension 使用枚举；扩展使用 namespaced ID，未知扩展必须可无损往返。
- 值采用归一化语义域；乐谱文件不保存 `reed_zeta` 等具体合成器旋钮。
- scope 新增 `voiceTrackIds` 和可选 `noteEventIds`；旧 `voiceNumbers` 只用于迁移，不能作为长期身份。
- segment 自带开始/结束和值/shape，不使用分离的 RAMP_START/RAMP_END 依赖配对状态。
- `source` 单向指向 notation attachment/note；attachment 上旧 controller ID 只为兼容读取，不再要求双写。

### 4.2 记谱符号是事实来源

- 动态、hairpin、articulation、slur/tie 直接由 `PerformanceCompiler` 解释，即使 controller track 为空
  （MusicXML 导入也可立即播放）。
- controller track 只保存显式用户曲线、符号解释 override 或未来录制/live automation。
- 迁移旧文件时，将可验证的 controller placeholder 转为来源关联；失配时以 notation 为准并产生 diagnostic。
- 新增/移动/删除符号不需要维护另一套等价事件，从根源上避免孤儿引用。

### 4.3 乐器与播放 route

在 `StorageScore` 新增默认值为空的 `playbackConfig`：

```kotlin
data class StoragePlaybackConfig(
    val routes: List<StoragePlaybackRoute> = emptyList(),
)

data class StoragePlaybackRoute(
    val id: PlaybackRouteId,
    val scope: ControllerScope,
    val instrumentId: String,       // 例 org.mecon.clarinet.bb
    val presetId: String? = null,
    val outputBus: Int = 0,
    val parameterOverrides: Map<String, Float> = emptyMap(),
)
```

route 是播放配置，不替代谱表 transposition。MusicXML part/instrument 元数据未来导入为 route；若缺少 route，
编译器产生 `missing_instrument_route`，UI 让用户选择，不按 map 顺序猜 General MIDI channel。

## 5. PerformancePlan

核心类型：

```kotlin
data class PerformancePlan(
    val schemaVersion: Int,
    val ticksPerQuarter: Int,
    val tempoMap: List<TempoPoint>,
    val routes: List<PerformanceRoute>,
    val events: List<PerformanceEvent>,
    val controlCurves: List<SemanticControlCurve>,
    val diagnostics: List<PerformanceDiagnostic>,
)
```

事件至少包括 `NoteOn/NoteTransition/NoteOff`，使用稳定 64 位 note ID，并保留 source pitch/voice/staff ID。
control curve 使用 musical tick；Rust backend 在 sample rate 已知后转换为 sample frame。计划必须可序列化为固定
fixture，以便 Kotlin 编译器与 Rust 协议做金标准测试。

## 6. 编译规则

### 6.1 力度与 hairpin

- DynamicLevel 先映射到单调的语义 anchor（具体响度/音色仍由乐器 calibration pack 决定）。
- hairpin 起点取当前位置有效 dynamics；终点优先使用区间结束处/之后第一个动态记号。
- 没有终点动态时使用配置的语义 delta，并输出 `inferred_hairpin_target` diagnostic；不凭 wedge 长度猜 dB。
- 相邻 hairpin/point 先按 scope 分组，再按明确优先级合并；冲突不静默覆盖。

### 6.2 奏法、slur 与 tie

- note articulation 生成 note-scoped intent/envelope；accent/marcato 主要修改 attack，不永久抬高整条 dynamics。
- slur 内相邻非休止音优先编译为 `NoteTransition`；staccato、breath/caesura 或显式 re-articulation 可打断。
- tie 合并同音持续时长并保持 note ID；let-ring/release 使用独立尾音策略。
- ornament/technique 先保留结构化 intent；未支持的乐器返回 capability diagnostic，不退化成错误音符流。

### 6.3 控制合并

顺序固定为 notation base → explicit automation → ML residual（Rust）→ live override → safety clamp。
Kotlin 只编译前两层；ML 和安全限制属于 Rust control runtime，避免两边重复映射旋钮。

## 7. Audio facade 重构

2026-08 的首版 `RhodyOutput`/`RhodyMidiReceiver` 是这一重构前的窄适配层：它保持现有 sequencer
作为音高/时值时钟，通过 JNA 动态加载 bridge，并逐 program 回退 MS Basic；不包含参数自动化、
奏法、共享空间处理或 UI，也不建立前端到 Rhody 工程的构建依赖。以下仍是长期目标。

`AudioEngine` 保留 UI 需要的 transport StateFlow，但内部改为可选择 backend：

```kotlin
interface PlaybackBackend {
    val capabilities: BackendCapabilities
    suspend fun prepare(plan: PerformancePlan): AudioResult<Unit>
    fun play(); fun pause(); fun stop(); fun seekTo(tick: Long)
    fun submitParameterPatch(patch: ParameterPatch)
    suspend fun close()
}
```

`RustInstrumentBackend` 通过 JNI 一次提交整个 plan/批量 patch，Rust 持有音频设备和实时线程；不从 JVM
逐 block 拉/推 `FloatArray`。当前 `JvmAudioEngine` 变成 `MidiPlaybackBackend`，用于无原生库 fallback、
MIDI 导出和行为对照；`ScoreToMidiConverter` 改为从 PerformancePlan 有损降级，而不是继续独立解释乐谱。

## 8. UI 与状态所有权

共享 `InstrumentPanel` 只读取 Rust `EngineManifest`，不在 Kotlin 硬编码旋钮范围：

- 嵌入模式：`apps:desktop` 提供当前 route/selection/transport，控制器走 JNI backend。
- 独立模式：`apps:instrument-controller` 通过本地 IPC 发现 Rust standalone/VST 实例。
- Rust engine 是运行参数事实来源；Compose ViewModel 镜像 state，参数手势使用 begin/set/end，避免撤销栈
  和宿主 automation 产生上百个离散点。
- 乐谱中的显式 automation 进入 ScoreStateManager 撤销栈；临时 audition/knob tweak 默认不改谱，用户执行
  “写入控制轨”时才持久化。

## 9. 兼容与迁移

1. 所有新顶层字段有默认值，旧 `.mecon` 可读取。
2. `ControllerEventType` 读取适配为新 lane；写出新格式前由 score format version 决定是否升级。
3. 旧 attachment controller ID 可读可 round-trip；新编辑不再创建反向 ID。
4. 缺 route 时 MIDI fallback 可临时使用默认 piano，但 Rust backend 必须提示并拒绝静默选错乐器。
5. 保存前运行 controller/route validator：无效 scope、越界值、倒置 segment、未知 source 均结构化报告。

## 10. 测试与实施顺序

| 阶段 | 测试 |
|---|---|
| P0 schema/迁移 | 旧 YAML round-trip、MusicXML 只有 attachment 仍生成 controls、未知 dimension 保留 |
| P1 compiler | dynamics/hairpin 推断、scope 优先级、slur/tie/articulation、tempo/seek 金标准 |
| P2 protocol | Kotlin ↔ Rust fixture、版本拒绝/降级、manifest 不重复定义 |
| P3 backend | JNI 生命周期、批量 plan、播放/暂停/seek、Rust 崩溃/缺库 fallback |
| P4 UI | 同一 Composable 在独立/嵌入 adapter contract test，写入控制轨进入撤销栈 |
| P5 E2E | 固定 score → PerformancePlan → Rust WAV，比较事件、特征、无卡音与播放高亮 |

实施必须按 P0→P5；在 PerformancePlan 和协议稳定前不先把 UI 直接接某个 FluteParams。首个切片只支持
长笛，且以 Mecon 直连为第一优先级；第三方 DAW/VST3 后置。数据结构和 capability 协商从第一天就是
多乐器/多 route。

## 11. 文档同步清单

实现时必须同步更新：

- `docs/data_model/storage.md`、`runtime.md`、`score-format.md`；
- `docs/audio/README.md` 与本文件；
- `docs/ARCHITECTURE.md` 模块/数据流；
- MusicXML instrument/dynamics/technique 导入导出说明；
- Rust 协议、参数 manifest、calibration pack 与 release 文档。
