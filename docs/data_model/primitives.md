# 基础类型 (Primitives)

> 路径：`api/src/commonMain/kotlin/com/mecon/api/primitive/`

所有基础类型均为不可变值类型，使用 `data class` 或 `@JvmInline value class` 实现。所有顺序量都实现 `Comparable`。

## 1. Fraction

`Fraction(numerator: Long, denominator: Long)`：精确分数，自动约分，支持四则运算与比较。

常量：`ZERO`、`ONE`、`HALF`、`QUARTER`、`EIGHTH`、`SIXTEENTH`、`POSITIVE_INFINITY`。

```kotlin
val q = Fraction(1, 4) + Fraction(1, 8)   // 3/8
val s = Fraction.ONE.simplified()
```

> 详见 `Fraction.kt`。

## 2. TimeCode

```kotlin
data class TimeCode(val components: List<Fraction>) : Comparable<TimeCode>
```

时间戳由 `[measure, beat, grace, ...]` 组成：

- `components[0]` = 小节号（从 0 计）
- `components[1]` = 小节内偏移（按四分音符 = 1）
- `components[2]` = grace 时序（同 onset 多事件的稳定排序）

字典序比较保证不同小节、不同 grace level 的事件有确定顺序。
自定义序列化器同时支持 YAML 紧凑形式（`"0:1/4"`）与列表形式。

> 详见 `TimeCode.kt`。

## 3. Pitch / NoteName / Accidental

```kotlin
enum class NoteName { C, D, E, F, G, A, B }   // diatonicStep 0..6
enum class Accidental { DOUBLE_FLAT, FLAT, NATURAL, SHARP, DOUBLE_SHARP }

data class Pitch(
    val diatonicSteps: Int,    // C0 = 0, 跨八度递增
    val chromaticOffset: Int,  // -2..+2 (相对自然音的半音差)
)
```

**保留等音异写**：`F#` 与 `Gb` 的 `diatonicSteps` 不同，`midiNumber` 相同。

常用工具：
- `Pitch.fromMidi(midi, prefer = ...)` 推断拼写
- `Pitch.fromName("C#4")` 解析记谱字符串
- `pitch.transpose(interval)`、`pitch.intervalTo(other)`

`PitchClass`：仅包含八度无关信息。`PitchRange`：闭区间。

> 详见 `Pitch.kt`。

## 4. Duration

```kotlin
enum class DurationBase(val ticks: Long) {
    WHOLE(4096), HALF(2048), QUARTER(1024),
    EIGHTH(512), SIXTEENTH(256), THIRTY_SECOND(128), ...
}

data class Tuplet(val actual: Int, val normal: Int)    // e.g. 3:2 三连音

data class Duration(
    val base: DurationBase,
    val dots: Int = 0,
    val tuplet: Tuplet? = null,
)
```

- `Duration.totalTicks`：考虑附点与连音的实际时值
- `Tuplet(actual, normal)` 表示「actual 个音占 normal 个同类音的时值」；编辑器选择 `beatUnit` 时优先采用常规记谱比例（如 `3:2` 三连音、`2:3` 附点拍二连音），再反推出每个连音拍的 `DurationBase`。
- 全音符 = 4096 ticks（与 1 拍 = 1024 一致）

> 详见 `Duration.kt`。

## 5. Interval

`Interval(quality, number)` 表示音程；`PERFECT`/`MAJOR`/`MINOR`/`AUGMENTED`/`DIMINISHED` 与度数组合。

`pitch.transpose(interval)` 在保留拼写的前提下移调。

## 6. KeySignature / TimeSignature / BarlineType

- `KeySignature(root, mode, customScale?, fifthsOverride?)`：调性用 `root/mode` 表示，`fifths` 为派生属性；常规大/小调用五度圈推导，升号正、降号负，参见 MusicXML 约定。同音异名但调号方向不同的情况（如 `C#`/`Db`、`F#`/`Gb`、`B`/`Cb`）用 `fifthsOverride` 保留记谱方向；新建 UI 应优先使用 `KeySignature.majorByFifths()` / `minorByFifths()` 构造完整调号。
- `TimeSignature(numerator, denominator, beatGroups?)`：常规拍号。`beatGroups`（可选，分母单位计数、和为 numerator）指定连梁分组，如 7/8 的 `[2,2,3]`；`null` 用 `defaultBeatGroups()`（复拍子按 3 分、简单拍子每拍一组）。`beatGroupCandidates()` 给复/不规则拍子的可选分组，`beatGroupIndexOf(beatPos)` 供自动 beaming 按分组归组
- `BarlineType`：`SINGLE / DOUBLE / FINAL / REPEAT_START / REPEAT_END / ...`

## 7. ID 包装类型

```kotlin
@JvmInline value class EventId(val value: String)
@JvmInline value class TrackId(val value: String)
@JvmInline value class PartId(val value: String)
```

通过 `value class` 包装避免裸 `String` 在 API 间互串。

## 设计取舍

- **`@JvmInline value class` 而非 `typealias`**：编译期类型隔离，运行期无开销。
- **`data class` 不可变**：所有修改返回新实例，配合 `kotlinx.collections.immutable` 在更新链上结构共享。
- **`Comparable` 一致性**：`Fraction`、`TimeCode`、`Pitch`、`Duration` 都按音乐语义排序，可直接用于 `sortedBy / BPlusTree`。
