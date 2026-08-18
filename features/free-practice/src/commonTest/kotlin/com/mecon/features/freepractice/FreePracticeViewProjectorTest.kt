package com.mecon.features.freepractice

import com.mecon.theory.KeySignatureMode
import com.mecon.theory.ModulationKey
import com.mecon.api.primitive.Pitch
import com.mecon.api.primitive.Fraction
import com.mecon.theory.freepractice.WorkspaceChordChoice
import com.mecon.theory.freepractice.WorkspaceChordTonality
import com.mecon.theory.freepractice.WorkspaceChordTonalReading
import com.mecon.theory.freepractice.WorkspaceSlotId
import com.mecon.theory.freepractice.HarmonyWorkspaceCommand
import com.mecon.theory.freepractice.HarmonyWorkspaceEditor
import com.mecon.theory.harmony.ChordSelectionCatalog
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FreePracticeViewProjectorTest {
    @Test
    fun simultaneousReadingsFollowTheirTonalLineStartOrderInsteadOfPrimaryOrder() {
        val c = ModulationKey(0, KeySignatureMode.MAJOR)
        val g = ModulationKey(1, KeySignatureMode.MAJOR)
        val initial = FreePracticePreset.workspace(voiceCount = 4, initialKey = c)
        val first = initial.slots.single()
        val selected = first.copy(
            id = WorkspaceSlotId("selected"),
            onset = first.duration,
            tonality = WorkspaceChordTonality(
                primary = WorkspaceChordTonalReading.of(g),
                alternates = listOf(WorkspaceChordTonalReading.of(c)),
            ),
        )
        val workspace = initial.copy(slots = listOf(first, selected))

        val timeline = FreePracticeViewProjector.timeline(workspace)
        val plan = FreePracticeViewProjector.plan(
            workspace = workspace,
            selectedSlotId = selected.id,
            catalog = PracticeCatalogView(requestKey = "test", chordChoices = emptyList()),
        )

        assertEquals(listOf(0, 1), timeline.slots.last().readings.map { it.fifths })
        assertEquals(listOf(0, 1), plan.selectedChordReadings.map { it.fifths })
    }

    @Test
    fun timelineCarriesPresentationReadyChordReadings() {
        val workspace = FreePracticePreset.workspace(
            voiceCount = 4,
            initialKey = ModulationKey(0, KeySignatureMode.MAJOR),
        )

        val slot = FreePracticeViewProjector.timeline(workspace).slots.single()

        assertTrue(slot.relativeTones.isNotEmpty())
        assertTrue(slot.absoluteTones.isNotEmpty())
        assertEquals("C", slot.readings.single().keyLabel)
        assertEquals(slot.symbol, slot.readings.single().functionalSymbol)
    }

    @Test
    fun trailingVisualFillerIsOneContinuousBlockAcrossBeatsAndMeasures() {
        val workspace = FreePracticePreset.workspace(
            voiceCount = 4,
            initialKey = ModulationKey(0, KeySignatureMode.MAJOR),
        )

        val timeline = FreePracticeViewProjector.timeline(
            workspace = workspace,
            scoreEnd = Fraction(3, 2),
            measureBoundaries = listOf(Fraction.HALF, Fraction.ONE, Fraction(3, 2)),
            defaultChordDuration = Fraction.QUARTER,
        )

        assertEquals(
            listOf(Fraction.QUARTER to Fraction(5, 4)),
            timeline.emptySlots.map { it.onset to it.duration },
        )
    }

    @Test
    fun planCarriesPresentationReadyRightPanelState() {
        val workspace = FreePracticePreset.workspace(
            voiceCount = 4,
            initialKey = ModulationKey(0, KeySignatureMode.MAJOR),
        )
        val selected = workspace.slots.single()

        val plan = FreePracticeViewProjector.plan(
            workspace = workspace,
            selectedSlotId = selected.id,
            catalog = PracticeCatalogView(
                requestKey = "test",
                chordChoices = emptyList(),
                chordGroups = listOf(
                    PracticeChordCatalogGroupView("diatonic", "自然音三和弦", "说明", emptyList())
                ),
            ),
        )

        assertEquals(selected.id, plan.selectedSlot?.id)
        assertEquals(selected.onset + selected.duration, plan.navigation.appendOnset)
        assertNull(plan.navigation.previousSlotId)
        assertNull(plan.navigation.nextSlotId)
        assertEquals(workspace.tonalLayouts.single().id, plan.activeTonalLayouts.single().id)
        assertTrue(plan.activeTonalLayouts.single().keyLabel.isNotBlank())
        assertTrue(plan.activeTonalLayouts.single().rangeLabel.isNotBlank())
        assertEquals(30, plan.tonalKeyChoices.size)
        assertTrue(plan.selectedChordReadings.isNotEmpty())
        assertTrue(plan.selectedChordReadings.single().symbolLabel.isNotBlank())
        assertTrue(plan.selectedChordReadings.single().relativeTonesLabel.isNotBlank())
        assertTrue(plan.bassChoices.isNotEmpty())
        assertEquals(plan.strings.anyBass, plan.bassChoices.first().relativeLabel)
        assertEquals("自然音三和弦", plan.chordCatalogGroups.single().titleLabel)
        val detail = requireNotNull(plan.chordDetail)
        assertTrue(detail.title.isNotBlank())
        assertTrue(detail.explanations.isNotEmpty())
        assertTrue(detail.explanations.first().routes.isNotEmpty())
        // Routes carry the interpretation they would pin, so a platform offering "apply this route"
        // dispatches straight from the read model instead of resolving the catalog a second time.
        assertTrue(
            detail.explanations.flatMap { it.routes }.any { it.interpretationRef != null },
            "projected routes must expose a ready-to-dispatch interpretation ref",
        )
        val construction = PracticeChordDetailConstructionView(
            description = "共享谱例",
            events = listOf(
                PracticeChordDetailConstructionEventView(
                    listOf(PracticeChordDetailConstructionToneView(Pitch.C4, muted = false))
                )
            ),
            caption = "共享谱例",
        )
        assertTrue(construction.toPreviewStorageScore().voiceTracks.values.any { it.events.isNotEmpty() })
        assertTrue(plan.tonalityChoices.any { it.id == "manual" && it.selected })
        assertFalse(plan.chordLocked)
        assertFalse(plan.inversionLocked)
    }

    @Test
    fun overlappingTonalLayoutsExposeOneSharedChordCatalogPerKey() {
        val c = ModulationKey(0, KeySignatureMode.MAJOR)
        val fs = ModulationKey(6, KeySignatureMode.MAJOR)
        val initial = FreePracticePreset.workspace(voiceCount = 4, initialKey = c)
        val workspace = HarmonyWorkspaceEditor.apply(
            initial,
            HarmonyWorkspaceCommand.InsertTonalLayout(key = fs, start = initial.slots.single().onset),
        )
        val insertedLayout = workspace.tonalLayouts.last()

        val plan = FreePracticeViewProjector.plan(
            workspace = workspace,
            selectedSlotId = workspace.slots.single().id,
            selectedTonalLayoutId = insertedLayout.id,
            catalog = projectPracticeCatalog(fs),
        )

        assertEquals(
            listOf(c, fs).map { it.fifths to it.mode.name },
            plan.chordCatalogFilters.map { it.key.fifths to it.key.mode.name },
        )
        assertEquals(
            workspace.tonalLayouts.first().id,
            plan.chordCatalogFilters.single { it.selected }.tonalLayoutId,
        )
        val reboundWorkspace = HarmonyWorkspaceEditor.apply(
            workspace,
            HarmonyWorkspaceCommand.SelectChordTonalLayout(0, insertedLayout.id),
        )
        val reboundPlan = FreePracticeViewProjector.plan(
            workspace = reboundWorkspace,
            selectedSlotId = reboundWorkspace.slots.single().id,
            selectedTonalLayoutId = insertedLayout.id,
            catalog = projectPracticeCatalog(fs),
        )
        assertEquals(insertedLayout.id, reboundPlan.chordCatalogFilters.single { it.selected }.tonalLayoutId)
        val independentPlan = FreePracticeViewProjector.plan(
            workspace = reboundWorkspace,
            selectedSlotId = reboundWorkspace.slots.single().id,
            selectedTonalLayoutId = insertedLayout.id,
            selectedIdiomTonalLayoutId = reboundWorkspace.tonalLayouts.first().id,
            catalog = projectPracticeCatalog(fs),
        )
        assertEquals(insertedLayout.id, independentPlan.chordCatalogFilters.single { it.selected }.tonalLayoutId)
        assertEquals(
            reboundWorkspace.tonalLayouts.first().id,
            independentPlan.idiomCatalogFilters.single { it.selected }.tonalLayoutId,
        )
        assertTrue(plan.chordCatalogFilters.all { it.chordGroups.isNotEmpty() })
        assertTrue(
            plan.chordCatalogFilters.map { it.chordGroups.first().choices.first().choice.pitchClasses }
                .distinct().size > 1,
        )
        assertTrue(
            plan.chordCatalogFilters.flatMap { it.chordGroups }
                .flatMap { it.choices }
                .any { it.alternateTonalReadings.isNotEmpty() },
        )

        val boundary = Fraction.QUARTER
        val baselineEnded = HarmonyWorkspaceEditor.apply(
            workspace,
            HarmonyWorkspaceCommand.SetTonalLayoutBounds(
                workspace.tonalLayouts.first().id,
                Fraction.ZERO,
                boundary,
            ),
        )
        val laterStarted = HarmonyWorkspaceEditor.apply(
            baselineEnded,
            HarmonyWorkspaceCommand.SetTonalLayoutBounds(insertedLayout.id, boundary, null),
        ).let { state ->
            state.copy(slots = listOf(state.slots.single().copy(onset = boundary)))
        }
        val boundaryPlan = FreePracticeViewProjector.plan(
            workspace = laterStarted,
            selectedSlotId = laterStarted.slots.single().id,
            selectedTonalLayoutId = workspace.tonalLayouts.first().id,
            selectedIdiomTonalLayoutId = workspace.tonalLayouts.first().id,
            catalog = projectPracticeCatalog(fs),
        )
        assertEquals(listOf(insertedLayout.id), boundaryPlan.idiomCatalogFilters.map { it.tonalLayoutId })
        assertTrue(boundaryPlan.idiomCatalogFilters.single().selected)
    }

    @Test
    fun bassChoiceLabelsStayAttachedToTheirPitchClassesWhenChordOrderWrapsAtC() {
        val workspace = FreePracticePreset.workspace(
            voiceCount = 4,
            initialKey = ModulationKey(-1, KeySignatureMode.MAJOR),
        )
        val selected = workspace.slots.single()

        val plan = FreePracticeViewProjector.plan(
            workspace = workspace,
            selectedSlotId = selected.id,
            catalog = PracticeCatalogView(requestKey = "test", chordChoices = emptyList()),
        )

        val orderedBassChoices = plan.bassChoices.filter { it.pitchClass != null }
        assertEquals(listOf(5, 9, 0), orderedBassChoices.map { it.pitchClass })
        assertEquals(listOf("1", "3", "5"), orderedBassChoices.map { it.relativeLabel })
        assertEquals(listOf("F", "A", "C"), orderedBassChoices.map { it.absoluteLabel })
        val bassChoices = orderedBassChoices.associateBy { it.pitchClass }
        assertEquals("F", bassChoices[5]?.absoluteLabel)
        assertEquals("A", bassChoices[9]?.absoluteLabel)
        assertEquals("C", bassChoices[0]?.absoluteLabel)
        assertEquals("1", bassChoices[5]?.relativeLabel)
        assertEquals("3", bassChoices[9]?.relativeLabel)
        assertEquals("5", bassChoices[0]?.relativeLabel)
    }

    @Test
    fun dominantBassChoicesFollowTheSameStructuralOrderAsDesktop() {
        val key = ModulationKey(0, KeySignatureMode.MAJOR)
        val dominant = ChordSelectionCatalog.choices(key).first { it.functionalSymbol == "V" }
        val initial = FreePracticePreset.workspace(voiceCount = 4, initialKey = key)
        val workspace = initial.copy(
            slots = listOf(
                initial.slots.single().copy(
                    chordChoice = WorkspaceChordChoice.of(dominant.pitchClasses, dominant.origin),
                )
            ),
        )

        val plan = FreePracticeViewProjector.plan(
            workspace = workspace,
            selectedSlotId = workspace.slots.single().id,
            catalog = PracticeCatalogView(requestKey = "test", chordChoices = emptyList()),
        )

        val bassChoices = plan.bassChoices.filter { it.pitchClass != null }
        assertEquals(listOf(7, 11, 2), bassChoices.map { it.pitchClass })
        assertEquals(listOf("5", "7", "2"), bassChoices.map { it.relativeLabel })
        assertEquals(listOf("G", "B", "D"), bassChoices.map { it.absoluteLabel })
    }
}
