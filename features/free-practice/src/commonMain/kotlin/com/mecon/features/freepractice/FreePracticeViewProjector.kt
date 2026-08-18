package com.mecon.features.freepractice

import com.mecon.theory.freepractice.HarmonyWorkspaceState
import com.mecon.theory.freepractice.WorkspaceChordChoice
import com.mecon.theory.freepractice.WorkspaceHarmonySlot
import com.mecon.theory.freepractice.WorkspaceKeyMode
import com.mecon.theory.freepractice.WorkspaceChordTonality
import com.mecon.theory.freepractice.WorkspaceChordTonalReading
import com.mecon.theory.freepractice.WorkspaceDerivedTonalSpan
import com.mecon.theory.harmony.ChordSelectionCatalog
import com.mecon.theory.harmony.ChordSelectionChoice
import com.mecon.theory.harmony.ChordInterpretationRef
import com.mecon.theory.harmony.ChordCatalogSnapshot
import com.mecon.theory.harmony.ChordKnowledgeContext
import com.mecon.theory.harmony.HarmonyTimelineReadingProjector
import com.mecon.theory.harmony.SoundingInterpretationQuery
import com.mecon.theory.schoenberg.SchoenbergHarmonicTreatments
import com.mecon.theory.freepractice.tonalityOptions
import kotlin.math.round

/** UI-neutral read models shared by Compose and React workbench adapters. */
object FreePracticeViewProjector {
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
                    absoluteTones = choice?.absoluteTones.orEmpty(),
                    relativeTones = choice?.relativeTones.orEmpty(),
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
            )
        }
        val projectedIdiomCatalog = idiomCatalog.copy(
            definitions = idiomCatalog.definitions.map { definition ->
                definition.copy(
                    variants = definition.variants.map { variant ->
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
                    },
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
        val chordDetail = if (effectiveKey != null && detailChoice != null) {
            val context = ChordKnowledgeContext(effectiveKey.tonalContext("free-practice-chord-detail"))
            ChordCatalogSnapshot.create(
                effectiveKey,
                treatmentRegistry = SchoenbergHarmonicTreatments.registry,
            ).resolve(
                SoundingInterpretationQuery(
                    audibleKey = detailChoice.audibleKey,
                    selectedOrigin = detailChoice.origin,
                    pinnedInterpretationRef = slot?.chordChoice?.pinnedInterpretationRef
                        ?.takeIf { it in detailChoice.interpretationRefs },
                ),
                context,
            ).let { detail ->
                PracticeChordDetailProjector.map(detail, PracticeChordDetailStrings::text)
            }
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
        )
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
}
