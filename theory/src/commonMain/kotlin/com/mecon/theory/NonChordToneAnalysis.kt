package com.mecon.theory

import com.mecon.api.primitive.Pitch
import kotlin.math.abs

/** 教材“和弦外音”章使用的拍位层级。v1 只区分小节首拍与其余拍位。 */
enum class BeatWeight { STRONG, WEAK }

/**
 * 和弦外音类型。缩写与教材、探索页 finding id 保持一致。
 * [NEIGHBOR_GROUP] 是 e + app 的复合标签；其成员仍可分别显示基础类型。
 */
enum class NonChordToneType(val abbreviation: String) {
    PASSING("p"),
    NEIGHBOR("n"),
    SUSPENSION("s"),
    RETARDATION("r"),
    APPOGGIATURA("app"),
    ESCAPE("e"),
    NEIGHBOR_GROUP("n.gr"),
    ANTICIPATION("ant"),
    SUSTAINED("sus.t"),
    PEDAL("ped"),
}

enum class MelodicMotion {
    HOLD,
    STEP_UP,
    STEP_DOWN,
    LEAP_UP,
    LEAP_DOWN,
}

data class NonChordToneContext(
    val previousPitch: Pitch?,
    val pitch: Pitch,
    val nextPitch: Pitch?,
    val previousChord: Chord?,
    val chord: Chord,
    val nextChord: Chord?,
    val beatWeight: BeatWeight,
    /** Lowest outer sustained tones are pedal tones; other voices use the generic sustained label. */
    val voiceBoundary: VoiceBoundary? = null,
    /** 是否属于当前调的自然音阶；规避音按教材要求只接受自然音。 */
    val isDiatonic: Boolean = true,
)

data class NonChordToneClassification(
    val primary: NonChordToneType,
    /** 同一表面音可能有多种合理解释，按教材常见度排序。 */
    val alternatives: List<NonChordToneType> = emptyList(),
)

/**
 * 教材和弦外音的纯判定器。生成、探索示例与主页面插件共享这一份定义。
 *
 * 这里刻意只消费音高、和弦与拍位，不依赖四部写作或 UI 数据结构；声部邻接、和弦时间线和
 * 增量失效范围由调用方负责。
 */
object NonChordToneClassifier {
    fun classify(context: NonChordToneContext): NonChordToneClassification? {
        if (context.chord.contains(context.pitch)) return null

        val approach = motion(context.previousPitch, context.pitch)
        val departure = motion(context.pitch, context.nextPitch)
        val candidates = buildList {
            if (
                approach == MelodicMotion.HOLD && departure.isStep &&
                context.beatWeight == BeatWeight.STRONG &&
                context.previousChord?.contains(context.pitch) == true
            ) {
                add(
                    if (departure == MelodicMotion.STEP_DOWN) NonChordToneType.SUSPENSION
                    else NonChordToneType.RETARDATION
                )
            }

            if (
                approach.isLeap && departure.isStep &&
                context.beatWeight == BeatWeight.STRONG
            ) add(NonChordToneType.APPOGGIATURA)

            if (
                approach == MelodicMotion.HOLD && departure == MelodicMotion.HOLD &&
                (context.previousChord?.contains(context.pitch) == true ||
                    context.nextChord?.contains(context.pitch) == true)
            ) add(
                if (
                    context.voiceBoundary == null ||
                    context.voiceBoundary == VoiceBoundary.LOWER_OUTER
                ) {
                    NonChordToneType.PEDAL
                } else {
                    NonChordToneType.SUSTAINED
                }
            )

            if (
                departure == MelodicMotion.HOLD &&
                context.beatWeight == BeatWeight.WEAK &&
                context.nextChord?.contains(context.pitch) == true
            ) add(NonChordToneType.ANTICIPATION)

            if (
                approach.isStep && departure.isStep && approach.direction == departure.direction &&
                context.beatWeight == BeatWeight.WEAK
            ) add(NonChordToneType.PASSING)

            if (
                approach.isStep && departure.isStep && approach.direction == -departure.direction &&
                context.previousPitch?.isEnharmonic(context.nextPitch ?: context.pitch) == true &&
                context.beatWeight == BeatWeight.WEAK
            ) add(NonChordToneType.NEIGHBOR)

            if (
                approach.isStep && departure.isLeap &&
                context.beatWeight == BeatWeight.WEAK && context.isDiatonic
            ) add(NonChordToneType.ESCAPE)

        }.distinct()

        return candidates.firstOrNull()?.let { NonChordToneClassification(it, candidates.drop(1)) }
    }

    fun motion(from: Pitch?, to: Pitch?): MelodicMotion? {
        if (from == null || to == null) return null
        val diatonicDelta = to.diatonicSteps - from.diatonicSteps
        val chromaticDelta = to.midiNumber - from.midiNumber
        return when {
            chromaticDelta == 0 -> MelodicMotion.HOLD
            abs(diatonicDelta) <= 1 && abs(chromaticDelta) <= 2 ->
                if (chromaticDelta > 0) MelodicMotion.STEP_UP else MelodicMotion.STEP_DOWN
            to.midiNumber > from.midiNumber -> MelodicMotion.LEAP_UP
            else -> MelodicMotion.LEAP_DOWN
        }
    }
}

private val MelodicMotion?.isStep: Boolean
    get() = this == MelodicMotion.STEP_UP || this == MelodicMotion.STEP_DOWN

private val MelodicMotion?.isLeap: Boolean
    get() = this == MelodicMotion.LEAP_UP || this == MelodicMotion.LEAP_DOWN

private val MelodicMotion?.direction: Int
    get() = when (this) {
        MelodicMotion.STEP_UP, MelodicMotion.LEAP_UP -> 1
        MelodicMotion.STEP_DOWN, MelodicMotion.LEAP_DOWN -> -1
        else -> 0
    }
