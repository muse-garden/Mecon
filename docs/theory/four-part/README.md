# 固定声部与四部和声基础

> 状态：第一阶段已落地为 `:theory` 中的固定声部视图与拼写敏感音程 API。

## 目标

四部和声、复调分析和教材规则引擎不直接消费完整渲染结果，而是消费缩谱后的固定声部乐谱：

- 每行谱表声明固定声部数。
- 单个声部同一事件只能是单音或休止，禁止和弦。
- 分析层可按声部查询前后音，也可按时间查询纵向同时发声的其他声部。

这对应 `todos/polyphony.md` 中的缩谱输入。当前实现只处理“已经缩减好”的乐谱视图；从总谱 pick、映射关系、多对多八度重复检测仍属于后续缩谱编辑功能。

## 当前 API

代码入口：

- `theory/.../FixedVoiceScore.kt`
- `theory/.../SpelledInterval.kt`

固定声部载入：

```kotlin
val layout = FixedVoiceLayout.fourPartKeyboard(
    score,
    FourPartKeyboardDistribution.TREBLE_2_BASS_2,
)
val fixed = FixedVoiceScore.load(score, layout)
```

四部键盘缩谱支持两种分布：

- `TREBLE_3_BASS_1`：高音谱表 S/A/T，低音谱表 B。
- `TREBLE_2_BASS_2`：高音谱表 S/A，低音谱表 T/B。

查询能力：

- `previousInVoice(event)` / `nextInVoice(event)`：同一固定声部的相邻事件。
- `eventsForVoice(voice)` / `noteEventsForVoice(voice)`：按固定声部取原始事件或过滤后的音符事件。
- `eventsSoundingAt(time)` / `notesSoundingAt(time)`：某时刻所有固定声部事件/音。
- `simultaneousNotes(event, includeSameStaff)`：某个事件起点处的纵向同时音，可选择只看其他谱表。
- `VoiceLeadingAnalysis.verticalities(score)`：按所有声部的 onset/endTime 建立纵向快照，适用于声部事件不完全对齐的进行分析。
- `VoiceLeadingAnalysis.arrangementOf(notes)`：按非低音声部相邻距离区分密集排列与开放排列。
- `VoiceLeadingAnalysis.pairMotions(score)`：按任意一对共同发声声部输出保持、斜向、反向、同向、平行五类运动，以及前后拼写音程。

## 桌面端交互

桌面右侧检查器通过 `:plugins:theory-analysis:desktop` 接入“乐理分析”插件面板：

- 面板只保存 UI 临时选择，不写入乐谱文件；当前可在 `高2/低2` 与 `高3/低1` 两种键盘缩谱分配之间切换。
- 每次 `RuntimeScore` 或分配方式变化时，面板即时调用 `FixedVoiceScore.validate/load`。
- 可分析时显示声部到谱表/voice 的映射；选中固定声部中的音符后，显示同声部前后音与起点处纵向同时音。
- 不可分析时显示 `FixedVoiceLoadDiagnostic` 摘要，例如谱表数量不够、某谱表声部数不匹配、单声部中包含和弦事件。

这个接入只消费 theory 的固定声部视图，不在 UI 重新实现声部规则。后续如果需要把缩谱配置持久化，应先补 Storage/PluginTrack 层的数据模型，再让面板读写该模型。

载入失败会抛出 `FixedVoiceScoreException`，诊断项包括：

- `STAFF_NOT_FOUND`
- `STAFF_VOICE_COUNT_MISMATCH`
- `CHORD_IN_MONOPHONIC_VOICE`

## 声部进行与教材规则

四部和声规则已通过 `textbook.FourPartTextbookRules.checkFixedVoiceScore(fixed, rangeProfile)` 接入固定声部视图：

- 禁止声部交错到 `SOPRANO` 上方或低声部下方；内声部间暂时交错只作为事实暴露，不单独报错。
- 除低音声部外，相邻声部间距必须保持在八度以内。
- 默认人声音域为 Soprano `C4-G5`、Alto `G3-D5`、Tenor `C3-E4`、Bass/Baritone `E2-C4`；可用 `VoiceRangeProfile` 替换为器乐范围。
- 禁止平行五度、平行八度/同度，包含复合纯五度与复合八度。
- 避免低声部与其他声部由减五度同向进入纯五度。
- 避免外声部同向进入纯五度或纯八度且高声部跳进的隐伏五度/八度。

声部进行检查按纵向快照工作，而不是按每个声部第 N 个音直接相互配对，所以一个声部持音、另一个声部移动时仍能正确发现交错与间距问题。详见 [../textbook/textbook-four-part-rules.md](../textbook/textbook-four-part-rules.md)。

## 音程 API

`SpelledInterval` 不只记录半音数，还记录：

- `number`：音程度数，如三度、四度、七度。
- `base`：`PERFECT` / `MAJOR` / `MINOR`。
- `offset`：相对基础性质的偏移。例：纯四度 `offset = 0`，增四度 `offset = 1`；小七度 `offset = 0`，减七度 `offset = -1`。
- `direction`：上行或下行。

因此 `C4-E#4` 会被识别为增三度，而 `C4-F4` 是纯四度；二者 `isEnharmonicallyEquivalentTo()` 为 true。

## 后续衔接

下一步可在此基础上添加：

- 教材章节规则预设：把《调性和声及20世纪音乐概述》的规则拆成可启停、可解释的 `TheoryRule`。
- 四部和声检查：导音解决、重复音限制等。
- 结构化文本输出：面向未来 MCP/LLM 的低 token 缩谱视图。

旋律类教材规则已通过 `MelodyTextbookRules.checkFixedVoiceScore(fixed, key)` 接入固定声部视图，诊断锚点保留原始 `EventId`。详见 [../textbook/textbook-melody-rules.md](../textbook/textbook-melody-rules.md)。
