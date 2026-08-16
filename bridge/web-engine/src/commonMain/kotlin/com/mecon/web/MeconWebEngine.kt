@file:OptIn(ExperimentalJsExport::class)

package com.mecon.web

import com.mecon.api.runtime.RuntimeScore
import com.mecon.api.runtime.ScoreTimeMap
import com.mecon.api.primitive.Accidental
import com.mecon.api.primitive.Duration
import com.mecon.api.primitive.DurationBase
import com.mecon.api.primitive.EventId
import com.mecon.api.primitive.Fraction
import com.mecon.api.primitive.Pitch
import com.mecon.api.primitive.TimeCode
import com.mecon.api.storage.Articulation
import com.mecon.api.storage.ScoreGeometry
import com.mecon.audio.converter.ScoreToMidiConverter
import com.mecon.core.serializer.ScoreSerializer
import com.mecon.renderer.frozen.FrozenScoreCodec
import com.mecon.renderer.frozen.FrozenScoreBundle
import com.mecon.renderer.frozen.FrozenScoreProjector
import com.mecon.renderer.layout.RenderLayoutConfig
import com.mecon.renderer.layout.AlignedTimeAxisRequest
import com.mecon.renderer.layout.TimeAxisSegmentRequest
import com.mecon.renderer.geometry.StaffSpace
import com.mecon.renderer.geometry.RelativePoint
import com.mecon.renderer.geometry.AbsolutePoint
import com.mecon.renderer.geometry.Pixels
import com.mecon.renderer.render.RenderCommand
import com.mecon.renderer.render.RenderElementType
import com.mecon.renderer.render.RenderResult
import com.mecon.renderer.render.RenderEngine
import com.mecon.renderer.smufl.BravuraFont
import com.mecon.features.freepractice.PracticeTimelineView
import com.mecon.features.freepractice.PracticeChordDetailConstructionView
import com.mecon.features.freepractice.toPreviewScore
import com.mecon.features.scoreediting.ScoreSelectionTarget
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
private data class WebRenderFrame(
    val bundle: FrozenScoreBundle,
    val geometry: ScoreGeometry? = null,
    val timeAxis: WebResolvedTimeAxis? = null,
    val playbackAnchors: List<WebPlaybackAnchor> = emptyList(),
)

@Serializable
private data class WebChordDetailFrame(
    val bundle: FrozenScoreBundle,
    val mutedElementIds: List<String>,
    val hiddenElementIds: List<String>,
)

@Serializable
private data class WebPlaybackAnchor(
    val time: Fraction,
    val scoreTime: TimeCode,
    val tick: Long,
)

@Serializable
private data class WebTimeAxisAnchor(
    val time: Fraction,
    val scoreTime: TimeCode,
    val x: Float,
)

@Serializable
private data class WebResolvedTimeAxis(
    val anchors: List<WebTimeAxisAnchor>,
    val measureBoundaries: List<WebTimeAxisAnchor> = emptyList(),
    val contentOriginX: Float,
    val contentEndX: Float,
    val intrinsicContentWidth: Float,
    val surfaceWidth: Float,
    val viewportWidth: Float,
    val scrollExtent: Float,
    val revision: Long,
)

@Serializable
private data class WebTransposeTarget(
    val eventId: String,
    val pitchIndices: Set<Int>? = null,
)

@Serializable
private data class WebRestMoveTarget(
    val eventId: String,
    val staffPosition: Int,
)

@Serializable
private data class WebTransposePreview(
    val baseCommands: List<RenderCommand>,
    val movedCommands: List<RenderCommand>,
    val hiddenElementIds: List<String>,
)

@Serializable
private data class WebNoteInputRequest(
    val x: Float,
    val y: Float,
    val duration: Duration,
    val accidental: Accidental? = null,
    val restMode: Boolean = false,
    val voiceNumber: Int = 1,
    val tupletCount: Int? = null,
    val graceMode: Boolean = false,
)

@Serializable
private data class WebNoteInputTarget(
    val voiceTrackId: String,
    val staffTrackId: String,
    val voiceNumber: Int,
    val start: TimeCode,
    val pitch: Pitch,
    val smallNoteAppendStartEventId: String? = null,
    val commands: List<RenderCommand>,
)

/** Selection-derived note palette state, projected from the same computed frame Web displays. */
@Serializable
private data class WebPaletteSelectionInfo(
    val editable: Boolean = false,
    val durationBase: DurationBase? = null,
    val dots: Int? = null,
    val accidental: Accidental? = null,
    val tieOut: Boolean? = null,
    val voiceNumber: Int? = null,
    val allRests: Boolean = false,
    val tupletCount: Int? = null,
    val effectiveBeamLeft: Boolean? = null,
    val effectiveBeamRight: Boolean? = null,
    val articulations: Set<Articulation> = emptySet(),
)

/**
 * Stable string-only JavaScript boundary for the complete browser engraving engine.
 *
 * Keeping Kotlin collections and internal classes behind JSON makes npm/React upgrades
 * independent from Kotlin's generated object representation.
 */
@JsExport
class MeconWebEngine(
    metadataJson: String,
    glyphNamesJson: String,
    private val engineVersion: String = "web",
) {
    private val font = BravuraFont.fromJson(metadataJson, glyphNamesJson)
    private val json = Json { encodeDefaults = true }
    private var latestRenderer: RenderEngine? = null
    private var latestRuntime: RuntimeScore? = null
    private var latestResult: RenderResult? = null

    /**
     * Run Storage -> Runtime -> Computed -> Layout -> Render and return frozen wire geometry.
     */
    fun renderScoreJson(scoreJson: String): String {
        return FrozenScoreCodec.encode(renderFrame(scoreJson).bundle)
    }

    /** Engrave the shared chord-detail example without teaching or pitch logic in React. */
    fun renderChordDetailConstructionJson(constructionJson: String): String {
        val construction = json.decodeFromString<PracticeChordDetailConstructionView>(constructionJson)
        val preview = construction.toPreviewScore()
        val preservedRenderer = latestRenderer
        val preservedRuntime = latestRuntime
        val preservedResult = latestResult
        val (frame, result) = try {
            renderFrame(ScoreSerializer.toJson(preview.score)) to requireNotNull(latestResult)
        } finally {
            latestRenderer = preservedRenderer
            latestRuntime = preservedRuntime
            latestResult = preservedResult
        }
        val mutedIds = preview.mutedSections.flatMap { sectionId ->
            result.sectionIndex.elementsForSectionId(sectionId).elementIds
        }.distinct().map { it.toString() }
        val hiddenIds = result.elements.asSequence()
            .filter { it.type == RenderElementType.TIME_SIGNATURE || it.type == RenderElementType.BARLINE }
            .map { it.id.toString() }
            .toList()
        return json.encodeToString(WebChordDetailFrame(frame.bundle, mutedIds, hiddenIds))
    }

    /** Render geometry plus the automatic anchor-relative overlay used by Web curve inspectors. */
    fun renderScoreFrameJson(scoreJson: String): String = json.encodeToString(renderFrame(scoreJson))

    /** Render notation and the free-practice timeline against one collision-resolved x projection. */
    fun renderFreePracticeFrameJson(scoreJson: String, timelineJson: String): String =
        json.encodeToString(renderFrame(scoreJson, json.decodeFromString<PracticeTimelineView>(timelineJson)))

    /** Width-aware variant used by the shared Web editor surface. */
    fun renderFreePracticeFrameForWidthJson(
        scoreJson: String,
        timelineJson: String,
        viewportWidthPx: Double,
    ): String = json.encodeToString(
        renderFrame(
            scoreJson,
            json.decodeFromString<PracticeTimelineView>(timelineJson),
            viewportWidthPx.toFloat(),
        )
    )

    /** Renderer-owned note drag preview; the browser only replays these commands. */
    fun transposePreviewJson(targetsJson: String, stepDelta: Int): String {
        val renderer = latestRenderer ?: return "null"
        val runtime = latestRuntime ?: return "null"
        val result = latestResult ?: return "null"
        val targets = json.decodeFromString<List<WebTransposeTarget>>(targetsJson)
            .associate { EventId(it.eventId) to it.pitchIndices }
        val preview = renderer.computeTransposePreview(result, runtime, targets, stepDelta)
            ?: return "null"
        val hiddenTypes = setOf(
            RenderElementType.NOTEHEAD,
            RenderElementType.ACCIDENTAL,
            RenderElementType.DOT,
            RenderElementType.LEDGER_LINE,
            RenderElementType.STEM,
            RenderElementType.FLAG,
        )
        val hiddenIds = targets.keys.flatMap { eventId ->
            result.elementsForEvent(eventId)
                .filter { it.type in hiddenTypes }
                .map { it.id.toString() }
        }.distinct()
        return json.encodeToString(
            WebTransposePreview(preview.baseCommands, preview.movedCommands, hiddenIds)
        )
    }

    /** Renderer-owned rest drag preview; the browser only replays these commands. */
    fun restMovePreviewJson(targetsJson: String): String {
        val renderer = latestRenderer ?: return "null"
        val result = latestResult ?: return "null"
        val targets = json.decodeFromString<List<WebRestMoveTarget>>(targetsJson)
            .associate { EventId(it.eventId) to it.staffPosition }
        val preview = renderer.computeRestMovePreview(result, targets) ?: return "null"
        val hiddenTypes = setOf(RenderElementType.REST, RenderElementType.DOT)
        val hiddenIds = targets.keys.flatMap { eventId ->
            result.elementsForEvent(eventId)
                .filter { it.type in hiddenTypes }
                .map { it.id.toString() }
        }.distinct()
        return json.encodeToString(
            WebTransposePreview(preview.baseCommands, preview.movedCommands, hiddenIds)
        )
    }

    /** Resolve a browser pointer into stable musical coordinates using the renderer's note ghost. */
    fun noteInputTargetJson(requestJson: String): String {
        val renderer = latestRenderer ?: return "null"
        val runtime = latestRuntime ?: return "null"
        val result = latestResult ?: return "null"
        val request = json.decodeFromString<WebNoteInputRequest>(requestJson)
        val ghost = renderer.computeGhost(
            result = result,
            runtime = runtime,
            point = AbsolutePoint(Pixels(request.x), Pixels(request.y)),
            duration = request.duration,
            accidental = request.accidental,
            restMode = request.restMode,
            voiceNumber = request.voiceNumber,
            tupletCount = request.tupletCount,
            graceMode = request.graceMode,
        ) ?: return "null"
        return json.encodeToString(
            WebNoteInputTarget(
                voiceTrackId = ghost.voiceTrackId.value,
                staffTrackId = ghost.staffTrackId.value,
                voiceNumber = ghost.voiceNumber,
                start = ghost.onset,
                pitch = ghost.pitch,
                smallNoteAppendStartEventId = ghost.smallNoteAppendStartEventId?.value,
                commands = ghost.commands,
            )
        )
    }

    /** Apply the shared accidental enum mapping to non-pointer step/manual note input. */
    fun applyAccidentalToPitchJson(pitchJson: String, accidentalName: String): String {
        val pitch = json.decodeFromString<Pitch>(pitchJson)
        val accidental = enumValueOf<Accidental>(accidentalName)
        return json.encodeToString(pitch.copy(chromaticOffset = accidental.offset))
    }

    /**
     * Project the shared selection into note-palette highlights without duplicating accidental,
     * tuplet, tie, or effective-beam interpretation in React.
     */
    fun paletteSelectionInfoJson(selectionJson: String): String {
        val runtime = latestRuntime
        val computed = latestRenderer?.lastRenderedComputed()
        if (runtime == null || computed == null) {
            return json.encodeToString(WebPaletteSelectionInfo())
        }
        val targets = json.decodeFromString<List<ScoreSelectionTarget>>(selectionJson)
            .filterIsInstance<ScoreSelectionTarget.Event>()
        val selections = linkedMapOf<EventId, Pair<Boolean, MutableSet<Int>>>()
        for (target in targets) {
            val current = selections[target.eventId]
            val whole = current?.first == true || target.pitchIndices == null
            val indices = current?.second ?: linkedSetOf()
            target.pitchIndices?.let(indices::addAll)
            selections[target.eventId] = whole to indices
        }
        val resolved = selections.mapNotNull { (eventId, selection) ->
            computed.getComputedEvent(eventId)?.let { event ->
                val selectedPitches = if (selection.first) {
                    event.pitchData
                } else {
                    selection.second.mapNotNull(event.pitchData::getOrNull)
                }
                Triple(event, selectedPitches, targets.firstOrNull { it.eventId == eventId })
            }
        }
        if (resolved.isEmpty()) return json.encodeToString(WebPaletteSelectionInfo())

        fun <T> common(values: List<T>): T? = values.distinct().singleOrNull()
        val events = resolved.map { it.first }
        val notePitches = resolved.filterNot { it.first.isRest }.flatMap { it.second }
        val voiceNumbers = resolved.mapNotNull { (event, _, target) ->
            val voiceId = target?.voiceTrackId ?: event.originVoiceTrackId
            voiceId?.let(runtime::getVoiceTrack)?.voiceNumber
                ?: runtime.voiceTracks.values.firstOrNull { voice ->
                    voice.events.toList().any { it.id == event.id }
                }?.voiceNumber
        }
        return json.encodeToString(
            WebPaletteSelectionInfo(
                editable = true,
                durationBase = common(events.map { it.duration.base }),
                dots = common(events.map { it.duration.dots }),
                accidental = if (notePitches.isEmpty()) null
                    else common(notePitches.map { it.effectiveAccidental }),
                tieOut = if (notePitches.isEmpty()) null
                    else common(notePitches.map { it.tieTarget != null }),
                voiceNumber = common(voiceNumbers),
                allRests = events.all { it.isRest },
                tupletCount = common(events.map { it.duration.tuplet?.actual }),
                effectiveBeamLeft = common(events.map { it.beamInfo?.let { beam -> beam.beamsLeft > 0 } ?: false }),
                effectiveBeamRight = common(events.map { it.beamInfo?.let { beam -> beam.beamsRight > 0 } ?: false }),
                articulations = events.map { it.articulations.toSet() }
                    .reduceOrNull { shared, next -> shared intersect next }
                    .orEmpty(),
            )
        )
    }

    private fun renderFrame(
        scoreJson: String,
        timeline: PracticeTimelineView? = null,
        viewportWidthPx: Float = 0f,
    ): WebRenderFrame {
        val score = ScoreSerializer.fromJson(scoreJson)
        val runtime = RuntimeScore.fromStorage(score)
        return with(font) {
            val initialRequest = timeline?.toAlignedTimeAxis(runtime)
            val renderer = RenderEngine(
                RenderLayoutConfig.DEFAULT.copy(alignedTimeAxisRequest = initialRequest)
            )
            val result = renderer.render(runtime)
            latestRenderer = renderer
            latestRuntime = runtime
            latestResult = result
            val scoreTimeMap = ScoreTimeMap.from(runtime)
            val playbackTimes = result.timeCodePositions.values
                .map { it.timeCode }
                .distinct()
            val playbackTicks = ScoreToMidiConverter.timeCodesToPlaybackTicks(playbackTimes, runtime)
            WebRenderFrame(
                bundle = FrozenScoreProjector.project(
                    result = result,
                    engineVersion = engineVersion,
                    fontFingerprint = "${font.metadata.fontName}-${font.metadata.fontVersion}",
                ),
                geometry = renderer.captureGeometry(),
                playbackAnchors = playbackTimes.zip(playbackTicks) { time, tick ->
                    WebPlaybackAnchor(scoreTimeMap.absolute(time), time, tick)
                }.sortedBy { it.tick },
                timeAxis = result.resolvedTimeAxis?.let { axis ->
                    val transformer = result.transformerSnapshot
                    val surfaceOriginX = result.bounds.origin.x.value
                    fun pixelX(x: StaffSpace): Float = transformer.toAbsolute(
                        RelativePoint(x, StaffSpace.ZERO)
                    ).x.value - surfaceOriginX
                    WebResolvedTimeAxis(
                        anchors = axis.anchors.map { anchor ->
                            WebTimeAxisAnchor(anchor.absoluteTime, anchor.time, pixelX(anchor.x))
                        },
                        measureBoundaries = axis.measureBoundaries.map { anchor ->
                            WebTimeAxisAnchor(anchor.absoluteTime, anchor.time, pixelX(anchor.x))
                        },
                        contentOriginX = 0f,
                        contentEndX = pixelX(axis.contentEndX),
                        intrinsicContentWidth = result.bounds.width.value,
                        surfaceWidth = maxOf(result.bounds.width.value, viewportWidthPx),
                        viewportWidth = viewportWidthPx,
                        scrollExtent = (maxOf(result.bounds.width.value, viewportWidthPx) - viewportWidthPx)
                            .coerceAtLeast(0f),
                        revision = axis.revision,
                    )
                },
            )
        }
    }

    private fun PracticeTimelineView.toAlignedTimeAxis(score: RuntimeScore): AlignedTimeAxisRequest? {
        val finish = maxOf(end, slots.maxOfOrNull { it.onset + it.duration } ?: Fraction.ZERO)
        if (finish <= Fraction.ZERO) return null
        val boundaries = buildSet {
            add(Fraction.ZERO)
            add(finish)
            slots.forEach { slot ->
                add(slot.onset)
                add(slot.onset + slot.duration)
            }
            tonalLayouts.forEach { layout ->
                add(layout.start)
                layout.end?.let(::add)
            }
        }.filter { it >= Fraction.ZERO && it <= finish }.sorted()
        if (boundaries.size < 2) return null
        val timeMap = ScoreTimeMap.from(score)
        return AlignedTimeAxisRequest(
            segments = boundaries.zipWithNext().map { (start, segmentEnd) ->
                TimeAxisSegmentRequest(
                    start = timeMap.timeCodeAt(start),
                    end = timeMap.timeCodeAt(segmentEnd),
                    // Bravura's Web surface uses 8 px per staff space. Eighteen spaces per
                    // quarter therefore matches the desktop timeline's 144 dp default scale.
                    preferredWidth = StaffSpace((segmentEnd - start).toFloat() * 72f),
                )
            },
            extraAnchors = boundaries.mapTo(linkedSetOf(), timeMap::timeCodeAt),
            notationContentStartGap = StaffSpace(0.75f),
            // The axis generation must identify the timeline it was built from, otherwise clients
            // cannot tell a freshly resolved axis from one belonging to an earlier practice frame.
            revision = axisGeneration(boundaries),
        )
    }

    /** Order-sensitive hash of the segment boundaries the axis is derived from. */
    private fun axisGeneration(boundaries: List<Fraction>): Long =
        boundaries.fold(17L) { hash, boundary ->
            hash * 31 + boundary.numerator * 131L + boundary.denominator
        }
}
