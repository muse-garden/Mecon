package com.mecon.renderer.render

import com.mecon.api.primitive.Duration
import com.mecon.api.primitive.EventId
import com.mecon.api.primitive.Fraction
import com.mecon.api.primitive.KeySignature
import com.mecon.api.primitive.Pitch
import com.mecon.api.primitive.TimeCode
import com.mecon.api.primitive.TimeSignature
import com.mecon.api.primitive.TrackId
import com.mecon.api.runtime.RuntimeScore
import com.mecon.api.runtime.events.RuntimePitchEvent
import com.mecon.api.runtime.events.RuntimeVoiceEvent
import com.mecon.api.runtime.orderedStaffs
import com.mecon.api.storage.StaffLayoutPreset
import com.mecon.api.storage.StorageScore
import com.mecon.api.storage.ScoreGeometry
import com.mecon.api.storage.TupletGeometry
import com.mecon.api.storage.tracks.StoragePitchTrack
import com.mecon.core.engine.edit.NoteEditEngine
import com.mecon.api.storage.tracks.StorageSystemBreak
import com.mecon.api.storage.tracks.StorageVoiceTrack
import com.mecon.core.engine.computeScore
import com.mecon.renderer.geometry.AbsolutePathSegment
import com.mecon.renderer.geometry.StaffSpace
import com.mecon.renderer.geometry.SlurDirection
import com.mecon.renderer.layout.PageGeometry
import com.mecon.renderer.layout.RenderLayoutConfig
import com.mecon.renderer.layout.UnifiedLayoutComputer
import com.mecon.renderer.snapshot.loadFont
import com.mecon.renderer.snapshot.renderScoreFile
import com.mecon.renderer.snapshot.testScoreDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.fail
import kotlin.test.assertTrue

class RenderGeometryRegressionTest {

    @Test
    fun syntheticSlurFixtureDoesNotProduceHugeLineLikeCommands() {
        val font = loadFont() ?: return
        val runtime = measure44NestedSlurScore()

        val result = with(font) {
            RenderEngine(RenderLayoutConfig.DEFAULT).render(runtime, pageGeometry = crossSystemGeometry)
        }

        val suspicious = result.elements
            .filter { element ->
                element.commands.any { command ->
                    command is DrawLine || command is DrawPath || command is DrawBezier
                }
            }
            .map { element ->
                val maxWidth = element.commands.maxOf { it.bounds.width.value }
                val maxHeight = element.commands.maxOf { it.bounds.height.value }
                element to maxOf(maxWidth, maxHeight)
            }
            .filter { (_, maxSpan) -> maxSpan > 1200f }
            .sortedByDescending { (_, maxSpan) -> maxSpan }

        assertTrue(
            suspicious.isEmpty(),
            suspicious.take(20).joinToString(separator = "\n") { (element, maxSpan) ->
                "${element.type} measure=${element.measureNumber} staff=${element.staffIndex} span=$maxSpan " +
                    "commands=${element.commands}"
            }
        )
    }

    @Test
    fun tupletStartingWithNoteAndRestMembersStaysNearStaff() {
        val font = loadFont() ?: return
        val base = RuntimeScore.fromStorage(StorageScore.create(StorageScore.CreationOptions("T", TimeSignature.COMMON, KeySignature.C_MAJOR)))
        val runtime = NoteEditEngine.insert(
            base,
            NoteEditEngine.Insertion(
                voiceTrackId = base.voiceTracks.keys.first(),
                start = TimeCode.of(1, Fraction.ZERO),
                duration = Duration.QUARTER,
                pitch = Pitch.C4,
                tupletCount = 3,
            ),
        )!!.score

        val result = with(font) {
            RenderEngine(RenderLayoutConfig.DEFAULT).render(runtime)
        }
        val tuplet = result.elements.singleOrNull { it.type == RenderElementType.TUPLET_BRACKET }
            ?: fail("expected one tuplet bracket element, got ${result.elements.map { it.type }}")

        assertTrue(
            tuplet.hitBox.origin.y.value > -20f,
            "tuplet should stay near the staff, not fly above the page: ${tuplet.hitBox}"
        )
        assertTrue(
            tuplet.hitBox.height.value < 40f,
            "tuplet bracket should remain compact when later members are rests: ${tuplet.hitBox}"
        )
    }

    @Test
    fun tupletStartingWithRestUsesFirstMemberStemAndHonoursLockedSide() {
        val font = loadFont() ?: return
        val base = RuntimeScore.fromStorage(StorageScore.create(
            StorageScore.CreationOptions("T", TimeSignature.COMMON, KeySignature.C_MAJOR)
        ))
        val voiceId = base.voiceTracks.keys.first()
        var runtime = NoteEditEngine.insert(
            base,
            NoteEditEngine.Insertion(
                voiceTrackId = voiceId,
                start = TimeCode.of(1, Fraction.ZERO),
                duration = Duration.QUARTER,
                pitch = null,
                isRest = true,
                tupletCount = 3,
            ),
        )!!.score
        runtime = NoteEditEngine.insert(
            runtime,
            NoteEditEngine.Insertion(
                voiceTrackId = voiceId,
                start = TimeCode.of(1, Fraction(1, 12)),
                duration = Duration.EIGHTH,
                pitch = Pitch(14, 0),
            ),
        )!!.score
        runtime = NoteEditEngine.insert(
            runtime,
            NoteEditEngine.Insertion(
                voiceTrackId = voiceId,
                start = TimeCode.of(1, Fraction(1, 6)),
                duration = Duration.EIGHTH,
                pitch = Pitch(16, 0),
            ),
        )!!.score

        fun layout(score: RuntimeScore) = computeScore(score).let { computed ->
            val result = with(font) {
                UnifiedLayoutComputer(RenderLayoutConfig.DEFAULT).computeLayout(computed, score)
            }
            TupletLayoutComputer(RenderLayoutConfig.DEFAULT).computeTupletLayouts(
                computed,
                LayoutQuery(result, result.staffLayouts.associateBy { it.staffIndex }, computed),
            ).single()
        }

        val automatic = layout(runtime)
        assertEquals(SlurDirection.BELOW, automatic.direction)
        val automaticEngine = with(font) { RenderEngine(RenderLayoutConfig.DEFAULT) }
        with(font) { automaticEngine.render(runtime) }
        assertEquals(
            TupletGeometry(above = false, directionLocked = false),
            automaticEngine.captureGeometry()?.tuplets?.get(automatic.startEventId),
        )

        val locked = runtime.copy(geometry = ScoreGeometry(tuplets = mapOf(
            automatic.startEventId to TupletGeometry(above = true, directionLocked = true),
        )))
        assertEquals(SlurDirection.ABOVE, layout(locked).direction)
        val lockedEngine = with(font) { RenderEngine(RenderLayoutConfig.DEFAULT) }
        with(font) { lockedEngine.render(locked) }
        assertEquals(
            TupletGeometry(above = true, directionLocked = true),
            lockedEngine.captureGeometry()?.tuplets?.get(automatic.startEventId),
        )
    }

    @Test
    fun tupletMembersDoNotIncludeAnotherVoiceOnTheSameStaff() {
        val font = loadFont() ?: return
        val base = StorageScore.create(
            StorageScore.CreationOptions("T", TimeSignature.COMMON, KeySignature.C_MAJOR)
        )
        val staffId = base.staffTracks.keys.first()
        val staff = base.staffTracks.getValue(staffId)
        val pitch2 = StoragePitchTrack.create("Voice 2 pitches")
        val voice2 = StorageVoiceTrack.create("Voice 2", 2, pitch2.id)
        val storage = base.copy(
            pitchTracks = base.pitchTracks + (pitch2.id to pitch2),
            voiceTracks = base.voiceTracks + (voice2.id to voice2),
            staffTracks = base.staffTracks +
                (staffId to staff.copy(voiceTrackIds = staff.voiceTrackIds + voice2.id)),
        )
        var runtime = RuntimeScore.fromStorage(storage)
        val voices = runtime.voiceTracks.values.sortedBy { it.voiceNumber }

        // Put the tuplet in the lower voice, whose bracket belongs below the staff.
        runtime = NoteEditEngine.insert(
            runtime,
            NoteEditEngine.Insertion(
                voiceTrackId = voices[1].id,
                start = TimeCode.of(1, Fraction.ZERO),
                duration = Duration.QUARTER,
                pitch = Pitch.C4,
                tupletCount = 3,
            ),
        )!!.score
        // A low note in voice 1 at the tuplet's last onset used to be collected as a
        // member because both layouts exposed only the shared staff-track ID.
        runtime = NoteEditEngine.insert(
            runtime,
            NoteEditEngine.Insertion(
                voiceTrackId = voices[0].id,
                start = TimeCode.of(1, Fraction(1, 6)),
                duration = Duration.EIGHTH,
                pitch = Pitch(-14, 0),
            ),
        )!!.score

        val computed = computeScore(runtime)
        val layout = with(font) {
            UnifiedLayoutComputer(RenderLayoutConfig.DEFAULT).computeLayout(computed, runtime)
        }
        val query = LayoutQuery(layout, layout.staffLayouts.associateBy { it.staffIndex }, computed)
        val tuplet = TupletLayoutComputer(RenderLayoutConfig.DEFAULT)
            .computeTupletLayouts(computed, query)
            .single()

        assertEquals(voices[1].id, layout.voiceEventLayouts[tuplet.startEventId]?.voiceTrackId)
        assertTrue(
            kotlin.math.abs(tuplet.end.y.value - tuplet.start.y.value) < 8f,
            "another voice must not pull the tuplet endpoint away from its own members: $tuplet"
        )
    }

    @Test
    fun paginatedTupletUsesItsOwnSystemStaffBaseline() {
        val font = loadFont() ?: return
        val storage = StorageScore.create(StorageScore.CreationOptions(
            title = "T",
            measureCount = 2,
            timeSignature = TimeSignature.COMMON,
            keySignature = KeySignature.C_MAJOR,
        )).let { score ->
            score.copy(
                globalTrack = score.globalTrack.copy(
                    events = score.globalTrack.events + StorageSystemBreak(TimeCode.of(2, Fraction.ZERO))
                )
            )
        }
        var runtime = RuntimeScore.fromStorage(storage)
        val voiceTrackId = runtime.voiceTracks.keys.first()
        runtime = NoteEditEngine.insert(
            runtime,
            NoteEditEngine.Insertion(
                voiceTrackId = voiceTrackId,
                start = TimeCode.of(1, Fraction.ZERO),
                duration = Duration.WHOLE,
                pitch = Pitch.C4,
            ),
        )!!.score
        runtime = NoteEditEngine.insert(
            runtime,
            NoteEditEngine.Insertion(
                voiceTrackId = voiceTrackId,
                start = TimeCode.of(2, Fraction.ZERO),
                duration = Duration.QUARTER,
                pitch = Pitch.C4,
                tupletCount = 3,
            ),
        )!!.score
        runtime = NoteEditEngine.insert(
            runtime,
            NoteEditEngine.Insertion(
                voiceTrackId = voiceTrackId,
                start = TimeCode.of(2, Fraction(1, 12)),
                duration = Duration.EIGHTH,
                pitch = Pitch.D4,
            ),
        )!!.score
        runtime = NoteEditEngine.insert(
            runtime,
            NoteEditEngine.Insertion(
                voiceTrackId = voiceTrackId,
                start = TimeCode.of(2, Fraction(1, 6)),
                duration = Duration.EIGHTH,
                pitch = Pitch.E4,
            ),
        )!!.score

        val computed = computeScore(runtime)
        val layout = with(font) {
            UnifiedLayoutComputer(RenderLayoutConfig.DEFAULT).computeLayout(
                computed, runtime, pageGeometry = crossSystemGeometry
            )
        }
        val staffLayoutByIndex = layout.staffLayouts.associateBy { it.staffIndex }
        val layoutQuery = LayoutQuery(layout, staffLayoutByIndex, computed)
        val tuplets = TupletLayoutComputer(RenderLayoutConfig.DEFAULT)
            .computeTupletLayouts(computed, layoutQuery)
        val tuplet = tuplets.singleOrNull { it.measureNumber == 2 }
            ?: fail("expected one measure-2 tuplet, got ${tuplets.map { it.measureNumber to it.start.y }}")
        val targetSystem = layout.systems.first { 2 in it.measureRange }
        val targetStaff = targetSystem.staffLayouts.first { it.staffIndex == 0 }
        val firstSystemStaff = layout.systems.first().staffLayouts.first { it.staffIndex == 0 }
        val tupletY = (tuplet.start.y.value + tuplet.end.y.value) * 0.5f
        val targetDistance = kotlin.math.abs(tupletY - targetStaff.centerY.value)
        val firstSystemDistance = kotlin.math.abs(tupletY - firstSystemStaff.centerY.value)

        assertTrue(targetSystem.systemIndex > 0, "measure 2 should be on a later paginated system")
        assertTrue(
            targetDistance < 12f,
            "paginated tuplet should use its own system staff center; tupletY=$tupletY target=${targetStaff.centerY}"
        )
        assertTrue(
            targetDistance < firstSystemDistance,
            "paginated tuplet must be closer to its own system than the first/flat staff baseline; " +
                "tupletY=$tupletY target=${targetStaff.centerY} first=${firstSystemStaff.centerY}"
        )
    }

    @Test
    fun debugSyntheticMeasure44Slurs() {
        if (System.getProperty("debug-measure-44-slurs") != "true") return
        val font = loadFont() ?: return
        val runtime = measure44NestedSlurScore()
        val computed = computeScore(runtime)

        val result = with(font) {
            RenderEngine(RenderLayoutConfig.DEFAULT).render(runtime)
        }

        val eventById = runtime.voiceTracks.values
            .flatMap { it.events.toList() }
            .associateBy { it.id }

        val computedLines = computed.slurs
            .filter { slur ->
                val start = eventById[slur.startEventId]
                val end = eventById[slur.endEventId]
                start?.onset?.measure == 44 || end?.onset?.measure == 44 ||
                    (start != null && end != null && start.onset.measure < 44 && end.onset.measure > 44)
            }
            .sortedWith(compareBy({ eventById[it.startEventId]?.onset }, { eventById[it.endEventId]?.onset }))
            .mapIndexed { index, slur ->
                val start = eventById[slur.startEventId]
                val end = eventById[slur.endEventId]
                "computed#$index track=${slur.voiceTrackId.value} voice=${slur.voiceNumber} " +
                    "nest=${slur.nestingLevel} start=${slur.startEventId.value}@${start?.onset} " +
                    "end=${slur.endEventId.value}@${end?.onset} pitches=${start?.pitches}->${end?.pitches}"
            }

        val runtimeLines = runtime.voiceTracks.values
            .flatMap { track ->
                track.events.toList()
                    .filter { it.onset.measure == 44 && (it.slurStarts > 0 || it.slurEnds > 0) }
                    .map { event ->
                        "event track=${track.id.value} voice=${track.voiceNumber} id=${event.id.value} " +
                            "onset=${event.onset} starts=${event.slurStarts} ends=${event.slurEnds} " +
                            "pitches=${event.pitches} duration=${event.duration}"
                    }
            }
            .sorted()

        val lines = result.elements
            .filter { it.measureNumber == 44 && it.type == RenderElementType.SLUR }
            .mapIndexed { index, element ->
                val command = element.commands.filterIsInstance<DrawPath>().firstOrNull()
                val pathLines = command?.path?.segments?.joinToString(separator = "\n      ") { segment ->
                    when (segment) {
                        is AbsolutePathSegment.MoveTo ->
                            "M (${segment.point.x.value}, ${segment.point.y.value})"
                        is AbsolutePathSegment.CubicTo ->
                            "C c1=(${segment.control1.x.value}, ${segment.control1.y.value}) " +
                                "c2=(${segment.control2.x.value}, ${segment.control2.y.value}) " +
                                "end=(${segment.end.x.value}, ${segment.end.y.value})"
                        is AbsolutePathSegment.LineTo ->
                            "L (${segment.point.x.value}, ${segment.point.y.value})"
                        is AbsolutePathSegment.QuadTo ->
                            "Q c=(${segment.control.x.value}, ${segment.control.y.value}) " +
                                "end=(${segment.end.x.value}, ${segment.end.y.value})"
                        AbsolutePathSegment.Close -> "Z"
                    }
                } ?: "<no path>"
                val bounds = command?.bounds ?: element.hitBox
                "slur#$index id=${element.id} event=${element.eventId?.value} track=${element.trackId?.value} " +
                    "staff=${element.staffIndex} system=${element.systemIndex} metadata=${element.metadata}\n" +
                    "  hitBox=${element.hitBox}\n" +
                    "  bounds=$bounds\n" +
                    "  path:\n      $pathLines"
            }

        fail(
            "measure 44 computed slurs (${computedLines.size}):\n" +
                computedLines.joinToString("\n") +
                "\n\nmeasure 44 runtime slur events (${runtimeLines.size}):\n" +
                runtimeLines.joinToString("\n") +
                "\n\nmeasure 44 rendered slurs (${lines.size}):\n" +
                lines.joinToString("\n\n")
        )
    }

    @Test
    fun crossSystemRightHandSlurTargetStubClearsTargetLineNotes() {
        val font = loadFont() ?: return
        val runtime = crossSystemSlurAvoidanceScore()

        val result = with(font) {
            RenderEngine(RenderLayoutConfig.DEFAULT).render(runtime, pageGeometry = crossSystemGeometry)
        }

        val targetStub = result.elements
            .mapNotNull { element ->
                val path = element.commands.filterIsInstance<DrawPath>().firstOrNull()
                if (
                    element.type == RenderElementType.SLUR &&
                    element.staffIndex == 0 &&
                    path != null &&
                    path.bounds.width.value > CROSS_SYSTEM_STUB_MIN_WIDTH
                ) {
                    element to path
                } else {
                    null
                }
            }
            .maxByOrNull { (element, _) -> element.hitBox.origin.y.value }
            ?: fail(
                "expected the target-system stub of the synthetic right-hand cross-system slur; " +
                    "slurs=${result.elements.filter { it.type == RenderElementType.SLUR }.map { it.hitBox }}"
            )

        val (targetStubElement, slurPath) = targetStub
        val slurBounds = slurPath.bounds
        val slurLeft = slurBounds.origin.x.value
        val slurRight = slurBounds.origin.x.value + slurBounds.width.value
        val slurBottom = slurBounds.origin.y.value + slurBounds.height.value

        val noteheadsAlongStub = result.elements
            .filter { element ->
                element.type == RenderElementType.NOTEHEAD &&
                    element.staffIndex == targetStubElement.staffIndex &&
                    element.systemIndex == targetStubElement.systemIndex &&
                    element.hitBox.center.x.value in slurLeft..slurRight
            }

        assertTrue(
            noteheadsAlongStub.isNotEmpty(),
            "expected target-system noteheads along the synthetic cross-system slur stub"
        )

        val slurTop = slurBounds.origin.y.value
        val topmostNoteheadTop = noteheadsAlongStub.minOf { it.hitBox.origin.y.value }
        val bottommostNoteheadBottom = noteheadsAlongStub.maxOf {
            it.hitBox.origin.y.value + it.hitBox.height.value
        }
        val clearsAbove = slurBottom <= topmostNoteheadTop - CROSS_SYSTEM_SLUR_CLEARANCE_PX
        val clearsBelow = slurTop >= bottommostNoteheadBottom + CROSS_SYSTEM_SLUR_CLEARANCE_PX
        assertTrue(
            clearsAbove || clearsBelow,
            "cross-system slur target stub must clear target-line noteheads on its chosen side; " +
                "stubBounds=$slurBounds noteheads=${noteheadsAlongStub.map { it.hitBox }}"
        )
    }

    @Test
    fun slursInSlurFixtureClearBeamGroups() {
        val font = loadFont() ?: return
        val scoreFile = File(testScoreDir(), "14_slurs.mscore.yaml")
        if (!scoreFile.exists()) return

        val result = renderScoreFile(scoreFile, font)
        val slurs = result.elements
            .filter { it.type == RenderElementType.SLUR }
            .mapNotNull { element -> element.commands.filterIsInstance<DrawPath>().firstOrNull() }
        val beams = result.elements
            .filter { it.type == RenderElementType.BEAM }
            .mapNotNull { element -> element.commands.filterIsInstance<DrawPath>().firstOrNull()?.bounds }

        val violations = slurs.flatMap { slur ->
            beams.mapNotNull { beam ->
                val slurBounds = slur.bounds
                val slurLeft = slurBounds.origin.x.value
                val slurRight = slurBounds.origin.x.value + slurBounds.width.value
                val beamLeft = beam.origin.x.value
                val beamRight = beam.origin.x.value + beam.width.value
                val beamTop = beam.origin.y.value
                val overlapLeft = maxOf(slurLeft, beamLeft)
                val overlapRight = minOf(slurRight, beamRight)
                val overlap = overlapRight - overlapLeft
                val slurAboveBeam = slurBounds.center.y.value < beam.center.y.value
                val slurLowerEdge = maxSampledPathYInXRange(slur, overlapLeft, overlapRight)
                val collides = slurLowerEdge != null && slurLowerEdge > beamTop - SLUR_BEAM_CLEARANCE_PX
                if (overlap >= SLUR_BEAM_MIN_OVERLAP_PX && slurAboveBeam && collides) {
                    "slur=${slur.bounds} beam=$beam overlap=$overlap slurLowerEdge=$slurLowerEdge"
                } else {
                    null
                }
            }
        }

        assertTrue(
            violations.isEmpty(),
            "slurs should clear beam groups in 14_slurs.mecon:\n${violations.joinToString("\n")}"
        )
    }

    private fun maxSampledPathYInXRange(path: DrawPath, left: Float, right: Float): Float? {
        var currentX = 0f
        var currentY = 0f
        var maxY: Float? = null

        fun sample(x: Float, y: Float) {
            if (x in left..right) {
                maxY = maxOf(maxY ?: y, y)
            }
        }

        for (segment in path.path.segments) {
            when (segment) {
                is AbsolutePathSegment.MoveTo -> {
                    currentX = segment.point.x.value
                    currentY = segment.point.y.value
                    sample(currentX, currentY)
                }
                is AbsolutePathSegment.CubicTo -> {
                    val startX = currentX
                    val startY = currentY
                    for (step in 1..SLUR_PATH_SAMPLE_STEPS) {
                        val t = step.toFloat() / SLUR_PATH_SAMPLE_STEPS
                        val u = 1f - t
                        val x = u * u * u * startX +
                            3f * u * u * t * segment.control1.x.value +
                            3f * u * t * t * segment.control2.x.value +
                            t * t * t * segment.end.x.value
                        val y = u * u * u * startY +
                            3f * u * u * t * segment.control1.y.value +
                            3f * u * t * t * segment.control2.y.value +
                            t * t * t * segment.end.y.value
                        sample(x, y)
                    }
                    currentX = segment.end.x.value
                    currentY = segment.end.y.value
                }
                is AbsolutePathSegment.LineTo -> {
                    for (step in 1..SLUR_PATH_SAMPLE_STEPS) {
                        val t = step.toFloat() / SLUR_PATH_SAMPLE_STEPS
                        sample(
                            currentX + (segment.point.x.value - currentX) * t,
                            currentY + (segment.point.y.value - currentY) * t
                        )
                    }
                    currentX = segment.point.x.value
                    currentY = segment.point.y.value
                }
                is AbsolutePathSegment.QuadTo -> {
                    val startX = currentX
                    val startY = currentY
                    for (step in 1..SLUR_PATH_SAMPLE_STEPS) {
                        val t = step.toFloat() / SLUR_PATH_SAMPLE_STEPS
                        val u = 1f - t
                        sample(
                            u * u * startX + 2f * u * t * segment.control.x.value + t * t * segment.end.x.value,
                            u * u * startY + 2f * u * t * segment.control.y.value + t * t * segment.end.y.value
                        )
                    }
                    currentX = segment.end.x.value
                    currentY = segment.end.y.value
                }
                AbsolutePathSegment.Close -> Unit
            }
        }

        return maxY
    }

    @Test
    fun syntheticNestedLeftHandSlursStayCompact() {
        val font = loadFont() ?: return
        val runtime = measure44NestedSlurScore()

        val result = with(font) {
            RenderEngine(RenderLayoutConfig.DEFAULT).render(runtime)
        }

        val shortLeftHandSlurs = result.elements
            .filter { element ->
                element.measureNumber == 44 &&
                    element.type == RenderElementType.SLUR &&
                    element.staffIndex == 1
            }
            .mapNotNull { element -> element.commands.filterIsInstance<DrawPath>().firstOrNull() }
            .filter { command -> command.bounds.width.value < 80f }

        assertTrue(shortLeftHandSlurs.size >= 4, "expected the synthetic nested left-hand slurs in measure 44")
        assertTrue(
            shortLeftHandSlurs.all { it.bounds.height.value <= 14f },
            shortLeftHandSlurs.joinToString(separator = "\n") { command ->
                "width=${command.bounds.width.value} height=${command.bounds.height.value} bounds=${command.bounds}"
            }
        )
    }

    @Test
    fun syntheticLeftHandSextupletSlotsIncrease() {
        val font = loadFont() ?: return
        val runtime = measure37SextupletScore()
        val computed = computeScore(runtime)
        val staff2 = runtime.orderedStaffs()[1]
        val lowerVoice = staff2.voiceTracks.first()

        val sextupletEvents = lowerVoice.events.toList()
            .filter { it.onset.measure == 37 }
            .take(12)
        assertEquals(
            listOf(
                TimeCode.of(37, Fraction.ZERO),
                TimeCode.of(37, Fraction(1, 24)),
                TimeCode.of(37, Fraction(1, 12)),
                TimeCode.of(37, Fraction(1, 8)),
                TimeCode.of(37, Fraction(1, 6)),
                TimeCode.of(37, Fraction(5, 24)),
            ),
            sextupletEvents.take(6).map { it.onset },
            "synthetic sextuplet notes should use sequential 1/24 onsets"
        )

        val layout = with(font) {
            UnifiedLayoutComputer(RenderLayoutConfig.DEFAULT).computeLayout(computed, runtime)
        }
        val firstSixXs = sextupletEvents.take(6)
            .map { event -> layout.timeSlotMap.atTime(event.onset)?.x ?: fail("missing slot for ${event.onset}") }

        assertTrue(
            firstSixXs.zipWithNext().all { (left, right) -> left < right },
            "measure 37 left-hand sextuplet slots should increase: $firstSixXs"
        )
    }

    private companion object {
        private val crossSystemGeometry = PageGeometry(
            paginated = true,
            lineWidth = StaffSpace(70f),
            pageContentHeight = StaffSpace(400f),
            paperWidth = StaffSpace(80f),
            paperHeight = StaffSpace(420f),
            leftMargin = StaffSpace(2f),
            topMargin = StaffSpace(2f),
        )

        private const val CROSS_SYSTEM_STUB_MIN_WIDTH = 40f
        private const val CROSS_SYSTEM_SLUR_CLEARANCE_PX = 2f
        private const val SLUR_BEAM_CLEARANCE_PX = 1f
        private const val SLUR_BEAM_MIN_OVERLAP_PX = 8f
        private const val SLUR_PATH_SAMPLE_STEPS = 24
    }

    private fun measure44NestedSlurScore(): RuntimeScore {
        val base = RuntimeScore.fromStorage(
            StorageScore.create(
                StorageScore.CreationOptions(
                    title = "Synthetic slur regression",
                    layout = StaffLayoutPreset.PIANO_GRAND,
                    measureCount = 44,
                    timeSignature = TimeSignature.COMMON,
                    keySignature = KeySignature.C_MAJOR,
                )
            )
        )
        val lowerVoice = base.orderedStaffs()[1].voiceTracks.first()
        val pitchTrackId = lowerVoice.pitchTrack.id

        return base
            .addNoteTo(
                lowerVoice.id,
                pitchTrackId,
                "synthetic-m44-start",
                TimeCode.of(44, Fraction.ZERO),
                Pitch(-7, 0),
                Duration.EIGHTH,
                slurStarts = 4,
            )
            .addNoteTo(
                lowerVoice.id,
                pitchTrackId,
                "synthetic-m44-end",
                TimeCode.of(44, Fraction(1, 8)),
                Pitch(-5, 0),
                Duration.EIGHTH,
                slurEnds = 4,
            )
    }

    private fun measure37SextupletScore(): RuntimeScore {
        val base = RuntimeScore.fromStorage(
            StorageScore.create(
                StorageScore.CreationOptions(
                    title = "Synthetic sextuplet regression",
                    layout = StaffLayoutPreset.PIANO_GRAND,
                    measureCount = 44,
                    timeSignature = TimeSignature.COMMON,
                    keySignature = KeySignature.C_MAJOR,
                )
            )
        )
        val lowerVoice = base.orderedStaffs()[1].voiceTracks.first()
        return requireNotNull(
            NoteEditEngine.insert(
                base,
                NoteEditEngine.Insertion(
                    voiceTrackId = lowerVoice.id,
                    start = TimeCode.of(37, Fraction.ZERO),
                    duration = Duration.QUARTER,
                    pitch = Pitch(-5, 0),
                    tupletCount = 6,
                ),
            )
        ).score
    }

    private fun crossSystemSlurAvoidanceScore(): RuntimeScore {
        val storage = StorageScore.create(StorageScore.CreationOptions(
            title = "",
            layout = StaffLayoutPreset.PIANO_GRAND,
            measureCount = 2,
            timeSignature = TimeSignature.COMMON,
            keySignature = KeySignature.C_MAJOR,
        )).let { score ->
            score.copy(
                globalTrack = score.globalTrack.copy(
                    events = score.globalTrack.events + StorageSystemBreak(TimeCode.of(2, Fraction.ZERO))
                )
            )
        }

        var runtime = RuntimeScore.fromStorage(storage)
        val rightHand = runtime.orderedStaffs().first()
        val voiceTrackId = rightHand.voiceTracks.first().id
        val pitchTrackId = rightHand.voiceTracks.first().pitchTrack.id

        runtime = runtime
            .addNoteTo(
                voiceTrackId,
                pitchTrackId,
                "slur-start",
                TimeCode.of(1, Fraction(3, 4)),
                Pitch.C5,
                Duration.QUARTER,
                slurStarts = 1,
            )
            .addNoteTo(
                voiceTrackId,
                pitchTrackId,
                "target-obstacle-0",
                TimeCode.of(2, Fraction(0, 4)),
                Pitch(12, 0),
                Duration.EIGHTH,
            )
            .addNoteTo(
                voiceTrackId,
                pitchTrackId,
                "target-obstacle-1",
                TimeCode.of(2, Fraction(1, 4)),
                Pitch(13, 0),
                Duration.EIGHTH,
            )
            .addNoteTo(
                voiceTrackId,
                pitchTrackId,
                "target-obstacle-2",
                TimeCode.of(2, Fraction(2, 4)),
                Pitch(12, 0),
                Duration.EIGHTH,
            )
            .addNoteTo(
                voiceTrackId,
                pitchTrackId,
                "slur-end",
                TimeCode.of(2, Fraction(3, 4)),
                Pitch.C5,
                Duration.QUARTER,
                slurEnds = 1,
            )

        return runtime
    }

    private fun RuntimeScore.addNoteTo(
        voiceTrackId: TrackId,
        pitchTrackId: TrackId,
        tag: String,
        onset: TimeCode,
        pitch: Pitch,
        duration: Duration,
        slurStarts: Int = 0,
        slurEnds: Int = 0,
    ): RuntimeScore {
        val pitchEvent = RuntimePitchEvent(EventId("p-$tag"), onset, listOf(pitch))
        val voiceEvent = RuntimeVoiceEvent(
            EventId(tag),
            onset,
            pitchEvent,
            duration,
            slurStarts = slurStarts,
            slurEnds = slurEnds,
        )
        return addPitchEvent(pitchTrackId, pitchEvent).addVoiceEvent(voiceTrackId, voiceEvent)
    }
}
