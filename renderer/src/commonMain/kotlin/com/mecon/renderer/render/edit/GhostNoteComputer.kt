package com.mecon.renderer.render.edit

import com.mecon.api.computed.ComputedPitchData
import com.mecon.api.primitive.Accidental
import com.mecon.api.primitive.Duration
import com.mecon.api.primitive.EventId
import com.mecon.api.primitive.Fraction
import com.mecon.api.primitive.Pitch
import com.mecon.api.primitive.TimeCode
import com.mecon.api.primitive.TrackId
import com.mecon.api.render.RenderColor
import com.mecon.api.runtime.RuntimeScore
import com.mecon.api.runtime.orderedStaffs
import com.mecon.api.runtime.tracks.RuntimeVoiceTrack
import com.mecon.api.storage.tracks.Clef
import com.mecon.core.engine.edit.NoteEditEngine
import com.mecon.renderer.elements.FlagElement
import com.mecon.renderer.elements.NoteElement
import com.mecon.renderer.geometry.AbsolutePoint
import com.mecon.renderer.geometry.Pixels
import com.mecon.renderer.geometry.RelativeLine
import com.mecon.renderer.geometry.RelativePoint
import com.mecon.renderer.geometry.StaffSpace
import com.mecon.renderer.geometry.NoteScale
import com.mecon.renderer.layout.NoteBodyElementBuilder
import com.mecon.renderer.layout.RenderLayoutConfig
import com.mecon.renderer.layout.VoiceEventLayoutBuilder
import com.mecon.renderer.layout.stem.StemDirectionResolver
import com.mecon.renderer.layout.stem.StemResolutionInput
import com.mecon.renderer.layout.stem.VoiceContext
import com.mecon.renderer.render.DrawLine
import com.mecon.renderer.render.RenderCommand
import com.mecon.renderer.render.RenderElementType
import com.mecon.renderer.render.RenderHelpers
import com.mecon.renderer.render.RenderResult
import com.mecon.renderer.smufl.BravuraFont
import com.mecon.renderer.smufl.SmuflGlyphs
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * A snapped note-pen preview: where a note/rest would be inserted, plus the tinted geometry to draw.
 *
 * [commands] are in absolute (global render) coordinates — the same space the [RenderResult]'s
 * elements live in before any page slicing — so the UI can either draw them directly (continuous
 * mode) or translate them onto the owning page (paginated mode) using [anchor].
 */
data class GhostNote(
    /** Voice track the note would be inserted into. */
    val voiceTrackId: TrackId,
    /** Staff track under the cursor; used to lazily create the requested voice if needed. */
    val staffTrackId: TrackId,
    /** Voice number selected in the note palette. */
    val voiceNumber: Int,
    /** Snapped onset. */
    val onset: TimeCode,
    /**
     * Exact small-note group to append to when hovering its dedicated visual gap.
     * Null means [onset] belongs to the ordinary time axis, including a group endpoint.
     */
    val smallNoteAppendStartEventId: EventId? = null,
    /** Pitch under the cursor (ignored when the rest tool is active). */
    val pitch: Pitch,
    /** Preview render commands, absolute (global) coordinates, ready to draw with a tint. */
    val commands: List<RenderCommand>,
    /** Notehead anchor (absolute, global) — used to locate the owning page in paginated mode. */
    val anchor: AbsolutePoint,
)

/**
 * Computes the note-pen ghost preview entirely in the renderer, so the previewed notehead, stem,
 * flag and ledger lines are produced by the *same* builders that engrave real notes — guaranteeing
 * the ghost's stem aligns exactly with how the note will look once committed.
 *
 * Staff / system resolution reuses the render result's [HierarchicalSpatialIndex] (the same
 * structure used for click hit-testing), which makes the preview work unchanged across continuous
 * and paginated / multi-system (分行) layouts.
 *
 * Pure and stateless: every call reads only its arguments (the displayed [RenderResult] and the
 * [RuntimeScore]) and allocates fresh geometry, so it is safe to call from the pointer-event
 * coroutine on every mouse move without locking against the render thread.
 */
context(BravuraFont)
class GhostNoteComputer(private val config: RenderLayoutConfig = RenderLayoutConfig.DEFAULT) {

    private data class SnapTarget(
        val onset: TimeCode,
        val absoluteX: Float,
        val smallNoteAppendStartEventId: EventId? = null,
    )

    private data class AppendZone(
        val startEventId: EventId,
        val endpoint: TimeCode,
        val previewX: Float,
        val lastRight: Float,
    )

    private val noteBodyBuilder = NoteBodyElementBuilder(config)
    private val voiceLayoutBuilder = VoiceEventLayoutBuilder(config)
    private val stemResolver = StemDirectionResolver(
        voiceConfig = config.voiceStemConfig,
        beamConfig = config.beamLayoutConfig
    )

    /**
     * Resolve a hovered absolute point into a ghost note, or null when the cursor is not near any
     * editable staff (or no insertable position can be found).
     *
     * @param result The currently displayed render result (provides spatial index + transformer +
     *   time-code positions — the single source of truth for what is on screen).
     * @param point  Cursor position in the same absolute coordinate space the result's [hitTest] uses
     *   (global render space; in paginated mode the caller maps the page-local click back first).
     */
    fun compute(
        result: RenderResult,
        runtime: RuntimeScore,
        point: AbsolutePoint,
        duration: Duration,
        accidental: Accidental?,
        restMode: Boolean,
        voiceNumber: Int = 1,
        tupletCount: Int? = null,
        graceMode: Boolean = false,
    ): GhostNote? {
        // The insertion duration is the whole tuplet span (for example a quarter-note span for
        // three eighth-note members). Engrave the ghost as the member unit while keeping the
        // original duration untouched for snapping and the eventual edit intent.
        val displayDuration = tupletCount
            ?.let { NoteEditEngine.tupletSpecFor(duration.toFraction(), it) }
            ?.let { Duration(it.beatUnit) }
            ?: duration
        val transformer = result.transformerSnapshot
        val relPoint = transformer.toRelative(point)

        // --- staff / system under the cursor (reuses the hit-test spatial structures) ---
        val staffHit = result.spatialIndex.staffAt(relPoint) ?: return null
        val staffTrack = runtime.orderedStaffs().getOrNull(staffHit.staffIndex) ?: return null
        val targetVoice = staffTrack.voiceTracks.firstOrNull { it.voiceNumber == voiceNumber }
        val voice = targetVoice
            ?: staffTrack.voiceTracks.firstOrNull()
            ?: return null
        val centerY = staffHit.centerY

        // --- Y → staff position (0.5 staff space per diatonic step; 0 = middle line) ---
        val staffPos = ((centerY.value - relPoint.y.value) / 0.5f).roundToInt()
        val diatonicSteps = staffPos + middleLineDiatonicSteps(staffTrack.clef)

        // --- X → snapped onset (absolute), then to relative for geometry placement ---
        // The snap is constrained to the hovered system's vertical band: in 分行/paginated layouts
        // every system restarts X near the left margin, so an X-only nearest-match could otherwise
        // land on a measure in a different line. Use the resolved staff's centre (absolute Y).
        val absCenterY = transformer.toAbsolute(RelativePoint(relPoint.x, centerY)).y.value
        val snap = snapOnset(
            result = result,
            runtime = runtime,
            voice = voice,
            targetAbsX = point.x.value,
            targetAbsY = point.y.value,
            systemY = absCenterY,
            includeNonDyadicVoiceOnsets = targetVoice != null,
            includeMeasureEnds = graceMode,
            smallNoteDuration = duration.takeIf { targetVoice != null },
        ) ?: return null
        val onset = snap.onset
        val snapAbsX = snap.absoluteX
        val snapRelX = transformer.toRelative(AbsolutePoint(Pixels(snapAbsX), Pixels(0f))).x
        val measureNumber = onset.measure

        // Resolve the chromatic offset: an explicit accidental wins; otherwise carry forward the last
        // accidental applied to this staff position earlier in the same measure (standard notation
        // rule), so a no-accidental click matches the prior altered pitch instead of reverting to
        // natural. effectiveAccidental stays the tool's choice, so a carried-over accidental shows no
        // glyph — exactly how the committed note will engrave via EffectiveAccidentalComputer.
        val chromaticOffset = accidental?.offset
            ?: carriedAccidentalOffset(voice, onset, diatonicSteps)
            ?: 0
        val pitch = Pitch(diatonicSteps = diatonicSteps, chromaticOffset = chromaticOffset)

        val onsetAbs = absoluteWholeNotes(onset, runtime)
        val inSmallNoteRegion = snap.smallNoteAppendStartEventId != null ||
            targetVoice?.events?.toList()?.any { event ->
                val span = event.tupletSpan
                span?.smallNotes == true &&
                    onsetAbs >= absoluteWholeNotes(event.onset, runtime) &&
                    onsetAbs < absoluteWholeNotes(span.endTimeCode, runtime)
            } == true
        val previewScale = when {
            graceMode -> config.graceNoteScale
            inSmallNoteRegion -> SMALL_NOTE_PREVIEW_SCALE
            else -> 1f
        }
        val pitchData = ComputedPitchData(
            pitch = pitch,
            midiPitch = pitch.midiNumber,
            staffPosition = staffPos,
            effectiveAccidental = accidental,
            needsLedgerLine = staffPos > 5 || staffPos < -5,
        )

        val commands = mutableListOf<RenderCommand>()
        // Anchor used for the geometry origin (and to locate the owning page). The rest sits with
        // its left edge on the onset; the note is shifted left so the notehead's RIGHT edge lands on
        // the onset X (the snap target), which the user reads as "the note ending at this beat".
        // A new/append grace note belongs before its principal onset, so preview it to the left.
        // An onset that already has a grace component is an existing grace event: keep the exact X
        // there so clicking another pitch previews and commits a chord at that same grace onset.
        val gracePreviewX = if (graceMode && onset.grace == null) {
            snapRelX - config.graceNotePrincipalGap - StaffSpace(0.8f)
        } else {
            snapRelX
        }
        // Aligned score/timeline layouts intentionally place notation just to the right of their
        // shared time boundary. The committed slot pass applies this same gap to every note event;
        // use it here as the preview origin instead of the ordinary right-edge-at-onset convention.
        // This is a renderer layout contract, not a Web pixel correction (desktop's ordinary score
        // layout has no aligned-time-axis request and keeps its established ghost coordinates).
        val alignedContentX = config.alignedTimeAxisRequest?.notationContentStartGap
            ?.takeUnless { graceMode }
            ?.let { snapRelX + it }
        var drawOffset = RelativePoint(alignedContentX ?: gracePreviewX, centerY)

        if (restMode) {
            // Rest preview: just the rest glyph, dropped on the staff centre.
            val body = NoteBodyElementBuilder(config, scale = previewScale).buildRestElement(displayDuration)
            for (g in body.geometryList) commands += g.draw(drawOffset, transformer)
        } else {
            // Note preview: build the real note body + stem/flag via the engraving builders so the
            // stem side, length and attachment match a committed note exactly.
            val stemDir = stemResolver.resolveIndividualDirection(
                StemResolutionInput(
                    eventId = GHOST_EVENT_ID,
                    pitchData = listOf(pitchData),
                    beamInfo = null,
                    userStemDirection = null,
                    voiceContext = VoiceContext(
                        voiceNumber = voiceNumber,
                        measureNumber = measureNumber,
                        hasMultipleVoices = staffTrack.voiceTracks.size > 1 || voiceNumber != 1,
                        staffIndex = staffHit.staffIndex,
                    ),
                )
            )
            val body = NoteBodyElementBuilder(config, scale = previewScale)
                .buildNoteGeometry(listOf(pitchData), displayDuration, stemDir)

            if (alignedContentX == null) {
                // Right-align: shift so the rightmost notehead edge sits on the onset X.
                val noteheadRight = body.noteheads.maxOfOrNull {
                    it.geometry.bounds.origin.x.value + it.geometry.bounds.width.value
                } ?: 0f
                drawOffset = RelativePoint(gracePreviewX - StaffSpace(noteheadRight), centerY)
            }

            for (nh in body.noteheads) commands += nh.geometry.draw(drawOffset, transformer)
            for (acc in body.accidentals) commands += acc.geometry.draw(drawOffset, transformer)
            for (dot in body.dots) commands += dot.geometry.draw(drawOffset, transformer)
            for (ledger in body.ledgerLineGeometries) commands += ledger.draw(drawOffset, transformer)

            val noteElement = NoteElement(
                time = onset,
                staffIndex = staffHit.staffIndex,
                eventId = GHOST_EVENT_ID,
                trackId = staffTrack.id,
                duration = displayDuration,
                measureNumber = measureNumber,
                pitchData = listOf(pitchData),
                isRest = false,
                beamInfo = null,
                resolvedStemDirection = stemDir,
                noteBody = body,
                noteScale = NoteScale(previewScale),
            )
            val layout = voiceLayoutBuilder.buildLayout(
                noteElement, staffHit.staffIndex, staffTrack.id, measureNumber, stemDir
            )
            val stem = layout.stem
            if (stem != null) {
                val stemLine = RelativeLine.vertical(
                    x = drawOffset.x + stem.relativeX,
                    startY = drawOffset.y + stem.topY,
                    endY = drawOffset.y + stem.bottomY,
                    thickness = config.engravingDefaults.stemThickness,
                )
                val absStem = transformer.toAbsolute(stemLine)
                commands += DrawLine(
                    start = absStem.start,
                    end = absStem.end,
                    thickness = absStem.thickness,
                    color = RenderColor.BLACK,
                    bounds = RenderHelpers.calculateLineBounds(absStem),
                )
                // Flag for unbeamed eighth-and-shorter notes (a ghost is never beamed).
                val flag = layout.flag
                if (flag != null) {
                    val glyph = FlagElement.getFlagGlyph(flag.flagCount, stem.direction)
                    val flagPos = transformer.toAbsolute(
                        RelativePoint(drawOffset.x + flag.relativeX, drawOffset.y + flag.relativeY)
                    )
                    val fontSize = transformer.toPixels(StaffSpace(4f))
                    commands += RenderHelpers.createGlyphCommand(glyph, flagPos, fontSize)
                }
            }
        }

        if (tupletCount != null) {
            addOpenTupletPreview(commands, drawOffset, centerY, tupletCount, transformer)
        }

        if (commands.isEmpty()) return null
        return GhostNote(
            voiceTrackId = voice.id,
            staffTrackId = staffTrack.id,
            voiceNumber = voiceNumber,
            onset = onset,
            smallNoteAppendStartEventId = snap.smallNoteAppendStartEventId,
            pitch = pitch,
            commands = commands,
            anchor = transformer.toAbsolute(drawOffset),
        )
    }

    /** Draw the pending-group cue: one anchored hook, an open bracket end, and its tuplet number. */
    private fun addOpenTupletPreview(
        commands: MutableList<RenderCommand>,
        noteOffset: RelativePoint,
        staffCenterY: StaffSpace,
        count: Int,
        transformer: com.mecon.renderer.render.CoordinateTransformer,
    ) {
        val startX = noteOffset.x
        val endX = startX + StaffSpace(4f)
        val y = staffCenterY - StaffSpace(4.5f)
        val midX = (startX + endX) / 2f
        val digits = count.toString().mapNotNull { it.digitToIntOrNull()?.let(SmuflGlyphs::tupletDigit) }
        val scale = 0.7f
        val digitWidths = digits.map { StaffSpace(this@BravuraFont.getAdvanceWidth(it).value * scale) }
        val totalWidth = digitWidths.fold(StaffSpace.ZERO) { acc, width -> acc + width }
        val gapPadding = StaffSpace(0.2f)
        val gapStart = midX - totalWidth / 2f - gapPadding
        val gapEnd = midX + totalWidth / 2f + gapPadding
        val thickness = config.engravingDefaults.tupletBracketThickness

        fun line(from: RelativePoint, to: RelativePoint) {
            val absolute = transformer.toAbsolute(RelativeLine(from, to, thickness))
            commands += DrawLine(
                start = absolute.start,
                end = absolute.end,
                thickness = absolute.thickness,
                color = RenderColor.BLACK,
                bounds = RenderHelpers.calculateLineBounds(absolute),
            )
        }
        line(RelativePoint(startX, y), RelativePoint(startX, y + StaffSpace(0.5f)))
        if (gapStart > startX) line(RelativePoint(startX, y), RelativePoint(gapStart, y))
        if (gapEnd < endX) line(RelativePoint(gapEnd, y), RelativePoint(endX, y))

        var cursor = midX - totalWidth / 2f
        val fontSize = transformer.toPixels(StaffSpace(4f * scale))
        for ((index, glyph) in digits.withIndex()) {
            val bbox = this@BravuraFont.getBBox(glyph)
            val verticalAdjust = if (bbox != null) {
                StaffSpace((bbox.northEast.y.value + bbox.southWest.y.value) * 0.5f * scale)
            } else {
                StaffSpace.ZERO
            }
            commands += RenderHelpers.createGlyphCommand(
                glyph,
                transformer.toAbsolute(RelativePoint(cursor, y + verticalAdjust)),
                fontSize,
            )
            cursor += digitWidths[index]
        }
    }

    /**
     * Snap [targetAbsX] to the nearest insertable onset: eligible existing onsets of [voice] plus
     * every beat point of the on-screen measures. When [voice] is only a fallback for a missing
     * target voice, non-dyadic onsets are excluded: another voice's tuplet grid must not leak into
     * the new voice, whose ordinary rests can only be split on power-of-two boundaries. Existing
     * target voices retain all their own onsets so their tuplets remain editable.
     *
     * Each candidate's absolute X comes from the displayed
     * time-code positions when known, else linear interpolation by absolute whole-note position
     * (meter-aware, so it is correct across mixed time signatures).
     *
     * Candidates are restricted to the system whose vertical band contains [systemY]: in
     * 分行/paginated layouts each system restarts X near the left margin, so without this filter an
     * X-only nearest match could snap onto a measure on a different line (and insert there).
     */
    private fun snapOnset(
        result: RenderResult,
        runtime: RuntimeScore,
        voice: RuntimeVoiceTrack,
        targetAbsX: Float,
        targetAbsY: Float,
        systemY: Float,
        includeNonDyadicVoiceOnsets: Boolean,
        includeMeasureEnds: Boolean,
        smallNoteDuration: Duration?,
    ): SnapTarget? {
        // Time positions belonging to the hovered system (Y band contains the staff centre). Fall
        // back to all positions if the band lookup finds nothing (e.g. degenerate single system).
        val systemPositions = result.timeCodePositions.values
            .filter { systemY in it.topY..it.bottomY }
            .ifEmpty { result.timeCodePositions.values.toList() }

        val tcToX: Map<TimeCode, Float> = systemPositions.associate { it.timeCode to it.x }
        // (absolute whole notes, absolute X) of every known in-system time position, ascending.
        val known: List<Pair<Double, Float>> = systemPositions
            .map { absoluteWholeNotes(it.timeCode, runtime) to it.x }
            .sortedBy { it.first }

        // Measure range spanned by this system → only synthesize beats (and accept onsets) here.
        val sysMeasures = systemPositions.map { it.timeCode.measure }
        val firstM = sysMeasures.minOrNull() ?: maxOf(1, result.firstMeasure)
        val lastM = sysMeasures.maxOrNull() ?: maxOf(firstM, result.lastMeasure)

        val voiceEvents = voice.events.toList()
        val candidates = LinkedHashSet<TimeCode>()
        voiceEvents.forEach { event ->
            if (
                event.onset.measure in firstM..lastM &&
                (includeNonDyadicVoiceOnsets || event.onset.isDyadicBeatPosition())
            ) {
                candidates.add(event.onset)
            }
        }
        if (smallNoteDuration != null) {
            voiceEvents.forEach { start ->
                val span = start.tupletSpan?.takeIf { it.smallNotes } ?: return@forEach
                if (start.onset.measure !in firstM..lastM) return@forEach
                val ratio = start.duration.tuplet ?: return@forEach
                val step = smallNoteDuration.copy(tuplet = ratio).toFraction()
                if (step <= Fraction.ZERO) return@forEach
                val spanStartAbs = absoluteFraction(start.onset, runtime)
                val spanEndAbs = absoluteFraction(span.endTimeCode, runtime)
                var offset = Fraction.ZERO
                var generated = 0
                while (
                    spanStartAbs + offset < spanEndAbs &&
                    generated < MAX_SMALL_NOTE_GRID_POINTS
                ) {
                    candidates.add(timeCodeAtAbsolute(spanStartAbs + offset, runtime))
                    offset += step
                    generated++
                }
                // Once the currently materialised members fill the fixed span, its exclusive end is
                // still an append handle. NoteInsertion will re-ratio and re-space the whole group.
                candidates.add(span.endTimeCode)
            }
        }
        for (m in firstM..lastM) {
            val ts = runtime.getTimeSignatureAt(m)
            for (i in 0 until ts.numerator) {
                candidates.add(TimeCode.of(m, ts.beatUnit * i))
            }
            if (includeMeasureEnds) {
                candidates.add(TimeCode.of(m, Fraction(ts.numerator, ts.denominator)))
            }
        }

        fun xForTimeCode(tc: TimeCode): Float? {
            tcToX[tc]?.let { return it }
            if (known.isEmpty()) return null
            val t = absoluteWholeNotes(tc, runtime)
            if (t <= known.first().first) return known.first().second
            if (t >= known.last().first) return known.last().second
            for (j in 0 until known.size - 1) {
                val (t0, x0) = known[j]
                val (t1, x1) = known[j + 1]
                if (t in t0..t1) {
                    if (t1 == t0) return x0
                    val f = ((t - t0) / (t1 - t0)).toFloat()
                    return x0 + f * (x1 - x0)
                }
            }
            return known.last().second
        }

        // Clicking a real notehead is an unambiguous request to edit that event (normally add a
        // chord tone). Resolve it before the preceding small-note append gap because a notehead can
        // visually extend left of its onset at the small-note span's exclusive endpoint.
        voiceEvents
            .asSequence()
            .filter { !it.isRest && it.onset.measure in firstM..lastM }
            .firstOrNull { event ->
                result.elementsForEvent(event.id)
                    .asSequence()
                    .filter { it.type == RenderElementType.NOTEHEAD }
                    .any { element ->
                        val box = element.hitBox
                        targetAbsX >= box.origin.x.value &&
                            targetAbsX <= box.origin.x.value + box.width.value &&
                            targetAbsY >= box.origin.y.value &&
                            targetAbsY <= box.origin.y.value + box.height.value
                    }
            }
            ?.let { event ->
                return SnapTarget(
                    onset = event.onset,
                    absoluteX = xForTimeCode(event.onset) ?: targetAbsX,
                )
            }

        // Once a small-note group is visually full there is no remaining rest onset to target.
        // Make the visual gap after its final notehead an append zone, while leaving positions
        // strictly beyond the fixed endpoint to the following ordinary measure.
        voiceEvents
            .asSequence()
            .mapNotNull { start ->
                val span = start.tupletSpan?.takeIf { it.smallNotes } ?: return@mapNotNull null
                if (start.onset.measure !in firstM..lastM) return@mapNotNull null
                val spanStart = absoluteFraction(start.onset, runtime)
                val spanEnd = absoluteFraction(span.endTimeCode, runtime)
                val lastEntered = voiceEvents
                    .filter {
                        !it.isRest &&
                            absoluteFraction(it.onset, runtime) >= spanStart &&
                            absoluteFraction(it.onset, runtime) < spanEnd
                    }
                    .maxByOrNull { absoluteFraction(it.onset, runtime) }
                    ?: return@mapNotNull null
                val noteheads = result.elementsForEvent(lastEntered.id)
                    .filter { it.type == RenderElementType.NOTEHEAD }
                val lastRight = noteheads.maxOfOrNull {
                    it.hitBox.origin.x.value + it.hitBox.width.value
                } ?: return@mapNotNull null
                val lastWidth = noteheads.maxOfOrNull { it.hitBox.width.value } ?: 0f
                val endpointX = xForTimeCode(span.endTimeCode) ?: return@mapNotNull null
                if (
                    endpointX > lastRight &&
                    targetAbsX > lastRight + APPEND_HIT_SLOP_PX &&
                    targetAbsX < endpointX - APPEND_ENDPOINT_GUARD_PX
                ) {
                    val previewX = minOf(
                        endpointX,
                        lastRight + maxOf(MIN_APPEND_PREVIEW_GAP_PX, lastWidth * 1.25f),
                    )
                    AppendZone(start.id, span.endTimeCode, previewX, lastRight)
                } else {
                    null
                }
            }
            .maxByOrNull { it.lastRight }
            ?.let { zone ->
                return SnapTarget(
                    onset = zone.endpoint,
                    absoluteX = zone.previewX,
                    smallNoteAppendStartEventId = zone.startEventId,
                )
            }

        return candidates
            .mapNotNull { tc -> xForTimeCode(tc)?.let { tc to it } }
            .minByOrNull { abs(it.second - targetAbsX) }
            ?.let { (onset, x) -> SnapTarget(onset, x) }
    }

    private fun TimeCode.isDyadicBeatPosition(): Boolean {
        val denominator = (beat ?: Fraction.ZERO).simplified().denominator
        return denominator and (denominator - 1) == 0
    }

    /**
     * The chromatic offset carried forward to [diatonicSteps] at [onset]: the accidental of the last
     * note (not rest) earlier in the same measure sounding at the same staff position. Null when no
     * such note exists, so the caller can fall back to natural / the key spelling.
     */
    private fun carriedAccidentalOffset(
        voice: RuntimeVoiceTrack,
        onset: TimeCode,
        diatonicSteps: Int,
    ): Int? = voice.events.before(onset)
        .filter { !it.isRest && it.onset.measure == onset.measure }
        .flatMap { it.pitches }
        .lastOrNull { it.diatonicSteps == diatonicSteps }
        ?.chromaticOffset

    /** Absolute position of [tc] in whole notes, summing measure lengths from the runtime meters. */
    private fun absoluteWholeNotes(tc: TimeCode, runtime: RuntimeScore): Double {
        val value = absoluteFraction(tc, runtime)
        return value.numerator.toDouble() / value.denominator
    }

    private fun absoluteFraction(tc: TimeCode, runtime: RuntimeScore): Fraction {
        var acc = Fraction.ZERO
        for (m in 1 until tc.measure) {
            acc += runtime.getTimeSignatureAt(m).measureDuration()
        }
        return acc + (tc.beat ?: Fraction.ZERO)
    }

    private fun timeCodeAtAbsolute(position: Fraction, runtime: RuntimeScore): TimeCode {
        var measure = 1
        var remaining = position
        while (true) {
            val length = runtime.getTimeSignatureAt(measure).measureDuration()
            if (remaining < length) return TimeCode.of(measure, remaining)
            remaining -= length
            measure++
        }
    }

    companion object {
        private const val SMALL_NOTE_PREVIEW_SCALE = 0.7f
        private const val MAX_SMALL_NOTE_GRID_POINTS = 512
        private const val APPEND_HIT_SLOP_PX = 2f
        private const val APPEND_ENDPOINT_GUARD_PX = 1f
        private const val MIN_APPEND_PREVIEW_GAP_PX = 8f
        /** Synthetic id for the (uncommitted) ghost note; never registered in any computed score. */
        private val GHOST_EVENT_ID = EventId("__ghost__")

        /** Diatonic step of the middle staff line per clef (inverse of core's StaffPositionComputer). */
        private fun middleLineDiatonicSteps(clef: Clef): Int = when (clef) {
            Clef.TREBLE -> 6   // B4
            Clef.BASS -> -6    // D3
            Clef.ALTO -> 0     // C4
            Clef.TENOR -> -2   // A3
            Clef.PERCUSSION -> 0
        }
    }
}
