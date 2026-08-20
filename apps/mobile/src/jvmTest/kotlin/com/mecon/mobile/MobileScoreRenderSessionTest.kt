package com.mecon.mobile

import com.mecon.api.primitive.Duration
import com.mecon.api.primitive.Fraction
import com.mecon.api.primitive.TimeCode
import com.mecon.api.primitive.KeySignature
import com.mecon.api.primitive.TimeSignature
import com.mecon.api.storage.StorageScore
import com.mecon.api.storage.events.HairpinStyle
import com.mecon.api.storage.events.HairpinType
import com.mecon.api.storage.events.DynamicLevel
import com.mecon.api.storage.events.TempoMarkType
import com.mecon.api.storage.events.OrnamentKind
import com.mecon.api.storage.tracks.BreathMarkShape
import com.mecon.api.storage.tracks.FermataShape
import com.mecon.api.storage.tracks.Clef
import com.mecon.features.scoreediting.ScoreEntryCursor
import com.mecon.features.scoreediting.ScoreEntryCursorAction
import com.mecon.features.scoreediting.ScoreInteractionCatalog
import com.mecon.renderer.render.DrawGlyph
import com.mecon.renderer.render.RenderElementType
import com.mecon.renderer.geometry.AbsolutePoint
import com.mecon.renderer.geometry.AbsoluteRect
import com.mecon.renderer.geometry.Pixels
import com.mecon.renderer.geometry.RelativePoint
import com.mecon.renderer.geometry.StaffSpace
import com.mecon.renderer.smufl.BravuraFont
import com.mecon.renderer.render.edit.ExpressionSpanKind
import com.mecon.renderer.render.edit.PointSymbolKind
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.assertNotNull
import kotlin.test.assertEquals
import com.mecon.features.scoreediting.ScoreSelectionTarget

class MobileScoreRenderSessionTest {
    @Test
    fun marqueeUsesDisplayedSpatialIndexAndReturnsStableEventTargets() {
        val fontRoot = File("../desktop/src/main/resources/bravura")
        val font = BravuraFont.fromJson(
            File(fontRoot, "bravuraMetadata.json").readText(),
            File(fontRoot, "glyphnames.json").readText(),
        )
        val controller = MobileScoreEditorController.open(
            StorageScore.create(StorageScore.CreationOptions(title = "", measureCount = 1)),
        )
        val voiceId = controller.state.value.frame.runtimeScore.voiceTracks.keys.single()
        controller.activate(
            ScoreInteractionCatalog.ENTRY_NOTE,
            ScoreEntryCursor(voiceId, TimeCode.of(1, Fraction.ZERO)),
        )
        controller.insertMidiNote(60, Duration.QUARTER)
        controller.insertMidiNote(64, Duration.QUARTER)
        val renderer = MobileScoreRenderSession(font)
        val result = renderer.render(controller.state.value.frame)
        val heads = result.elements.filter { it.type == RenderElementType.NOTEHEAD }
        val left = heads.minOf { it.hitBox.origin.x.value } - 2f
        val top = heads.minOf { it.hitBox.origin.y.value } - 2f
        val right = heads.maxOf { it.hitBox.bottomRight.x.value } + 2f
        val bottom = heads.maxOf { it.hitBox.bottomRight.y.value } + 2f

        val selected = renderer.selectionTargetsInRegion(
            result,
            AbsoluteRect(
                AbsolutePoint(Pixels(left), Pixels(top)),
                Pixels(right - left),
                Pixels(bottom - top),
            ),
        ).filterIsInstance<ScoreSelectionTarget.Event>()

        assertEquals(2, selected.size)
        assertEquals(setOf(0), selected[0].pitchIndices)
        assertTrue(selected.all { it.voiceTrackId == voiceId })
        val firstHead = heads.first()
        val candidates = renderer.selectionCandidatesAt(result, firstHead.center)
        assertEquals(1, candidates.filterIsInstance<ScoreSelectionTarget.Event>().size)
        assertEquals(setOf(0), (candidates.single() as ScoreSelectionTarget.Event).pitchIndices)
    }

    @Test
    fun fermataHitResolvesToItsEditablePerformanceAttachment() {
        val fontRoot = File("../desktop/src/main/resources/bravura")
        val font = BravuraFont.fromJson(
            File(fontRoot, "bravuraMetadata.json").readText(),
            File(fontRoot, "glyphnames.json").readText(),
        )
        val controller = MobileScoreEditorController.open(
            StorageScore.create(StorageScore.CreationOptions(title = "", measureCount = 1)),
        )
        val voiceId = controller.state.value.frame.runtimeScore.voiceTracks.keys.single()
        controller.activate(
            ScoreInteractionCatalog.ENTRY_NOTE,
            ScoreEntryCursor(voiceId, TimeCode.of(1, Fraction.ZERO)),
        )
        controller.insertMidiNote(60, Duration.QUARTER)
        val added = controller.addFermata(TimeCode.of(1, Fraction.QUARTER), FermataShape.NORMAL)
        val markId = (added.frame.selection.single() as ScoreSelectionTarget.Attachment).attachmentId
        val renderer = MobileScoreRenderSession(font)
        val result = renderer.render(added.frame)
        val fermataElement = result.elements.first {
            it.type == RenderElementType.ARTICULATION && it.eventId == markId
        }

        val picked = assertNotNull(renderer.selectionTargetAt(result, fermataElement.center))
        assertEquals(ScoreSelectionTarget.Attachment(markId), picked)
    }

    @Test
    fun attachmentHitAndSemanticNudgeUseTheCapturedRenderGeometry() {
        val fontRoot = File("../desktop/src/main/resources/bravura")
        val font = BravuraFont.fromJson(
            File(fontRoot, "bravuraMetadata.json").readText(),
            File(fontRoot, "glyphnames.json").readText(),
        )
        val controller = MobileScoreEditorController.open(
            StorageScore.create(StorageScore.CreationOptions(title = "", measureCount = 1)),
        )
        val staffId = controller.state.value.frame.runtimeScore.staffTracks.keys.single()
        val added = controller.addDynamic(staffId, TimeCode.of(1, Fraction.ZERO), DynamicLevel.MF)
        val attachment = added.frame.selection.single() as ScoreSelectionTarget.Attachment
        val renderer = MobileScoreRenderSession(font)
        val result = renderer.render(controller.state.value.frame)
        val element = result.elements.first { it.type == RenderElementType.DYNAMIC }
        val picked = assertNotNull(renderer.selectionTargetAt(result, element.center))
        assertEquals(attachment.attachmentId, (picked as ScoreSelectionTarget.Attachment).attachmentId)
        assertTrue(renderer.elementsForSelection(result, picked).any { it.id == element.id })
        val before = assertNotNull(result.capturedGeometry?.attachments?.get(attachment.attachmentId))

        controller.activate(ScoreInteractionCatalog.HANDLE)
        controller.setSelection(listOf(picked))
        val nudged = assertNotNull(controller.nudgeSelectedGeometry(result.capturedGeometry, 0f, 0.5f))
        val after = assertNotNull(nudged.frame.runtimeScore.geometry?.attachments?.get(attachment.attachmentId))
        assertEquals(before.startDy + 0.5f, after.startDy)
    }

    @Test
    fun mobileFrameUsesTheRealRendererBeforeAndAfterPianoInput() {
        val fontRoot = File("../desktop/src/main/resources/bravura")
        val font = BravuraFont.fromJson(
            File(fontRoot, "bravuraMetadata.json").readText(),
            File(fontRoot, "glyphnames.json").readText(),
        )
        val controller = MobileScoreEditorController.open(
            StorageScore.create(StorageScore.CreationOptions(title = "", measureCount = 2)),
        )
        val voiceId = controller.state.value.frame.runtimeScore.voiceTracks.keys.single()
        controller.activate(
            ScoreInteractionCatalog.ENTRY_NOTE,
            ScoreEntryCursor(voiceId, TimeCode.of(1, Fraction.ZERO)),
        )
        val renderer = MobileScoreRenderSession(font)

        val empty = renderer.render(controller.state.value.frame)
        assertTrue(
            empty.elements.any { it.type == RenderElementType.STAFF },
            "expected staff geometry, got ${empty.elements.map { it.type }.distinct()}",
        )
        assertTrue(empty.elements.any { element -> element.commands.any { it is DrawGlyph } })
        val renderedBarline = empty.elements.first { it.type == RenderElementType.BARLINE }
        val boundaryHit = assertNotNull(empty.barlineHitAt(renderedBarline.center, Pixels(24f)))
        assertNotNull(empty.barlinePositionAt(boundaryHit))
        val selectedBarline = assertNotNull(renderer.selectionTargetAt(empty, renderedBarline.center))
        assertTrue(renderer.elementsForSelection(empty, selectedBarline).any { it.id == renderedBarline.id })
        val renderedClef = empty.elements.first { it.type == RenderElementType.CLEF }
        val selectedClef = assertNotNull(renderer.selectionTargetAt(empty, renderedClef.center))
        assertTrue(renderer.elementsForSelection(empty, selectedClef).any { it.id == renderedClef.id })
        assertTrue(empty.timeCodePositions.isNotEmpty())
        val measureOnlyCaret = assertNotNull(empty.insertionPositionAt(TimeCode.ofMeasure(1)))
        val explicitDownbeatCaret = assertNotNull(empty.insertionPositionAt(TimeCode.of(1, Fraction.ZERO)))
        assertEquals(explicitDownbeatCaret.x, measureOnlyCaret.x)
        val previousMeasureEndCaret = assertNotNull(empty.insertionPositionAt(TimeCode.of(1, Fraction.ONE)))
        val nextMeasureStartCaret = assertNotNull(empty.insertionPositionAt(TimeCode.of(2, Fraction.ZERO)))
        assertEquals(nextMeasureStartCaret.x, previousMeasureEndCaret.x)
        val secondMeasure = empty.measureBounds.single { it.measureNumber == 2 }
        val secondMeasureLeft = empty.transformerSnapshot.toAbsolute(
            RelativePoint(secondMeasure.leftX, StaffSpace.ZERO),
        ).x.value
        assertTrue(nextMeasureStartCaret.x > secondMeasureLeft)
        assertTrue(nextMeasureStartCaret.topY < nextMeasureStartCaret.bottomY)
        val position = empty.timeCodePositions.values.first()
        val ghost = assertNotNull(
            renderer.computeNoteGhost(
                frame = controller.state.value.frame,
                result = empty,
                point = AbsolutePoint(
                    Pixels(position.x),
                    Pixels((position.topY + position.bottomY) / 2f),
                ),
                duration = Duration.QUARTER,
                restMode = false,
            ),
        )
        assertTrue(ghost.commands.isNotEmpty())
        assertTrue(ghost.onset.measure == 1)
        assertNotNull(renderer.computeClefGhost(controller.state.value.frame, empty, ghost.anchor, Clef.BASS))
        assertNotNull(
            renderer.computeTimeSignatureGhost(
                controller.state.value.frame,
                empty,
                ghost.anchor,
                TimeSignature(3, 4),
            ),
        )
        assertNotNull(
            renderer.computeKeySignatureGhost(
                controller.state.value.frame,
                empty,
                ghost.anchor,
                KeySignature.G_MAJOR,
            ),
        )
        val staffId = controller.state.value.frame.runtimeScore.staffTracks.keys.single()
        listOf(
            PointSymbolKind.Dynamic(DynamicLevel.MF),
            PointSymbolKind.Tempo(120f),
            PointSymbolKind.Fermata(FermataShape.NORMAL),
            PointSymbolKind.Breath(BreathMarkShape.COMMA),
            PointSymbolKind.Ornament(OrnamentKind.TRILL),
        ).forEach { kind ->
            val pointGhost = assertNotNull(
                renderer.computePointSymbolGhost(
                    controller.state.value.frame,
                    empty,
                    staffId,
                    ghost.onset,
                    kind,
                ),
            )
            assertTrue(pointGhost.commands.isNotEmpty())
        }
        val times = empty.timeCodePositions.keys.sorted()
        if (times.size >= 2) {
            val hairpinGhost = assertNotNull(
                renderer.computeExpressionSpanGhost(
                    controller.state.value.frame,
                    empty,
                    staffId,
                    times[0],
                    times[1],
                    ExpressionSpanKind.Hairpin(HairpinType.CRESCENDO, HairpinStyle.WEDGE),
                ),
            )
            assertTrue(hairpinGhost.startHandle.x < hairpinGhost.endHandle.x)
            assertNotNull(
                renderer.computeDefaultExpressionSpanGhost(
                    controller.state.value.frame,
                    empty,
                    staffId,
                    times[0],
                    ExpressionSpanKind.Hairpin(HairpinType.CRESCENDO, HairpinStyle.WEDGE),
                ),
                "one semantic anchor must immediately produce a visible default span",
            )
            assertNotNull(
                renderer.computeExpressionSpanGhost(
                    controller.state.value.frame,
                    empty,
                    staffId,
                    times[0],
                    times[1],
                    ExpressionSpanKind.GradualTempo(TempoMarkType.RITARDANDO),
                ),
            )
            assertNotNull(
                renderer.computeExpressionSpanGhost(
                    controller.state.value.frame,
                    empty,
                    staffId,
                    times[0],
                    times[1],
                    ExpressionSpanKind.Ornament(OrnamentKind.TRILL),
                ),
            )
        }

        controller.insertMidiNote(60, Duration.QUARTER)
        val entered = renderer.render(controller.state.value.frame)
        assertTrue(entered.elements.any { it.type == RenderElementType.NOTEHEAD })
        assertTrue(entered.spatialIndex.allSystems().isNotEmpty())
        val notehead = entered.elements.first { it.type == RenderElementType.NOTEHEAD }
        val picked = assertNotNull(
            renderer.selectionTargetAt(entered, notehead.center) as? ScoreSelectionTarget.Event,
        )
        assertEquals(voiceId, picked.voiceTrackId)
        assertEquals(setOf(0), picked.pitchIndices)
        val afterInsertedNote = assertNotNull(controller.state.value.interaction.entryCursor)
        assertEquals(TimeCode.of(1, Fraction.QUARTER), afterInsertedNote.position)
        assertNotNull(
            entered.insertionPositionAt(afterInsertedNote.position),
            "the empty rhythmic slot after the final note must have a visible caret",
        )

        controller.moveEntryCursor(ScoreEntryCursorAction.PREVIOUS_NOTE)
        val previousNote = assertNotNull(controller.state.value.interaction.entryCursor)
        val previousNoteCaret = assertNotNull(entered.insertionPositionAt(previousNote.position))
        controller.moveEntryCursor(ScoreEntryCursorAction.NEXT_NOTE)
        val nextNote = assertNotNull(controller.state.value.interaction.entryCursor)
        assertEquals(TimeCode.of(1, Fraction.QUARTER), nextNote.position)
        assertEquals(null, nextNote.anchorEventId)
        val trailingCaret = assertNotNull(entered.insertionPositionAt(nextNote.position))
        assertTrue(
            trailingCaret.x > previousNoteCaret.x,
            "NEXT_NOTE after the last note must move to its following insertion slot",
        )

        controller.moveEntryCursor(ScoreEntryCursorAction.NEXT_MEASURE)
        val nextMeasure = assertNotNull(controller.state.value.interaction.entryCursor)
        assertNotNull(entered.insertionPositionAt(nextMeasure.position))
    }

    @Test
    fun caretRemainsVisibleAtTheDownbeatBeyondTheFinalRenderedMeasure() {
        val fontRoot = File("../desktop/src/main/resources/bravura")
        val font = BravuraFont.fromJson(
            File(fontRoot, "bravuraMetadata.json").readText(),
            File(fontRoot, "glyphnames.json").readText(),
        )
        val controller = MobileScoreEditorController.open(
            StorageScore.create(StorageScore.CreationOptions(title = "", measureCount = 1)),
        )
        val voiceId = controller.state.value.frame.runtimeScore.voiceTracks.keys.single()
        controller.activate(
            ScoreInteractionCatalog.ENTRY_NOTE,
            ScoreEntryCursor(voiceId, TimeCode.of(1, Fraction.ZERO)),
        )
        controller.insertMidiNote(60, Duration.WHOLE)
        val renderer = MobileScoreRenderSession(font)
        val result = renderer.render(controller.state.value.frame)
        val afterFinalMeasure = assertNotNull(controller.state.value.interaction.entryCursor)

        assertEquals(TimeCode.of(2, Fraction.ZERO), afterFinalMeasure.position)
        assertNotNull(
            result.insertionPositionAt(afterFinalMeasure.position),
            "the logical next-measure downbeat must remain visible before the score expands",
        )

        controller.moveEntryCursor(ScoreEntryCursorAction.PREVIOUS_NOTE)
        assertEquals(TimeCode.of(1, Fraction.ZERO), controller.state.value.interaction.entryCursor?.position)
        controller.moveEntryCursor(ScoreEntryCursorAction.NEXT_NOTE)
        assertEquals(TimeCode.of(2, Fraction.ZERO), controller.state.value.interaction.entryCursor?.position)
        assertNotNull(result.insertionPositionAt(controller.state.value.interaction.entryCursor!!.position))
    }
}
