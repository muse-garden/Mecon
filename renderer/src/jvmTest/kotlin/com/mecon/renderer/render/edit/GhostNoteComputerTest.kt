package com.mecon.renderer.render.edit

import com.mecon.api.primitive.Accidental
import com.mecon.api.primitive.Duration
import com.mecon.api.primitive.DurationBase
import com.mecon.api.primitive.EventId
import com.mecon.api.primitive.KeySignature
import com.mecon.api.primitive.Pitch
import com.mecon.api.primitive.TimeSignature
import com.mecon.api.runtime.RuntimeScore
import com.mecon.api.runtime.orderedStaffs
import com.mecon.api.storage.Articulation
import com.mecon.api.storage.StaffLayoutPreset
import com.mecon.api.storage.StorageScore
import com.mecon.api.storage.tracks.Clef
import com.mecon.api.storage.tracks.StorageClefChange
import com.mecon.core.engine.computeScore
import com.mecon.core.engine.computeScoreIncremental
import com.mecon.core.serializer.ScoreSerializer
import com.mecon.renderer.layout.RenderLayoutConfig
import com.mecon.renderer.render.DrawGlyph
import com.mecon.renderer.render.DrawLine
import com.mecon.renderer.render.RenderElementType
import com.mecon.renderer.render.RenderEngine
import com.mecon.renderer.snapshot.loadFont
import com.mecon.renderer.snapshot.scoreFiles
import com.mecon.renderer.smufl.SmuflGlyphs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GhostNoteComputerTest {

    private fun emptyScore(): RuntimeScore =
        RuntimeScore.fromStorage(StorageScore.create(StorageScore.CreationOptions("T", TimeSignature.COMMON, KeySignature.C_MAJOR)))

    private fun noteheadRight(element: com.mecon.renderer.render.RenderElement): Float =
        element.hitBox.bottomRight.x.value

    private fun ghostNoteheadRight(ghost: GhostNote): Float = ghost.commands
        .filterIsInstance<DrawGlyph>()
        .first { it.glyph.name.startsWith("notehead") }
        .bounds.bottomRight.x.value

    @Test
    fun ghostAlignsToRenderedNoteheadWithAccidentalDotAndArticulation() {
        val font = loadFont() ?: return
        val initial = emptyScore()
        val staff = initial.orderedStaffs().single()
        val voice = staff.voiceTracks.single()
        val onset = com.mecon.api.primitive.TimeCode.of(1, com.mecon.api.primitive.Fraction.ZERO)
        val dottedEighth = Duration(DurationBase.EIGHTH, dots = 1)
        val inserted = assertNotNull(
            com.mecon.core.engine.edit.NoteEditEngine.insert(
                initial,
                com.mecon.core.engine.edit.NoteEditEngine.Insertion(
                    voiceTrackId = voice.id,
                    staffTrackId = staff.id,
                    start = onset,
                    duration = dottedEighth,
                    pitch = Pitch(diatonicSteps = 6, chromaticOffset = 1),
                    articulations = listOf(Articulation.STACCATO),
                ),
            ),
        )
        val eventId = assertNotNull(inserted.insertedEventId)

        with(font) {
            val engine = RenderEngine(RenderLayoutConfig.DEFAULT.copy(padEmptyMeasures = true))
            val result = engine.render(inserted.score)
            val rendered = result.elementsForEvent(eventId)
            assertTrue(rendered.any { it.type == RenderElementType.ACCIDENTAL })
            assertTrue(rendered.any { it.type == RenderElementType.DOT })
            assertTrue(rendered.any { it.type == RenderElementType.ARTICULATION })
            val notehead = rendered.single { it.type == RenderElementType.NOTEHEAD }
            val slotRight = assertNotNull(result.timeCodePositions[onset]).x
            assertTrue(
                slotRight > noteheadRight(notehead),
                "fixture must put the slot edge to the right of the rendered notehead",
            )

            val ghost = assertNotNull(
                engine.computeGhost(
                    result = result,
                    runtime = inserted.score,
                    // Hover the time-slot edge, which lies to the right of the visible head in
                    // this fixture. The snapped preview must still return to the rendered column.
                    point = com.mecon.renderer.geometry.AbsolutePoint(
                        com.mecon.renderer.geometry.Pixels(slotRight),
                        notehead.hitBox.center.y,
                    ),
                    duration = dottedEighth,
                    accidental = Accidental.SHARP,
                    restMode = false,
                ),
            )

            assertEquals(onset, ghost.onset)
            assertEquals(
                noteheadRight(notehead),
                ghostNoteheadRight(ghost),
                absoluteTolerance = 0.01f,
                message = "ghost must follow the final notehead column, not accidental/dot slot width",
            )
        }
    }

    @Test
    fun ghostUsesSelectedVoiceNoteColumnAfterSameTimeCollisionAvoidance() {
        val font = loadFont() ?: return
        val initial = emptyScore()
        val staff = initial.orderedStaffs().single()
        val voice1 = staff.voiceTracks.single()
        val onset = com.mecon.api.primitive.TimeCode.of(1, com.mecon.api.primitive.Fraction.ZERO)
        val upper = assertNotNull(
            com.mecon.core.engine.edit.NoteEditEngine.insert(
                initial,
                com.mecon.core.engine.edit.NoteEditEngine.Insertion(
                    voiceTrackId = voice1.id,
                    staffTrackId = staff.id,
                    voiceNumber = 1,
                    start = onset,
                    duration = Duration.QUARTER,
                    pitch = Pitch.D4,
                ),
            ),
        )
        val lower = assertNotNull(
            com.mecon.core.engine.edit.NoteEditEngine.insert(
                upper.score,
                com.mecon.core.engine.edit.NoteEditEngine.Insertion(
                    voiceTrackId = voice1.id,
                    staffTrackId = staff.id,
                    voiceNumber = 2,
                    start = onset,
                    duration = Duration.QUARTER,
                    pitch = Pitch.C4,
                ),
            ),
        )
        val upperId = assertNotNull(upper.insertedEventId)
        val lowerId = assertNotNull(lower.insertedEventId)

        with(font) {
            val engine = RenderEngine(RenderLayoutConfig.DEFAULT.copy(padEmptyMeasures = true))
            val result = engine.render(lower.score)
            val upperHead = result.elementsForEvent(upperId).single { it.type == RenderElementType.NOTEHEAD }
            val lowerHead = result.elementsForEvent(lowerId).single { it.type == RenderElementType.NOTEHEAD }
            assertTrue(
                kotlin.math.abs(noteheadRight(upperHead) - noteheadRight(lowerHead)) > 0.01f,
                "fixture must trigger separate note columns for the two voices",
            )

            val ghost = assertNotNull(
                engine.computeGhost(
                    result = result,
                    runtime = lower.score,
                    point = lowerHead.hitBox.center,
                    duration = Duration.QUARTER,
                    accidental = null,
                    restMode = false,
                    voiceNumber = 2,
                ),
            )

            assertEquals(onset, ghost.onset)
            assertEquals(
                noteheadRight(lowerHead),
                ghostNoteheadRight(ghost),
                absoluteTolerance = 0.01f,
                message = "voice-2 ghost must use voice 2's collision-resolved note column",
            )
        }
    }

    @Test
    fun emptyLeftHandGhostBorrowsRightHandAccidentalNoteColumn() {
        val font = loadFont() ?: return
        val initial = RuntimeScore.fromStorage(
            StorageScore.create(
                StorageScore.CreationOptions(
                    title = "Grand staff ghost",
                    layout = StaffLayoutPreset.PIANO_GRAND,
                ),
            ),
        )
        val staffs = initial.orderedStaffs()
        val rightHand = staffs[0]
        val leftHand = staffs[1]
        val onset = com.mecon.api.primitive.TimeCode.of(1, com.mecon.api.primitive.Fraction.ZERO)
        val inserted = assertNotNull(
            com.mecon.core.engine.edit.NoteEditEngine.insert(
                initial,
                com.mecon.core.engine.edit.NoteEditEngine.Insertion(
                    voiceTrackId = rightHand.voiceTracks.single().id,
                    staffTrackId = rightHand.id,
                    // Deliberately place the only visible right-hand note in voice 2. The left-hand
                    // voice-1 ghost therefore exercises the score-wide, voice-independent fallback.
                    voiceNumber = 2,
                    start = onset,
                    duration = Duration.QUARTER,
                    pitch = Pitch(diatonicSteps = 6, chromaticOffset = 1),
                ),
            ),
        )
        val rightEventId = assertNotNull(inserted.insertedEventId)

        with(font) {
            val engine = RenderEngine(RenderLayoutConfig.DEFAULT.copy(padEmptyMeasures = true))
            val result = engine.render(inserted.score)
            val rightHead = result.elementsForEvent(rightEventId)
                .single { it.type == RenderElementType.NOTEHEAD }
            val slotRight = assertNotNull(result.timeCodePositions[onset]).x
            assertTrue(slotRight > noteheadRight(rightHead), "right-hand accidental must expand the slot")
            assertNull(
                result.noteheadRightPositions[onset]?.get(1 to 1),
                "left hand must have no local notehead anchor in this fixture",
            )
            assertNull(
                result.sharedNoteheadRightPositionsByVoice[onset]?.get(1),
                "no staff may provide a voice-1 notehead; this fixture must exercise global fallback",
            )
            assertEquals(
                noteheadRight(rightHead),
                assertNotNull(result.sharedNoteheadRightPositions[onset]),
                absoluteTolerance = 0.01f,
            )
            val leftStaffElement = result.elements.first {
                it.type == RenderElementType.STAFF && it.staffIndex == 1
            }

            val ghost = assertNotNull(
                engine.computeGhost(
                    result = result,
                    runtime = inserted.score,
                    point = com.mecon.renderer.geometry.AbsolutePoint(
                        com.mecon.renderer.geometry.Pixels(slotRight),
                        leftStaffElement.hitBox.center.y,
                    ),
                    duration = Duration.QUARTER,
                    accidental = null,
                    restMode = false,
                    voiceNumber = 1,
                ),
            )

            assertEquals(leftHand.id, ghost.staffTrackId)
            assertEquals(onset, ghost.onset)
            assertEquals(
                noteheadRight(rightHead),
                ghostNoteheadRight(ghost),
                absoluteTolerance = 0.01f,
                message = "empty left-hand ghost must align to the right-hand note column",
            )

            val missingVoiceGhost = assertNotNull(
                engine.computeGhost(
                    result = result,
                    runtime = inserted.score,
                    point = com.mecon.renderer.geometry.AbsolutePoint(
                        com.mecon.renderer.geometry.Pixels(slotRight),
                        leftStaffElement.hitBox.center.y,
                    ),
                    duration = Duration.QUARTER,
                    accidental = null,
                    restMode = false,
                    voiceNumber = 2,
                ),
            )
            assertEquals(2, missingVoiceGhost.voiceNumber)
            assertEquals(
                noteheadRight(rightHead),
                ghostNoteheadRight(missingVoiceGhost),
                absoluteTolerance = 0.01f,
                message = "a not-yet-created left-hand voice must also use the shared note column",
            )
        }
    }

    @Test
    fun ghostUsesKeySignatureWhenNoAccidentalIsSelected() {
        val font = loadFont() ?: return
        val runtime = RuntimeScore.fromStorage(
            StorageScore.create(StorageScore.CreationOptions("F major", TimeSignature.COMMON, KeySignature.F_MAJOR)),
        )
        with(font) {
            val engine = RenderEngine(RenderLayoutConfig.DEFAULT.copy(padEmptyMeasures = true))
            val result = engine.render(runtime)
            val staff = result.elements.first { it.type == RenderElementType.STAFF }
            val ghost = assertNotNull(
                engine.computeGhost(result, runtime, staff.hitBox.center, Duration.QUARTER, null, false),
            )

            assertEquals(6, ghost.pitch.diatonicSteps, "treble middle line is B4")
            assertEquals(-1, ghost.pitch.chromaticOffset, "F major defaults B to B-flat")
        }
    }

    @Test
    fun ghostUsesClefTimelineAtSnappedOnset() {
        val font = loadFont() ?: return
        val storage = StorageScore.create(StorageScore.CreationOptions("Clef", measureCount = 2))
        val staffId = storage.staffTracks.keys.single()
        val bassAtM2 = com.mecon.api.primitive.TimeCode.of(2, com.mecon.api.primitive.Fraction.ZERO)
        val changed = storage.copy(
            staffTracks = storage.staffTracks + (
                staffId to storage.staffTracks.getValue(staffId).copy(
                    clefChanges = listOf(StorageClefChange(bassAtM2, Clef.BASS)),
                )
            ),
        )
        val runtime = RuntimeScore.fromStorage(changed)
        with(font) {
            val engine = RenderEngine(RenderLayoutConfig.DEFAULT.copy(padEmptyMeasures = true))
            val result = engine.render(runtime)
            val x = assertNotNull(result.timeCodePositions[bassAtM2]?.x)
            val staff = result.elements.first { it.type == RenderElementType.STAFF }
            val ghost = assertNotNull(
                engine.computeGhost(
                    result,
                    runtime,
                    com.mecon.renderer.geometry.AbsolutePoint(
                        com.mecon.renderer.geometry.Pixels(x),
                        staff.hitBox.center.y,
                    ),
                    Duration.QUARTER,
                    null,
                    false,
                ),
            )

            assertEquals(bassAtM2, ghost.onset)
            assertEquals(-6, ghost.pitch.diatonicSteps, "bass middle line is D3")
        }
    }

    /** An empty (rest-only) measure is padded to a usable width only when [padEmptyMeasures] is on. */
    @Test
    fun padEmptyMeasuresWidensBlankBar() {
        val font = loadFont() ?: return
        val runtime = emptyScore()
        with(font) {
            val off = RenderEngine(RenderLayoutConfig.DEFAULT).render(runtime)
            val on = RenderEngine(RenderLayoutConfig.DEFAULT.copy(padEmptyMeasures = true)).render(runtime)
            assertTrue(
                on.bounds.width.value > off.bounds.width.value,
                "padded blank bar should be wider: off=${off.bounds.width.value}, on=${on.bounds.width.value}"
            )
        }
    }

    /**
     * After inserting a dotted eighth at beat 0 of a 4/4 bar, the gap left is a 16th rest at 3/16.
     * The ghost must be able to snap there — it is an off-beat onset that only exists as a voice
     * event (not a synthesized integer beat). Regression for the edit engine leaving [orderedStaffs]
     * (which [GhostNoteComputer] resolves the voice through) pointing at stale, pre-edit voice events.
     */
    @Test
    fun ghostSnapsToSixteenthRestAfterDottedEighth() {
        val font = loadFont() ?: return
        var runtime = emptyScore()
        val voiceId = runtime.orderedStaffs().first().voiceTracks.first().id
        val dottedEighth = Duration(com.mecon.api.primitive.DurationBase.EIGHTH, dots = 1)
        runtime = com.mecon.core.engine.edit.NoteEditEngine.insert(
            runtime,
            com.mecon.core.engine.edit.NoteEditEngine.Insertion(
                voiceTrackId = voiceId,
                start = com.mecon.api.primitive.TimeCode.of(1, com.mecon.api.primitive.Fraction.ZERO),
                duration = dottedEighth,
                pitch = com.mecon.api.primitive.Pitch.fromName("B4"),
            )
        )!!.score

        with(font) {
            val engine = RenderEngine(RenderLayoutConfig.DEFAULT.copy(padEmptyMeasures = true))
            val result = engine.render(runtime)
            val onset316 = com.mecon.api.primitive.TimeCode.of(1, com.mecon.api.primitive.Fraction(3, 16))
            // X (absolute) where the 16th rest is laid out — hovering there must snap to 3/16.
            val x316 = result.timeCodePositions[onset316]?.x
            assertNotNull(x316, "the inserted edit should produce a slot at 3/16")
            val staff = result.elements.first { it.type == RenderElementType.STAFF }
            val cy = staff.hitBox.center.y.value
            val ghost = engine.computeGhost(
                result, runtime,
                com.mecon.renderer.geometry.AbsolutePoint(
                    com.mecon.renderer.geometry.Pixels(x316), com.mecon.renderer.geometry.Pixels(cy)
                ),
                Duration(com.mecon.api.primitive.DurationBase.SIXTEENTH),
                null, false,
            )
            assertNotNull(ghost, "ghost should resolve over the 16th rest")
            assertTrue(
                ghost.onset == onset316,
                "ghost should snap to the 16th-rest onset 3/16, but was ${ghost.onset}"
            )
        }
    }

    /** A missing voice may reuse dyadic slots from another voice, but never its tuplet-only grid. */
    @Test
    fun missingVoiceDoesNotSnapToOtherVoiceTupletOnset() {
        val font = loadFont() ?: return
        var runtime = emptyScore()
        val voiceId = runtime.orderedStaffs().first().voiceTracks.first().id
        runtime = com.mecon.core.engine.edit.NoteEditEngine.insert(
            runtime,
            com.mecon.core.engine.edit.NoteEditEngine.Insertion(
                voiceTrackId = voiceId,
                start = com.mecon.api.primitive.TimeCode.of(1, com.mecon.api.primitive.Fraction.ZERO),
                duration = Duration.QUARTER,
                pitch = com.mecon.api.primitive.Pitch.fromName("B4"),
                tupletCount = 3,
            )
        )!!.score

        with(font) {
            val engine = RenderEngine(RenderLayoutConfig.DEFAULT.copy(padEmptyMeasures = true))
            val result = engine.render(runtime)
            val tripletSecond = com.mecon.api.primitive.TimeCode.of(
                1,
                com.mecon.api.primitive.Fraction(1, 12),
            )
            val tripletX = result.timeCodePositions[tripletSecond]?.x
            assertNotNull(tripletX, "the second triplet member should have a laid-out slot")
            val staff = result.elements.first { it.type == RenderElementType.STAFF }
            val point = com.mecon.renderer.geometry.AbsolutePoint(
                com.mecon.renderer.geometry.Pixels(tripletX),
                com.mecon.renderer.geometry.Pixels(staff.hitBox.center.y.value),
            )

            val voice1Ghost = engine.computeGhost(
                result, runtime, point, Duration.EIGHTH, null, false, voiceNumber = 1,
            )
            assertNotNull(voice1Ghost)
            assertTrue(
                voice1Ghost.onset == tripletSecond,
                "the tuplet's own voice should still snap to its second member",
            )

            val missingVoice2Ghost = engine.computeGhost(
                result, runtime, point, Duration.EIGHTH, null, false, voiceNumber = 2,
            )
            assertNotNull(missingVoice2Ghost)
            val snappedBeat = missingVoice2Ghost.onset.beat ?: com.mecon.api.primitive.Fraction.ZERO
            val denominator = snappedBeat.simplified().denominator
            assertTrue(
                missingVoice2Ghost.onset != tripletSecond && denominator and (denominator - 1) == 0,
                "voice 2 must snap to a dyadic boundary, but was ${missingVoice2Ghost.onset}",
            )
        }
    }

    @Test
    fun smallNoteGhostCreatesFinerGridThanConvertedRests() {
        val font = loadFont() ?: return
        val initial = emptyScore()
        val voiceId = initial.orderedStaffs().first().voiceTracks.first().id
        val rest = com.mecon.core.engine.edit.NoteEditEngine.insert(
            initial,
            com.mecon.core.engine.edit.NoteEditEngine.Insertion(
                voiceTrackId = voiceId,
                start = com.mecon.api.primitive.TimeCode.of(1, com.mecon.api.primitive.Fraction.ZERO),
                duration = Duration.QUARTER,
                pitch = null,
                isRest = true,
            )
        )!!
        val converted = com.mecon.core.engine.edit.NoteEditEngine.createSmallNoteRegions(
            rest.score,
            listOf(
                com.mecon.core.engine.edit.NoteEditEngine.SmallNoteEdit(
                    voiceId,
                    setOf(rest.insertedEventId!!),
                )
            ),
        ) as com.mecon.core.engine.edit.NoteEditEngine.EditOutcome.Changed
        val runtime = converted.score
        val regionEvents = runtime.getVoiceTrack(voiceId)!!.events.toList()
            .filter { it.onset.measure == 1 && (it.onset.beat ?: com.mecon.api.primitive.Fraction.ZERO) < com.mecon.api.primitive.Fraction(1, 4) }
            .sortedBy { it.onset }
        val start = regionEvents.first { it.tupletSpan?.smallNotes == true }
        val regionEnd = start.tupletSpan!!.endTimeCode
        val fineDuration = Duration(com.mecon.api.primitive.DurationBase.THIRTY_SECOND)
        val fineStep = fineDuration.copy(tuplet = start.duration.tuplet).toFraction()
        val fineOnset = com.mecon.api.primitive.TimeCode.of(1, fineStep)

        with(font) {
            val engine = RenderEngine(RenderLayoutConfig.DEFAULT.copy(padEmptyMeasures = true))
            val result = engine.render(runtime)
            val hiddenRestIds = regionEvents.filter { it.isRest }.map { it.id }.toSet()
            val markerRests = result.elements.filter {
                it.type == RenderElementType.REST && it.eventId in hiddenRestIds
            }
            assertTrue(
                markerRests.size == 1 && markerRests.single().eventId == start.id,
                "only the small-note region start should render a rest marker",
            )
            assertTrue(
                markerRests.single().commands
                    .filterIsInstance<com.mecon.renderer.render.DrawGlyph>()
                    .all { it.color == com.mecon.api.render.RenderColor.rgb(70, 140, 215) },
                "the small-note region marker should use its editing color",
            )
            val x0 = assertNotNull(result.timeCodePositions[start.onset]?.x)
            val nextPlaceholder = regionEvents.first { it.onset > start.onset }
            val x1 = assertNotNull(result.timeCodePositions[nextPlaceholder.onset]?.x)
            val placeholderStep =
                (nextPlaceholder.onset.beat ?: com.mecon.api.primitive.Fraction.ZERO) -
                    (start.onset.beat ?: com.mecon.api.primitive.Fraction.ZERO)
            val interpolation = fineStep / placeholderStep
            val f = interpolation.numerator.toFloat() / interpolation.denominator
            val staff = result.elements.first { it.type == RenderElementType.STAFF }
            val ghost = engine.computeGhost(
                result = result,
                runtime = runtime,
                point = com.mecon.renderer.geometry.AbsolutePoint(
                    com.mecon.renderer.geometry.Pixels(x0 + (x1 - x0) * f),
                    com.mecon.renderer.geometry.Pixels(staff.hitBox.center.y.value),
                ),
                duration = fineDuration,
                accidental = null,
                restMode = false,
            )
            assertNotNull(ghost)
            assertTrue(
                ghost.onset == fineOnset,
                "small-note ghost should synthesize $fineOnset instead of ${ghost.onset}",
            )
        }
    }

    @Test
    fun filledSmallNoteRegionUsesExplicitGapIntentWithoutHijackingEndpoint() {
        val font = loadFont() ?: return
        val initial = emptyScore()
        val voiceId = initial.orderedStaffs().first().voiceTracks.first().id
        val rest = assertNotNull(
            com.mecon.core.engine.edit.NoteEditEngine.insert(
                initial,
                com.mecon.core.engine.edit.NoteEditEngine.Insertion(
                    voiceTrackId = voiceId,
                    start = com.mecon.api.primitive.TimeCode.of(
                        1,
                        com.mecon.api.primitive.Fraction(1, 2),
                    ),
                    duration = Duration.HALF,
                    pitch = null,
                    isRest = true,
                ),
            ),
        )
        val followingRestId = EventId("rest-${voiceId.value}-m2")
        val converted = com.mecon.core.engine.edit.NoteEditEngine.createSmallNoteRegions(
            rest.score,
            listOf(
                com.mecon.core.engine.edit.NoteEditEngine.SmallNoteEdit(
                    voiceId,
                    setOf(assertNotNull(rest.insertedEventId)),
                ),
            ),
        ) as com.mecon.core.engine.edit.NoteEditEngine.EditOutcome.Changed
        val start = converted.score.getVoiceTrack(voiceId)!!.events.toList()
            .first { it.tupletSpan?.smallNotes == true }
        val regionEnd = start.tupletSpan!!.endTimeCode
        assertEquals(
            com.mecon.api.primitive.TimeCode.of(2, com.mecon.api.primitive.Fraction.ZERO),
            regionEnd,
        )
        val duration = Duration(com.mecon.api.primitive.DurationBase.SIXTEENTH)
        val step = duration.copy(tuplet = assertNotNull(start.duration.tuplet)).toFraction()
        var runtime = converted.score
        repeat(4) { index ->
            runtime = assertNotNull(
                com.mecon.core.engine.edit.NoteEditEngine.insert(
                    runtime,
                    com.mecon.core.engine.edit.NoteEditEngine.Insertion(
                        voiceTrackId = voiceId,
                        start = com.mecon.api.primitive.TimeCode.of(
                            1,
                            com.mecon.api.primitive.Fraction(1, 2) +
                                step * com.mecon.api.primitive.Fraction(index, 1),
                        ),
                        duration = duration,
                        pitch = com.mecon.api.primitive.Pitch.C4,
                    ),
                ),
            ).score
        }
        val previousComputed = computeScore(runtime)

        with(font) {
            val engine = RenderEngine(RenderLayoutConfig.DEFAULT.copy(padEmptyMeasures = true))
            val result = engine.render(runtime)
            val currentStart = runtime.getVoiceTrack(voiceId)!!.events.toList()
                .first { it.tupletSpan?.smallNotes == true }
            val endpointX = assertNotNull(result.timeCodePositions[regionEnd]?.x)
            val staff = result.elements.first { it.type == RenderElementType.STAFF }
            val lastEntered = runtime.getVoiceTrack(voiceId)!!.events.toList()
                .filter { !it.isRest && it.onset >= start.onset && it.onset < regionEnd }
                .maxBy { it.onset }
            val lastNoteRight = result.elementsForEvent(lastEntered.id)
                .filter { it.type == RenderElementType.NOTEHEAD }
                .maxOf { it.hitBox.origin.x.value + it.hitBox.width.value }
            assertTrue(endpointX - lastNoteRight > 4f, "fixture needs a visible append gap")
            val appendZoneGhost = assertNotNull(
                engine.computeGhost(
                    result = result,
                    runtime = runtime,
                    point = com.mecon.renderer.geometry.AbsolutePoint(
                        com.mecon.renderer.geometry.Pixels(
                            lastNoteRight + (endpointX - lastNoteRight) * 0.5f,
                        ),
                        com.mecon.renderer.geometry.Pixels(staff.hitBox.center.y.value),
                    ),
                    duration = duration,
                    accidental = null,
                    restMode = false,
                ),
            )
            assertEquals(
                regionEnd,
                appendZoneGhost.onset,
                "clicking just after the final small note must append without targeting the barline",
            )
            assertEquals(currentStart.id, appendZoneGhost.smallNoteAppendStartEventId)
            val ghost = assertNotNull(
                engine.computeGhost(
                    result = result,
                    runtime = runtime,
                    point = com.mecon.renderer.geometry.AbsolutePoint(
                        com.mecon.renderer.geometry.Pixels(endpointX),
                        com.mecon.renderer.geometry.Pixels(staff.hitBox.center.y.value),
                    ),
                    duration = duration,
                    accidental = null,
                    restMode = false,
                ),
            )
            assertEquals(
                regionEnd,
                ghost.onset,
                "the fixed region endpoint must belong to the following normal time axis",
            )
            assertNull(
                ghost.smallNoteAppendStartEventId,
                "clicking the next measure head must not append to the preceding small-note group",
            )
            val followingEnd = com.mecon.api.primitive.TimeCode.of(
                3,
                com.mecon.api.primitive.Fraction.ZERO,
            )
            val followingEndX = assertNotNull(result.timeCodePositions[followingEnd]?.x)
            val ordinaryGhost = assertNotNull(
                engine.computeGhost(
                    result = result,
                    runtime = runtime,
                    point = com.mecon.renderer.geometry.AbsolutePoint(
                        com.mecon.renderer.geometry.Pixels(
                            endpointX + (followingEndX - endpointX) * 0.25f,
                        ),
                        com.mecon.renderer.geometry.Pixels(staff.hitBox.center.y.value),
                    ),
                    duration = duration,
                    accidental = null,
                    restMode = false,
                ),
            )
            assertEquals(
                com.mecon.api.primitive.TimeCode.of(2, com.mecon.api.primitive.Fraction(1, 4)),
                ordinaryGhost.onset,
                "snapping inside the following measure must not return an overlong beat in measure 1",
            )
            val appended = assertNotNull(
                com.mecon.core.engine.edit.NoteEditEngine.insert(
                    runtime,
                    com.mecon.core.engine.edit.NoteEditEngine.Insertion(
                        voiceTrackId = voiceId,
                        start = appendZoneGhost.onset,
                        duration = duration,
                        pitch = com.mecon.api.primitive.Pitch.D4,
                        smallNoteAppendStartEventId = appendZoneGhost.smallNoteAppendStartEventId,
                    ),
                ),
            )
            assertEquals(
                5,
                appended.score.getVoiceTrack(voiceId)!!.events.toList()
                    .count { !it.isRest && it.onset >= start.onset && it.onset < regionEnd },
            )
            assertTrue(
                appended.score.getVoiceTrack(voiceId)!!.events.toList()
                    .none { it.onset.measure == 2 },
                "appending to the preceding small-note group must not store rests in an empty measure; " +
                    "events=${appended.score.getVoiceTrack(voiceId)!!.events.toList()}",
            )
            val incremental = computeScoreIncremental(
                previousComputed,
                appended.score,
                appended.editInterval,
            )
            val unchangedFollowingRest = assertNotNull(
                incremental.computed.computedEvents[followingRestId],
                "the following empty measure must retain its derived whole-measure rest",
            )
            assertEquals(
                Duration.WHOLE,
                unchangedFollowingRest.duration,
                "editing the preceding small-note group must not change the following empty-measure rest",
            )
            val beforeRestGlyph = result.elementsForEvent(followingRestId)
                .flatMap { it.commands }
                .filterIsInstance<DrawGlyph>()
                .single()
                .glyph
            val incrementalRender = engine.renderIncremental(
                incremental.computed,
                incremental.changeSet,
            )
            val afterRestGlyph = incrementalRender.elementsForEvent(followingRestId)
                .flatMap { it.commands }
                .filterIsInstance<DrawGlyph>()
                .single()
                .glyph
            assertEquals(
                beforeRestGlyph,
                afterRestGlyph,
                "incremental small-note editing must not replace the empty-measure rest glyph",
            )

            val normalNoteResult = assertNotNull(
                com.mecon.core.engine.edit.NoteEditEngine.insert(
                    runtime,
                    com.mecon.core.engine.edit.NoteEditEngine.Insertion(
                        voiceTrackId = voiceId,
                        start = regionEnd,
                        duration = Duration.QUARTER,
                        pitch = com.mecon.api.primitive.Pitch.C4,
                    ),
                ),
            )
            val normalNoteId = assertNotNull(normalNoteResult.insertedEventId)
            val noteRuntime = normalNoteResult.score
            val noteRender = engine.render(noteRuntime)
            val notehead = noteRender.elementsForEvent(normalNoteId)
                .first { it.type == RenderElementType.NOTEHEAD }
            val chordGhost = assertNotNull(
                engine.computeGhost(
                    result = noteRender,
                    runtime = noteRuntime,
                    point = com.mecon.renderer.geometry.AbsolutePoint(
                        notehead.hitBox.center.x,
                        notehead.hitBox.center.y,
                    ),
                    duration = Duration.QUARTER,
                    accidental = null,
                    restMode = false,
                ),
            )
            assertEquals(regionEnd, chordGhost.onset)
            assertNull(
                chordGhost.smallNoteAppendStartEventId,
                "a real following notehead must win over the preceding append gap",
            )
            val chorded = assertNotNull(
                com.mecon.core.engine.edit.NoteEditEngine.insert(
                    noteRuntime,
                    com.mecon.core.engine.edit.NoteEditEngine.Insertion(
                        voiceTrackId = voiceId,
                        start = chordGhost.onset,
                        duration = Duration.QUARTER,
                        pitch = com.mecon.api.primitive.Pitch.D4,
                        smallNoteAppendStartEventId = chordGhost.smallNoteAppendStartEventId,
                    ),
                ),
            ).score
            assertEquals(
                setOf(com.mecon.api.primitive.Pitch.C4, com.mecon.api.primitive.Pitch.D4),
                chorded.getVoiceTrack(voiceId)!!.events.toList()
                    .first { it.id == normalNoteId }.pitches.toSet(),
                "clicking the following normal note must add a chord tone there",
            )
            assertEquals(
                4,
                chorded.getVoiceTrack(voiceId)!!.events.toList()
                    .count { !it.isRest && it.onset >= start.onset && it.onset < regionEnd },
                "editing the following normal note must not append to the small-note group",
            )
        }
    }

    /**
     * A no-accidental ghost inherits the accidental of an earlier note at the same staff position in
     * the same measure (standard notation carry-over), instead of reverting to natural. Here a sharped
     * B4 is placed at beat 0; hovering beat 1 at the same line with no accidental selected must preview
     * a B♯4 (chromaticOffset +1), not a B♮4.
     */
    @Test
    fun ghostInheritsMeasureAccidentalWhenNoneSelected() {
        val font = loadFont() ?: return
        var runtime = emptyScore()
        val voiceId = runtime.orderedStaffs().first().voiceTracks.first().id
        // B4 sits on the treble middle line (diatonicSteps 6); +1 makes it a B♯4.
        val bSharp4 = com.mecon.api.primitive.Pitch(diatonicSteps = 6, chromaticOffset = 1)
        runtime = com.mecon.core.engine.edit.NoteEditEngine.insert(
            runtime,
            com.mecon.core.engine.edit.NoteEditEngine.Insertion(
                voiceTrackId = voiceId,
                start = com.mecon.api.primitive.TimeCode.of(1, com.mecon.api.primitive.Fraction.ZERO),
                duration = Duration.QUARTER,
                pitch = bSharp4,
            )
        )!!.score

        with(font) {
            val engine = RenderEngine(RenderLayoutConfig.DEFAULT.copy(padEmptyMeasures = true))
            val result = engine.render(runtime)
            val beat1 = com.mecon.api.primitive.TimeCode.of(1, com.mecon.api.primitive.Fraction(1, 4))
            val x1 = result.timeCodePositions[beat1]?.x
            assertNotNull(x1, "beat 1 should have a laid-out slot")
            // Staff centre maps to the middle line → the same B4 staff position as the placed note.
            val staff = result.elements.first { it.type == RenderElementType.STAFF }
            val cy = staff.hitBox.center.y.value
            val ghost = engine.computeGhost(
                result, runtime,
                com.mecon.renderer.geometry.AbsolutePoint(
                    com.mecon.renderer.geometry.Pixels(x1), com.mecon.renderer.geometry.Pixels(cy)
                ),
                Duration.QUARTER,
                null, false,
            )
            assertNotNull(ghost, "ghost should resolve over beat 1")
            assertTrue(
                ghost.pitch.diatonicSteps == 6 && ghost.pitch.chromaticOffset == 1,
                "ghost should carry the measure's B♯ forward, but was ${ghost.pitch}"
            )
        }
    }

    /** The note-pen ghost resolves a staff hover into a note preview whose geometry includes a stem. */
    @Test
    fun computeGhostProducesAlignedStem() {
        val font = loadFont() ?: return
        val scoreFile = scoreFiles().firstOrNull { it.name == "01_durations.mscore.yaml" } ?: return
        val runtime = RuntimeScore.fromStorage(ScoreSerializer.fromYaml(scoreFile.readText()))
        with(font) {
            val engine = RenderEngine(RenderLayoutConfig.DEFAULT.copy(padEmptyMeasures = true))
            val result = engine.render(runtime)

            // Hover over the centre of the first staff.
            val staff = result.elements.first { it.type == RenderElementType.STAFF }
            val ghost = engine.computeGhost(
                result = result,
                runtime = runtime,
                point = staff.hitBox.center,
                duration = Duration.QUARTER,
                accidental = null,
                restMode = false,
            )
            assertNotNull(ghost, "ghost should resolve when hovering over a staff")
            assertTrue(ghost.commands.isNotEmpty(), "ghost should carry preview geometry")
            assertTrue(
                ghost.commands.any { it is DrawLine },
                "a quarter-note ghost should include a stem (DrawLine)"
            )
        }
    }

    @Test
    fun pendingTupletGhostUsesMemberNoteValue() {
        val font = loadFont() ?: return
        val runtime = emptyScore()
        with(font) {
            val engine = RenderEngine(RenderLayoutConfig.DEFAULT.copy(padEmptyMeasures = true))
            val result = engine.render(runtime)
            val staff = result.elements.first { it.type == RenderElementType.STAFF }
            val ghost = assertNotNull(
                engine.computeGhost(
                    result = result,
                    runtime = runtime,
                    point = staff.hitBox.center,
                    duration = Duration.QUARTER,
                    accidental = null,
                    restMode = false,
                    tupletCount = 3,
                ),
            )
            val glyphs = ghost.commands.filterIsInstance<DrawGlyph>().map { it.glyph.codepoint }
            assertTrue(
                SmuflGlyphs.flag8thUp.codepoint in glyphs || SmuflGlyphs.flag8thDown.codepoint in glyphs,
                "a quarter-span triplet ghost should display an eighth-note member",
            )
            assertTrue(
                SmuflGlyphs.tuplet3.codepoint in glyphs,
                "the pending tuplet marker should remain visible",
            )
        }
    }
}
