# 旋律分析事实

> 代码入口：`theory/src/commonMain/kotlin/com/mecon/theory/MelodyAnalysis.kt`

## 1. 职责

`MelodyAnalysis` 只产出可复用的乐理事实，不判断某本教材的“应该/避免”。教材、四部和声、复调和动机分析都应复用这些事实 API，再在各自实现层决定规则 ID、严重度和例外条件。

这层当前提供：

- `highestItems(items, pitchOf)` / `peak(items, pitchOf)`：找最高音及所有原始条目。
- `motions(items, pitchOf)`：返回相邻条目的拼写敏感音程、方向、级进/跳进、增减音程、七度、大于八度等事实。
- `stepwiseRatio(items, pitchOf)` / `directionChanges(items, pitchOf)`：给“主要级进”“曲线清晰”类规则提供量化事实。
- `scaleDegree(pitch, key)` / `scaleDegrees(items, key, pitchOf)`：按调性给音级，非调内音返回 `-1`。
- `hasDescendingScaleFragment(items, key, startIndex, pitchOf)`：识别 `1-7-6-5` 下行音阶片段。
- `outlinesTriad(items, pitchOf)`：判断一组三音是否形成大、小、减、增三和弦外形。

## 2. 数据结构复用

旋律分析不定义独立的“旋律事件”包装结构。API 以泛型条目 `T` 加 `pitchOf: (T) -> Pitch` 工作：

```kotlin
MelodyAnalysis.peak(pitches, pitchOf = { it })
MelodyAnalysis.motions(fixed.noteEventsForVoice(soprano), pitchOf = { it.pitch!! })
```

因此调用方可以直接保留原始数据：

- 简单测试或工具可传 `List<Pitch>`。
- 固定声部规则可传 `List<FixedVoiceScoreEvent>`，继续使用原事件的 `EventId`、`onset`、`voice` 和上下文查询。

## 3. FixedVoiceScore 衔接

`FixedVoiceScore` 是固定声部分析的统一视图。为避免调用方直接操作 `eventsByVoice`，它提供：

- `eventsForVoice(voiceId)` / `eventsForVoice(voice)`
- `noteEventsForVoice(voiceId)` / `noteEventsForVoice(voice)`

教材规则、四部和声检查和未来复调检查都应从这些查询入口拿同声部事件，再把原始 `FixedVoiceScoreEvent` 交给 `MelodyAnalysis`。

## 4. 测试约定

通用事实测试放在 `MelodyAnalysisTest`。测试只断言事实，例如最高点位置、音程性质、音级片段和三和弦外形；不检查教材规则是否违规。
