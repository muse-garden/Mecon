package com.mecon.plugins.chord

import com.mecon.api.computed.pluginEventsOf
import com.mecon.api.plugin.AnnotationElement
import com.mecon.api.plugin.AnnotationLayoutContext
import com.mecon.api.plugin.AnnotationStaffProvider
import com.mecon.api.plugin.AnnotationTextLine
import com.mecon.api.plugin.PluginStaffId
import com.mecon.api.plugin.StaffAnchor
import com.mecon.api.primitive.EventId
import com.mecon.api.primitive.Fraction
import com.mecon.api.primitive.TimeCode
import com.mecon.api.primitive.TrackId
import com.mecon.api.render.FormattedText
import com.mecon.api.render.RenderColor
import com.mecon.api.runtime.ScoreTimeMap
import com.mecon.theory.ChordSymbolDisplayStyle
import com.mecon.theory.KeySignatureMode
import com.mecon.theory.ModulationKey
import com.mecon.theory.harmony.HarmonyKeyAccent
import com.mecon.theory.harmony.HarmonyTimelineReadingProjector
import com.mecon.theory.harmony.HarmonyTonalRange
import com.mecon.theory.harmony.HarmonyTonalTimeline
import com.mecon.theory.harmony.timelineLabel

/** Harmony timeline mixed into the score above every rendered system. */
object ChordTimelineAnnotationProvider : AnnotationStaffProvider {
    override val staffId = PluginStaffId("mecon.chord_analysis.timeline")
    override val anchor: StaffAnchor = StaffAnchor.AboveAllStaves
    override val pluginTrackTypes: Set<String> = setOf(
        StorageChordEvent.TRACK_TYPE,
        StorageTonalRegionEvent.TRACK_TYPE,
    )

    override fun layout(ctx: AnnotationLayoutContext): List<AnnotationElement> {
        if (ChordSymbolDisplaySettings.scoreDisplayMode != ChordAnalysisScoreDisplayMode.TIMELINE) {
            return emptyList()
        }
        val score = ctx.computedScore
        val timeMap = ScoreTimeMap.from(score.runtime)
        val lastMeasure = score.runtime.measures.map { it.key }.maxOrNull() ?: 1
        val scoreEnd = TimeCode.of(lastMeasure + 1, Fraction.ZERO)
        val scoreEndAbsolute = timeMap.absolute(scoreEnd)
        val tonalProjection = tonalRanges(ctx, timeMap, lastMeasure, scoreEndAbsolute)
        val tonalRanges = tonalProjection.ranges
        val displayRanges = displayTonalRanges(
            tonalRanges,
            scoreEndAbsolute,
            tonalProjection.baselineEventIds,
        )
        // The score key-signature is the visual baseline and must never be displaced by an
        // explicit region. Pack only explicit tonalities, starting below that fixed first lane.
        val explicitRanges = displayRanges.filterNot(TimelineTonalRange::baseline)
        val explicitLanes = HarmonyTonalTimeline.lanes(
            explicitRanges.map(TimelineTonalRange::range),
            scoreEndAbsolute,
        )
        val explicitLaneByRangeId = explicitRanges.indices.associate { index ->
            explicitRanges[index].range.id to explicitLanes[index] + 1
        }
        val lanes = displayRanges.map { display ->
            if (display.baseline) 0 else explicitLaneByRangeId.getValue(display.range.id)
        }
        val laneCount = (lanes.maxOrNull() ?: -1) + 1
        val chordTop = laneCount * TONAL_LANE_HEIGHT

        val chords = score.pluginEventsOf<StorageChordEvent>(StorageChordEvent.TRACK_TYPE)
            .sortedWith(compareBy<StorageChordEvent>(StorageChordEvent::onset, { it.id.value }))
            .groupBy(StorageChordEvent::onset)
            .map { (_, atOnset) -> atOnset.last() }

        return buildList {
            displayRanges.forEachIndexed { index, display ->
                val range = display.range
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
                                    if (display.showLabel) range.keys.single().timelineLabel() else "",
                                ),
                                fontSize = 9f,
                                color = accent.withAlpha(if (display.dimmed) 105 else 255),
                            )
                        ),
                        fillColor = accent.withAlpha(if (display.dimmed) 10 else 34),
                        strokeColor = accent.withAlpha(if (display.dimmed) 70 else 220),
                        strokeWidth = 1f,
                        horizontalInset = 0.15f,
                        sourceEventId = display.sourceEventId,
                        interactive = display.sourceEventId != null,
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
                    includeDefaultKeyWithActive = tonalProjection.baselineEventIds.isEmpty(),
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
    ): TonalProjection {
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
        val storageRegions = ctx.computedScore
            .pluginEventsOf<StorageTonalRegionEvent>(StorageTonalRegionEvent.TRACK_TYPE)
        val baselineEventIds = storageRegions
            .filter { it.role == TonalRegionRole.SCORE_KEY_BASELINE }
            .mapTo(linkedSetOf()) { it.id.value }
        val regions = storageRegions
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
        val controlledBaselines = regions.filter { it.id in baselineEventIds }
        val retainedNativeBaselines = baselines.filterNot { native ->
            controlledBaselines.any { controlled ->
                controlled.start == native.start && controlled.keys == native.keys
            }
        }
        return TonalProjection(
            ranges = retainedNativeBaselines + regions,
            baselineEventIds = baselineEventIds,
        )
    }

    /**
     * Keeps the key-signature baseline in the fixed top lane. A persisted score-key baseline owns
     * its own endpoints; legacy scores keep using covered native pieces as resize proxies for the
     * explicit region. Each explicit key gets one line below it, matching free practice.
     */
    private fun displayTonalRanges(
        ranges: List<HarmonyTonalRange>,
        scoreEnd: Fraction,
        baselineEventIds: Set<String>,
    ): List<TimelineTonalRange> {
        val baselines = ranges.filter { it.priority == 0 || it.id in baselineEventIds }
        val explicit = ranges.filterNot { it.priority == 0 || it.id in baselineEventIds }
        val baselineSegments = baselines.flatMap { baseline ->
            val baselineEnd = baseline.end ?: scoreEnd
            val cuts = buildSet {
                add(baseline.start)
                add(baselineEnd)
                explicit.forEach { region ->
                    val regionEnd = region.end ?: scoreEnd
                    if (region.start > baseline.start && region.start < baselineEnd) add(region.start)
                    if (regionEnd > baseline.start && regionEnd < baselineEnd) add(regionEnd)
                }
            }.sorted()
            cuts.zipWithNext().mapNotNull { (start, end) ->
                if (end <= start) return@mapNotNull null
                val coveringRegion = explicit
                    .asSequence()
                    .filter { region ->
                        val regionEnd = region.end ?: scoreEnd
                        region.start < end && regionEnd > start
                    }
                    .sortedWith(compareBy<HarmonyTonalRange>({ it.start }, { it.id }))
                    .firstOrNull()
                TimelineTonalRange(
                    range = baseline.copy(
                        id = "${baseline.id}:$start",
                        start = start,
                        end = end,
                    ),
                    // Covered baseline pieces are interactive proxies for the explicit range.
                    // This makes the exact point where the score key turns light draggable too.
                    sourceEventId = if (baseline.id in baselineEventIds) {
                        EventId(baseline.id)
                    } else {
                        coveringRegion?.let { EventId(it.id) }
                    },
                    dimmed = coveringRegion != null,
                    showLabel = start == baseline.start,
                    baseline = true,
                )
            }
        }
        val explicitLines = explicit.flatMap { region ->
            region.keys.mapIndexed { index, key ->
                TimelineTonalRange(
                    range = region.copy(
                        id = "${region.id}:key:$index",
                        keys = listOf(key),
                        resolvedKey = key.takeIf { region.resolvedKey == key },
                    ),
                    sourceEventId = EventId(region.id),
                    dimmed = false,
                    showLabel = true,
                    baseline = false,
                )
            }
        }
        return baselineSegments + explicitLines
    }

    private fun cardLines(
        chord: ComputedChordEvent,
        readings: List<com.mecon.theory.harmony.HarmonyTimelineReading>,
        keys: List<ModulationKey>,
    ): List<AnnotationTextLine> {
        if (readings.size > 1) {
            val useRelativeTones =
                ChordSymbolDisplaySettings.style == ChordSymbolDisplayStyle.SCALE_DEGREE
            return readings.take(MAX_CARD_LINES).map { reading ->
                AnnotationTextLine(
                    FormattedText.plain(
                        buildString {
                            val tones = if (useRelativeTones) {
                                reading.relativeTones
                            } else {
                                reading.absoluteTones
                            }
                            append(reading.key.timelineLabel())
                            append(": ")
                            append(reading.functionalSymbol)
                            if (tones.isNotEmpty()) {
                                append(" · ")
                                append(tones.joinToString("–"))
                            }
                        },
                    ),
                    fontSize = 9f,
                    color = CARD_TEXT,
                )
            }
        }
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

    private fun tonalAccent(key: ModulationKey): RenderColor = when (HarmonyKeyAccent.of(key)) {
        HarmonyKeyAccent.PRIMARY -> LightTimelinePalette.PrimaryDark
        HarmonyKeyAccent.EMERALD -> LightTimelinePalette.Emerald
        HarmonyKeyAccent.ORANGE -> LightTimelinePalette.Orange
    }

    private fun RenderColor.withAlpha(alpha: Int): RenderColor = copy(alpha = alpha)

    private data class TimelineTonalRange(
        val range: HarmonyTonalRange,
        val sourceEventId: EventId?,
        val dimmed: Boolean,
        val showLabel: Boolean,
        val baseline: Boolean,
    )

    private data class TonalProjection(
        val ranges: List<HarmonyTonalRange>,
        val baselineEventIds: Set<String>,
    )

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
