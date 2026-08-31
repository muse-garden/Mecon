# 音频引擎 (Audio)

> 模块：`audio/src/`
>
> **状态**：✅ JVM MIDI 基础播放已实现；🚧 音色管理、精确 Solo/Mute、跨平台实现均在计划中。
>
> Rust 物理乐器引擎、通用控制轨、PerformancePlan、JNI backend 与共享 Kotlin UI 的评审设计见
> [vst-integration.md](vst-integration.md)。该设计保留本页 MIDI 实现作为 fallback/导出路径，
> 但不再把 MIDI 作为高保真控制协议。舞台混响、preview pack、即时试听、后台渲染与 patch 见
> [adaptive-rendering.md](adaptive-rendering.md)。

## 1. 接口定义 (`AudioEngine`)

> 路径：`audio/src/commonMain/.../engine/AudioEngine.kt`

```kotlin
interface AudioEngine {
    val playbackState:        StateFlow<PlaybackState>    // IDLE / PLAYING / PAUSED
    val currentPositionTicks: StateFlow<Long>             // 50ms 轮询
    val tempoMultiplier:      StateFlow<Float>
    val masterVolume:         StateFlow<Float>
    val soundFontManager:     SoundFontManager?

    fun loadScore(runtime: RuntimeScore)
    fun play()
    fun pause()
    fun stop()
    fun seekTo(positionTicks: Long)

    fun setTrackMuted(trackId: TrackId, muted: Boolean)   // 🚧 占位实现
    fun setTrackSolo(trackId: TrackId, solo: Boolean)     // 🚧 占位实现
}
```

## 2. JVM 实现 (`JvmAudioEngine`)

> 路径：`audio/src/jvmMain/.../JvmAudioEngine.kt`

- transport 使用 `javax.sound.midi.Sequencer`；Windows x64 发声端优先使用内置 FluidSynth 2.5.6，初始化失败时回退系统 `Synthesizer`
- `loadScore()` 把 `RuntimeScore` 转换为 MIDI 序列（`javax.sound.midi.Sequence`）
- `currentPositionTicks` 通过 50ms 定时协程轮询 `sequencer.tickPosition`
- `setTrackMuted / setTrackSolo` 是占位实现（调用即记录但不真实静音轨道）

**已知限制**：
- JVM Sound API 使用系统默认 MIDI 合成器，音色依平台而定
- Solo 实际上需要给每条 MIDI 轨道调 `sequencer.setTrackMute()`，当前未接入

## 3. 状态流

```
AudioEngine.loadScore(runtime)
    └── buildMidiSequence(runtime) → javax.sound.midi.Sequence
              ↓
    sequencer.setSequence(sequence)

play()  → sequencer.start() + 开始轮询协程
pause() → sequencer.stop()
stop()  → sequencer.stop() + 重置位置

currentPositionTicks 每 50ms 由轮询协程更新
```

UI 层（`App.kt`）用 `collectAsState()` 订阅 `playbackState` 与 `currentPositionTicks` 驱动进度条。

### 3.1 连音线

`ScoreToMidiConverter` 以 pitch event 驱动，而连音线记在 voice event 上。转换器按声部内
「下一个含同一音高的 pitch event」跟踪连音链：**续接音不发 note-on，链首的 note-off 顺延到链尾**，
因此一条连音链只击发一次。匹配不上的链（跨谱表等复杂情形）保持未连音行为，只在确定时才合并。

这条在 2026-08-31 前是缺失的：任何带连音线的乐谱都会重复击发。它在圣咏配和声上尤其致命——
延留音被重新击发就成了倚音，模块要表达的音响差别正好听不出来。门禁见
`ScoreToMidiConverterTest.tiedNotesSoundOnceAndHoldForTheWholeChain`。

## 4. 扩展计划 🚧

### 4.1 SoundFont 与乐器映射

StorageInstrument.playback 保存 MIDI bank/program，并为 SoundFont 与 VST 保留 soundFontId、pluginId 和 pluginState。ScoreToMidiConverter 让同一乐器的多行谱表共享 MIDI 通道，并在 tick 0 写 Program Change；普通音高乐器会跳过 General MIDI 保留给打击乐的第 10 通道（零基 channel 9）。

桌面包内置 `apps/desktop/src/main/resources/soundfonts/MS Basic.sf3`。`JvmSoundFontManager` 首次启动时将其释放到 `~/.mecon/soundfonts/`，并作为基础 fallback 加载。Windows x64 包同时内置 FluidSynth 2.5.6（LGPL 2.1）及依赖；运行时释放到 `~/.mecon/native/`，SF3 不转换为展开后的 SF2。

JVM 首版可用 `-Dmecon.audio.defaultLibrary=rhody|ms-basic`（或环境变量 `MECON_AUDIO_DEFAULT_LIBRARY`）选择默认音色库，未设置时优先 Rhody。该选择暂不接 UI。Rhody 通过 JNA 在运行时发现 `rhody_bridge`，可用 `-Dmecon.rhody.library=<文件或目录>` / `MECON_RHODY_LIBRARY` 显式指定；否则检查工作目录、`native/`、`lib/`、`java.library.path` 以及相邻开发树的 `vst-experiment/rhody/target/{release,debug}`。前端没有 Gradle/Cargo 项目依赖，找不到或打不开 native library/JavaSound 输出时整库回退 MS Basic；库存在时，钢琴、Rhody 有音高打击乐、管风琴、长笛和短笛按 GM program 分流，未实现的乐器与 MIDI 第 10 通道逐通道回退 MS Basic。

此版本只传 MIDI 音高、velocity 与 note-on/note-off 时值，tempo/transport 继续由现有 JVM sequencer 驱动；Rhody 参数自动化、奏法、空间声场和设置 UI 留给后续版本。

FluidSynth 启用 `synth.dynamic-sample-loading`。打开或新建乐谱后，`ScoreFileController` 立即让 `PlaybackController.preloadScore` 转换 MIDI、收集全部 Program Change，并由 `JvmAudioEngine` pin 本谱使用的 preset；首次点击播放复用已加载的 score，不再次准备样本。准备期间 `SoundFontLoadState.Loading(current, total)` 发布 preset 进度，桌面顶栏显示进度条并禁用播放入口。试听首次遇到尚未准备的 preset 时也走同一 pin 机制，已 pin 样本保持到 SoundFont 卸载或音频引擎关闭。其他桌面平台当前仍使用系统 MIDI fallback；SF3 会明确报告需要 FluidSynth，不再交给 Gervill 播放噪声。

原生文件来源与许可证：`apps/desktop/src/main/resources/native/`。升级 FluidSynth 时须同步更新版本常量、二进制、许可证和 Windows 集成测试。

新建乐谱使用 `NewScoreInstruments.kt` 中的 `ScoreInstrumentCatalog` 与 `MsBasicCatalog`：记谱乐器和 MS Basic patch 是多对多关系（例如各弦乐器同时关联独奏音色与两种弦乐合奏音色）。当前创建时写入列表中的默认 program；后续奏法路由应在同一映射上选择其他 patch，不把奏法固化进乐器目录。

### 4.2 轨道 Mute / Solo

在 `setTrackMuted()` 中实际调用：

```kotlin
val midiTrackIndex = trackToMidiIndex[trackId] ?: return
sequencer.setTrackMute(midiTrackIndex, muted)
```

需要在 `buildMidiSequence()` 时维护 `trackId → MIDI Track Index` 映射。

### 4.3 跨平台实现

| 平台 | 推荐方案 | 状态 |
|------|---------|------|
| JVM (Desktop) | `javax.sound.midi` / Gervill SF2 | ✅ 基础版 |
| Android | Oboe (C++ JNI) / MediaPlayer MIDI | 🚧 未开始 |
| iOS | AVFoundation / AVMIDIPlayer | 🚧 未开始 |
| Web | Web Audio API + MIDI.js | 🚧 未开始 |

跨平台统一由 `expect class AudioEngineFactory` 的 `actual` 实现封装，业务层只持有 `AudioEngine` 接口。

### 4.4 实时演奏（低延迟路径）

桌面端可用：

- `javax.sound.midi.MidiDevice` 直接连接 USB MIDI 设备
- Oboe (via JNI) 实现 < 20ms 延迟

低延迟路径与播放路径应解耦，各自维护独立的输出管道。

## 5. 时间坐标对齐

`RuntimeScore` 中 `TimeCode` 与 MIDI tick 的转换：

```
ticks = timeCode.toFraction().toFloat() * (resolution * 4)
// resolution = 每四分音符的 MIDI tick 数（通常 480 或 1024）
```

`Duration.totalTicks`（Mecon 内部 tick，全音符 = 4096）与 MIDI tick 按 `resolution / 1024` 换算。

### 5.1 反复、房子与导航

`ScoreToMidiConverter.playbackTimeline()` 先按 `RuntimeMeasure.repeatStart/repeatEnd/repeatCount`
展开小节顺序，再把音符与速度事件复制到演奏时间线。未遇到显式开始反复的结束记号从乐谱开头
回跳；`repeatCount` 表示包含首次演奏在内的总次数，最小为 2。

谱面仍使用原始时间坐标。播放光标通过 `PlaybackTimeline.sourceTicksAt()` 把展开后的 MIDI tick
映回当前原谱小节，因此第二次反复时光标会回到反复段开头；“从选区播放”则使用
`timeCodeToPlaybackTicks()` 定位该小节在展开时间线中的首次出现。

`voltaNumbers` 按当前反复遍数筛选小节，因此第一遍跳过二房子、后续遍数跳过不匹配的房子。
导航指令只执行一次：D.C. 回到开头，D.S. 回到 Segno；al Fine 在第二轮遇到 Fine 时结束，
al Coda 在第二轮遇到 To Coda 时跳到 Coda。转换器设有演奏小节数上限，损坏或自相矛盾的
导入数据不会造成无限循环。
