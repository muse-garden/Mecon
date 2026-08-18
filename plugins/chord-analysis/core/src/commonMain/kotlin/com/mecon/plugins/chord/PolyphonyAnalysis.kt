package com.mecon.plugins.chord

import com.mecon.api.computed.ComputedScore
import com.mecon.api.computed.ComputedVoiceEvent
import com.mecon.api.computed.pluginEventsOf
import com.mecon.api.plugin.AnnotationAlignment
import com.mecon.api.plugin.AnnotationElement
import com.mecon.api.plugin.AnnotationLayoutContext
import com.mecon.api.plugin.AnnotationStaffProvider
import com.mecon.api.plugin.PluginStaffId
import com.mecon.api.plugin.StaffAnchor
import com.mecon.api.primitive.EventId
import com.mecon.api.primitive.KeySignature
import com.mecon.api.primitive.Pitch
import com.mecon.api.primitive.TimeCode
import com.mecon.api.primitive.TrackId
import com.mecon.api.render.RenderColor
import com.mecon.api.runtime.TimeIndexedList
import com.mecon.api.runtime.ScoreTimeMap
import com.mecon.theory.Chord
import com.mecon.theory.ChordQuality
import com.mecon.theory.ChordRecognizer
import com.mecon.theory.ChordSymbolDisplayStyle
import com.mecon.theory.ChordSymbolFormatter
import com.mecon.theory.KeySignatureMode
import com.mecon.theory.ModulationCommonChordCatalog
import com.mecon.theory.ModulationKey
import com.mecon.theory.harmony.HarmonyTonalRange
import com.mecon.theory.harmony.HarmonyTonalTimeline

data class PolyphonyActivePitch(
    val eventId: EventId,
    val pitchIndex: Int,
    val pitch: Pitch,
    val endTime: TimeCode,
)

data class PolyphonyTimeFrame(
    val time: TimeCode,
    val activePitches: List<PolyphonyActivePitch>,
    val tonalKeys: List<ModulationKey>,
    val recognizedChord: Chord?,
)

/** Shared formatting policy for scale-degree labels on the score and piano roll. */
object PolyphonyDegreeFormatter {
    fun format(keys: List<ModulationKey>, pitch: Pitch): String {
        val ambiguous = keys.size > 1
        return keys.joinToString(" · ") { key ->
            val degree = ModulationCommonChordCatalog.relativePitchLabel(key, pitch.pitchClass)
            if (ambiguous) {
                "${key.displayName}${if (key.mode == KeySignatureMode.MINOR) "m" else ""}:$degree"
            } else {
                degree
            }
        }
    }
}

/**
 * Resolves explicit ambiguous regions and the tonal center that remains after
 * a resolved region ends.
 */
object PolyphonyTonalContextResolver {
    internal data class Projection(
        val timeMap: ScoreTimeMap,
        val ranges: List<HarmonyTonalRange>,
    )

    internal fun projection(
        score: ComputedScore,
        regions: List<StorageTonalRegionEvent>,
    ): Projection {
        val timeMap = ScoreTimeMap.from(score.runtime)
        return Projection(
            timeMap = timeMap,
            ranges = regions.mapNotNull { region ->
                HarmonyTonalRange.clippedOrNull(
                    id = region.id.value,
                    start = timeMap.absolute(region.onset),
                    end = timeMap.absolute(region.endOnset),
                    keys = region.keys.map(PolyphonyTonalKey::toModulationKey),
                    resolvedKey = region.resolvedKey?.toModulationKey(),
                    priority = 10,
                )
            },
        )
    }

    fun keysAt(
        score: ComputedScore,
        time: TimeCode,
        regions: List<StorageTonalRegionEvent> =
            score.pluginEventsOf(StorageTonalRegionEvent.TRACK_TYPE),
    ): List<ModulationKey> = keysAt(score, time, projection(score, regions))

    internal fun keysAt(
        score: ComputedScore,
        time: TimeCode,
        projection: Projection,
    ): List<ModulationKey> {
        val signature = score.runtime.getKeySignatureAt(time.measure)
        val defaultKey =
            ModulationKey(
                fifths = signature.fifths,
                mode = KeySignatureMode.fromApiMode(signature.mode),
            )
        return HarmonyTonalTimeline.keysAt(
            time = projection.timeMap.absolute(time),
            ranges = projection.ranges,
            defaultKey = defaultKey,
        )
    }
}

/**
 * Stateful frame cache for the polyphonic assistant.
 *
 * Voice edits use [TimeIndexedList.changedSpan] and patch only the interval in
 * which the old/new event can sound. Persisted analysis-region edits are rarer
 * and intentionally rebuild the frame snapshot to keep interval semantics
 * straightforward and deterministic.
 */
object PolyphonyAnalysisEngine {
    private data class PluginSignature(
        val nonChordTones: List<StorageNonChordToneEvent>,
        val tonalRegions: List<StorageTonalRegionEvent>,
        val defaultKeySignature: KeySignature,
        val keySignatures: List<Pair<Int, KeySignature>>,
    ) {
        val nonChordByNote: Map<Pair<EventId, Int>, List<StorageNonChordToneEvent>> by lazy {
            nonChordTones.groupBy { it.voiceEventId to it.pitchIndex }
        }
    }

    private var cachedVoices: TimeIndexedList<ComputedVoiceEvent>? = null
    private var cachedPluginSignature: PluginSignature? = null
    private var cachedTonalProjection: PolyphonyTonalContextResolver.Projection? = null
    private var cachedFrames: Map<TimeCode, PolyphonyTimeFrame> = emptyMap()

    var lastRecomputedFrameCount: Int = 0
        private set

    fun compute(score: ComputedScore): Map<TimeCode, PolyphonyTimeFrame> {
        val voices = score.computedEvents.asTimeIndexedList()
        val signature = PluginSignature(
            nonChordTones = score
                .pluginEventsOf<StorageNonChordToneEvent>(StorageNonChordToneEvent.TRACK_TYPE)
                .sortedWith(
                    compareBy<StorageNonChordToneEvent> { it.onset }.thenBy { it.id.value }
                ),
            tonalRegions = score
                .pluginEventsOf<StorageTonalRegionEvent>(StorageTonalRegionEvent.TRACK_TYPE)
                .sortedWith(
                    compareBy<StorageTonalRegionEvent> { it.onset }.thenBy { it.id.value }
                ),
            defaultKeySignature = score.runtime.defaultKeySignature,
            keySignatures = score.runtime.measures.map { it.key to it.value.keySignature },
        )
        val previousVoices = cachedVoices
        if (previousVoices == null || cachedPluginSignature != signature) {
            val tonalProjection = PolyphonyTonalContextResolver.projection(
                score,
                signature.tonalRegions,
            )
            return rebuildAll(score, voices, signature, tonalProjection)
        }

        val changed = previousVoices.changedSpan(voices)
        if (changed == null) {
            lastRecomputedFrameCount = 0
            return cachedFrames
        }
        val tonalProjection = cachedTonalProjection
            ?: PolyphonyTonalContextResolver.projection(score, signature.tonalRegions)

        val oldChanged = previousVoices.range(changed.start, changed.end)
        val newChanged = voices.range(changed.start, changed.end)
        val windowEnd = (oldChanged.asSequence() + newChanged.asSequence())
            .map { it.endTime }
            .fold(changed.end, ::maxOf)
        val starts = voices.range(changed.start, windowEnd)
        val boundaries = linkedSetOf<TimeCode>().apply {
            cachedFrames.keys.filterTo(this) { it >= changed.start && it <= windowEnd }
            oldChanged.forEach { add(it.onset); add(it.endTime) }
            starts.forEach { add(it.onset); add(it.endTime) }
        }.sorted()

        val active = cachedFrames.entries
            .lastOrNull { it.key < changed.start }
            ?.value
            ?.activePitches
            .orEmpty()
            .associateByTo(linkedMapOf()) { it.eventId to it.pitchIndex }
        val startsByTime = starts.filterNot(ComputedVoiceEvent::isRest).groupBy(ComputedVoiceEvent::onset)
        val patched = cachedFrames
            .filterKeys { it < changed.start || it > windowEnd }
            .toMutableMap()

        for (time in boundaries) {
            active.entries.removeAll { it.value.endTime <= time }
            startsByTime[time].orEmpty().forEach { event ->
                event.pitchData.forEachIndexed { pitchIndex, pitchData ->
                    val pitch = PolyphonyActivePitch(event.id, pitchIndex, pitchData.pitch, event.endTime)
                    active[event.id to pitchIndex] = pitch
                }
            }
            buildFrame(score, time, active.values.toList(), signature, tonalProjection)
                ?.let { patched[time] = it }
        }

        cachedVoices = voices
        cachedPluginSignature = signature
        cachedTonalProjection = tonalProjection
        cachedFrames = patched.entries.sortedBy { it.key }.associate { it.toPair() }
        lastRecomputedFrameCount = boundaries.size
        return cachedFrames
    }

    fun resetForTesting() {
        cachedVoices = null
        cachedPluginSignature = null
        cachedTonalProjection = null
        cachedFrames = emptyMap()
        lastRecomputedFrameCount = 0
    }

    private fun rebuildAll(
        score: ComputedScore,
        voices: TimeIndexedList<ComputedVoiceEvent>,
        signature: PluginSignature,
        tonalProjection: PolyphonyTonalContextResolver.Projection,
    ): Map<TimeCode, PolyphonyTimeFrame> {
        val events = voices.toList().filterNot(ComputedVoiceEvent::isRest)
        val startsByTime = events.groupBy(ComputedVoiceEvent::onset)
        val boundaries = events.flatMap { listOf(it.onset, it.endTime) }.distinct().sorted()
        val active = linkedMapOf<Pair<EventId, Int>, PolyphonyActivePitch>()
        val frames = linkedMapOf<TimeCode, PolyphonyTimeFrame>()
        for (time in boundaries) {
            active.entries.removeAll { it.value.endTime <= time }
            startsByTime[time].orEmpty().forEach { event ->
                event.pitchData.forEachIndexed { pitchIndex, pitchData ->
                    val pitch = PolyphonyActivePitch(event.id, pitchIndex, pitchData.pitch, event.endTime)
                    active[event.id to pitchIndex] = pitch
                }
            }
            buildFrame(score, time, active.values.toList(), signature, tonalProjection)
                ?.let { frames[time] = it }
        }
        cachedVoices = voices
        cachedPluginSignature = signature
        cachedTonalProjection = tonalProjection
        cachedFrames = frames
        lastRecomputedFrameCount = boundaries.size
        return cachedFrames
    }

    private fun buildFrame(
        score: ComputedScore,
        time: TimeCode,
        active: List<PolyphonyActivePitch>,
        signature: PluginSignature,
        tonalProjection: PolyphonyTonalContextResolver.Projection,
    ): PolyphonyTimeFrame? {
        if (active.isEmpty()) return null
        val eligible = active.filterNot { pitch ->
            signature.nonChordByNote[pitch.eventId to pitch.pitchIndex].orEmpty().any { it.contains(time) }
        }
        val chord = ChordRecognizer.recognize(eligible.map(PolyphonyActivePitch::pitch))
            .filter { it.complete }
            .singleOrNull()
            ?.chord
        return PolyphonyTimeFrame(
            time = time,
            activePitches = active.sortedBy(PolyphonyActivePitch::pitch),
            tonalKeys = PolyphonyTonalContextResolver.keysAt(score, time, tonalProjection),
            recognizedChord = chord,
        )
    }
}

/**
 * One compact analysis band above every rendered system.
 */
object PolyphonyAnnotationProvider : AnnotationStaffProvider {
    override val staffId: PluginStaffId = PluginStaffId("mecon.chord_analysis.polyphony")
    override val anchor: StaffAnchor = StaffAnchor.AboveAllStaves
    override val pluginTrackTypes: Set<String> = emptySet()

    override fun layout(ctx: AnnotationLayoutContext): List<AnnotationElement> {
        if (!PolyphonyDisplaySettings.isEnabled) return emptyList()
        val frames = PolyphonyAnalysisEngine.compute(ctx.computedScore)
        if (frames.isEmpty()) return emptyList()
        val nonChordByNote = ctx.computedScore
            .pluginEventsOf<StorageNonChordToneEvent>(StorageNonChordToneEvent.TRACK_TYPE)
            .groupBy { it.voiceEventId to it.pitchIndex }

        return buildList {
            if (PolyphonyDisplaySettings.showDegreeTrack) {
                frames.values.forEach { frame ->
                    frame.activePitches
                        .sortedByDescending(PolyphonyActivePitch::pitch)
                        .forEachIndexed { row, active ->
                            val explicitNonChord = nonChordByNote[
                                active.eventId to active.pitchIndex
                            ].orEmpty().any { it.contains(frame.time) }
                            val text = PolyphonyDegreeFormatter.format(
                                frame.tonalKeys,
                                active.pitch,
                            ) + if (explicitNonChord) "×" else ""
                            add(
                                AnnotationElement.Text.plain(
                                    time = frame.time,
                                    text = text,
                                    relativeY = row * DEGREE_ROW_GAP,
                                    trackId = TrackId("$DEGREE_ROW_TRACK_PREFIX$row"),
                                    fontSize = DEGREE_FONT_SIZE,
                                    color = when {
                                        active.eventId to active.pitchIndex in
                                            PolyphonyDisplaySettings.selectedNoteheads -> SELECTED_COLOR
                                        explicitNonChord -> NON_CHORD_COLOR
                                        else -> DEGREE_COLOR
                                    },
                                    alignment = AnnotationAlignment.CENTER,
                                    interactive = false,
                                )
                            )
                        }
                }
            }

            addAll(tonalRegionMarkers(ctx.computedScore))
            if (PolyphonyDisplaySettings.showPassingChords) {
                addAll(passingChordElements(ctx.computedScore, frames))
            }
        }
    }

    private fun tonalRegionMarkers(score: ComputedScore): List<AnnotationElement> =
        score.pluginEventsOf<StorageTonalRegionEvent>(StorageTonalRegionEvent.TRACK_TYPE)
            .flatMap { region ->
                val candidates = region.keys.joinToString(" · ") { it.displayName() }
                val endLabel = region.resolvedKey?.let { "→ ${it.displayName()} ⟧" } ?: "⟧"
                listOf(
                    AnnotationElement.Text.plain(
                        time = region.onset,
                        text = "⟦ $candidates${if (region.isAmbiguous) " ?" else ""}",
                        relativeY = REGION_ROW_Y,
                        sourceEventId = region.id,
                        trackId = REGION_TRACK_ID,
                        fontSize = REGION_FONT_SIZE,
                        color = REGION_COLOR,
                        alignment = AnnotationAlignment.LEFT,
                    ),
                    AnnotationElement.Text.plain(
                        time = region.endOnset,
                        text = endLabel,
                        relativeY = REGION_ROW_Y,
                        sourceEventId = region.id,
                        trackId = REGION_TRACK_ID,
                        fontSize = REGION_FONT_SIZE,
                        color = REGION_COLOR,
                        alignment = AnnotationAlignment.RIGHT,
                    ),
                )
            }

    private fun passingChordElements(
        score: ComputedScore,
        frames: Map<TimeCode, PolyphonyTimeFrame>,
    ): List<AnnotationElement> {
        val marked = score.pluginEventsOf<StorageChordEvent>(StorageChordEvent.TRACK_TYPE)
            .sortedBy(StorageChordEvent::onset)
        val frameByTime = frames
        val timeline = (marked.map(StorageChordEvent::onset) + frameByTime.keys).distinct().sorted()
        val markedAt = marked.groupBy(StorageChordEvent::onset)
        var previousIdentity: Pair<Int, ChordQuality>? = null
        val out = mutableListOf<AnnotationElement>()

        for (time in timeline) {
            val userChord = markedAt[time]?.lastOrNull()
            if (userChord != null) {
                previousIdentity = userChord.root to userChord.quality
                continue
            }
            val frame = frameByTime[time] ?: continue
            val chord = frame.recognizedChord ?: continue
            val identity = chord.root.value to chord.quality
            if (identity == previousIdentity) continue
            previousIdentity = identity
            val degreeRowsHeight = if (PolyphonyDisplaySettings.showDegreeTrack) {
                frame.activePitches.size * DEGREE_ROW_GAP
            } else {
                0f
            }
            out += AnnotationElement.Text.plain(
                time = time,
                text = formatChord(chord, frame.tonalKeys),
                relativeY = degreeRowsHeight + PASSING_CHORD_OFFSET,
                trackId = PASSING_CHORD_TRACK_ID,
                fontSize = PASSING_CHORD_FONT_SIZE,
                color = PASSING_CHORD_COLOR,
                alignment = AnnotationAlignment.CENTER,
                interactive = false,
            )
        }
        return out
    }

    private fun formatChord(chord: Chord, keys: List<ModulationKey>): String {
        if (ChordSymbolDisplaySettings.style == ChordSymbolDisplayStyle.LETTER) {
            return ChordSymbolFormatter.format(chord)
        }
        return keys.joinToString(" · ") { key ->
            val degree = ModulationCommonChordCatalog.relativePitchLabel(key, chord.root)
            val keyPrefix = if (keys.size > 1) "${key.displayName}${
                if (key.mode == KeySignatureMode.MINOR) "m" else ""
            }:" else ""
            keyPrefix + degree + ChordSymbolFormatter.qualitySuffix(chord.quality)
        }
    }

    private fun PolyphonyTonalKey.displayName(): String =
        toModulationKey().let { it.displayName + if (it.mode == KeySignatureMode.MINOR) "m" else "" }

    private const val DEGREE_FONT_SIZE = 12f
    private const val DEGREE_ROW_GAP = 1.15f
    private const val PASSING_CHORD_FONT_SIZE = 10f
    private const val PASSING_CHORD_OFFSET = 0.6f
    private const val REGION_FONT_SIZE = 10f
    private const val REGION_ROW_Y = -1.35f
    private const val DEGREE_ROW_TRACK_PREFIX = "__polyphony_degree_row_"
    private val PASSING_CHORD_TRACK_ID = TrackId("__polyphony_passing_chords")
    private val REGION_TRACK_ID = TrackId("__polyphony_tonal_regions")
    private val DEGREE_COLOR = RenderColor.rgb(71, 85, 105)
    private val SELECTED_COLOR = RenderColor.rgb(249, 115, 22)
    private val NON_CHORD_COLOR = RenderColor.rgb(168, 85, 247)
    private val PASSING_CHORD_COLOR = RenderColor.rgb(14, 116, 144)
    private val REGION_COLOR = RenderColor.rgb(99, 102, 241)
}
