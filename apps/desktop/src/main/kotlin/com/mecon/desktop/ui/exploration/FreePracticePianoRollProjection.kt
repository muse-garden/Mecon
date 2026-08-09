package com.mecon.desktop.ui.exploration

import com.mecon.api.primitive.Fraction
import com.mecon.api.primitive.TimeCode
import com.mecon.api.runtime.RuntimeScore
import com.mecon.api.runtime.ScoreTimeMap
import com.mecon.desktop.ui.views.TICKS_PER_QUARTER
import com.mecon.desktop.ui.views.pianoroll.PianoRollChordSpan
import com.mecon.exploration.VoicePlanScoreAssembler
import com.mecon.renderer.geometry.ScaleFactor
import com.mecon.renderer.geometry.StaffSpace
import com.mecon.renderer.layout.AlignedTimeAxisRequest
import com.mecon.renderer.layout.ResolvedTimeAxis
import com.mecon.renderer.layout.TimeAxisSegmentRequest
import com.mecon.theory.harmony.ChordSelectionCatalog
import com.mecon.theory.freepractice.HarmonyWorkspaceState
import kotlin.math.roundToInt

internal fun freePracticeChordSpans(
    workspace: HarmonyWorkspaceState,
): List<PianoRollChordSpan> {
    val choicesByLayout = workspace.tonalLayouts.associate { layout ->
        layout.id to ChordSelectionCatalog.groups(layout.key)
            .asSequence()
            .flatMap { it.chords.asSequence() }
            .toList()
    }
    return workspace.slots.mapNotNull { slot ->
        val layout = workspace.selectedTonalLayout(slot) ?: return@mapNotNull null
        val chord = choicesByLayout[layout.id]?.matchingChoice(slot)
        val pitchClasses = slot.chordChoice?.pitchClasses ?: chord?.pitchClasses?.toList()
            ?: return@mapNotNull null
        PianoRollChordSpan(
            onsetTicks = slot.onset.toPianoRollTicks(),
            endTicks = (slot.onset + slot.duration).toPianoRollTicks(),
            symbol = chord?.functionalSymbol ?: "·",
            pitchClasses = pitchClasses,
        )
    }
}

internal data class FreePracticeGridTime(
    val absoluteTicks: Long,
    val timeCode: TimeCode,
)

internal data class FreePracticeGridRange(
    val startTicks: Long,
    val endTicks: Long,
    val start: TimeCode,
    val duration: Fraction,
)

/**
 * Quantizes inside each measure so durations that do not evenly divide 2/4 do not shift the grid
 * in every following measure.
 */
internal fun freePracticeGridTime(
    hitTicks: Long,
    gridDuration: Fraction,
): FreePracticeGridTime {
    val durationTicks = gridDuration.toPianoRollTicks().coerceAtLeast(1L)
    val ticksPerMeasure = VoicePlanScoreAssembler.FREE_PRACTICE_METER
        .measureDuration()
        .toPianoRollTicks()
    val safeTicks = hitTicks.coerceAtLeast(0L)
    val measureIndex = safeTicks / ticksPerMeasure
    val ticksInMeasure = safeTicks % ticksPerMeasure
    val snappedInMeasure = (ticksInMeasure / durationTicks) * durationTicks
    val absoluteTicks = measureIndex * ticksPerMeasure + snappedInMeasure
    return FreePracticeGridTime(
        absoluteTicks = absoluteTicks,
        timeCode = TimeCode.of(
            measureIndex.toInt() + 1,
            Fraction(snappedInMeasure.toInt(), TICKS_PER_QUARTER * 4).simplified(),
        ),
    )
}

internal fun freePracticeTimeCodeAt(position: Fraction): TimeCode {
    val measureDuration = VoicePlanScoreAssembler.FREE_PRACTICE_METER.measureDuration()
    val safePosition = position.coerceAtLeast(Fraction.ZERO)
    val measureOffset = safePosition / measureDuration
    val measureIndex = measureOffset.numerator / measureOffset.denominator
    return TimeCode.of(
        measureIndex + 1,
        safePosition - measureDuration * measureIndex,
    )
}

internal fun freePracticeAbsolute(time: TimeCode): Fraction {
    val measureDuration = VoicePlanScoreAssembler.FREE_PRACTICE_METER.measureDuration()
    return measureDuration * (time.measure.coerceAtLeast(1) - 1) +
        (time.beat ?: Fraction.ZERO)
}

/**
 * Resolves a piano-roll click only when the complete note fits in the declared score range.
 */
internal fun freePracticeGridTimeWithinScore(
    hitTicks: Long,
    gridDuration: Fraction,
    score: RuntimeScore,
): FreePracticeGridTime? {
    val gridTime = freePracticeGridTime(hitTicks, gridDuration)
    val durationTicks = gridDuration.toPianoRollTicks().coerceAtLeast(1L)
    val scoreEndTicks = score.measures.sumOf { it.value.duration.toPianoRollTicks() }
    if (durationTicks > scoreEndTicks) return null
    return gridTime.takeIf {
        it.absoluteTicks <= scoreEndTicks - durationTicks
    }
}

internal fun freePracticeGridRangeWithinScore(
    firstTicks: Long,
    secondTicks: Long,
    gridDuration: Fraction,
    score: RuntimeScore,
): FreePracticeGridRange? {
    val first = freePracticeGridTime(firstTicks, gridDuration)
    val second = freePracticeGridTime(secondTicks, gridDuration)
    val unitTicks = gridDuration.toPianoRollTicks().coerceAtLeast(1L)
    val startTicks = minOf(first.absoluteTicks, second.absoluteTicks)
    val scoreEndTicks = score.measures.sumOf { it.value.duration.toPianoRollTicks() }
    val endTicks = (maxOf(first.absoluteTicks, second.absoluteTicks) + unitTicks)
        .coerceAtMost(scoreEndTicks)
    if (endTicks <= startTicks) return null
    return FreePracticeGridRange(
        startTicks = startTicks,
        endTicks = endTicks,
        start = freePracticeGridTime(startTicks, gridDuration).timeCode,
        duration = Fraction(
            (endTicks - startTicks).toInt(),
            TICKS_PER_QUARTER * 4,
        ).simplified(),
    )
}

internal fun nearestChordToneMidi(midi: Int, pitchClasses: Set<Int>): Int {
    if (pitchClasses.isEmpty()) return midi.coerceIn(0, 127)
    return (-11..11)
        .asSequence()
        .map { (midi + it).coerceIn(0, 127) }
        .filter { it.mod(12) in pitchClasses }
        .distinct()
        .minWithOrNull(compareBy<Int> { kotlin.math.abs(it - midi) }.thenBy { it })
        ?: midi.coerceIn(0, 127)
}

internal fun freePracticeDuration(value: Fraction): com.mecon.api.primitive.Duration {
    for (base in com.mecon.api.primitive.DurationBase.entries) {
        for (dots in 0..3) {
            val duration = com.mecon.api.primitive.Duration(base, dots)
            if (duration.toFraction() == value) return duration
        }
    }
    return com.mecon.api.primitive.Duration(
        base = com.mecon.api.primitive.DurationBase.WHOLE,
        tuplet = com.mecon.api.primitive.Tuplet(
            actual = value.denominator,
            normal = value.numerator,
        ),
    )
}

internal fun freePracticeAlignedTimeAxisRequest(
    workspace: HarmonyWorkspaceState,
    score: RuntimeScore,
    beatWidthPx: Float,
    renderDensity: Float,
    revision: Long,
): AlignedTimeAxisRequest {
    require(renderDensity > 0f) { "Render density must be positive" }
    val timeMap = ScoreTimeMap.from(score)
    val lastEnd = workspace.slots.maxOf { it.onset + it.duration }
    val boundaries = buildSet {
        add(Fraction.ZERO)
        add(lastEnd)
        workspace.slots.forEach { slot ->
            add(slot.onset)
            add(slot.onset + slot.duration)
        }
        workspace.tonalLayouts.forEach { layout ->
            add(layout.start)
            layout.end?.let(::add)
        }
    }.filter { it >= Fraction.ZERO && it <= lastEnd }.sorted()
    val pixelsPerWhole = beatWidthPx * 4f
    return AlignedTimeAxisRequest(
        segments = boundaries.zipWithNext().map { (start, end) ->
            TimeAxisSegmentRequest(
                start = timeMap.timeCodeAt(start),
                end = timeMap.timeCodeAt(end),
                preferredWidth = StaffSpace(
                    (end - start).toFloat() *
                        pixelsPerWhole /
                        freePracticePixelsPerStaffSpace(renderDensity),
                ),
            )
        },
        extraAnchors = boundaries.mapTo(linkedSetOf(), timeMap::timeCodeAt),
        notationContentStartGap = StaffSpace(0.75f),
        revision = revision,
    )
}

internal fun freePracticePixelsPerStaffSpace(renderDensity: Float): Float =
    ScaleFactor.DEFAULT.pixelsPerStaffSpace * renderDensity

internal fun freePracticeAxisScreenPixels(
    position: com.mecon.renderer.geometry.StaffSpace,
    renderDensity: Float,
): Float = position.value * freePracticePixelsPerStaffSpace(renderDensity)

/**
 * Axis position in the density-independent units the shared timeline scene is laid out in. It is
 * [freePracticeAxisScreenPixels] at density 1, so `unitsDp.toPx()` returns the same device pixel the
 * notation surface uses on any display scale.
 */
internal fun freePracticeAxisSceneUnits(
    position: com.mecon.renderer.geometry.StaffSpace,
): Float = freePracticeAxisScreenPixels(position, renderDensity = 1f)

internal fun freePracticeAxisStaffSpace(
    screenPixels: Float,
    renderDensity: Float,
): com.mecon.renderer.geometry.StaffSpace =
    com.mecon.renderer.geometry.StaffSpace(
        screenPixels / freePracticePixelsPerStaffSpace(renderDensity),
    )

/**
 * Uses the settled renderer projection where it exists and gives a newly appended workspace tail
 * an immediate provisional width. The next resolved axis replaces this extension atomically.
 */
internal fun freePracticeExtendedAxisX(
    axis: ResolvedTimeAxis,
    settledEndTime: Fraction,
    absoluteTime: Fraction,
    beatWidthPx: Float,
    renderDensity: Float,
): Float {
    val settledEndX = freePracticeAxisScreenPixels(
        axis.xAt(freePracticeTimeCodeAt(settledEndTime)),
        renderDensity,
    )
    if (absoluteTime <= settledEndTime) {
        return freePracticeAxisScreenPixels(
            axis.xAt(freePracticeTimeCodeAt(absoluteTime)),
            renderDensity,
        )
    }
    return settledEndX + (absoluteTime - settledEndTime).toFloat() * beatWidthPx * 4f
}

internal fun freePracticeExtendedAxisTime(
    axis: ResolvedTimeAxis,
    settledEndTime: Fraction,
    screenPixels: Float,
    beatWidthPx: Float,
    renderDensity: Float,
): Fraction {
    val settledEndX = freePracticeAxisScreenPixels(
        axis.xAt(freePracticeTimeCodeAt(settledEndTime)),
        renderDensity,
    )
    if (screenPixels <= settledEndX) {
        return freePracticeAbsolute(
            axis.timeAt(freePracticeAxisStaffSpace(screenPixels, renderDensity)),
        )
    }
    require(beatWidthPx > 0f) { "Beat width must be positive" }
    val tail = Fraction(
        (((screenPixels - settledEndX) / (beatWidthPx * 4f)) * 1_000_000f).roundToInt(),
        1_000_000,
    ).simplified()
    return settledEndTime + tail
}

internal fun Fraction.toPianoRollTicks(): Long =
    (numerator.toLong() * TICKS_PER_QUARTER * 4L) / denominator
