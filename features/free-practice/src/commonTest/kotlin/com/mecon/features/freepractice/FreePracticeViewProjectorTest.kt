package com.mecon.features.freepractice

import com.mecon.theory.KeySignatureMode
import com.mecon.theory.ModulationKey
import com.mecon.api.primitive.Pitch
import com.mecon.api.primitive.Fraction
import com.mecon.theory.freepractice.WorkspaceChordChoice
import com.mecon.theory.freepractice.WorkspaceChordTonality
import com.mecon.theory.freepractice.WorkspaceChordTonalReading
import com.mecon.theory.freepractice.WorkspaceSlotId
import com.mecon.theory.freepractice.WorkspaceIdiomInstance
import com.mecon.theory.freepractice.WorkspaceIdiomInstanceId
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
    fun chordCatalogPublishesReadyToRenderToneCountFilters() {
        val key = ModulationKey(0, KeySignatureMode.MAJOR)
        val workspace = FreePracticePreset.workspace(voiceCount = 4, initialKey = key)

        val plan = FreePracticeViewProjector.plan(
            workspace = workspace,
            selectedSlotId = workspace.slots.single().id,
            catalog = projectPracticeCatalog(key),
        )

        val filters = plan.chordCatalogFilters.single().toneCountFilters
        assertEquals(listOf("any", "tones-3", "tones-4"), filters.take(3).map { it.id })
        assertEquals(listOf("任意", "3音", "4音"), filters.take(3).map { it.label })
        filters.filter { it.toneCount != null }.forEach { filter ->
            assertTrue(filter.chordGroups.flatMap { it.choices }.all {
                it.choice.pitchClasses.size == filter.toneCount
            })
        }
    }

    @Test
    fun idiomCatalogCollapsesSizeVariantsWithoutMergingReinterpretationContexts() {
        val key = ModulationKey(0, KeySignatureMode.MAJOR)
        val catalog = projectPracticeCatalog(key)
        val triad = catalog.chordChoices.first { it.choice.pitchClasses.size == 3 }.choice
        val seventh = catalog.chordChoices.first { it.choice.pitchClasses.size == 4 }.choice
        val structureId = "cadence-basic"
        fun variant(
            id: String,
            context: String,
            title: String,
            choice: WorkspaceChordChoice,
            toneCount: Int,
            availableByDefault: Boolean,
            relatedToFocus: Boolean,
        ) =
            PracticeIdiomVariantView(
                id = id,
                structureId = structureId,
                interpretationContextId = context,
                title = title,
                durations = listOf(Fraction.QUARTER),
                chordIdentities = listOf(title),
                chordChoices = listOf(choice),
                chordToneCounts = listOf(toneCount),
                availableByDefault = availableByDefault,
                relatedToFocus = relatedToFocus,
            )
        val definition = PracticeIdiomDefinitionView(
            id = "cadence",
            title = "终止式",
            sourceExerciseId = "exercise",
            sourceChapterId = "chapter",
            availableByDefault = true,
            variants = listOf(
                variant("local-triad", "", "V", triad, 3, true, false),
                variant("local-seventh", "", "V7", seventh, 4, true, false),
                variant("viewed-triad", "viewed-as:6:MAJOR", "V", triad, 3, false, true),
                variant("viewed-seventh", "viewed-as:6:MAJOR", "V7", seventh, 4, false, true),
            ),
        )
        val initial = FreePracticePreset.workspace(voiceCount = 4, initialKey = key)
        val instanceId = WorkspaceIdiomInstanceId("idiom-test")
        val workspace = initial.copy(
            idiomInstances = listOf(
                WorkspaceIdiomInstance(
                    id = instanceId,
                    definitionId = definition.id,
                    variantId = "viewed-triad",
                    sourceExerciseId = definition.sourceExerciseId,
                    sourceChapterId = definition.sourceChapterId,
                    tonalLayoutId = initial.tonalLayouts.single().id,
                    slotIds = listOf(initial.slots.single().id),
                )
            ),
        )

        val plan = FreePracticeViewProjector.plan(
            workspace = workspace,
            selectedSlotId = workspace.slots.single().id,
            catalog = catalog,
            idiomCatalog = PracticeIdiomCatalogView(definitions = listOf(definition)),
            selectedIdiomInstanceId = instanceId,
        )

        val projected = plan.idiomCatalog.definitions.single()
        assertEquals(4, projected.variants.size)
        assertEquals(2, projected.choices.size)
        assertEquals("local-triad", projected.choices.single { it.availableByDefault }.defaultVariantId)
        assertEquals("viewed-triad", projected.choices.single { it.relatedToFocus }.relatedVariantId)
        val form = requireNotNull(plan.selectedIdiomForm)
        assertEquals(listOf(3, 4), form.steps.single().options.map { it.toneCount })
        assertEquals(listOf("三和弦", "七和弦"), form.steps.single().options.map { it.label })
    }

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
        assertTrue(plan.voiceLeading.available)
        assertEquals("tertian.triad", plan.voiceLeading.familyId)
        assertEquals(listOf(1, 2), plan.voiceLeading.groups.map { it.transformationCount })
        val parallelRisk = plan.voiceLeading.groups.flatMap { it.candidates }
            .first { it.choice.pitchClasses == listOf(1, 4, 8) }
        assertTrue(parallelRisk.paths.any {
            PracticeVoiceLeadingParallelRisk.PARALLEL_FIFTH in it.parallelRisks
        })
        assertTrue(parallelRisk.rootConnection.hintLabel.isNotBlank())
        val aMinor = plan.voiceLeading.groups.flatMap { it.candidates }
            .single { it.choice.pitchClasses == listOf(0, 4, 9) }
        assertEquals("6m", aMinor.relativeLabel)
        assertEquals(listOf("1", "3", "5"), aMinor.sourceTones.map { it.relativeLabel })
        assertEquals(listOf("1", "3", "6"), aMinor.targetTones.map { it.relativeLabel })
        assertEquals(listOf("5"), aMinor.sourceTones.filter { it.changed }.map { it.relativeLabel })
        assertEquals(listOf("6"), aMinor.targetTones.filter { it.changed }.map { it.relativeLabel })
        assertFalse(plan.chordLocked)
        assertFalse(plan.inversionLocked)
    }

    @Test
    fun seventhChordProjectionProvidesThreeStepsAndASharedSameDirectionFilterFlag() {
        val key = ModulationKey(0, KeySignatureMode.MAJOR)
        val dominantSeventh = ChordSelectionCatalog.choices(key)
            .first { it.functionalSymbol == "V7" }
        val initial = FreePracticePreset.workspace(voiceCount = 4, initialKey = key)
        val workspace = initial.copy(
            slots = listOf(
                initial.slots.single().copy(
                    chordChoice = WorkspaceChordChoice.of(
                        dominantSeventh.pitchClasses,
                        dominantSeventh.origin,
                    ),
                )
            ),
        )

        val plan = FreePracticeViewProjector.plan(
            workspace = workspace,
            selectedSlotId = workspace.slots.single().id,
            catalog = PracticeCatalogView(requestKey = "test", chordChoices = emptyList()),
        )

        assertEquals("tertian.seventh", plan.voiceLeading.familyId)
        assertTrue(plan.voiceLeading.groups.any { it.transformationCount == 3 })
        val threeStep = plan.voiceLeading.groups.single { it.transformationCount == 3 }
        assertTrue(threeStep.candidates.any { candidate ->
            candidate.paths.any { it.threeTonesSameDirection }
        })
        assertTrue(threeStep.candidates.all { candidate ->
            candidate.paths.all { path ->
                path.moves.map { it.sourceToneIndex }.distinct().size == path.moves.size
            }
        })
    }

    @Test
    fun voiceLeadingPresentationUsesSourceRootOrderAndKeepsTargetColumnsAligned() {
        val key = ModulationKey(0, KeySignatureMode.MAJOR)
        val initial = FreePracticePreset.workspace(voiceCount = 4, initialKey = key)
        val workspace = initial.copy(
            slots = listOf(initial.slots.single().copy(
                chordChoice = WorkspaceChordChoice.of(
                    pitchClasses = listOf(3, 5, 9, 11),
                    preferredRootPitchClass = 11,
                ),
            )),
        )

        val plan = FreePracticeViewProjector.plan(
            workspace = workspace,
            selectedSlotId = workspace.slots.single().id,
            catalog = PracticeCatalogView(requestKey = "test", chordChoices = emptyList()),
        )
        val target = plan.voiceLeading.groups.flatMap { it.candidates }
            .single { it.choice.pitchClasses == listOf(0, 3, 6, 9) }

        assertEquals(listOf("7", "♯2", "4", "6"), target.sourceTones.map { it.relativeLabel })
        assertEquals(listOf("1", "♯2", "♯4", "6"), target.targetTones.map { it.relativeLabel })
    }

    @Test
    fun nonFunctionalVoiceLeadingChordRemainsVisibleOnTheTimeline() {
        val key = ModulationKey(0, KeySignatureMode.MAJOR)
        val initial = FreePracticePreset.workspace(voiceCount = 4, initialKey = key)
        val workspace = initial.copy(slots = listOf(initial.slots.single().copy(
            chordChoice = WorkspaceChordChoice.of(
                pitchClasses = listOf(1, 4, 8),
                preferredRootPitchClass = 1,
            ),
        )))

        val slot = FreePracticeViewProjector.timeline(workspace).slots.single()

        assertTrue(slot.absoluteSymbol?.isNotBlank() == true)
        assertTrue(slot.relativeSymbol?.isNotBlank() == true)
        assertEquals(listOf("C#", "E", "G#"), slot.absoluteTones)
        assertEquals(listOf("♯1", "3", "♯5"), slot.relativeTones)
        assertEquals(listOf(1, 4, 8), slot.pitchClasses)
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

    private fun planForTonicWithFollower(
        follower: WorkspaceChordChoice? = null,
    ): PracticePlanView {
        val key = ModulationKey(0, KeySignatureMode.MAJOR)
        val initial = FreePracticePreset.workspace(voiceCount = 4, initialKey = key)
        val tonic = initial.slots.single().copy(
            chordChoice = WorkspaceChordChoice.of(listOf(0, 4, 7), preferredRootPitchClass = 0),
        )
        val slots = listOfNotNull(
            tonic,
            follower?.let {
                tonic.copy(
                    id = WorkspaceSlotId("follower"),
                    onset = tonic.onset + tonic.duration,
                    chordChoice = it,
                )
            },
        )
        return FreePracticeViewProjector.plan(
            workspace = initial.copy(slots = slots),
            selectedSlotId = tonic.id,
            catalog = PracticeCatalogView(requestKey = "test", chordChoices = emptyList()),
        )
    }

    @Test
    fun pathwaysOfferTheSuspendedOrderingOfTheTonicToDominantConnection() {
        val section = planForTonicWithFollower().voiceLeading.pathways

        assertTrue(section.available)
        assertEquals(
            listOf(PracticeVoiceLeadingPlacement.PASSING_CHORD, PracticeVoiceLeadingPlacement.NON_CHORD_TONE),
            section.placementOptions.map { it.placement },
        )
        assertEquals(listOf(true, false), section.placementOptions.map { it.enabled })

        val suspensions = section.groups.single { it.id == "suspension" }
        val cadential = suspensions.pathways.single { it.id == "s1:4>2|s0:0>11" }
        assertEquals(listOf("1", "5sus4", "5"), cadential.nodes.map { it.relativeLabel })
        assertEquals(listOf("C", "Gsus4", "G"), cadential.nodes.map { it.absoluteLabel })
        assertEquals(
            listOf(
                PracticeVoiceLeadingNodeStability.STABLE,
                PracticeVoiceLeadingNodeStability.TRANSITIONAL,
                PracticeVoiceLeadingNodeStability.STABLE,
            ),
            cadential.nodes.map { it.stability },
        )
        // The suspended fourth is the held tonic; it resolves down onto the leading tone.
        assertEquals("C 延留音", cadential.nodes[1].figurationLabel)
        assertTrue(cadential.arc > 0.0)
        assertTrue(cadential.resolutionDrop > 0.0)
        assertEquals(listOf(listOf(0, 2, 7), listOf(2, 7, 11)), cadential.insertedChoices.map { it.pitchClasses })
        assertEquals(listOf(7, 7), cadential.insertedChoices.map { it.preferredRootPitchClass })
    }

    @Test
    fun aStablePassingChordCarriesNoNonChordToneLabels() {
        val section = planForTonicWithFollower().voiceLeading.pathways
        val passing = section.groups.single { it.id == "passing" }
        assertTrue(passing.pathways.isNotEmpty())
        passing.pathways.forEach { pathway ->
            assertTrue(
                pathway.nodes.none {
                    it.stability == PracticeVoiceLeadingNodeStability.TRANSITIONAL
                },
                "${pathway.id} is not a purely stable pathway",
            )
        }
        val viaMediant = passing.pathways.singleOrNull { it.id == "s0:0>11|s1:4>2" }
        if (viaMediant != null) {
            assertEquals(listOf("1", "3m", "5"), viaMediant.nodes.map { it.relativeLabel })
            assertTrue(viaMediant.nodes.all { it.figurationLabel.isEmpty() })
            assertTrue(viaMediant.arc <= 0.0)
        }
    }

    @Test
    fun anExistingFollowingChordNarrowsPathwaysToThatDestination() {
        val focused = planForTonicWithFollower(
            WorkspaceChordChoice.of(listOf(2, 7, 11), preferredRootPitchClass = 7),
        ).voiceLeading.pathways

        assertTrue(focused.available)
        assertTrue(focused.descriptionLabel.contains("G"))
        assertTrue(focused.groups.isNotEmpty())
        focused.groups.flatMap { it.pathways }.forEach { pathway ->
            assertEquals(listOf(2, 7, 11), pathway.choice.pitchClasses, "${pathway.id} left the target")
        }
        // Unfocused projection still offers other destinations, so this really is a filter.
        assertTrue(
            planForTonicWithFollower().groups().any { it.choice.pitchClasses != listOf(2, 7, 11) },
        )
    }

    private fun PracticePlanView.groups(): List<PracticeVoiceLeadingPathwayView> =
        voiceLeading.pathways.groups.flatMap { it.pathways }
}
