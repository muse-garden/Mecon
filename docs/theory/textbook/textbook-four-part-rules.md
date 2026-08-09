# 教材四部和声规则

> 代码入口：`theory/src/commonMain/kotlin/com/mecon/theory/textbook/FourPartTextbookRules.kt`

## 1. 分层原则

四部和声规则继续沿用“通用事实 API → 教材规则适配”的做法：

- `VoiceLeadingAnalysis` 负责固定声部纵向快照、密集/开放排列、声部交错事实、相邻声部间距和声部对运动事实。
- `FourPartTextbookRules` 只把教材禁则转成 `RuleFinding<EventId>`，旧 UI 仍可通过 `checkFixedVoiceScore()` 得到兼容的 `RuleDiagnostic<EventId>`。
- `FixedVoiceScore` 仍是入口；规则不直接读取 renderer 或 desktop 状态。

多声部进行不按事件序号配对。`VoiceLeadingAnalysis.verticalities()` 会收集所有固定声部事件的 `onset` 与 `endTime`，在每个变化点重新查询正在发声的音。因此当一个声部持音、另一个声部中途移动时，交错和间距仍按实际纵向关系判断。

## 2. 当前事实 API

- `VerticalArrangement.DENSE`：非低音声部间，所有相邻声部距离不超过纯四度。
- `VerticalArrangement.OPEN`：非低音声部间，存在相邻声部距离超过纯四度。
- `VoiceLeadingAnalysis.crossings(score)`：比较相邻纵向快照，返回高低位置发生互换的声部对；内声部交错本身只作为事实暴露。
- `VoiceLeadingAnalysis.pairMotions(score)`：比较相邻纵向快照中任意一对共同发声的声部，返回前后事件、运动方向、五类关系和前后拼写音程。
- `VoiceLeadingAnalysis.transitions(score)`：返回相邻纵向快照组成的 `FixedVoiceTransition`。
- `VoiceLeadingAnalysis.transitionsTouching(score, eventIds)`：只返回包含指定事件的 transition，供搜索器或局部重查使用。
- `VoiceLeadingAnalysis.outerBoundaryCrossings(verticality)`：检查是否有声部高于 `SOPRANO` 或低于低声部。
- `VoiceLeadingAnalysis.nonLowAdjacentSpacingViolations(verticality)`：检查非低音声部相邻距离是否超过八度。

低声部角色包括 `BASS` 与 `BARITONE`。当前四部键盘缩谱默认仍映射到 `BASS`，`BARITONE` 用于后续更细的人声或器乐配置。

`VoicePairMotionKind` 将任意一对声部的连续运动分为五类：

- `HOLD`：两声部都不改变音高。
- `OBLIQUE`：只有一个声部改变音高。
- `CONTRARY`：两个声部向相反方向运动。
- `SIMILAR`：两个声部同向运动，但移动音程不同。
- `PARALLEL`：两个声部同向运动，且移动音程相同。

## 3. 当前规则

`FourPartTextbookRules.checkFixedVoiceScore(fixed, rangeProfile)` 当前覆盖：

- `OUTER_VOICE_CROSSING`：禁止任何声部交错至高音声部上方，或低音声部下方；`HARD`。
- `UPPER_VOICE_SPACING`：除低音声部外，任何两个相邻声部间距离保持在八度以内；`HARD`。
- `VOICE_RANGE`：按当前 `VoiceRangeProfile` 检查声部音域；`HARD`。
- `PARALLEL_FIFTH`：两声部前后均相距纯五度并作平行进行；复合纯五度同样计入；若通过把某个声部终点改到另一八度来规避平行五度，也按同一规则诊断；`HARD`。
- `PARALLEL_OCTAVE`：两声部前后均相距纯同度或纯八度并作平行进行，两个八度等复合八度同样计入；若通过改变八度来规避平行八度，也按同一规则诊断；`HARD`。
- `UNEQUAL_FIFTH`：低声部与其他声部由减五度同向进行到纯五度；`SOFT`。
- `HIDDEN_FIFTH`：外声部同向进入纯五度，且高声部跳进；`SOFT`。
- `HIDDEN_OCTAVE`：外声部同向进入纯同度或纯八度，且高声部跳进；`SOFT`。

默认人声音域由 `VoiceRangeProfile.humanFourPart()` 给出：

- Soprano: `C4-G5`
- Alto: `G3-D5`
- Tenor: `C3-E4`
- Bass / Baritone: `E2-C4`

若用于器乐写作或不同教材，可传入新的 `VoiceRangeProfile` 覆盖任意角色范围。

## 4. 局部检查

全谱检查：

```kotlin
FourPartTextbookRules.checkFixedVoiceScore(fixed)
FourPartTextbookRules.checkFixedVoiceScoreFindings(fixed)
```

搜索或局部编辑检查：

```kotlin
VoiceLeadingAnalysis.transitionsTouching(fixed, changedEventIds)
    .flatMap { transition -> FourPartTextbookRules.checkFixedVoiceTransition(transition) }
```

规则新增时应优先抽成可复用的局部事实判断。平五、平八、隐伏五八、不相等五度等声部进行规则只需要当前 `FixedVoiceTransition`；音域和上方三声部距离只需要当前 verticality。搜索器应用候选后应检查候选影响到的 slice/transition，而不是调用全谱扫描。

## 5. 测试约定

新增四部和声规则时至少覆盖：

- 一个规则触发测试；
- 一个合法例外测试；
- 若规则涉及声部进行，覆盖不同声部事件不完全对齐的情况。
- 若规则会用于搜索剪枝，覆盖 `checkFixedVoiceTransition()` 或对应局部入口。
