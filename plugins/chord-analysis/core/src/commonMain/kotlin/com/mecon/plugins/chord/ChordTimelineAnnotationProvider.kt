package com.mecon.plugins.chord

import com.mecon.api.computed.pluginEventsOf
import com.mecon.api.plugin.AnnotationElement
import com.mecon.api.plugin.AnnotationLayoutContext
import com.mecon.api.plugin.AnnotationStaffProvider
import com.mecon.api.plugin.AnnotationTextLine
import com.mecon.api.plugin.PluginStaffId
import com.mecon.api.plugin.StaffAnchor
import com.mecon.api.primitive.Fraction
import com.mecon.api.primitive.TimeCode
import com.mecon.api.primitive.TrackId
import com.mecon.api.render.FormattedText
import com.mecon.api.render.RenderColor
import com.mecon.api.runtime.ScoreTimeMap
import com.mecon.theory.ChordSymbolDisplayStyle
import com.mecon.theory.KeySignatureMode
import com.mecon.theory.ModulationKey
import com.mecon.theory.harmony.HarmonyTimelineReadingProjector
import com.mecon.theory.harmony.HarmonyTonalRange
import com.mecon.theory.harmony.HarmonyTonalTimeline
import com.mecon.theory.harmony.timelineLabel

/** Read-only harmony timeline mixed into the score above every rendered system. */
object ChordTimelineAnnotationProvider : AnnotationStaffProvider {
    override val staffId = PluginStaffId("mecon.chord_analysis.timeline")
    override val anchor: StaffAnchor = StaffAnchor.AboveAllStaves
    override val pluginTrackTypes: Set<String> = setOf(StorageChordEvent.TRACK_TYPE)

    override fun layout(ctx: AnnotationLayoutContext): List<AnnotationElement> {
        if (ChordSymbolDisplaySettings.scoreDisplayMode != ChordAnalysisScoreDisplayMode.TIMELINE) {
            return emptyList()
        }
        val score = ctx.computedScore
        val timeMap = ScoreTimeMap.from(score.runtime)
        val lastMeasure = score.runtime.measures.map { it.key }.maxOrNull() ?: 1
        val scoreEnd = TimeCode.of(lastMeasure + 1, Fraction.ZERO)
        val scoreEndAbsolute = timeMap.absolute(scoreEnd)
        val tonalRanges = tonalRanges(ctx, timeMap, lastMeasure, scoreEndAbsolute)
        val lanes = HarmonyTonalTimeline.lanes(tonalRanges, scoreEndAbsolute)
        val laneCount = (lanes.maxOrNull() ?: -1) + 1
        val chordTop = laneCount * TONAL_LANE_HEIGHT

        val chords = score.pluginEventsOf<StorageChordEvent>(StorageChordEvent.TRACK_TYPE)
            .sortedWith(compareBy<StorageChordEvent>(StorageChordEvent::onset, { it.id.value }))
            .groupBy(StorageChordEvent::onset)
            .map { (_, atOnset) -> atOnset.last() }
        if (chords.isEmpty()) return emptyList()

        return buildList {
            tonalRanges.forEachIndexed { index, range ->
                val end = range.end ?: scoreEndAbsolute
                if (end <= range.start) return@forEachIndexed
                val accent = tonalAccent(range.keys.first())
                add(
                    AnnotationElement.Range(
                        time = timeMap.timeCodeAt(range.start),
                        endTime = timeMap.timeCodeAt(end),
                        relativeY = lanes[index] * TONAL_LANE_HEIGHT,
                        trackId = TONAL_TRACK_ID,
                        height = TONAL_BAR_HEIGHT,
                        lines = listOf(
                            AnnotationTextLine(
                                content = FormattedText.plain(
                                    range.keys.joinToString(" · ") { it.timelineLabel() } +
                                        if (range.derived) " · 自动" else "",
                                ),
                                fontSize = 9f,
                                color = accent,
                            )
                        ),
                        fillColor = accent.withAlpha(if (range.derived) 18 else 34),
                        strokeColor = accent.withAlpha(if (range.derived) 150 else 220),
                        strokeWidth = 1f,
                        horizontalInset = 0.15f,
                        interactive = false,
                    )
                )
            }

            chords.forEachIndexed { index, storage ->
                val end = chords.getOrNull(index + 1)?.onset ?: scoreEnd
                if (end <= storage.onset) return@forEachIndexed
                val computed = ComputedChordEvent.fromRuntime(RuntimeChordEvent.fromStorage(storage))
                val keys = HarmonyTonalTimeline.keysAt(
                    time = timeMap.absolute(storage.onset),
                    ranges = tonalRanges,
                    defaultKey = score.runtime.getKeySignatureAt(storage.onset.measure).toModulationKey(),
                )
                val readings = HarmonyTimelineReadingProjector.readings(computed.chord, keys)
                val lines = cardLines(computed, readings, keys)
                add(
                    AnnotationElement.Range(
                        time = storage.onset,
                        endTime = end,
                        relativeY = chordTop,
                        sourceEventId = storage.id,
                        trackId = CHORD_TRACK_ID,
                        height = CARD_HEIGHT,
                        lines = lines,
                        fillColor = CARD_FILL,
                        strokeColor = CARD_BORDER,
                        strokeWidth = 1f,
                        horizontalInset = CARD_INSET,
                        minimumWidth = CARD_MIN_WIDTH,
                    )
                )
            }
        }
    }

    private fun tonalRanges(
        ctx: AnnotationLayoutContext,
        timeMap: ScoreTimeMap,
        lastMeasure: Int,
        scoreEnd: Fraction,
    ): List<HarmonyTonalRange> {
        val baselines = mutableListOf<HarmonyTonalRange>()
        var startMeasure = 1
        var key = ctx.computedScore.runtime.getKeySignatureAt(1).toModulationKey()
        for (measure in 2..lastMeasure + 1) {
            val next = if (measure <= lastMeasure) {
                ctx.computedScore.runtime.getKeySignatureAt(measure).toModulationKey()
            } else null
            if (next != key) {
                baselines += HarmonyTonalRange(
                    id = "signature:$startMeasure",
                    start = timeMap.absolute(TimeCode.of(startMeasure, Fraction.ZERO)),
                    end = timeMap.absolute(TimeCode.of(measure, Fraction.ZERO)),
                    keys = listOf(key),
                    priority = 0,
                )
                if (next != null) {
                    startMeasure = measure
                    key = next
                }
            }
        }
        val regions = ctx.computedScore
            .pluginEventsOf<StorageTonalRegionEvent>(StorageTonalRegionEvent.TRACK_TYPE)
            .mapNotNull { region ->
                // Clipping to the score end collapses regions left behind by a measure deletion.
                HarmonyTonalRange.clippedOrNull(
                    id = region.id.value,
                    start = timeMap.absolute(region.onset),
                    end = minOf(scoreEnd, timeMap.absolute(region.endOnset)),
                    keys = region.keys.map(PolyphonyTonalKey::toModulationKey),
                    resolvedKey = region.resolvedKey?.toModulationKey(),
                    priority = 10,
                )
            }
        return baselines + regions
    }

    private fun cardLines(
        chord: ComputedChordEvent,
        readings: List<com.mecon.theory.harmony.HarmonyTimelineReading>,
        keys: List<ModulationKey>,
    ): List<AnnotationTextLine> {
        if (ChordSymbolDisplaySettings.style == ChordSymbolDisplayStyle.SCALE_DEGREE && readings.isNotEmpty()) {
            return readings.take(MAX_CARD_LINES).map { reading ->
                val prefix = if (readings.size > 1) "${reading.key.timelineLabel()}: " else ""
                val tones = reading.relativeTones.takeIf(List<String>::isNotEmpty)
                    ?.joinToString("–")
                    ?.let { " · $it" }
                    .orEmpty()
                AnnotationTextLine(
                    FormattedText.plain(prefix + reading.functionalSymbol + tones),
                    fontSize = if (readings.size > 1) 9f else 11f,
                    color = CARD_TEXT,
                )
            }
        }
        val signature = keys.firstOrNull()?.keySignature
            ?: com.mecon.api.primitive.KeySignature.C_MAJOR
        val absoluteTones = readings.firstOrNull()?.absoluteTones.orEmpty()
        return buildList {
            add(
                AnnotationTextLine(
                    chord.formattedSymbol(ChordSymbolDisplayStyle.LETTER, signature)
                        .toFormattedText(ChordSymbolDisplayStyle.LETTER),
                    fontSize = 12f,
                    color = CARD_TEXT,
                )
            )
            if (absoluteTones.isNotEmpty()) {
                add(
                    AnnotationTextLine(
                        FormattedText.plain(absoluteTones.joinToString("–")),
                        fontSize = 9f,
                        color = CARD_MUTED,
                    )
                )
            }
        }
    }

    private fun com.mecon.api.primitive.KeySignature.toModulationKey(): ModulationKey =
        ModulationKey(fifths, KeySignatureMode.fromApiMode(mode))

    private fun tonalAccent(key: ModulationKey): RenderColor = when ((key.fifths - key.mode.ordinal).mod(3)) {
        0 -> LightTimelinePalette.PrimaryDark
        1 -> LightTimelinePalette.Emerald
        else -> LightTimelinePalette.Orange
    }

    private fun RenderColor.withAlpha(alpha: Int): RenderColor = copy(alpha = alpha)

    private const val TONAL_LANE_HEIGHT = 2.75f
    private const val TONAL_BAR_HEIGHT = 2.2f
    private const val CARD_HEIGHT = 6.25f
    private const val CARD_INSET = 0.2f
    private const val CARD_MIN_WIDTH = 7f
    private const val MAX_CARD_LINES = 3
    private val CHORD_TRACK_ID = TrackId("__harmony_timeline_chords")
    private val TONAL_TRACK_ID = TrackId("__harmony_timeline_tonality")
    private val CARD_FILL = LightTimelinePalette.SelectedSurface
    private val CARD_BORDER = LightTimelinePalette.SelectedBorder
    private val CARD_TEXT = LightTimelinePalette.TextPrimary
    private val CARD_MUTED = LightTimelinePalette.TextMuted

    /** Fixed light-theme roles: the score timeline stays paper-like regardless of app chrome. */
    private object LightTimelinePalette {
        val PrimaryDark = RenderColor.rgb(29, 78, 216)
        val SelectedSurface = RenderColor.rgb(220, 234, 254)
        val SelectedBorder = RenderColor.rgb(96, 165, 250)
        val TextPrimary = RenderColor.rgb(30, 41, 59)
        val TextMuted = RenderColor.rgb(100, 116, 139)
        val Emerald = RenderColor.rgb(63, 146, 127)
        val Orange = RenderColor.rgb(182, 129, 85)
    }
}
