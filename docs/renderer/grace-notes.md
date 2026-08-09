# 装饰音 (Grace Notes)

> 源文件：
> - 数据：`api/.../storage/events/StorageVoiceEvent.kt`（`GraceNoteInfo`）
> - Tie 规则：`core/.../engine/Computers.kt::TieTargetComputer.resolveGraceTieTarget`
> - 播放：`audio/.../converter/ScoreToMidiConverter.kt`
> - 渲染缩放：`renderer/.../layout/RenderConstants.kt`、`renderer/.../layout/NoteBodyElementBuilder.kt`、`renderer/.../elements/NoteElement.kt`、`renderer/.../layout/VoiceEventLayoutBuilder.kt`、`renderer/.../render/RenderEngine.kt`、`renderer/.../elements/FlagElement.kt`
> - 水平排版：`renderer/.../layout/UnifiedLayoutComputer.kt`
> - 钢琴卷帘：`apps/desktop/.../ui/views/pianoroll/PianoRollNotes.kt`

装饰音不是独立的事件类型——它沿用普通 `StoragePitchEvent / StorageVoiceEvent`，仅通过 `TimeCode` 的第三分量与首音上的 `GraceNoteInfo` 标记。

## 1. 位置编码

同一 `(measure, beat)` 下用 `[-1, 0)` 区间内的负分数表示装饰音位置（编码表见 [storage.md 4.2](../data_model/storage.md#42-装饰音-grace-notes)）。三分量 `TimeCode` 的自然排序保证 `(m, b, -k/N)` 排在本音 `(m, b)` 之前。

派生属性：

| 属性 | 来源 | 含义 |
|------|------|------|
| `RuntimeVoiceEvent.isGrace` | `onset.grace != null` | 是否属于装饰音组 |
| `RuntimeVoiceEvent.isGraceGroupStart` | `graceInfo != null` | 是否为组首（携带元数据） |
| `ComputedVoiceEvent.isGrace` | 同上 | Computed 层透传 |

## 2. GraceNoteInfo

```kotlin
data class GraceNoteInfo(
    val totalDuration: Duration,        // 整组占用的音乐时值
    val stealFrom: GraceTimeSource,     // PREVIOUS | PRINCIPAL
)
```

只挂在装饰音组**首音**的 `StorageVoiceEvent.graceInfo` 上，经 Runtime / Computed 层无修改透传。允许整组无对应本音（"孤悬装饰音"），播放与渲染都不做特殊处理。

## 3. 播放语义 (MIDI)

`ScoreToMidiConverter` 在两遍式时值解析的第二遍处理装饰音组（位于 `resolvePitchTimings`）：

1. 在 `pitchEvents` 中向后扫描同 `(measure, beat)` 的事件，把装饰音收入 `graceEvents`，第一个 `grace == null` 的视为 `principal`。
2. `totalDurationTicks = fractionToTicks(info.totalDuration)`，`perGraceTicks = totalDurationTicks / n`。
3. 按 `stealFrom` 分支：
   - **PREVIOUS**：`windowStart = principalTicks - totalDurationTicks`；前一同轨非休止事件的尾部缩短至 `windowStart`；装饰音依次落在 `[windowStart + k·perGrace, ...)`；本音时间不变。
   - **PRINCIPAL**：装饰音落在 `[principalTicks + k·perGrace, ...)`，本音 `onset` 推迟到 `principalTicks + totalDurationTicks`。

无本音时整组退化为占位事件，不修改邻音；该路径的实际行为以 `ScoreToMidiConverterTest::testGrace*` 为准。

## 4. 渲染缩放（NoteScale）

缩放因子统一用 `NoteScale`（`geometry/NoteScale.kt`）表示：

```kotlin
@JvmInline value class NoteScale(val value: Float) {
    companion object {
        val NORMAL = NoteScale(1f)
        val GRACE  = NoteScale(RenderConstants.GRACE_NOTE_SCALE)  // 0.6f
    }
}
```

`NoteElement.create()` 读取 `event.isGrace`，构造 `NoteScale(config.graceNoteScale)` 存入 `NoteElement.noteScale`，同时把 `noteScale.value`（Float）传给 `NoteBodyElementBuilder`。缩放覆盖所有与音符比例相关的视觉元素：

| 元素 | 实现位置 | 缩放方式 |
|------|---------|---------|
| 符头 / 休止符 / 附点 / 临时记号 glyph | `NoteBodyElementBuilder` | `GlyphGeometry.fromBBox(..., scale)` |
| 临时记号宽度 | `NoteBodyElementBuilder` | `accidentalWidth(...) * scale` |
| SMuFL 符干锚点 | `NoteBodyElementBuilder` | `anchor.x.value * scale`（未缩放则符干脱钩） |
| 符干长度 | `VoiceEventLayoutBuilder` | `config.stemLength * noteScale.value` |
| 符干粗细 | `RenderEngine` | `stemThickness * noteScale.value` |
| 符尾 glyph 字号 | `FlagElement` | `StaffSpace(4f * scale)` |
| 符杠厚度 / 间距 | `RenderEngine` → `BeamGroupElement` | `beamThickness/beamSpacing * noteScale.value` |
| 符杠最小符杆长约束 | `BeamGroupProcessor` | `minStemLength` 各项按 scale 缩放（见 [stem-and-beam.md §5.1](stem-and-beam.md)） |

**渲染不同大小的音符**：对 `NoteElement` 设置 `noteScale` 即可，无需修改任何渲染逻辑。

```kotlin
// 装饰音（由 NoteElement.create 自动处理）
NoteElement.create(event, ..., config)    // event.isGrace → noteScale = NoteScale.GRACE

// 提示音 / 小音符（手动构造时）
NoteElement(
    ...,
    noteBody  = NoteBodyElementBuilder(config, scale = 0.75f).buildNoteGeometry(...),
    noteScale = NoteScale(0.75f),
)
```

`RenderConstants` 中与水平间距相关的常量（`GRACE_NOTE_SPACING`、`GRACE_NOTE_PRINCIPAL_GAP`）仍为装饰音专用；其他缩放类型如有不同间距需求，应在 `RenderLayoutConfig` 中另行声明。

## 4.1 水平排版

`UnifiedLayoutComputer` 把装饰音的横向定位分为两步：

**预处理（比例算法之前）**

装饰音（`onset.grace != null`）不直接进入 `ProportionalLayoutComputer`。所有装饰音先按主音的 `(measure, beat)` 分组（`gracesForPrincipal`）。对每个有装饰音的主音，计算装饰音组所需的左侧空间：

```
clusterOverhang = GRACE_NOTE_PRINCIPAL_GAP
                + Σ maxWidth(graceTimes)          // 各槽最宽音符
                + (N − 1) × GRACE_NOTE_SPACING    // 组内间距
                + firstGrace.leftOverhang          // 首个装饰音的临时记号外伸
```

该值注入主音的 `NoteElement.extraLeftOverhang`（`@Transient` 字段，仅参与本次布局，不序列化），使比例算法像处理普通带临时记号音符一样自动预留空间。因此**装饰音天然不会与前一小节或前一音符重合**。

**定位（比例算法之后）**

主音 X 坐标确定后，`positionGraceNoteGroups` 从主音左缘向左倒序摆放：

```
lastGrace.slotX  = principalLeftEdge − GRACE_NOTE_PRINCIPAL_GAP
prevGrace.slotX  = nextGrace.slotX − nextGrace.maxWidth − GRACE_NOTE_SPACING
```

其中 `principalLeftEdge = principalSlotX − principalMaxWidth`（`slotX` 是右端坐标）。多个谱表共享同一时间槽 X，以最大宽度为准。

**跨小节线装饰音**

装饰音的 `TimeCode` 为 `(m, b, -k/N)`，当 `b = 0` 时其值小于小节起始 `(m, 0)`，会落在前一小节的边界范围之外。`gracesForPrincipal` 在小节循环**之前**全量构建，因此无论装饰音 TimeCode 落在哪个边界区间，都能在主音所在小节内被正确找到并摆放。

## 4.2 编辑预览与增量窗口

- 装饰音 ghost 吸附到普通主音时向左偏移，提示实际插入位置；吸附到已有三分量
  `TimeCode` 时不再偏移，以便在该装饰音上继续输入和弦。
- 小音符区域以 `TupletSpan.smallNotes` 标记。普通音符 ghost 始终检查这些区域，按工具栏
  当前时值和区域既有 tuplet 比例动态生成吸附网格；吸附后的 onset 在区域内时自动缩小，
  不受转换区域时最初产生的休止符槽限制，因此可继续任意细分。Computed 层透传
  `ComputedTupletInfo.smallNotes`，Renderer 将区域起点的隐藏休止符绘制为蓝色编辑标记，
  其余隐藏占位不绘制。Core 转换时用 tuplet beat unit 生成恰好覆盖 span 的占位，任何占位
  的实际结束位置都不得超过 `endTimeCode`，避免在后一空小节物化普通休止符。
- 已输入成员占满区域时，ghost 在末音符头与 `TupletSpan.endTimeCode` 之间的空白中产生
  带 `smallNoteAppendStartEventId` 的显式追加意图。Core 只按该组首事件 ID 追加，并依据
  固定区域总时长与全部成员显示时值之和重算共用 tuplet ratio；不得仅凭 onset 等于端点
  推断追加。
- 小音符动态网格以绝对全音符位置迭代，再转换回规范化 `TimeCode`。区域结束于小节线时，
  后一小节的候选必须表示为 `(nextMeasure, 0...)`，不能继续增加上一小节的 `beat`；区域
  包含判断和端点匹配也按绝对时间比较。
- 对已占满的区域，`GhostNoteComputer` 从 RenderResult 读取最后一个成员的 NOTEHEAD 右缘，
  将它到区域端点之前的可见空白作为追加热区，并把追加 ghost 放在末音之后。端点自身始终
  属于后续普通时间轴；真实 NOTEHEAD 命中优先于追加热区，因此后继正常音符仍按和弦编辑。
- Computed 增量窗口按小节取事件时，下界必须提前一个小节再按 `onset.measure` 过滤。
  直接从 `TimeCode.ofMeasure(m)` 做 B+ 树 range 会漏掉 `(m, 0, grace<0)`，谱首装饰音尤其
  容易触发；旧事件删除窗口使用相同规则。

## 5. Tie 规则

装饰音作为 tie 源时不能直接复用普通 tie 解析（普通逻辑只看下一拍的同音）。`TieTargetComputer.resolveGraceTieTarget` 用一段 `firstAfter` 循环，**只在同一 `(measure, beat)`** 内向后扫描：

- 命中第一个含相同音高的事件 → 作为目标（可能是另一装饰音，也可能是本音）；
- 扫到本音（`grace == null`）后立即终止——不外溢到下一拍；
- 未命中 → 返回 `null`（上层视为 let-ring 或无效 tie）。

测试覆盖：`core/.../TieTargetComputerGraceTest.kt`。设计原文见 [ties-and-slurs.md §8.4](ties-and-slurs.md)。

## 6. 钢琴卷帘

`apps/desktop/.../ui/views/PianoRollView.kt` 不再自行换算 TimeCode → tick，而是先 `ScoreToMidiConverter.convert(...)`，再用 `buildPianoRollNotes` 按 `(sourceEventId, midi)` 配对 note-on/off：

- 装饰音矩形以 `alpha = 0.5`（普通音 0.8）绘制；
- 高度方向额外 `scaleY * 0.25f` 内缩，与本音区分；
- 时间轴上的 onset / duration 与 MIDI 完全一致——播放头与色块边界对齐，便于排查 timing。

## 7. 设计取舍

**为什么不引入独立的 `StorageGraceEvent`？**
装饰音的字段结构与普通音符完全一致；额外类型只会引发"双套渲染路径"。用 `TimeCode` 第三分量隐藏排序、用首音元数据集中表达组级信息，是最小侵入的选项。

**为什么 `graceInfo` 只挂首音？**
组内其它装饰音的位置、数量都由 `TimeCode.grace` 反推得到，重复字段会导致不一致。播放层用 `pitchEventId → GraceNoteInfo` 的反向索引一次拿到即可。

**为什么 tie 规则限制在同一拍？**
装饰音的 tie 语义是"连接到本音或同组下一装饰音"，跨拍延音应由本音承担。把搜索止步在 `grace == null` 上能避免跨小节误连，也与播放层"装饰音窗口绑定本音"的语义对齐。

**为什么用 `extraLeftOverhang` 而不是把装饰音宽度加进主音的 `minimumWidth`？**
`minimumWidth` 影响右端计算（`slotX = leftEdge + width`）。把装饰音塞入 `minimumWidth` 会把主音右端也右移，破坏对齐。`leftOverhang` 专门表示"锚点左侧的额外占位"，不影响右端，语义精确。

**为什么装饰音在比例算法之外单独定位？**
装饰音没有实际时值贡献，放入比例算法会导致其 "Duration" 影响间距计算，使周围正常音符间距失真。两步分离（主音 overhang 预留空间 → 固定间距摆放装饰音）让两部分逻辑互不干扰。
