package com.mecon.features.freepractice

import com.mecon.api.primitive.Fraction
import com.mecon.theory.freepractice.WorkspaceIdiomInstanceId
import com.mecon.theory.freepractice.WorkspaceKeyMode
import com.mecon.theory.freepractice.WorkspaceSlotId
import com.mecon.theory.freepractice.WorkspaceTonalLayoutId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PracticeTimelineControllerTest {
    @Test
    fun defaultChordDurationAdjustsScaleToKeepTheStandardChordWidth() {
        assertEquals(576f, PracticeTimelineScale.pixelsPerWhole(Fraction.QUARTER))
        assertEquals(288f, PracticeTimelineScale.pixelsPerWhole(Fraction.HALF))
        assertEquals(144f, PracticeTimelineScale.pixelsPerWhole(Fraction.ONE))
        assertEquals(
            PracticeTimelineScale.DEFAULT_CHORD_WIDTH,
            PracticeTimelineScale.pixelsPerWhole(Fraction(4, 1)) * Fraction(4, 1).toFloat(),
        )
    }

    private val first = WorkspaceSlotId("slot-a")
    private val second = WorkspaceSlotId("slot-b")

    private fun request(
        viewport: Float = 900f,
        scrollLeft: Float = 0f,
        gesture: PracticeTimelineGestureState? = null,
    ) = PracticeTimelineSceneRequest(
        revision = 7,
        axisRevision = 11,
        viewportWidth = viewport,
        scrollLeft = scrollLeft,
        contentOriginX = 20f,
        axisAnchors = listOf(
            PracticeTimelineAxisAnchor(Fraction.ZERO, 0f),
            PracticeTimelineAxisAnchor(Fraction.QUARTER, 144f),
            PracticeTimelineAxisAnchor(Fraction.HALF, 288f),
        ),
        axisContentEndX = 288f,
        timeline = PracticeTimelineView(
            end = Fraction.HALF,
            slots = listOf(
                PracticeTimelineSlotView(first, Fraction.ZERO, Fraction.QUARTER, "I"),
                PracticeTimelineSlotView(second, Fraction.QUARTER, Fraction.QUARTER, "V"),
            ),
        ),
        selectedSlotId = first.value,
        gesture = gesture,
    )

    @Test
    fun sceneUsesOneRawOriginAndFillsViewport() {
        val scene = PracticeTimelineSceneProjector.project(request())
        assertEquals(20f, scene.contentAnchors.timeZeroX)
        assertEquals(scene.contentAnchors.timeZeroX, scene.contentAnchors.scoreOriginX)
        assertEquals(900f, scene.contentWidth)
        assertEquals(0f, scene.scrollExtent)
        assertTrue(scene.hitObjects.any { it.kind == PracticeTimelineHitKind.APPEND })
        assertTrue(scene.accessibility.any { it.id == "append" && "activate" in it.actions })
    }

    @Test
    fun appendCellIsAlwaysPlacedAfterTheClosingBarline() {
        val oneBeatChord = request().copy(
            timeline = PracticeTimelineView(
                end = Fraction.HALF,
                slots = listOf(
                    PracticeTimelineSlotView(first, Fraction.ZERO, Fraction.QUARTER, "I"),
                ),
            ),
            selectedSlotId = first.value,
            defaultChordDuration = Fraction.QUARTER,
        )

        val scene = PracticeTimelineSceneProjector.project(oneBeatChord)
        val chord = scene.hitObjects.single { it.id == "slot:${first.value}" }.bounds
        val append = scene.hitObjects.single { it.id == "append" }.bounds

        assertTrue(chord.x + chord.width < scene.contentAnchors.contentEndX)
        assertEquals(scene.contentAnchors.contentEndX, append.x)
        assertEquals(chord.width, append.width)
    }

    @Test
    fun projectedEmptyChordSlotIsPassiveWhileItsSeparateAppendButtonAddsAtTheExactBeat() {
        val emptyBeat = PracticeTimelineEmptySlotView(
            id = "empty:1:4",
            onset = Fraction.QUARTER,
            duration = Fraction.QUARTER,
        )
        val oneBeatChord = request().copy(
            timeline = PracticeTimelineView(
                end = Fraction.HALF,
                slots = listOf(
                    PracticeTimelineSlotView(first, Fraction.ZERO, Fraction.QUARTER, "I"),
                ),
                emptySlots = listOf(emptyBeat),
            ),
            selectedSlotId = first.value,
            defaultChordDuration = Fraction.QUARTER,
        )

        val scene = PracticeTimelineSceneProjector.project(oneBeatChord)
        val chord = scene.hitObjects.single { it.id == "slot:${first.value}" }.bounds
        val emptySlot = scene.drawObjects.single { it.id == "empty-slot:${emptyBeat.id}" }
        val append = scene.hitObjects.single { it.id == "append" }
        val realChordlessFill = PracticeTimelineSceneProjector.project(
            oneBeatChord.copy(
                timeline = oneBeatChord.timeline.copy(
                    slots = oneBeatChord.timeline.slots.map { it.copy(symbol = null) },
                ),
            ),
        ).drawObjects.single { it.id == "slot:${first.value}" }.fill

        assertEquals(chord.x + chord.width + 3f, emptySlot.bounds.x)
        assertEquals(scene.contentAnchors.contentEndX - 3f, emptySlot.bounds.x + emptySlot.bounds.width)
        assertTrue(scene.drawObjects.none { it.id == "empty-slot:${emptyBeat.id}:text" })
        assertTrue(emptySlot.fill != realChordlessFill)
        assertTrue(scene.hitObjects.none { it.id == "empty-slot:${emptyBeat.id}" })
        assertTrue(scene.accessibility.none { it.id == "empty-slot:${emptyBeat.id}" })
        assertEquals(PracticeTimelineHitKind.APPEND, append.kind)
        assertEquals(scene.contentAnchors.contentEndX, append.bounds.x)
        assertEquals(chord.width, append.bounds.width)

        val passiveClick = FreePracticeTimelineController.handle(
            scene,
            oneBeatChord,
            PracticeTimelineInput(
                type = PracticeTimelineInputType.DOWN,
                sceneGeneration = scene.generation,
                pointerId = 12,
                x = emptySlot.bounds.x + emptySlot.bounds.width / 2f,
                y = emptySlot.bounds.y + emptySlot.bounds.height / 2f,
            ),
        )
        assertFalse(passiveClick.accepted)
        assertTrue(passiveClick.ignored)
        assertEquals("no_target", passiveClick.reasonKey)

        val activated = FreePracticeTimelineController.handle(
            scene,
            oneBeatChord,
            PracticeTimelineInput(
                type = PracticeTimelineInputType.ACTIVATE,
                sceneGeneration = scene.generation,
                actionTargetId = append.id,
            ),
        )
        assertEquals(Fraction.QUARTER, activated.appendAt)
        assertEquals(Fraction.QUARTER, activated.appendDuration)
    }

    @Test
    fun appendCellRemainsAfterAddingARealChordlessSlotAtTheBarline() {
        val filledMeasure = request().copy(
            timeline = PracticeTimelineView(
                end = Fraction.HALF,
                slots = listOf(
                    PracticeTimelineSlotView(first, Fraction.ZERO, Fraction.QUARTER, "I"),
                    PracticeTimelineSlotView(second, Fraction.QUARTER, Fraction.QUARTER, null),
                ),
            ),
            selectedSlotId = second.value,
            defaultChordDuration = Fraction.QUARTER,
        )

        val scene = PracticeTimelineSceneProjector.project(filledMeasure)
        val append = scene.hitObjects.single { it.id == "append" }
        val activated = FreePracticeTimelineController.handle(
            scene,
            filledMeasure,
            PracticeTimelineInput(
                type = PracticeTimelineInputType.ACTIVATE,
                sceneGeneration = scene.generation,
                actionTargetId = append.id,
            ),
        )

        assertEquals(scene.contentAnchors.contentEndX, append.bounds.x)
        assertEquals(Fraction.HALF, activated.appendAt)
        assertEquals(Fraction.QUARTER, activated.appendDuration)
    }

    @Test
    fun appendCellStartsAtTheEngravedBarlineInsteadOfThePaddedAxisSlot() {
        val oneBeatChord = request().copy(
            measureBoundaries = listOf(
                PracticeTimelineAxisAnchor(Fraction.HALF, 275f),
            ),
            timeline = PracticeTimelineView(
                end = Fraction.HALF,
                slots = listOf(
                    PracticeTimelineSlotView(first, Fraction.ZERO, Fraction.QUARTER, "I"),
                ),
            ),
            selectedSlotId = first.value,
            defaultChordDuration = Fraction.QUARTER,
        )

        val scene = PracticeTimelineSceneProjector.project(oneBeatChord)
        val trailingSpace = scene.hitObjects.single { it.id == "append" }.bounds
        val closingBarline = scene.drawObjects.single { it.id.startsWith("grid:measure:") && it.bounds.x > 20f }

        assertEquals(295f, trailingSpace.x)
        assertEquals(295f, closingBarline.bounds.x)
        assertEquals(295f, scene.contentAnchors.contentEndX)
    }

    @Test
    fun appendCellUsesTheDefaultDurationEvenWhenTheBlankRemainderIsShorter() {
        val threeFourTail = request().copy(
            axisAnchors = listOf(
                PracticeTimelineAxisAnchor(Fraction.ZERO, 0f),
                PracticeTimelineAxisAnchor(Fraction.HALF, 288f),
                PracticeTimelineAxisAnchor(Fraction(3, 4), 432f),
            ),
            axisContentEndX = 432f,
            timeline = PracticeTimelineView(
                end = Fraction(3, 4),
                slots = listOf(
                    PracticeTimelineSlotView(first, Fraction.ZERO, Fraction.HALF, "I"),
                ),
            ),
            selectedSlotId = first.value,
            defaultChordDuration = Fraction.HALF,
        )

        val scene = PracticeTimelineSceneProjector.project(threeFourTail)
        val trailingSpace = scene.hitObjects.single { it.id == "append" }.bounds
        val activated = FreePracticeTimelineController.handle(
            scene,
            threeFourTail,
            PracticeTimelineInput(
                type = PracticeTimelineInputType.ACTIVATE,
                sceneGeneration = scene.generation,
                actionTargetId = "append",
            ),
        )

        assertEquals(288f, trailingSpace.width)
        assertEquals(scene.contentAnchors.contentEndX, trailingSpace.x)
        assertEquals(Fraction.HALF, activated.appendDuration)
    }

    @Test
    fun appendAffordanceStaysAfterTheFinalChordWhenContentOverflows() {
        val unscrolled = PracticeTimelineSceneProjector.project(request(viewport = 220f))
        val scrolled = PracticeTimelineSceneProjector.project(request(viewport = 220f, scrollLeft = 70f))
        assertEquals(308f, unscrolled.contentAnchors.appendX)
        assertEquals(unscrolled.contentAnchors.appendX, scrolled.contentAnchors.appendX)
        val finalChord = scrolled.hitObjects.first { it.id == "slot:${second.value}" }.bounds
        val append = scrolled.hitObjects.first { it.id == "append" }.bounds
        assertEquals(finalChord.x + finalChord.width, append.x)
        assertTrue(append.x + append.width <= scrolled.contentWidth)
        assertTrue(scrolled.scrollExtent > 0f)
    }

    @Test
    fun minimumAppendWidthIsIncludedInTheScrollableContent() {
        val narrowTail = request().copy(
            pixelsPerWhole = 40f,
            axisAnchors = emptyList(),
            axisContentEndX = 0f,
            axisSurfaceWidth = 0f,
            viewportWidth = 100f,
        )
        val scene = PracticeTimelineSceneProjector.project(narrowTail)
        val append = scene.hitObjects.first { it.id == "append" }.bounds

        assertEquals(56f, append.width)
        assertTrue(append.x + append.width <= scene.contentWidth)
    }

    @Test
    fun sceneRetainsTheDesktopTimelineVisualContract() {
        val base = request()
        val rich = base.copy(
            timeline = base.timeline.copy(
                slots = listOf(
                    base.timeline.slots.first().copy(
                        readings = listOf(
                            PracticeTimelineChordReadingView(
                                0, WorkspaceKeyMode.MAJOR, "C", "I",
                                absoluteTones = listOf("C", "E", "G"),
                                relativeTones = listOf("1", "3", "5"),
                            ),
                            PracticeTimelineChordReadingView(
                                1, WorkspaceKeyMode.MAJOR, "G", "IV",
                                absoluteTones = listOf("C", "E", "G"),
                                relativeTones = listOf("4", "6", "1"),
                            ),
                        ),
                    ),
                    base.timeline.slots.last(),
                ),
                tonalLayouts = listOf(
                    PracticeTonalLayoutView(
                        WorkspaceTonalLayoutId("key-c"),
                        0,
                        WorkspaceKeyMode.MAJOR,
                        Fraction.ZERO,
                        isBaseline = true,
                    )
                ),
                derivedTonalSpans = listOf(
                    PracticeDerivedTonalSpanView(
                        4,
                        WorkspaceKeyMode.MINOR,
                        "C♯m",
                        Fraction.QUARTER,
                        Fraction.HALF,
                    )
                ),
                idioms = listOf(
                    PracticeIdiomView(
                        id = WorkspaceIdiomInstanceId("cadence"),
                        definitionId = "cadence",
                        variantId = "authentic",
                        slotIds = listOf(first, second),
                        title = "终止式",
                        start = Fraction.ZERO,
                        end = Fraction(1, 32),
                    )
                ),
            ),
        )
        val scene = PracticeTimelineSceneProjector.project(rich)

        assertTrue(scene.drawObjects.any { it.id == "grid:dot:0" && it.kind == PracticeTimelineDrawKind.CIRCLE })
        assertTrue(scene.drawObjects.any { it.id == "measure:1" && it.text == "1" })
        assertTrue(scene.drawObjects.any { it.id == "tonal:key-c:label:text" && it.text?.startsWith("C · 1") == true })
        assertTrue(scene.drawObjects.any { it.id == "derived-tonal:0:line" && it.dashPattern.isNotEmpty() })
        assertTrue(scene.drawObjects.any { it.id == "slot:slot-a:reading:0" && it.text == "C: I · 1–3–5" })
        assertTrue(scene.drawObjects.any { it.id == "slot:slot-a:start:paint" })
        val idiomBracket = scene.drawObjects.first { it.id == "idiom:cadence" }
        val idiomMask = scene.drawObjects.first { it.id == "idiom:cadence:text:mask" }
        val idiomText = scene.drawObjects.first { it.id == "idiom:cadence:text" }
        val idiomHit = scene.hitObjects.first { it.id == "idiom:cadence" }
        assertEquals(16f, idiomBracket.bounds.height)
        assertEquals(22f, idiomHit.bounds.height)
        assertEquals(PracticeTimelineDrawKind.ROUND_RECT, idiomMask.kind)
        assertEquals(base.palette.surfaceDark, idiomMask.fill)
        assertEquals(11f, idiomText.fontSize)
        assertTrue(idiomMask.bounds.width > idiomBracket.bounds.width)
        assertTrue(idiomMask.z < idiomText.z)
        val longerTitleScene = PracticeTimelineSceneProjector.project(
            rich.copy(
                timeline = rich.timeline.copy(
                    idioms = rich.timeline.idioms.map { it.copy(title = "完整正格终止式") },
                ),
            ),
        )
        val longerMask = longerTitleScene.drawObjects.first { it.id == "idiom:cadence:text:mask" }
        assertTrue(longerMask.bounds.width > idiomMask.bounds.width)
        assertTrue(scene.contentHeight >= 148f)
    }

    @Test
    fun tonalLinesReuseRowsWhenTheirRangesDoNotOverlap() {
        val base = request()
        val timeline = base.timeline.copy(
            tonalLayouts = listOf(
                PracticeTonalLayoutView(
                    WorkspaceTonalLayoutId("baseline"),
                    0,
                    WorkspaceKeyMode.MAJOR,
                    Fraction.ZERO,
                    isBaseline = true,
                ),
                PracticeTonalLayoutView(
                    WorkspaceTonalLayoutId("first"),
                    1,
                    WorkspaceKeyMode.MAJOR,
                    Fraction.ZERO,
                    Fraction.QUARTER,
                ),
                PracticeTonalLayoutView(
                    WorkspaceTonalLayoutId("second"),
                    2,
                    WorkspaceKeyMode.MAJOR,
                    Fraction.QUARTER,
                    Fraction.HALF,
                ),
            ),
        )
        val scene = PracticeTimelineSceneProjector.project(base.copy(timeline = timeline))

        fun y(id: String) = scene.drawObjects.first { it.id == "tonal:$id" }.bounds.y
        assertEquals(20f, y("baseline"))
        assertEquals(42f, y("first"))
        assertEquals(y("first"), y("second"))
    }

    @Test
    fun compactModeMovesIdiomTitlesIntoTheirFirstChordAndRemovesAuxiliaryLines() {
        val base = request()
        val timeline = base.timeline.copy(
            tonalLayouts = listOf(
                PracticeTonalLayoutView(
                    WorkspaceTonalLayoutId("baseline"),
                    0,
                    WorkspaceKeyMode.MAJOR,
                    Fraction.ZERO,
                    isBaseline = true,
                ),
            ),
            idioms = listOf(
                PracticeIdiomView(
                    id = WorkspaceIdiomInstanceId("cadence"),
                    definitionId = "cadence",
                    variantId = "authentic",
                    slotIds = listOf(first, second),
                    title = "正格终止",
                ),
            ),
        )
        val full = PracticeTimelineSceneProjector.project(base.copy(timeline = timeline))
        val compact = PracticeTimelineSceneProjector.project(
            base.copy(timeline = timeline, displayMode = PracticeTimelineDisplayMode.COMPACT),
        )

        assertTrue(compact.contentHeight < full.contentHeight)
        assertFalse(compact.drawObjects.any { it.id.startsWith("tonal:") })
        assertFalse(compact.drawObjects.any { it.id == "idiom:cadence" })
        assertFalse(compact.hitObjects.any {
            it.kind == PracticeTimelineHitKind.TONAL_LAYOUT || it.kind == PracticeTimelineHitKind.IDIOM
        })
        assertTrue(compact.drawObjects.any {
            it.id == "slot:slot-a:idiom-label:0" && it.text == "正格终止"
        })
        assertFalse(compact.drawObjects.any { it.id.startsWith("slot:slot-b:idiom-label:") })
        assertEquals(20f, compact.hitObjects.first { it.id == "slot:slot-a" }.bounds.y)
        assertTrue(
            compact.hitObjects.first { it.id == "slot:slot-a" }.bounds.height >
                base.copy(timeline = timeline).let(PracticeTimelineSceneProjector::project)
                    .hitObjects.first { it.id == "slot:slot-a" }.bounds.height,
        )
    }

    @Test
    fun dragProducesOneSharedPreviewAndCommitEdit() {
        val initial = request()
        val scene = PracticeTimelineSceneProjector.project(initial)
        val slot = scene.hitObjects.first { it.id == "slot:${first.value}" }
        val down = FreePracticeTimelineController.handle(
            scene,
            initial,
            PracticeTimelineInput(
                PracticeTimelineInputType.DOWN,
                scene.generation,
                pointerId = 3,
                x = slot.bounds.x + 30f,
                y = slot.bounds.y + 20f,
                ctrl = true,
            ),
        )
        val gesture = assertNotNull(down.gesture)
        assertEquals(first.value, down.selectSlotId)
        assertTrue(down.effects.any { it.type == "capturePointer" })

        val draggingRequest = initial.copy(gesture = gesture)
        val moved = FreePracticeTimelineController.handle(
            scene,
            draggingRequest,
            PracticeTimelineInput(
                PracticeTimelineInputType.MOVE,
                scene.generation,
                pointerId = 3,
                x = gesture.startX + 72f,
                y = slot.bounds.y + 20f,
            ),
        )
        val edit = moved.previewEdit as PracticeTimelineEdit.TranslateChordRange
        assertEquals(Fraction.EIGHTH, edit.delta)
        assertTrue(edit.includeFollowing)

        // A Worker preview, scroll or resize can reproject the scene before the browser's queued UP
        // arrives. The active gesture is already bound to stable IDs and must still commit once.
        val reprojectedRequest = initial.copy(
            axisRevision = initial.axisRevision + 1,
            gesture = assertNotNull(moved.gesture),
        )
        val reprojectedScene = PracticeTimelineSceneProjector.project(reprojectedRequest)
        assertTrue(reprojectedScene.generation != scene.generation)
        val up = FreePracticeTimelineController.handle(
            reprojectedScene,
            reprojectedRequest,
            PracticeTimelineInput(PracticeTimelineInputType.UP, scene.generation, pointerId = 3),
        )
        assertEquals(edit, up.commitEdit)
        assertTrue(up.effects.any { it.type == "releasePointer" })
    }

    /**
     * The resolved axis ends with a barline anchor collapsed onto the following measure boundary:
     * a whole beat of musical time inside a few pixels. Extrapolating from that last anchor pair
     * moved the dragged chord several measures per pointer pixel and squeezed everything a Ctrl
     * drag pushed past the score.
     */
    private val beyondAxis = WorkspaceSlotId("slot-c")

    /** Adds a chord that a drag has already pushed past the last anchor of the settled axis. */
    private fun collapsedTailRequest() = request().let { base ->
        base.copy(
            axisAnchors = base.axisAnchors + PracticeTimelineAxisAnchor(Fraction(3, 4), 300f),
            axisContentEndX = 300f,
            timeline = base.timeline.copy(
                end = Fraction.ONE,
                slots = base.timeline.slots + PracticeTimelineSlotView(
                    beyondAxis,
                    Fraction(3, 4),
                    Fraction.QUARTER,
                    "I",
                ),
            ),
        )
    }

    /**
     * A chord that sits *inside* a collapsed tail segment — which is where a freshly appended chord
     * lands once the score is shorter than the timeline, e.g. right after an undo — used to move a
     * couple of pixels for a whole grid step, so its drag preview could not be seen at all.
     */
    @Test
    fun chordsInsideACollapsedTailSegmentStillMoveVisibly() {
        val slot = WorkspaceSlotId("slot-tail")
        fun requestAt(onset: Fraction) = request().copy(
            axisAnchors = listOf(
                PracticeTimelineAxisAnchor(Fraction.ZERO, 0f),
                PracticeTimelineAxisAnchor(Fraction.QUARTER, 144f),
                PracticeTimelineAxisAnchor(Fraction.HALF, 288f),
                // The trailing barline anchor collapsed onto the next measure boundary.
                PracticeTimelineAxisAnchor(Fraction(3, 4), 292f),
            ),
            axisContentEndX = 292f,
            timeline = PracticeTimelineView(
                end = Fraction(3, 4),
                slots = listOf(
                    PracticeTimelineSlotView(first, Fraction.ZERO, Fraction.QUARTER, "I"),
                    PracticeTimelineSlotView(slot, onset, Fraction.QUARTER, "V"),
                ),
            ),
            selectedSlotId = slot.value,
        )

        fun x(onset: Fraction) = PracticeTimelineSceneProjector.project(requestAt(onset))
            .hitObjects.first { it.id == "slot:${slot.value}" }.bounds.x

        val settled = x(Fraction.HALF)
        val previewed = x(Fraction(5, 8))
        // One eighth at the requested 576 px per whole note.
        assertEquals(72f, previewed - settled, 1f)
        // Anchors before the collapsed segment still align the timeline with the notation surface.
        assertEquals(144f + 20f, x(Fraction.QUARTER), 0.5f)
    }

    @Test
    fun dragPastTheAxisEndFollowsThePointerAtTheRequestedSpacing() {
        val initial = collapsedTailRequest()
        val scene = PracticeTimelineSceneProjector.project(initial)
        val slot = scene.hitObjects.first { it.id == "slot:${beyondAxis.value}" }
        val down = FreePracticeTimelineController.handle(
            scene,
            initial,
            PracticeTimelineInput(
                PracticeTimelineInputType.DOWN,
                scene.generation,
                pointerId = 3,
                x = slot.bounds.x + 30f,
                y = slot.bounds.y + 20f,
            ),
        )
        val gesture = assertNotNull(down.gesture)
        val moved = FreePracticeTimelineController.handle(
            scene,
            initial.copy(gesture = gesture),
            PracticeTimelineInput(
                PracticeTimelineInputType.MOVE,
                scene.generation,
                pointerId = 3,
                x = gesture.startX + 400f,
                y = slot.bounds.y + 20f,
            ),
        )
        val edit = moved.previewEdit as PracticeTimelineEdit.TranslateChordRange
        // 400 px at the requested 576 px per whole note, to the nearest 1/16 grid step.
        assertEquals(400.0 / 576.0, edit.delta.toDouble(), Fraction.SIXTEENTH.toDouble())
    }

    @Test
    fun chordsPastTheAxisEndKeepTheSpacingOfChordsInsideIt() {
        // A resolved notation surface, as the browser shell supplies during a drag preview.
        val previewing = collapsedTailRequest().copy(axisSurfaceWidth = 320f)
        val scene = PracticeTimelineSceneProjector.project(previewing)
        fun bounds(id: String) = scene.hitObjects.first { it.id == "slot:$id" }.bounds
        assertEquals(bounds(first.value).width, bounds(beyondAxis.value).width, 0.5f)
        // The surface width is a floor, not a cap: the dragged chord must stay reachable.
        assertTrue(scene.contentWidth >= bounds(beyondAxis.value).let { it.x + it.width })

        // A wider settled notation surface remains the shared scroll-width floor; the append
        // affordance keeps its musical position after the final chord within that surface.
        val settled = request().copy(axisSurfaceWidth = 1200f, viewportWidth = 600f)
        assertEquals(1200f, PracticeTimelineSceneProjector.project(settled).contentWidth)
    }

    @Test
    fun appendPressAndReleaseInsertsWithoutReportingAnError() {
        val request = request()
        val scene = PracticeTimelineSceneProjector.project(request)
        val append = assertNotNull(scene.hitObjects.firstOrNull { it.kind == PracticeTimelineHitKind.APPEND })
        val down = FreePracticeTimelineController.handle(
            scene,
            request,
            PracticeTimelineInput(
                PracticeTimelineInputType.DOWN,
                scene.generation,
                pointerId = 5,
                x = append.bounds.x + append.bounds.width / 2f,
                y = append.bounds.y + append.bounds.height / 2f,
            ),
        )
        assertTrue(down.accepted)
        assertEquals(request.timeline.end, down.appendAt)
        assertEquals(null, down.gesture)

        // The pointer release that follows the append click has no gesture; it is dropped silently.
        val up = FreePracticeTimelineController.handle(
            scene,
            request,
            PracticeTimelineInput(PracticeTimelineInputType.UP, scene.generation, pointerId = 5),
        )
        assertFalse(up.accepted)
        assertEquals("no_gesture", up.reasonKey)
        assertTrue(up.ignored)
    }

    @Test
    fun hoverTargetsCarryCursorAndHighlightForEveryInteractiveElement() {
        val scene = PracticeTimelineSceneProjector.project(request())
        val kinds = scene.hoverTargets.mapTo(mutableSetOf()) { it.kind }
        assertTrue(PracticeTimelineHitKind.SLOT in kinds)
        assertTrue(PracticeTimelineHitKind.SLOT_END in kinds)
        assertTrue(PracticeTimelineHitKind.SHARED_BOUNDARY in kinds)
        assertTrue(PracticeTimelineHitKind.APPEND in kinds)
        // Every hoverable element claims a cursor and paints something; nothing is left to the shell.
        scene.hoverTargets.forEach { target ->
            assertTrue(target.cursor.isNotEmpty(), "${target.hitId} has no cursor")
            assertTrue(target.overlay.isNotEmpty(), "${target.hitId} has no highlight")
        }

        val slot = assertNotNull(scene.hoverTargets.firstOrNull { it.hitId == "slot:${first.value}" })
        assertEquals("grab", slot.cursor)
        val endHandle = assertNotNull(scene.hoverTargets.firstOrNull { it.hitId == "slot:${first.value}:end" })
        assertEquals("ew-resize", endHandle.cursor)
    }

    @Test
    fun hoverResolutionFollowsTheSameHitPriorityAsPressing() {
        val request = request()
        val scene = PracticeTimelineSceneProjector.project(request)
        val boundary = assertNotNull(
            scene.hitObjects.firstOrNull { it.kind == PracticeTimelineHitKind.SHARED_BOUNDARY },
        )
        val x = boundary.bounds.x + boundary.bounds.width / 2f
        val y = boundary.bounds.y + boundary.bounds.height / 2f

        // The boundary overlaps both slots and their resize handles; hover must resolve exactly the
        // element a press would start a gesture on, otherwise the highlight lies about the outcome.
        val hovered = assertNotNull(FreePracticeTimelineController.hoverTarget(scene, x, y))
        assertEquals(boundary.id, hovered.hitId)
        val down = FreePracticeTimelineController.handle(
            scene,
            request,
            PracticeTimelineInput(PracticeTimelineInputType.DOWN, scene.generation, pointerId = 9, x = x, y = y),
        )
        assertEquals(PracticeTimelineGestureMode.SHARED_BOUNDARY, assertNotNull(down.gesture).mode)
        assertEquals(null, FreePracticeTimelineController.hoverTarget(scene, -50f, y))
    }

    @Test
    fun hoverHighlightPaintsAboveEveryBaseObject() {
        val scene = PracticeTimelineSceneProjector.project(request())
        val topBaseZ = scene.drawObjects.maxOf { it.z }
        scene.hoverTargets.flatMap { it.overlay }.forEach { overlay ->
            assertTrue(overlay.z > topBaseZ, "${overlay.id} would paint under the scene")
        }
    }

    @Test
    fun staleSceneIsExplicitlyRejectedWithoutCommit() {
        val request = request()
        val scene = PracticeTimelineSceneProjector.project(request)
        val result = FreePracticeTimelineController.handle(
            scene,
            request,
            PracticeTimelineInput(PracticeTimelineInputType.DOWN, scene.generation - 1, pointerId = 1),
        )
        assertFalse(result.accepted)
        assertEquals("stale_scene", result.reasonKey)
        assertEquals(null, result.commitEdit)
    }

    @Test
    fun stalePointerLifecycleWithoutAnActiveGestureIsIgnored() {
        val request = request()
        val scene = PracticeTimelineSceneProjector.project(request)
        val result = FreePracticeTimelineController.handle(
            scene,
            request,
            PracticeTimelineInput(PracticeTimelineInputType.UP, scene.generation - 1, pointerId = 1),
        )
        assertFalse(result.accepted)
        assertEquals("stale_scene", result.reasonKey)
        assertTrue(result.ignored)
    }
}
