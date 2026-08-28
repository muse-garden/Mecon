package com.mecon.features.freepractice

import com.mecon.theory.freepractice.HarmonyWorkspaceState
import com.mecon.theory.freepractice.WorkspaceChordChoice
import com.mecon.theory.freepractice.WorkspaceHarmonySlot
import com.mecon.theory.freepractice.WorkspaceKeyMode
import com.mecon.theory.freepractice.WorkspaceChordTonality
import com.mecon.theory.freepractice.WorkspaceChordTonalReading
import com.mecon.theory.freepractice.WorkspaceDerivedTonalSpan
import com.mecon.theory.ModulationKey
import com.mecon.theory.harmony.ChordSelectionCatalog
import com.mecon.theory.harmony.ChordSelectionChoice
import com.mecon.theory.harmony.ChordInterpretationRef
import com.mecon.theory.harmony.ChordCatalogSnapshot
import com.mecon.theory.harmony.ChordKnowledgeContext
import com.mecon.theory.harmony.HarmonyTimelineReadingProjector
import com.mecon.theory.harmony.SoundingInterpretationQuery
import com.mecon.theory.schoenberg.SchoenbergHarmonicTreatments
import com.mecon.theory.freepractice.tonalityOptions
import com.mecon.api.primitive.PitchClass
import com.mecon.theory.Chord
import com.mecon.theory.ChordSymbolDisplayStyle
import com.mecon.theory.ChordSymbolFormatter
import com.mecon.theory.ModulationPitchLabels
import com.mecon.theory.voiceleading.SchoenbergChromaticRootMotion
import com.mecon.theory.voiceleading.StandardVoiceLeadingChordFamilies
import com.mecon.theory.voiceleading.VoiceLeadingParallelRisk
import com.mecon.theory.voiceleading.VoiceLeadingFigurationPlacement
import com.mecon.theory.voiceleading.VoiceLeadingFigurationProjector
import com.mecon.theory.voiceleading.VoiceLeadingPathNode
import com.mecon.theory.voiceleading.VoiceLeadingStability
import com.mecon.theory.voiceleading.VoiceLeadingTransformations
import com.mecon.theory.NonChordToneType
import kotlin.math.round

/** UI-neutral read models shared by Compose and React workbench adapters. */
object FreePracticeViewProjector {
    /**
     * Projects the chord-detail read model for one catalog choice in one key.
     *
     * Both the committed selection and the desktop chord picker's pre-commit preview must describe a
     * chord identically; the panel used to repeat this four-call pipeline (knowledge context ->
     * catalog snapshot -> sounding-interpretation query -> detail projection) itself, which was the
     * only reason Desktop imported the harmony catalog internals at all. That copy also dropped the
     * [pinnedInterpretationRef] containment guard below, so a pin belonging to a *different* choice
     * could leak into the preview.
     */
    fun chordDetail(
        key: ModulationKey,
        choice: ChordSelectionChoice,
        pinnedInterpretationRef: ChordInterpretationRef? = null,
    ): PracticeChordDetailView = ChordCatalogSnapshot.create(
        key,
        treatmentRegistry = SchoenbergHarmonicTreatments.registry,
    ).resolve(
        SoundingInterpretationQuery(
            audibleKey = choice.audibleKey,
            selectedOrigin = choice.origin,
            pinnedInterpretationRef = pinnedInterpretationRef
                ?.takeIf { it in choice.interpretationRefs },
        ),
        ChordKnowledgeContext(key.tonalContext("free-practice-chord-detail")),
    ).let { detail -> PracticeChordDetailProjector.map(detail, PracticeChordDetailStrings::text) }

    private fun presentationOrderedReadings(
        workspace: HarmonyWorkspaceState,
        slot: WorkspaceHarmonySlot,
        readings: List<WorkspaceChordTonalReading>,
        derivedSpans: List<WorkspaceDerivedTonalSpan>,
    ): List<WorkspaceChordTonalReading> = readings.withIndex()
        .sortedWith(
            compareBy<IndexedValue<WorkspaceChordTonalReading>> { indexed ->
                val key = indexed.value.key
                val manualStarts = workspace.tonalLayouts.asSequence()
                    .filter { it.key == key && it.contains(slot.onset) }
                    .map { it.start }
                val derivedStarts = derivedSpans.asSequence()
                    .filter { it.key == key && it.start <= slot.onset && it.end > slot.onset }
                    .map { it.start }
                (manualStarts + derivedStarts).minOrNull() ?: slot.onset
            }.thenBy { it.index },
        )
        .map { it.value }

    fun timeline(
        workspace: HarmonyWorkspaceState,
        idiomCatalog: PracticeIdiomCatalogView = PracticeIdiomCatalogView(),
        scoreEnd: com.mecon.api.primitive.Fraction? = null,
        measureBoundaries: List<com.mecon.api.primitive.Fraction> = emptyList(),
        defaultChordDuration: com.mecon.api.primitive.Fraction? = null,
    ): PracticeTimelineView {
        val lockedSlots = workspace.idiomInstances.flatMapTo(hashSetOf()) { instance ->
            instance.inversionLockedSlotIds.orEmpty()
        }
        val idiomSlots = workspace.idiomInstances.flatMapTo(hashSetOf()) { it.slotIds }
        val derivedTonalSpans = workspace.derivedTonalSpans()
        val choicesByKey = buildMap {
            (workspace.tonalLayouts.map { it.key } +
                workspace.slots.flatMap { slot -> slot.tonality?.readings.orEmpty().map { it.key } })
                .distinct()
                .forEach { key -> put(key, ChordSelectionCatalog.choices(key)) }
        }
        val workspaceEnd = workspace.slots.maxOf { it.onset + it.duration }
        val timelineEnd = maxOf(workspaceEnd, scoreEnd ?: com.mecon.api.primitive.Fraction.ZERO)
        // This is one passive background filler, not a sequence of prospective chord slots. Real
        // insertion still uses defaultChordDuration, while the filler remains visually continuous
        // across beat and measure boundaries until the closing barline.
        val emptySlots = if (defaultChordDuration != null && workspaceEnd < timelineEnd) {
            listOf(
                PracticeTimelineEmptySlotView(
                    id = "empty:${workspaceEnd.numerator}:${workspaceEnd.denominator}",
                    onset = workspaceEnd,
                    duration = timelineEnd - workspaceEnd,
                )
            )
        } else {
            emptyList()
        }
        return PracticeTimelineView(
            end = timelineEnd,
            slots = workspace.slots.map { slot ->
                val tonalReadings = slot.tonality?.readings
                val displayTonalReadings = tonalReadings?.let {
                    presentationOrderedReadings(workspace, slot, it, derivedTonalSpans)
                }
                val selectedLayout = workspace.selectedTonalLayout(slot)
                val choice = tonalReadings?.firstOrNull()?.let { reading ->
                    choicesByKey[reading.key]?.matching(slot, reading.interpretationRef)
                } ?: selectedLayout?.key?.let { key -> choicesByKey[key]?.matching(slot) }
                val fallback = if (choice == null) {
                    val fallbackKey = tonalReadings?.firstOrNull()?.key ?: selectedLayout?.key
                    slot.chordChoice?.let { committed ->
                        fallbackKey?.let { key -> nonFunctionalTimelineChord(committed, key) }
                    }
                } else {
                    null
                }
                val readings = displayTonalReadings?.mapNotNull { reading ->
                    choicesByKey[reading.key]
                        ?.matching(slot, reading.interpretationRef)
                        ?.toTimelineReading(reading.key.fifths, WorkspaceKeyMode.fromTheory(reading.key.mode))
                } ?: choice?.let { selected ->
                    workspace.activeTonalLayouts(slot.onset).mapNotNull { layout ->
                        choicesByKey[layout.key]
                            ?.firstOrNull { it.pitchClasses == selected.pitchClasses }
                            ?.toTimelineReading(layout.fifths, layout.mode)
                    }
                }.orEmpty()
                PracticeTimelineSlotView(
                    id = slot.id,
                    onset = slot.onset,
                    duration = slot.duration,
                    symbol = choice?.functionalSymbol ?: slot.chordIdentity,
                    absoluteTones = choice?.absoluteTones ?: fallback?.absoluteTones.orEmpty(),
                    relativeTones = choice?.relativeTones ?: fallback?.relativeTones.orEmpty(),
                    readings = readings,
                    pitchClasses = slot.chordChoice?.pitchClasses.orEmpty(),
                    bassPitchClass = slot.chordChoice?.bassPitchClass,
                    isPivotChord = slot.isPivotChord,
                    inversionLocked = slot.id in lockedSlots,
                    capabilities = PracticeTimelineSlotCapabilities(
                        canTranslate = slot.id !in idiomSlots,
                        canResizeStart = slot.id !in idiomSlots,
                        canResizeEnd = slot.id !in idiomSlots,
                        canRemove = workspace.slots.size > 1 && slot.id !in idiomSlots,
                    ),
                    absoluteSymbol = fallback?.absoluteSymbol,
                    relativeSymbol = fallback?.relativeSymbol,
                )
            },
            tonalLayouts = workspace.tonalLayouts.map { it.toView() },
            derivedTonalSpans = workspace.derivedTonalSpans()
                .filterNot { derived ->
                    workspace.tonalLayouts.any { manual ->
                        manual.key == derived.key &&
                            manual.start <= derived.start &&
                            (manual.end?.let { it >= derived.end } != false)
                    }
                }
                .map { span ->
                    PracticeDerivedTonalSpanView(
                        fifths = span.key.fifths,
                        mode = WorkspaceKeyMode.fromTheory(span.key.mode),
                        keyLabel = span.key.displayName + if (span.key.mode.name == "MINOR") "m" else "",
                        start = span.start,
                        end = span.end,
                    )
                },
            idioms = workspace.idiomInstances.map { instance ->
                val memberSlots = instance.slotIds.mapNotNull { id ->
                    workspace.slots.firstOrNull { it.id == id }
                }
                val definition = idiomCatalog.definitions.firstOrNull { it.id == instance.definitionId }
                val variant = definition?.variants?.firstOrNull { it.id == instance.variantId }
                PracticeIdiomView(
                    id = instance.id,
                    definitionId = instance.definitionId,
                    variantId = instance.variantId,
                    slotIds = instance.slotIds,
                    title = variant?.title ?: definition?.title,
                    start = memberSlots.minOfOrNull { it.onset },
                    end = memberSlots.maxOfOrNull { it.onset + it.duration },
                )
            },
            emptySlots = emptySlots,
        )
    }

    fun plan(
        workspace: HarmonyWorkspaceState,
        selectedSlotId: com.mecon.theory.freepractice.WorkspaceSlotId?,
        selectedTonalLayoutId: com.mecon.theory.freepractice.WorkspaceTonalLayoutId? = null,
        catalog: PracticeCatalogView,
        selectedIdiomTonalLayoutId: com.mecon.theory.freepractice.WorkspaceTonalLayoutId? = null,
        idiomCatalog: PracticeIdiomCatalogView = PracticeIdiomCatalogView(),
        selectedIdiomInstanceId: com.mecon.theory.freepractice.WorkspaceIdiomInstanceId? = null,
    ): PracticePlanView {
        val strings = PracticePlanStrings()
        val slot = selectedSlotId?.let { id -> workspace.slots.firstOrNull { it.id == id } }
        val slotIndex = slot?.let(workspace.slots::indexOf) ?: -1
        val activeLayouts = slot?.let { workspace.activeTonalLayouts(it.onset) }.orEmpty()
        val selectedChord = slot?.chordChoice?.let { committed ->
            catalog.chordChoices.firstOrNull { it.choice.matches(committed) }
        }
        val editableLayout = selectedTonalLayoutId
            ?.let { id -> workspace.tonalLayouts.firstOrNull { it.id == id } }
            ?: slot?.let(workspace::selectedTonalLayout)
            ?: activeLayouts.firstOrNull()
        val chordCatalogLayout = slot?.let(workspace::selectedTonalLayout)
            ?: editableLayout
        val idiomCatalogLayout = selectedIdiomTonalLayoutId
            ?.let { id -> activeLayouts.firstOrNull { it.id == id } }
            ?: activeLayouts.singleOrNull()
            ?: slot?.let(workspace::selectedTonalLayout)
            ?: activeLayouts.lastOrNull()
        val allowedSounds: Set<Set<Int>> = catalog.chordChoices
            .mapTo(linkedSetOf()) { it.choice.pitchClasses.toSet() }
        val keyedCatalogs = activeLayouts.distinctBy { it.key }.map { layout ->
            val keyedCatalog = projectPracticeCatalog(layout.key)
            val filtered = if (!catalog.harmonicRoleFilterEnabled) keyedCatalog else {
                val groups = keyedCatalog.chordGroups.map { group ->
                    group.copy(choices = group.choices.filter {
                        it.choice.pitchClasses.toSet() in allowedSounds
                    })
                }.filter { it.choices.isNotEmpty() }
                keyedCatalog.copy(
                    chordChoices = groups.flatMap { it.choices },
                    chordGroups = groups,
                    harmonicRoleFilterEnabled = true,
                )
            }
            layout to filtered
        }
        val catalogChoicesBySound = keyedCatalogs.associate { (layout, keyedCatalog) ->
            layout.key to keyedCatalog.chordChoices.associateBy { it.choice.pitchClasses.toSet() }
        }
        val chordCatalogFilters = keyedCatalogs.map { (layout, currentCatalog) ->
            val enrichedGroups = currentCatalog.chordGroups.map { group ->
                group.copy(
                    choices = group.choices.map { choice ->
                        choice.copy(
                            alternateTonalReadings = keyedCatalogs.mapNotNull { (otherLayout, _) ->
                                if (otherLayout.key == layout.key) return@mapNotNull null
                                catalogChoicesBySound[otherLayout.key]
                                    ?.get(choice.choice.pitchClasses.toSet())?.let { other ->
                                    PracticeChordCatalogAlternateReadingView(
                                        key = PracticeKeyView(otherLayout.fifths, otherLayout.mode),
                                        keyLabel = otherLayout.key.displayLabel(),
                                        functionalSymbol = other.symbol,
                                        relativeLabel = other.relativeLabel,
                                        absoluteLabel = other.absoluteLabel,
                                    )
                                }
                            },
                        )
                    },
                )
            }
            PracticeChordCatalogFilterView(
                id = "${layout.key.fifths}:${layout.key.mode.name}",
                key = PracticeKeyView(layout.fifths, layout.mode),
                keyLabel = layout.key.displayLabel(),
                tonalLayoutId = layout.id,
                selected = layout.id == chordCatalogLayout?.id,
                chordGroups = enrichedGroups,
                toneCountFilters = chordToneCountFilters(enrichedGroups, strings.anyBass),
            )
        }
        val projectedIdiomCatalog = idiomCatalog.copy(
            definitions = idiomCatalog.definitions.map { definition ->
                val variants = definition.variants.map { variant ->
                        val lead = variant.durations.take(variant.anchorStepIndex)
                            .fold(com.mecon.api.primitive.Fraction.ZERO) { total, duration -> total + duration }
                        val disabledReason = if (slot != null && slot.onset - lead <
                            com.mecon.api.primitive.Fraction.ZERO
                        ) {
                            "前置步骤越过谱首"
                        } else null
                        variant.copy(
                            displayLabel = variantDisplayLabel(
                                variant,
                                idiomCatalog.includeOffKey,
                                disabledReason,
                            ),
                            enabled = disabledReason == null,
                            disabledReasonLabel = disabledReason,
                        )
                    }
                definition.copy(
                    variants = variants,
                    choices = projectIdiomChoices(variants),
                )
            },
        )
        val timeline = timeline(workspace, projectedIdiomCatalog)
        val selectedSlotView = slot?.id?.let { id -> timeline.slots.firstOrNull { it.id == id } }
        val lockedSlots = workspace.idiomInstances.flatMapTo(hashSetOf()) { it.slotIds }
        val inversionLockedSlots = workspace.idiomInstances.flatMapTo(hashSetOf()) {
            it.inversionLockedSlotIds.orEmpty()
        }
        val referenceKey = editableLayout?.key
        val currentTonality = slot?.tonality
        val availableTonalityOptions = if (slot != null && referenceKey != null) {
            slot.tonalityOptions(referenceKey)
        } else emptyList()
        val tonalityChoices = if (slot != null && referenceKey != null && slot.id !in lockedSlots) {
            buildList {
                add(
                    PracticeChordTonalityChoiceView(
                        id = "manual",
                        key = PracticeKeyView(referenceKey.fifths, WorkspaceKeyMode.fromTheory(referenceKey.mode)),
                        keyLabel = referenceKey.displayLabel(),
                        functionalSymbol = "manual",
                        displayLabel = strings.followManualTonality,
                        tonality = null,
                        selected = currentTonality == null,
                    )
                )
                availableTonalityOptions.forEach { option ->
                    val reading = option.toReading()
                    add(
                        PracticeChordTonalityChoiceView(
                            id = "${option.key.fifths}:${option.key.mode.name}:${option.interpretationRef}",
                            key = PracticeKeyView(
                                option.key.fifths,
                                WorkspaceKeyMode.fromTheory(option.key.mode),
                            ),
                            keyLabel = option.key.displayLabel(),
                            functionalSymbol = option.functionalSymbol,
                            absoluteTones = option.absoluteTones,
                            relativeTones = option.relativeTones,
                            relativeTonesLabel = option.relativeTones.joinToString(" · "),
                            absoluteTonesLabel = option.absoluteTones.joinToString(" · "),
                            displayLabel = "${option.key.displayLabel()} · ${option.functionalSymbol}",
                            tonality = WorkspaceChordTonality(reading),
                            selected = currentTonality?.primary == reading &&
                                currentTonality.alternates.isEmpty(),
                        )
                    )
                }
            }
        } else emptyList()
        val previous = workspace.slots.getOrNull(slotIndex - 1)
        val previousManualKey = previous?.let(workspace::selectedTonalLayout)?.key
        val previousTemporaryKeys = previous?.tonality?.readings.orEmpty()
            .map { it.key }
            .filter { it != previousManualKey }
        val continuationChoices = if (slot != null && referenceKey != null && previousTemporaryKeys.isNotEmpty()) {
            (previousTemporaryKeys + referenceKey).distinct().map { key ->
                val option = availableTonalityOptions.firstOrNull { it.key == key }
                val reading = option?.toReading()
                    ?: com.mecon.theory.freepractice.WorkspaceChordTonalReading.of(key)
                PracticeChordTonalityChoiceView(
                    id = "continue:${key.fifths}:${key.mode.name}",
                    key = PracticeKeyView(key.fifths, WorkspaceKeyMode.fromTheory(key.mode)),
                    keyLabel = key.displayLabel(),
                    functionalSymbol = option?.functionalSymbol.orEmpty(),
                    absoluteTones = option?.absoluteTones.orEmpty(),
                    relativeTones = option?.relativeTones.orEmpty(),
                    relativeTonesLabel = option?.relativeTones.orEmpty().joinToString(" · "),
                    absoluteTonesLabel = option?.absoluteTones.orEmpty().joinToString(" · "),
                    displayLabel = key.displayLabel() + option?.let { " · ${it.functionalSymbol}" }.orEmpty(),
                    tonality = if (key == referenceKey) null else WorkspaceChordTonality(reading),
                    selected = (currentTonality?.primary?.key ?: referenceKey) == key &&
                        currentTonality?.alternates.orEmpty().isEmpty(),
                )
            }
        } else emptyList()
        val currentTonalityRows = currentTonality?.readings.orEmpty().mapIndexed { index, reading ->
            val option = availableTonalityOptions.firstOrNull {
                it.key == reading.key &&
                    (reading.interpretationRef == null || it.interpretationRef == reading.interpretationRef)
            }
            val remaining = currentTonality?.readings.orEmpty().filterNot { it.key == reading.key }
            PracticeTonalityReadingRowView(
                id = "reading:${reading.key.fifths}:${reading.key.mode.name}",
                headingLabel = (if (index == 0) strings.primaryTonality else strings.alternateTonality) +
                    " · ${reading.key.displayLabel()}",
                relativeDetailLabel = option?.let {
                    "${it.functionalSymbol} · ${it.relativeTones.joinToString(" · ")}"
                }.orEmpty(),
                absoluteDetailLabel = option?.let {
                    "${it.functionalSymbol} · ${it.absoluteTones.joinToString(" · ")}"
                }.orEmpty(),
                primary = index == 0,
                removeTonality = remaining.firstOrNull()?.let { primary ->
                    WorkspaceChordTonality(primary, remaining.drop(1))
                },
            )
        }
        val effectiveKey = currentTonality?.primary?.key ?: referenceKey
        val detailChoice = if (slot != null && effectiveKey != null) {
            ChordSelectionCatalog.choices(effectiveKey).matching(
                slot,
                currentTonality?.primary?.interpretationRef ?: slot.chordInterpretationRef,
            )
        } else null
        val voiceLeading = projectVoiceLeading(
            source = slot?.chordChoice,
            key = effectiveKey,
            sourceRootPitchClass = slot?.chordChoice?.preferredRootPitchClass
                ?: detailChoice?.rootPitchClass,
            // When the following slot already holds a chord, pathways are the "fill the gap
            // between these two chords" question rather than open-ended discovery.
            followingChoice = if (slotIndex >= 0) {
                workspace.slots.getOrNull(slotIndex + 1)?.chordChoice
            } else null,
        )
        val chordDetail = if (effectiveKey != null && detailChoice != null) {
            chordDetail(
                key = effectiveKey,
                choice = detailChoice,
                pinnedInterpretationRef = slot?.chordChoice?.pinnedInterpretationRef,
            )
        } else null
        val existingTonalityKeys = currentTonality?.readings.orEmpty().mapTo(hashSetOf()) { it.key }
        val primaryOption = availableTonalityOptions.firstOrNull { it.key == effectiveKey }
        val doubleTonalityChoices = if (
            slot != null && referenceKey != null && slot.id !in lockedSlots &&
            currentTonality?.readings.orEmpty().size < 2
        ) {
            availableTonalityOptions.filter { it.key !in existingTonalityKeys && it.key != effectiveKey }
                .mapNotNull { option ->
                    val base = currentTonality ?: primaryOption?.let { WorkspaceChordTonality(it.toReading()) }
                    base?.let {
                        val change = option.key.fifths - referenceKey.fifths
                        val direction = when {
                            change > 0 -> "升号方向 +$change"
                            change < 0 -> "降号方向 +${-change}"
                            else -> "同调号"
                        }
                        PracticeChordTonalityChoiceView(
                            id = "double:${option.key.fifths}:${option.key.mode.name}:${option.interpretationRef}",
                            key = PracticeKeyView(option.key.fifths, WorkspaceKeyMode.fromTheory(option.key.mode)),
                            keyLabel = option.key.displayLabel(),
                            functionalSymbol = option.functionalSymbol,
                            absoluteTones = option.absoluteTones,
                            relativeTones = option.relativeTones,
                            relativeTonesLabel = "${option.functionalSymbol} · " +
                                option.relativeTones.joinToString(" · "),
                            absoluteTonesLabel = "${option.functionalSymbol} · " +
                                option.absoluteTones.joinToString(" · "),
                            displayLabel = "${option.key.displayLabel()} · $direction",
                            directionLabel = direction,
                            tonality = it.copy(alternates = listOf(option.toReading())),
                        )
                    }
                }
        } else emptyList()
        val selectedReadings = selectedSlotView?.readings.orEmpty()
        val chordReadings = selectedReadings.map { reading ->
            reading.copy(
                symbolLabel = (if (selectedReadings.size > 1) "${reading.keyLabel}：" else "") +
                    reading.functionalSymbol,
                absoluteTonesLabel = reading.absoluteTones.joinToString(" · ")
                    .ifBlank { strings.chordTonesEmpty },
                relativeTonesLabel = reading.relativeTones.joinToString(" · ")
                    .ifBlank { strings.chordTonesEmpty },
            )
        }
        val bassChoices = buildList {
            add(
                PracticeBassOptionView(
                    pitchClass = null,
                    relativeLabel = strings.anyBass,
                    absoluteLabel = strings.anyBass,
                    selected = slot?.chordChoice?.bassPitchClass == null,
                )
            )
            val orderedBassChoices = detailChoice?.pitchClasses
                ?.zip(detailChoice.relativeTones.zip(detailChoice.absoluteTones))
                .orEmpty()
            orderedBassChoices.forEach { (pitchClass, labels) ->
                add(
                    PracticeBassOptionView(
                        pitchClass = pitchClass,
                        relativeLabel = labels.first,
                        absoluteLabel = labels.second,
                        selected = slot?.chordChoice?.bassPitchClass == pitchClass,
                    )
                )
            }
            if (orderedBassChoices.isEmpty()) {
                slot?.chordChoice?.pitchClasses.orEmpty().forEach { pitchClass ->
                    add(
                        PracticeBassOptionView(
                            pitchClass = pitchClass,
                            relativeLabel = pitchClass.toString(),
                            absoluteLabel = pitchClass.toString(),
                            selected = slot?.chordChoice?.bassPitchClass == pitchClass,
                        )
                    )
                }
            }
        }
        val coveredInstances = slot?.let { workspace.idiomInstancesForSlot(it.id) }.orEmpty()
            .sortedBy { instance ->
                instance.slotIds.mapNotNull { id -> workspace.slots.firstOrNull { it.id == id }?.onset }
                    .minOrNull()
            }
        val coveredRows = coveredInstances.map { instance ->
            val orderedIds = instance.slotIds.sortedBy { id -> workspace.slots.first { it.id == id }.onset }
            val chordNumber = orderedIds.indexOf(slot?.id).takeIf { it >= 0 }?.plus(1) ?: 1
            val definitionTitle = projectedIdiomCatalog.definitions
                .firstOrNull { it.id == instance.definitionId }?.title ?: instance.definitionId
            PracticeCoveredIdiomView(
                id = instance.id,
                displayLabel = "$definitionTitle（第${chordNumber}个和弦）",
                startsHere = orderedIds.firstOrNull() == slot?.id,
            )
        }
        val targetKeys = projectedIdiomCatalog.definitions.flatMap { definition ->
            definition.variants.filter { it.relatedToFocus }
        }
            .mapNotNull { it.suggestedKey }
            .distinct()
            .sortedWith(compareBy<PracticeKeyView>({ kotlin.math.abs(it.fifths) }, { it.mode.ordinal }, { it.fifths }))
            .map { key ->
                PracticePlanKeyFilterView(
                    id = "${key.fifths}:${key.mode.name}",
                    label = key.toTheoryKey().displayName,
                    key = key,
                )
            }
        return PracticePlanView(
            strings = strings,
            selectedSlotId = slot?.id,
            selectedSlot = selectedSlotView,
            navigation = PracticePlanNavigationView(
                previousSlotId = workspace.slots.getOrNull(slotIndex - 1)?.id,
                nextSlotId = workspace.slots.getOrNull(slotIndex + 1)?.id,
                lastSlotId = workspace.slots.lastOrNull()?.id,
                appendOnset = workspace.slots.lastOrNull()?.let { it.onset + it.duration },
            ),
            currentKey = editableLayout?.let { PracticeKeyView(it.fifths, it.mode) },
            tonalKeyChoices = WorkspaceKeyMode.entries.flatMap { mode ->
                (-7..7).map { fifths ->
                    val key = PracticeKeyView(fifths, mode)
                    PracticePlanKeyFilterView(
                        id = "$fifths:${mode.name}",
                        label = key.toTheoryKey().displayName,
                        key = key,
                    )
                }
            },
            activeTonalLayoutIds = activeLayouts.map { it.id },
            activeTonalLayouts = activeLayouts.map { it.toView() },
            editableTonalLayoutId = editableLayout?.id,
            editableTonalLayout = editableLayout?.toView(),
            selectedChord = selectedChord,
            chordDetail = chordDetail,
            voiceLeading = voiceLeading,
            chordCatalogGroups = catalog.chordGroups,
            chordCatalogFilters = chordCatalogFilters,
            selectedChordReadings = chordReadings,
            bassOptions = slot?.chordChoice?.pitchClasses.orEmpty(),
            bassChoices = bassChoices,
            pivotEnabled = slot?.isPivotChord == true,
            chordLocked = slot?.id in lockedSlots,
            inversionLocked = slot?.id in inversionLockedSlots,
            coveredIdioms = slot?.id?.let { selectedId ->
                timeline.idioms.filter { selectedId in it.slotIds }
            }.orEmpty(),
            coveredIdiomRows = coveredRows,
            tonalityChoices = tonalityChoices,
            continuationTonalityChoices = continuationChoices,
            currentTonalityRows = currentTonalityRows,
            doubleTonalityChoices = doubleTonalityChoices,
            idiomTargetKeys = targetKeys,
            idiomCatalogFilters = activeLayouts.map { layout ->
                PracticePlanTonalLayoutFilterView(
                    id = layout.id.value,
                    key = PracticeKeyView(layout.fifths, layout.mode),
                    label = layout.key.displayLabel(),
                    tonalLayoutId = layout.id,
                    selected = layout.id == idiomCatalogLayout?.id,
                )
            },
            idiomCatalog = projectedIdiomCatalog,
            selectedIdiomForm = selectedIdiomForm(
                workspace,
                selectedIdiomInstanceId,
                projectedIdiomCatalog,
            ),
        )
    }

    private fun chordToneCountFilters(
        groups: List<PracticeChordCatalogGroupView>,
        anyLabel: String,
    ): List<PracticeChordToneCountFilterView> {
        val counts = groups.asSequence()
            .flatMap { it.choices.asSequence() }
            .map { it.choice.pitchClasses.distinct().size }
            .distinct()
            .sorted()
            .toList()
        return listOf(
            PracticeChordToneCountFilterView(
                id = "any",
                label = anyLabel,
                chordGroups = groups,
            )
        ) + counts.map { count ->
            PracticeChordToneCountFilterView(
                id = "tones-$count",
                toneCount = count,
                label = "${count}音",
                chordGroups = groups.mapNotNull { group ->
                    group.copy(
                        choices = group.choices.filter {
                            it.choice.pitchClasses.distinct().size == count
                        },
                    ).takeIf { it.choices.isNotEmpty() }
                },
            )
        }
    }

    /**
     * The executable variants remain intact, while the catalog shows one basic formula per
     * definition. Focused routes may expose a different formula, but size combinations never
     * become separate rows.
     */
    private fun projectIdiomChoices(
        variants: List<PracticeIdiomVariantView>,
    ): List<PracticeIdiomChoiceView> {
        val defaultStructureId = variants.firstOrNull { it.availableByDefault }?.structureId
        return variants.groupBy(PracticeIdiomVariantView::realizationFamilyKey)
            .map { (family, realizations) ->
            fun baseline(source: List<PracticeIdiomVariantView>) = source.minWithOrNull(
                compareBy<PracticeIdiomVariantView>(
                    { it.effectiveToneCounts().sum() },
                    { it.title },
                    { it.id },
                )
            )
            val base = requireNotNull(baseline(realizations))
            val default = baseline(realizations.filter { it.availableByDefault }) ?: base
            val related = baseline(realizations.filter { it.relatedToFocus })
            val action = related ?: default
            PracticeIdiomChoiceView(
                id = "${family.structureId}@${family.interpretationContextId}@" +
                    "${family.suggestedKey?.fifths}:${family.suggestedKey?.mode?.name}",
                title = base.title,
                displayLabel = action.displayLabel.ifBlank { base.title },
                variantIds = realizations.map { it.id },
                defaultVariantId = default.id,
                relatedVariantId = related?.id,
                suggestedKey = family.suggestedKey,
                availableByDefault = family.structureId == defaultStructureId &&
                    realizations.any { it.availableByDefault },
                relatedToFocus = related != null,
                enabled = action.enabled,
                disabledReasonLabel = action.disabledReasonLabel,
            )
        }
    }

    private fun selectedIdiomForm(
        workspace: HarmonyWorkspaceState,
        selectedIdiomInstanceId: com.mecon.theory.freepractice.WorkspaceIdiomInstanceId?,
        catalog: PracticeIdiomCatalogView,
    ): PracticeSelectedIdiomFormView? {
        val instance = selectedIdiomInstanceId?.let { id ->
            workspace.idiomInstances.firstOrNull { it.id == id }
        } ?: return null
        val definition = catalog.definitions.firstOrNull { it.id == instance.definitionId } ?: return null
        val selected = definition.variants.firstOrNull { it.id == instance.variantId } ?: return null
        val selectedCounts = selected.effectiveToneCounts()
        if (selectedCounts.size != selected.chordIdentities.size) return null
        val selectedFamily = selected.realizationFamilyKey()
        val realizations = definition.variants.filter { it.realizationFamilyKey() == selectedFamily }
        val base = realizations.minWithOrNull(
            compareBy<PracticeIdiomVariantView>({ it.effectiveToneCounts().sum() }, { it.id })
        ) ?: return null
        val steps = selectedCounts.indices.mapNotNull { stepIndex ->
            val candidates = realizations.filter { candidate ->
                val counts = candidate.effectiveToneCounts()
                counts.size == selectedCounts.size && counts.indices.all { index ->
                    index == stepIndex || counts[index] == selectedCounts[index]
                }
            }.map { it.effectiveToneCounts()[stepIndex] }.distinct().sorted()
            if (candidates.size < 2) return@mapNotNull null
            PracticeIdiomToneCountStepView(
                stepIndex = stepIndex,
                chordLabel = base.chordIdentities[stepIndex],
                options = candidates.map { toneCount ->
                    PracticeIdiomToneCountOptionView(
                        toneCount = toneCount,
                        label = chordStackLabel(toneCount),
                        selected = toneCount == selectedCounts[stepIndex],
                    )
                },
            )
        }
        return PracticeSelectedIdiomFormView(
            idiomInstanceId = instance.id,
            definitionId = definition.id,
            structureId = selected.structureId,
            title = base.title,
            steps = steps,
        ).takeIf { it.steps.isNotEmpty() }
    }

    private fun PracticeIdiomVariantView.effectiveToneCounts(): List<Int> =
        chordToneCounts.takeIf { it.size == chordIdentities.size }
            ?: chordChoices.map { it.pitchClasses.distinct().size }

    private fun chordStackLabel(toneCount: Int): String = when (toneCount) {
        3 -> "三和弦"
        4 -> "七和弦"
        5 -> "九和弦"
        6 -> "十一和弦"
        7 -> "十三和弦"
        else -> "${toneCount}音和弦"
    }

    private fun Iterable<ChordSelectionChoice>.matching(
        slot: WorkspaceHarmonySlot,
        interpretationRef: ChordInterpretationRef? = null,
    ): ChordSelectionChoice? {
        val choices = toList()
        slot.chordChoice?.let { committed ->
            val sounding = choices.filter { it.pitchClasses == committed.pitchClasses.toSet() }
            return sounding.firstOrNull {
                val pinned = interpretationRef ?: committed.pinnedInterpretationRef
                pinned != null && pinned in it.interpretationRefs
            } ?: sounding.firstOrNull()
        }
        return choices.firstOrNull { choice ->
            slot.chordInterpretationRef?.let { it in choice.interpretationRefs }
                ?: (choice.identity == slot.chordIdentity)
        }
    }

    private fun ChordSelectionChoice.toTimelineReading(
        fifths: Int,
        mode: WorkspaceKeyMode,
    ): PracticeTimelineChordReadingView {
        val key = com.mecon.theory.ModulationKey(fifths, mode.toTheory())
        val reading = HarmonyTimelineReadingProjector.reading(this, key)
        return PracticeTimelineChordReadingView(
            fifths = fifths,
            mode = mode,
            keyLabel = key.displayName + if (mode == WorkspaceKeyMode.MINOR) "m" else "",
            functionalSymbol = reading.functionalSymbol,
            absoluteTones = reading.absoluteTones,
            relativeTones = reading.relativeTones,
            absoluteTonesLabel = reading.absoluteTones.joinToString(" · "),
            relativeTonesLabel = reading.relativeTones.joinToString(" · "),
        )
    }

    private fun com.mecon.theory.freepractice.WorkspaceTonalLayout.toView() =
        PracticeTonalLayoutView(
            id = id,
            fifths = fifths,
            mode = mode,
            start = start,
            end = end,
            isBaseline = isBaseline,
            keyLabel = key.displayLabel(),
            rangeLabel = "${start.timelineLabel()} – ${end?.timelineLabel() ?: "末尾"}",
            baselineLabel = "初始".takeIf { isBaseline },
        )

    private fun com.mecon.api.primitive.Fraction.timelineLabel(): String {
        val beats = toDouble() * 4.0
        val rounded = round(beats * 100.0) / 100.0
        val number = if (rounded % 1.0 == 0.0) rounded.toInt().toString() else rounded.toString()
        return "$number 拍"
    }

    private fun com.mecon.theory.ModulationKey.displayLabel(): String =
        displayName + if (mode.name == "MINOR") "m" else ""

    private fun PracticeKeyView.toTheoryKey() =
        com.mecon.theory.ModulationKey(fifths, mode.toTheory())

    private fun variantDisplayLabel(
        variant: PracticeIdiomVariantView,
        showOffKeyIdioms: Boolean,
        reason: String?,
    ): String = buildString {
        append(variant.title)
        variant.suggestedKey?.takeIf { showOffKeyIdioms }?.let { target ->
            append(" · 目标 ").append(target.toTheoryKey().displayName).append(" · ")
            append(
                when {
                    variant.targetKeyDistance > 0 -> "至少偏离 ${variant.targetKeyDistance} 个升号"
                    variant.targetKeyDistance < 0 -> "至少偏离 ${-variant.targetKeyDistance} 个降号"
                    else -> "调号距离 0"
                }
            )
        }
        if (reason != null) append(" · ").append(reason)
    }

    private fun WorkspaceChordChoice.matches(other: WorkspaceChordChoice): Boolean =
        pitchClasses == other.pitchClasses &&
            (other.pinnedInterpretationRef == null || pinnedInterpretationRef == other.pinnedInterpretationRef)

    private data class NonFunctionalTimelineChord(
        val absoluteSymbol: String?,
        val relativeSymbol: String?,
        val absoluteTones: List<String>,
        val relativeTones: List<String>,
    )

    /**
     * Voice-leading targets are allowed to leave the current functional catalog. Keep their
     * stored sonority visible instead of projecting the occupied slot as "选择和弦".
     */
    private fun nonFunctionalTimelineChord(
        choice: WorkspaceChordChoice,
        key: com.mecon.theory.ModulationKey,
    ): NonFunctionalTimelineChord {
        // The pathway universe, not the base families: a suspended sonority written by a pathway
        // insertion still has to show its own symbol instead of falling back to bare pitch names.
        val universe = PracticeVoiceLeadingPathwayCatalog.universe
        val readings = universe.recognize(choice.pitchClasses)
        val reading = choice.preferredRootPitchClass?.let { preferred ->
            readings.firstOrNull { it.rootPitchClass == preferred }
        } ?: readings.firstOrNull()
        val orderedPitchClasses = if (reading != null) {
            universe.definitionOf(reading.definitionId).members.map { member ->
                (reading.rootPitchClass + member.semitones).mod(12)
            }.distinct()
        } else {
            val root = choice.preferredRootPitchClass
            if (root == null) choice.pitchClasses else listOf(root) + choice.pitchClasses.filterNot { it == root }
        }
        val chord = reading?.let { Chord(PitchClass(it.rootPitchClass), it.quality) }
        return NonFunctionalTimelineChord(
            absoluteSymbol = chord?.let {
                ChordSymbolFormatter.format(it, ChordSymbolDisplayStyle.LETTER, key.keySignature)
            },
            relativeSymbol = chord?.let {
                ChordSymbolFormatter.format(it, ChordSymbolDisplayStyle.SCALE_DEGREE, key.keySignature)
            },
            absoluteTones = orderedPitchClasses.map { pitchLabel(it, key, absolute = true) },
            relativeTones = orderedPitchClasses.map { pitchLabel(it, key, absolute = false) },
        )
    }

    private fun projectVoiceLeading(
        source: WorkspaceChordChoice?,
        key: com.mecon.theory.ModulationKey?,
        sourceRootPitchClass: Int?,
        followingChoice: WorkspaceChordChoice? = null,
    ): PracticeVoiceLeadingView {
        if (source == null || key == null) return PracticeVoiceLeadingView()
        // Pathways stand on their own vocabulary: a suspended sonority inserted by a previous
        // pathway is not part of any base family, but must still offer its own continuations.
        val pathwaySection = projectVoiceLeadingPathways(
            source,
            key,
            sourceRootPitchClass,
            followingChoice,
        )
        val family = StandardVoiceLeadingChordFamilies.matching(source.pitchClasses)
            ?: return PracticeVoiceLeadingView(pathways = pathwaySection)
        val candidates = VoiceLeadingTransformations.enumerate(source.pitchClasses, family)
        val views = candidates.map { candidate ->
            val targetReading = candidate.readings.firstOrNull {
                it.rootPitchClass == candidate.rootConnection.targetRootPitchClass
            } ?: candidate.readings.first()
            val targetChord = Chord(PitchClass(targetReading.rootPitchClass), targetReading.quality)
            val paths = candidate.paths.mapIndexed { pathIndex, path ->
                val risks = buildSet {
                    if (VoiceLeadingParallelRisk.PARALLEL_FIFTH in path.parallelRisks) {
                        add(PracticeVoiceLeadingParallelRisk.PARALLEL_FIFTH)
                    }
                    if (
                        VoiceLeadingParallelRisk.PARALLEL_OCTAVE_IF_MOVED_TONE_IS_DOUBLED in
                        path.parallelRisks
                    ) {
                        add(PracticeVoiceLeadingParallelRisk.PARALLEL_OCTAVE_IF_DOUBLED)
                    }
                }
                PracticeVoiceLeadingPathView(
                    id = candidate.targetPitchClasses.joinToString("-") + ":$pathIndex",
                    moves = path.moves.map { move ->
                        val arrow = if (move.semitones > 0) "↑" else "↓"
                        val amount = kotlin.math.abs(move.semitones)
                        PracticeVoiceLeadingMoveView(
                            order = move.order,
                            sourceToneIndex = move.sourceToneIndex,
                            fromPitchClass = move.fromPitchClass,
                            toPitchClass = move.toPitchClass,
                            semitones = move.semitones,
                            absoluteLabel = pitchLabel(move.fromPitchClass, key, absolute = true) +
                                " → ${pitchLabel(move.toPitchClass, key, absolute = true)} ($arrow$amount)",
                            relativeLabel = pitchLabel(move.fromPitchClass, key, absolute = false) +
                                " → ${pitchLabel(move.toPitchClass, key, absolute = false)} ($arrow$amount)",
                        )
                    },
                    parallelRisks = risks,
                    warningLabel = when {
                        PracticeVoiceLeadingParallelRisk.PARALLEL_FIFTH in risks ->
                            "固定声部直连容易形成平行五度；若移动音被重复，还可能形成平行八度。"
                        PracticeVoiceLeadingParallelRisk.PARALLEL_OCTAVE_IF_DOUBLED in risks ->
                            "同向移动音若在实际织体中被重复，可能形成平行八度。"
                        else -> ""
                    },
                    threeTonesSameDirection = path.threeTonesSameDirection,
                )
            }
            val connection = candidate.rootConnection
            val motion = PracticeVoiceLeadingRootMotion.valueOf(connection.motion.name)
            val primaryPathIndex = 0
            val primaryPath = candidate.paths[primaryPathIndex]
            val sourceReadings = VoiceLeadingTransformations.recognize(source.pitchClasses, family)
            val sourceReading = sourceRootPitchClass?.let { preferredRoot ->
                sourceReadings.firstOrNull { it.rootPitchClass == preferredRoot }
            } ?: sourceReadings.first { it.rootPitchClass == connection.sourceRootPitchClass }
            val sourceDefinition = family.definitions.first { it.id == sourceReading.definitionId }
            // The source establishes the columns: root, third, fifth, seventh (when present).
            // The target then stays in those original-tone columns instead of being re-sorted.
            val sourceToneOrder = sourceDefinition.members.map { member ->
                (sourceReading.rootPitchClass + member.semitones).mod(12)
            }.distinct()
            val movedTargetBySource = primaryPath.moves.associate { move ->
                move.fromPitchClass to move.toPitchClass
            }
            val targetToneOrder = sourceToneOrder.map { pitchClass ->
                movedTargetBySource[pitchClass] ?: pitchClass
            }
            check(targetToneOrder.toSet() == candidate.targetPitchClasses.toSet()) {
                "Aligned voice-leading target must preserve every original tone column"
            }
            val changedSources = primaryPath.moves.mapTo(hashSetOf()) { it.fromPitchClass }
            val changedTargets = primaryPath.moves.mapTo(hashSetOf()) { it.toPitchClass }
            fun tones(pitchClasses: List<Int>, changed: Set<Int>) = pitchClasses
                .map { pitchClass ->
                    PracticeVoiceLeadingToneView(
                        pitchClass = pitchClass,
                        absoluteLabel = pitchLabel(pitchClass, key, absolute = true),
                        relativeLabel = pitchLabel(pitchClass, key, absolute = false),
                        changed = pitchClass in changed,
                    )
                }
            val sourceRootAbsolute = pitchLabel(connection.sourceRootPitchClass, key, absolute = true)
            val targetRootAbsolute = pitchLabel(connection.targetRootPitchClass, key, absolute = true)
            val sourceRootRelative = pitchLabel(connection.sourceRootPitchClass, key, absolute = false)
            val targetRootRelative = pitchLabel(connection.targetRootPitchClass, key, absolute = false)
            PracticeVoiceLeadingCandidateView(
                id = "${family.id.value}:${candidate.targetPitchClasses.joinToString("-")}",
                choice = WorkspaceChordChoice.of(
                    candidate.targetPitchClasses,
                    preferredRootPitchClass = targetReading.rootPitchClass,
                ),
                transformationCount = candidate.transformationCount,
                quality = targetReading.quality.name,
                absoluteLabel = ChordSymbolFormatter.format(
                    targetChord,
                    ChordSymbolDisplayStyle.LETTER,
                    key.keySignature,
                ),
                relativeLabel = ChordSymbolFormatter.format(
                    targetChord,
                    ChordSymbolDisplayStyle.SCALE_DEGREE,
                    key.keySignature,
                ),
                primaryPathIndex = primaryPathIndex,
                sourceTones = tones(sourceToneOrder, changedSources),
                targetTones = tones(targetToneOrder, changedTargets),
                paths = paths,
                availableWhenThreeToneSameDirectionFiltered = paths.any {
                    !it.threeTonesSameDirection
                },
                rootConnection = PracticeVoiceLeadingRootConnectionView(
                    sourceRootPitchClass = connection.sourceRootPitchClass,
                    targetRootPitchClass = connection.targetRootPitchClass,
                    motion = motion,
                    colorToken = when (motion) {
                        PracticeVoiceLeadingRootMotion.RISING -> "root-motion-rising"
                        PracticeVoiceLeadingRootMotion.DESCENDING -> "root-motion-descending"
                        PracticeVoiceLeadingRootMotion.SUPERSTRONG -> "root-motion-superstrong"
                        PracticeVoiceLeadingRootMotion.REPEATED -> "root-motion-repeated"
                        PracticeVoiceLeadingRootMotion.UNCLASSIFIED -> "root-motion-unclassified"
                    },
                    absoluteLabel = "$sourceRootAbsolute → $targetRootAbsolute",
                    relativeLabel = "$sourceRootRelative → $targetRootRelative",
                    hintLabel = rootMotionHint(connection.motion, connection.directedSemitones),
                ),
            )
        }
        return PracticeVoiceLeadingView(
            available = true,
            familyId = family.id.value,
            groups = views.groupBy { it.transformationCount }.entries.sortedBy { it.key }.map { (count, items) ->
                PracticeVoiceLeadingStepGroupView(
                    transformationCount = count,
                    titleLabel = "${count} 步变换",
                    candidates = items,
                )
            },
            pathways = pathwaySection,
        )
    }

    /**
     * Suspension and passing-chord candidates: the same move multiset in a different order.
     *
     * Ordering is what distinguishes them, so the projection presents the whole ordered node chain
     * plus its tension profile rather than only the destination chord.
     */
    private fun projectVoiceLeadingPathways(
        source: WorkspaceChordChoice,
        key: com.mecon.theory.ModulationKey,
        sourceRootPitchClass: Int?,
        followingChoice: WorkspaceChordChoice?,
    ): PracticeVoiceLeadingPathwaySectionView {
        val all = PracticeVoiceLeadingPathwayCatalog.entries(source.pitchClasses)
        // Filtering to a known destination is presentation only; the session still validates every
        // pathway against the full catalog.
        val target = followingChoice?.pitchClasses?.sorted()
        val focused = target?.let { wanted ->
            all.filter { it.pathway.targetPitchClasses == wanted }
        }.orEmpty()
        val entries = if (focused.isNotEmpty()) focused else all
        val destinationLabel = if (focused.isNotEmpty() && followingChoice != null) {
            "填充到下一个和弦 " + nonFunctionalTimelineChord(followingChoice, key)
                .let { it.absoluteSymbol ?: it.absoluteTones.joinToString("-") }
        } else ""
        val grouped = entries.groupBy { it.groupId }
        val placementOptions = listOf(
            PracticeVoiceLeadingPlacementOptionView(
                placement = PracticeVoiceLeadingPlacement.PASSING_CHORD,
                label = "作为经过和弦",
                enabled = true,
                hintLabel = "中间和弦各占一个和弦槽，可以单独改和弦与时值。",
            ),
            PracticeVoiceLeadingPlacementOptionView(
                placement = PracticeVoiceLeadingPlacement.NON_CHORD_TONE,
                label = "作为和弦外音",
                enabled = false,
                hintLabel = "把中间和弦压进声部里的延留 / 先现时值，待装饰层接入后启用。",
            ),
        )
        if (grouped.isEmpty()) {
            return PracticeVoiceLeadingPathwaySectionView(placementOptions = placementOptions)
        }
        val groups = listOf(
            PracticeVoiceLeadingPathwayCatalog.SUSPENSION_GROUP_ID to
                ("挂留" to "中间和弦没有稳定名称，听感上是强位不协和再解决。"),
            PracticeVoiceLeadingPathwayCatalog.PASSING_GROUP_ID to
                ("经过" to "中间和弦本身成立，可以作为独立的经过和弦听。"),
        ).mapNotNull { (groupId, labels) ->
            val groupEntries = grouped[groupId].orEmpty()
            if (groupEntries.isEmpty()) return@mapNotNull null
            val shown = groupEntries.take(PracticeVoiceLeadingPathwayCatalog.MAX_PER_GROUP)
            PracticeVoiceLeadingPathwayGroupView(
                id = groupId,
                titleLabel = "${labels.first} · ${shown.size}/${groupEntries.size}",
                descriptionLabel = labels.second,
                pathways = shown.map { entry ->
                    pathwayView(entry, key, sourceRootPitchClass)
                },
            )
        }
        return PracticeVoiceLeadingPathwaySectionView(
            available = groups.isNotEmpty(),
            descriptionLabel = listOfNotNull(
                PracticeVoiceLeadingPathwaySectionView().descriptionLabel,
                destinationLabel.takeIf { it.isNotEmpty() },
            ).joinToString(" · "),
            placementOptions = placementOptions,
            groups = groups,
        )
    }

    private fun pathwayView(
        entry: PracticeVoiceLeadingPathwayCatalog.Entry,
        key: com.mecon.theory.ModulationKey,
        sourceRootPitchClass: Int?,
    ): PracticeVoiceLeadingPathwayView {
        val pathway = entry.pathway
        val figuration = VoiceLeadingFigurationProjector.project(
            pathway,
            VoiceLeadingFigurationPlacement.SUSPENSION_BEFORE_TARGET,
        )
        val figurationByStep = figuration.nodes.associateBy { it.stepIndex }
        val nodes = pathway.nodes.mapIndexed { index, node ->
            val rootPitchClass = if (index == 0) {
                sourceRootPitchClass ?: entry.nodeRootPitchClasses[index]
            } else entry.nodeRootPitchClasses[index]
            val metrics = entry.profile.nodes[index]
            val changed = pathway.steps.getOrNull(index - 1)?.let { setOf(it.toPitchClass) }.orEmpty()
            PracticeVoiceLeadingPathwayNodeView(
                stepIndex = node.stepIndex,
                stability = when (node.stability) {
                    VoiceLeadingStability.STABLE -> PracticeVoiceLeadingNodeStability.STABLE
                    VoiceLeadingStability.TRANSITIONAL -> PracticeVoiceLeadingNodeStability.TRANSITIONAL
                },
                absoluteLabel = nodeSymbol(node, rootPitchClass, key, absolute = true),
                relativeLabel = nodeSymbol(node, rootPitchClass, key, absolute = false),
                tones = orderedNodeTones(node, rootPitchClass).map { pitchClass ->
                    PracticeVoiceLeadingToneView(
                        pitchClass = pitchClass,
                        absoluteLabel = pitchLabel(pitchClass, key, absolute = true),
                        relativeLabel = pitchLabel(pitchClass, key, absolute = false),
                        changed = pitchClass in changed,
                    )
                },
                tension = round2(metrics.tension),
                ambiguity = round2(metrics.ambiguity),
                figurationLabel = figurationByStep[node.stepIndex]?.nonChordTones.orEmpty()
                    .joinToString("、") { role ->
                        "${pitchLabel(role.pitchClass, key, absolute = true)} " +
                            nonChordToneLabel(role.nonChordTone)
                    },
            )
        }
        val profile = entry.profile
        return PracticeVoiceLeadingPathwayView(
            id = entry.id,
            choice = WorkspaceChordChoice.of(
                pathway.targetPitchClasses,
                preferredRootPitchClass = entry.nodeRootPitchClasses.last(),
            ),
            insertedChoices = pathway.nodes.drop(1).mapIndexed { index, node ->
                WorkspaceChordChoice.of(
                    node.pitchClasses,
                    preferredRootPitchClass = entry.nodeRootPitchClasses[index + 1],
                )
            },
            stepCount = pathway.stepCount,
            absoluteLabel = nodes.joinToString(" → ") { it.absoluteLabel },
            relativeLabel = nodes.joinToString(" → ") { it.relativeLabel },
            nodes = nodes,
            peakTension = round2(profile.peakTension),
            arc = round2(profile.arc),
            centroid = round2(profile.centroid),
            resolutionDrop = round2(profile.resolutionDrop),
            drive = round2(entry.drive),
            metricsLabel = "推动力 ${round2(entry.drive)} · 张力峰 ${round2(profile.peakTension)} · " +
                "拱形 ${round2(profile.arc)} · 解决落差 ${round2(profile.resolutionDrop)}",
            figurationTypeLabels = figuration.types.map(::nonChordToneLabel),
        )
    }

    /** Root, third, fifth (and seventh) order of the chosen reading; falls back to sorted tones. */
    private fun orderedNodeTones(node: VoiceLeadingPathNode, rootPitchClass: Int): List<Int> {
        val reading = node.readings.firstOrNull { it.rootPitchClass == rootPitchClass }
            ?: return node.pitchClasses
        return PracticeVoiceLeadingPathwayCatalog.universe.definitionOf(reading.definitionId)
            .members.map { (reading.rootPitchClass + it.semitones).mod(12) }.distinct()
    }

    private fun nodeSymbol(
        node: VoiceLeadingPathNode,
        rootPitchClass: Int,
        key: com.mecon.theory.ModulationKey,
        absolute: Boolean,
    ): String {
        val reading = node.readings.firstOrNull { it.rootPitchClass == rootPitchClass }
            ?: node.readings.first()
        return ChordSymbolFormatter.format(
            Chord(PitchClass(reading.rootPitchClass), reading.quality),
            if (absolute) ChordSymbolDisplayStyle.LETTER else ChordSymbolDisplayStyle.SCALE_DEGREE,
            key.keySignature,
        )
    }

    private fun nonChordToneLabel(type: NonChordToneType?): String = when (type) {
        NonChordToneType.SUSPENSION -> "延留音"
        NonChordToneType.RETARDATION -> "上行延留音"
        NonChordToneType.ANTICIPATION -> "先现音"
        NonChordToneType.PASSING -> "经过音"
        NonChordToneType.NEIGHBOR -> "邻音"
        NonChordToneType.APPOGGIATURA -> "倚音"
        NonChordToneType.ESCAPE -> "规避音"
        NonChordToneType.NEIGHBOR_GROUP -> "邻音组"
        NonChordToneType.SUSTAINED -> "保持音"
        NonChordToneType.PEDAL -> "持续音"
        null -> "和弦音"
    }

    private fun round2(value: Double): Double = round(value * 100) / 100

    private fun pitchLabel(
        pitchClass: Int,
        key: com.mecon.theory.ModulationKey,
        absolute: Boolean,
    ): String = if (absolute) {
        ChordSymbolFormatter.formatPitchClass(
            PitchClass(pitchClass),
            ChordSymbolDisplayStyle.LETTER,
            key.keySignature,
        )
    } else {
        ModulationPitchLabels.relativePitchLabel(key, PitchClass(pitchClass))
    }

    private fun rootMotionHint(
        motion: SchoenbergChromaticRootMotion,
        directedSemitones: Int,
    ): String {
        val interval = when (directedSemitones) {
            0 -> "同根音"
            1 -> "根音上行小二度"
            2 -> "根音上行大二度"
            3 -> "根音上行小三度"
            4 -> "根音上行大三度"
            5 -> "根音上行纯四度"
            6 -> "根音相隔三全音"
            7 -> "根音下行纯四度"
            8 -> "根音下行大三度"
            9 -> "根音下行小三度"
            10 -> "根音下行大二度"
            else -> "根音下行小二度"
        }
        val explanation = when (motion) {
            SchoenbergChromaticRootMotion.RISING -> "上升进行：连接较稳，可优先考虑。"
            SchoenbergChromaticRootMotion.DESCENDING -> "下降进行：较弱，宜由后续根音方向补偿。"
            SchoenbergChromaticRootMotion.SUPERSTRONG -> "超越进行：不保留旧根音关系，宜节省使用。"
            SchoenbergChromaticRootMotion.REPEATED -> "同根音变化：属于和声色彩改变而非根音推进。"
            SchoenbergChromaticRootMotion.UNCLASSIFIED -> "三全音根音关系不属于勋伯格三类调内根音进行。"
        }
        return "$interval；$explanation"
    }
}
