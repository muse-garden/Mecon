# 教材旋律规则

> 代码入口：`theory/src/commonMain/kotlin/com/mecon/theory/textbook/MelodyTextbookRules.kt`

## 1. 分层原则

教材规则实现只负责把书里的表述转成规则诊断、严重度和例外条件。它应调用 [melody-analysis.md](../melody-analysis.md) 中的通用事实 API，不再定义平行的旋律事件结构，也不在通用 theory 层写入“某教材认为违规”的判断。

新增教材内容时遵循：

1. 先确认通用事实 API 是否存在。
2. 缺事实先补 `MelodyAnalysis` 或其他 theory 通用能力。
3. 教材层只写很薄的规则适配。
4. 严重度按 [../../ai/roadmap.md](../../ai/roadmap.md) 的 `HARD` / `SOFT` / `HINT` 软约束设计。

教材里的“必须/避免”不自动等于 `HARD`。`HARD` 留给严格练习、搜索剪枝或数据结构上不合法的情况；普通风格建议先以 `SOFT` / `HINT` 表达，之后由 preset 调权。

## 2. 当前规则

`MelodyTextbookRules` 当前覆盖：

- `UNIQUE_CLIMAX`：最高点应尽量唯一；默认 `SOFT`。
- `MOSTLY_STEPWISE`：级进比例过低时给 `SOFT` 诊断。
- `CONTOUR_CLARITY`：长时间同向或连续反向过多给 `HINT`。
- `DISCOURAGED_LEAP`：增音程、七度、大于纯八度的跳进给 `SOFT`。
- `DIMINISHED_LEAP_RESOLUTION`：减音程跳进若未立即反向级进，给 `SOFT`。
- `CONSECUTIVE_LEAP_TRIAD_OUTLINE`：同方向连续较小跳进不构成三和弦外形时给 `SOFT`。
- `LEADING_TONE_RESOLUTION`：7 级未上行到 1 级给 `SOFT`，但 `1-7-6-5` 下行音阶例外。
- `FOURTH_DEGREE_RESOLUTION`：4 级未下行到 3 级给 `HINT`，弱于 7-1。

## 3. FixedVoiceScore 入口

和声写作场景应优先使用：

```kotlin
MelodyTextbookRules.checkFixedVoiceScore(fixed, key)
```

这个入口直接消费 `FixedVoiceScore.noteEventsForVoice()` 返回的 `FixedVoiceScoreEvent`：

- 高潮点原则只应用于 `FixedVoiceRole.SOPRANO`。
- 级进、跳进、连续跳进外形、倾向音解决等原则应用于所有固定声部。
- 诊断锚点使用原始 `EventId`，方便 UI 和未来 MCP 工具回指谱面。

如果只是测试一串音高或构造临时工具，可用 `checkPitches(pitches, key)`；如果已有其他原始事件类型，可用泛型 `checkVoice(items, key, pitchOf, anchorOf)`。

## 4. 测试约定

教材规则测试放在 `MelodyTextbookRulesTest`。新增规则时至少覆盖：

- 一个规则触发测试；
- 一个规则例外或关闭测试；
- 若规则面向和声写作，覆盖 `checkFixedVoiceScore()` 的 `EventId` 锚点和声部应用范围。
