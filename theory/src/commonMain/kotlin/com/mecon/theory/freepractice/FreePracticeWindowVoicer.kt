package com.mecon.theory.freepractice

import com.mecon.api.primitive.Fraction
import com.mecon.api.primitive.EventId
import com.mecon.api.primitive.Pitch
import com.mecon.api.primitive.TrackId
import com.mecon.api.runtime.RuntimeScore
import com.mecon.api.runtime.ScoreTimeMap
import com.mecon.theory.HarmonicTimeSpan
import com.mecon.theory.HarmonySlotId
import com.mecon.theory.DiversitySearchConfig
import com.mecon.theory.ModulationKey
import com.mecon.theory.PrefixDiversitySearchConfig
import com.mecon.theory.RuleFinding
import com.mecon.theory.SearchCancellation
import com.mecon.theory.SearchConfig
import com.mecon.theory.SlotWindow
import com.mecon.theory.TonalPlan
import com.mecon.theory.TonalSpan
import com.mecon.theory.constraint.ChordSelectionTargetCatalog
import com.mecon.theory.constraint.ChordTarget
import com.mecon.theory.constraint.ConstraintProgram
import com.mecon.theory.constraint.ConstraintProgramSolver
import com.mecon.theory.constraint.ConstraintSolveContext
import com.mecon.theory.constraint.ConstraintSolveDiagnostic
import com.mecon.theory.constraint.ConstraintSolveDiagnosticCode
import com.mecon.theory.constraint.ConstraintSolveOutcome
import com.mecon.theory.constraint.FixedVoiceBoundaryFrame
import com.mecon.theory.constraint.FreeHarmonyRequest
import com.mecon.theory.constraint.FreeHarmonySlotSpec
import com.mecon.theory.constraint.FreeHarmonySolver
import com.mecon.theory.constraint.FreeHarmonyStyle
import com.mecon.theory.constraint.HarmonicTexturePlan
import com.mecon.theory.constraint.HarmonicVoiceParticipation
import com.mecon.theory.constraint.VoiceParticipationSpan
import com.mecon.theory.constraint.VoicePitchPin
import com.mecon.theory.constraint.VoiceLeadingRelaxationPlan
import com.mecon.theory.constraint.ObservedConstraintFrame
import com.mecon.theory.constraint.PolyphonicConstraintSolution
import com.mecon.theory.constraint.VoicePitchBaseline
import com.mecon.theory.constraint.WindowFeasibilityRuleProvider
import com.mecon.theory.harmony.chordSelectionTonalContext
import com.mecon.theory.schoenberg.SchoenbergPracticeTeachingRuleProjector
import com.mecon.theory.writing.AnalyticalNoteSpan
import com.mecon.theory.writing.AnalyticalVoiceSeparator
import com.mecon.theory.writing.SourceNoteheadId

enum class WorkspaceSlotMaterialState {
    EMPTY,
    BOUNDARY_READY,
    PARTIAL_OR_COMPLEX,
}

data class WorkspaceMaterialProjection(
    val stateBySlotId: Map<WorkspaceSlotId, WorkspaceSlotMaterialState>,
    val pitchesBySlotAndVoice: Map<WorkspaceSlotId, Map<TrackId, Pitch>>,
    val eventIdsBySlotAndVoice: Map<WorkspaceSlotId, Map<TrackId, EventId>> = emptyMap(),
)

object FreePracticeMaterialProjector {
    fun project(
        workspace: HarmonyWorkspaceState,
        score: RuntimeScore,
    ): WorkspaceMaterialProjection {
        val timeMap = ScoreTimeMap.from(score)
        val notes = score.getAllVoiceEvents()
            .asSequence()
            .filterNot { it.isGrace || it.isRest }
            .flatMap { event ->
                val onset = timeMap.absolute(event.onset)
                event.pitches.asSequence().mapIndexed { pitchIndex, pitch ->
                    AnalyticalNoteSpan(
                        source = SourceNoteheadId(event.id, pitchIndex),
                        onset = onset,
                        duration = event.duration.toFraction(),
                        pitch = pitch,
                    )
                }
            }
            .toList()
        val separation = AnalyticalVoiceSeparator.separate(notes, workspace.voices.size)
        val voiceIds = workspace.voices.sortedBy { it.order }.map { it.id }
        val states = linkedMapOf<WorkspaceSlotId, WorkspaceSlotMaterialState>()
        val pitches = linkedMapOf<WorkspaceSlotId, Map<TrackId, Pitch>>()
        val eventIds = linkedMapOf<WorkspaceSlotId, Map<TrackId, EventId>>()
        workspace.slots.forEach { slot ->
            val end = slot.onset + slot.duration
            val overlapping = notes.filter { it.onset < end && it.end > slot.onset }
            if (overlapping.isEmpty()) {
                states[slot.id] = WorkspaceSlotMaterialState.EMPTY
                return@forEach
            }
            val atRightBoundary = overlapping.filter { it.onset < end && it.end >= end }
            val mapped = atRightBoundary.mapNotNull { note ->
                separation.voiceByNotehead[note.source]?.let { voiceIndex ->
                    voiceIds.getOrNull(voiceIndex)?.let { voiceId ->
                        Triple(voiceId, note.pitch, note.source.eventId)
                    }
                }
            }
            val complete = separation.unassigned.none { source ->
                overlapping.any { it.source == source }
            } && mapped.size == voiceIds.size && mapped.map { it.first }.distinct().size == voiceIds.size
            if (complete) {
                states[slot.id] = WorkspaceSlotMaterialState.BOUNDARY_READY
                pitches[slot.id] = mapped.associate { (voiceId, pitch) -> voiceId to pitch }
                eventIds[slot.id] = mapped.associate { (voiceId, _, eventId) -> voiceId to eventId }
            } else {
                states[slot.id] = WorkspaceSlotMaterialState.PARTIAL_OR_COMPLEX
            }
        }
        return WorkspaceMaterialProjection(states, pitches, eventIds)
    }
}

enum class PracticeWritingTrigger {
    AUTOMATIC_CHORD_CHANGE,
    IDIOM_CHANGE,
    SELECTION_REWRITE,
    ALTERNATE,
}

data class PracticeWritingScope(
    val slotIds: List<WorkspaceSlotId>,
    val triggerSlotId: WorkspaceSlotId,
    val leftBoundarySlotId: WorkspaceSlotId?,
    val trigger: PracticeWritingTrigger,
) {
    init {
        require(slotIds.isNotEmpty())
        require(triggerSlotId in slotIds)
    }
}

object PracticeWritingScopePlanner {
    fun automatic(
        workspace: HarmonyWorkspaceState,
        projection: WorkspaceMaterialProjection,
        triggerSlotId: WorkspaceSlotId,
        configuredBacktrack: Int,
    ): PracticeWritingScope? {
        require(configuredBacktrack >= 0)
        val triggerIndex = workspace.slots.indexOfFirst { it.id == triggerSlotId }
        if (triggerIndex < 0 || workspace.slots[triggerIndex].chordChoice == null) return null
        var start = triggerIndex
        repeat(configuredBacktrack) {
            val previous = workspace.slots.getOrNull(start - 1)
            if (previous?.chordChoice == null) return@repeat
            start--
        }
        start = includeEmptySelectedPredecessors(workspace, projection, start)
        val boundaryIndex = start - 1
        val boundarySlot = workspace.slots.getOrNull(boundaryIndex)
        val boundaryId = boundarySlot?.id?.takeIf {
            boundarySlot.chordChoice != null &&
                projection.stateBySlotId[it] == WorkspaceSlotMaterialState.BOUNDARY_READY
        }
        return PracticeWritingScope(
            slotIds = workspace.slots.subList(start, triggerIndex + 1).map { it.id },
            triggerSlotId = triggerSlotId,
            leftBoundarySlotId = boundaryId,
            trigger = PracticeWritingTrigger.AUTOMATIC_CHORD_CHANGE,
        )
    }

    fun selected(
        workspace: HarmonyWorkspaceState,
        selectedSlotIds: Set<WorkspaceSlotId>,
        projection: WorkspaceMaterialProjection,
    ): PracticeWritingScope? {
        val indices = workspace.slots.indices.filter { workspace.slots[it].id in selectedSlotIds }
        if (indices.isEmpty()) return null
        val start = indices.first()
        val end = indices.last()
        if (indices != (start..end).toList()) return null
        val slots = workspace.slots.subList(start, end + 1)
        if (slots.any { it.chordChoice == null }) return null
        val boundaryId = workspace.slots.getOrNull(start - 1)?.id?.takeIf {
            projection.stateBySlotId[it] == WorkspaceSlotMaterialState.BOUNDARY_READY
        }
        return PracticeWritingScope(
            slotIds = slots.map { it.id },
            triggerSlotId = slots.last().id,
            leftBoundarySlotId = boundaryId,
            trigger = PracticeWritingTrigger.SELECTION_REWRITE,
        )
    }

    fun idiom(
        workspace: HarmonyWorkspaceState,
        slotIds: List<WorkspaceSlotId>,
        projection: WorkspaceMaterialProjection,
    ): PracticeWritingScope? {
        if (slotIds.isEmpty() || slotIds.distinct().size != slotIds.size) return null
        val requested = slotIds.toSet()
        val indices = workspace.slots.indices.filter { workspace.slots[it].id in requested }
        if (indices.size != slotIds.size) return null
        var start = indices.first()
        val end = indices.last()
        if (indices != (start..end).toList()) return null
        val requestedSlots = workspace.slots.subList(start, end + 1)
        if (requestedSlots.any { it.chordChoice == null }) return null
        start = includeEmptySelectedPredecessors(workspace, projection, start)
        val slots = workspace.slots.subList(start, end + 1)
        val boundaryId = workspace.slots.getOrNull(start - 1)?.id?.takeIf {
            projection.stateBySlotId[it] == WorkspaceSlotMaterialState.BOUNDARY_READY
        }
        return PracticeWritingScope(
            slotIds = slots.map { it.id },
            triggerSlotId = slots.last().id,
            leftBoundarySlotId = boundaryId,
            trigger = PracticeWritingTrigger.IDIOM_CHANGE,
        )
    }

    private fun includeEmptySelectedPredecessors(
        workspace: HarmonyWorkspaceState,
        projection: WorkspaceMaterialProjection,
        initialStart: Int,
    ): Int {
        var start = initialStart
        while (start > 0) {
            val previous = workspace.slots[start - 1]
            if (previous.chordChoice == null ||
                projection.stateBySlotId[previous.id] != WorkspaceSlotMaterialState.EMPTY
            ) break
            start--
        }
        return start
    }
}

data class FreePracticeWindowSolveResult(
    val scope: PracticeWritingScope,
    val program: ConstraintProgram?,
    val outcome: ConstraintSolveOutcome,
    val segments: List<PracticeWritingSegment> = emptyList(),
)

data class PracticeWritingSegment(
    val id: HarmonySlotId,
    val workspaceSlotId: WorkspaceSlotId,
    val onset: Fraction,
    val duration: Fraction,
)

data class PracticeWritingLockRules(
    val lockedNoteheads: Set<SourceNoteheadId> = emptySet(),
    val lockedVoiceTrackIds: Set<TrackId> = emptySet(),
    val lockedStaffTrackIds: Set<TrackId> = emptySet(),
    val explicitChordTones: Set<SourceNoteheadId> = emptySet(),
)

data class FreePracticeCandidateSession(
    val scope: PracticeWritingScope,
    val workspace: HarmonyWorkspaceState,
    val fallbackKey: ModulationKey,
    val candidates: List<PolyphonicConstraintSolution>,
    val nextCandidateIndex: Int = 1,
) {
    val primary: PolyphonicConstraintSolution? get() = candidates.firstOrNull()
    val nextCandidate: PolyphonicConstraintSolution? get() = candidates.getOrNull(nextCandidateIndex)
    val hasNextCandidate: Boolean get() = nextCandidateIndex < candidates.size

    fun advance(): FreePracticeCandidateSession = copy(nextCandidateIndex = nextCandidateIndex + 1)

    fun merge(additional: List<PolyphonicConstraintSolution>): FreePracticeCandidateSession = copy(
        candidates = (candidates + additional).distinctBy { it.diversityGroupKey },
    )
}

/** Search budgets calibrated for the interactive free-writing pipeline. */
object FreePracticeSearchPolicy {
    /** Publish a legal first realization quickly; the background pass owns quality optimization. */
    val initial: SearchConfig = SearchConfig(
        maxResults = 1,
        beamWidth = 12,
        prefixDiversity = PrefixDiversitySearchConfig(
            enabled = true,
            frontierWidth = 8,
        ),
    )

    /** Compare a wider set of prefixes, then spend the remaining budget on directed restarts. */
    fun optimization(seed: Long): SearchConfig = SearchConfig(
        maxResults = 4,
        beamWidth = 24,
        prefixDiversity = PrefixDiversitySearchConfig(
            enabled = true,
            frontierWidth = 24,
        ),
        diversity = DiversitySearchConfig(
            enabled = true,
            seed = seed,
            penaltyMutationBias = 2.0,
        ),
    )
}

private data class PreparedPracticeWindow(
    val slots: List<WorkspaceHarmonySlot>,
    val segments: List<PracticeWritingSegment>,
    val allowedBySlot: List<List<ChordTarget>>,
    val program: ConstraintProgram,
    val projection: WorkspaceMaterialProjection,
    val boundary: FixedVoiceBoundaryFrame?,
    val relaxationPlan: VoiceLeadingRelaxationPlan,
)

private data class LockedPracticeSpan(
    val source: SourceNoteheadId,
    val voiceId: TrackId,
    val onset: Fraction,
    val end: Fraction,
    val pitch: Pitch,
    val explicitlyChordTone: Boolean,
)

private sealed interface PracticeWindowPreparation {
    data class Prepared(val window: PreparedPracticeWindow) : PracticeWindowPreparation
    data class Rejected(val message: String) : PracticeWindowPreparation

    val preparedWindow: PreparedPracticeWindow? get() = (this as? Prepared)?.window
}

object FreePracticeWindowVoicer {
    fun solve(
        workspace: HarmonyWorkspaceState,
        score: RuntimeScore,
        scope: PracticeWritingScope,
        fallbackKey: ModulationKey,
        searchConfig: SearchConfig = SearchConfig(),
        context: ConstraintSolveContext = ConstraintSolveContext(),
        lockRules: PracticeWritingLockRules = PracticeWritingLockRules(),
        teachingRuleProjector: PracticeTeachingRuleProjector =
            SchoenbergPracticeTeachingRuleProjector,
    ): FreePracticeWindowSolveResult {
        val preparation = prepare(
            workspace = workspace,
            score = score,
            scope = scope,
            fallbackKey = fallbackKey,
            searchConfig = searchConfig,
            teachingRuleProjector = teachingRuleProjector,
            lockRules = lockRules,
        )
        val prepared = when (preparation) {
            is PracticeWindowPreparation.Rejected -> return invalid(scope, preparation.message)
            is PracticeWindowPreparation.Prepared -> preparation.window
        }
        val baseline = prepared.projection.pitchesBySlotAndVoice
            .filterKeys(scope.slotIds::contains)
            .mapKeys { (id, _) -> HarmonySlotId(id.value) }
            .takeIf { it.isNotEmpty() }
            ?.let(::VoicePitchBaseline)
        val projectedBaseline = baseline.takeIf { context.preserveProjectedBaseline }
        val firstContext = context.copy(
            leftBoundary = prepared.boundary ?: context.leftBoundary,
            baseline = context.baseline ?: projectedBaseline,
            voiceLeadingRelaxation = context.voiceLeadingRelaxation.merge(prepared.relaxationPlan),
        )
        val first = ConstraintProgramSolver.solvePolyphonicOutcome(prepared.program, firstContext)
        val outcome = if (shouldRelaxBoundary(first, prepared.boundary)) {
            ConstraintProgramSolver.solvePolyphonicOutcome(
                prepared.program,
                firstContext.copy(relaxBoundaryLargeLeaps = true),
            )
        } else {
            first
        }
        return FreePracticeWindowSolveResult(
            scope,
            prepared.program,
            validateSolvedTargets(outcome, prepared.allowedBySlot),
            prepared.segments,
        )
    }

    /**
     * Checks every contiguous, fully realized workspace run with the normal free-writing program.
     *
     * This compiles a program per run and evaluates the full rule set, so callers must treat it as
     * a background task and pass a [cancellation] they can trip when the workspace moves on.
     */
    fun check(
        workspace: HarmonyWorkspaceState,
        score: RuntimeScore,
        fallbackKey: ModulationKey,
        cancellation: SearchCancellation = SearchCancellation.NONE,
        teachingRuleProjector: PracticeTeachingRuleProjector =
            SchoenbergPracticeTeachingRuleProjector,
    ): List<RuleFinding<EventId>> {
        val projection = FreePracticeMaterialProjector.project(workspace, score)
        val runs = buildList {
            var current = mutableListOf<WorkspaceHarmonySlot>()
            fun flush() {
                if (current.isNotEmpty()) add(current.toList())
                current = mutableListOf()
            }
            workspace.slots.forEach { slot ->
                if (
                    slot.chordChoice != null &&
                    projection.stateBySlotId[slot.id] == WorkspaceSlotMaterialState.BOUNDARY_READY
                ) {
                    current += slot
                } else {
                    flush()
                }
            }
            flush()
        }
        return runs.flatMap { slots ->
            if (cancellation.isCancelled()) return emptyList()
            val scope = PracticeWritingScope(
                slotIds = slots.map { it.id },
                triggerSlotId = slots.last().id,
                leftBoundarySlotId = null,
                trigger = PracticeWritingTrigger.SELECTION_REWRITE,
            )
            val prepared = prepare(
                workspace = workspace,
                score = score,
                scope = scope,
                fallbackKey = fallbackKey,
                searchConfig = SearchConfig(),
                teachingRuleProjector = teachingRuleProjector,
                projection = projection,
            ).preparedWindow ?: return@flatMap emptyList()
            val observed = prepared.slots.mapIndexed { index, slot ->
                val pitches = requireNotNull(projection.pitchesBySlotAndVoice[slot.id])
                val target = prepared.allowedBySlot[index].firstExplaining(pitches)
                    ?: return@flatMap emptyList()
                ObservedConstraintFrame(
                    slotIndex = index,
                    target = target,
                    pitchesByVoiceId = pitches,
                    sourceEventIdsByVoiceId =
                        projection.eventIdsBySlotAndVoice[slot.id].orEmpty(),
                )
            }
            ConstraintProgramSolver.checkObserved(
                prepared.program,
                observed,
                ConstraintSolveContext(cancellation = cancellation),
            ).findings
        }.distinctBy { finding ->
            Triple(finding.ruleId, finding.kind, finding.anchors)
        }
    }

    private fun prepare(
        workspace: HarmonyWorkspaceState,
        score: RuntimeScore,
        scope: PracticeWritingScope,
        fallbackKey: ModulationKey,
        searchConfig: SearchConfig,
        teachingRuleProjector: PracticeTeachingRuleProjector,
        projection: WorkspaceMaterialProjection = FreePracticeMaterialProjector.project(workspace, score),
        lockRules: PracticeWritingLockRules = PracticeWritingLockRules(),
    ): PracticeWindowPreparation {
        val slotsById = workspace.slots.associateBy { it.id }
        val slots = scope.slotIds.map { id ->
            slotsById[id] ?: return PracticeWindowPreparation.Rejected(
                "写作范围包含已失效的和弦槽 ${id.value}。",
            )
        }
        if (slots.any { it.chordChoice == null }) {
            return PracticeWindowPreparation.Rejected("写作范围内存在未选择的和弦。")
        }
        val timeMap = ScoreTimeMap.from(score)
        val staffLockedVoiceIds = score.staffTracks.values
            .filter { it.id in lockRules.lockedStaffTrackIds }
            .flatMapTo(linkedSetOf()) { staff -> staff.voiceTracks.map { it.id } }
        val continuouslyLockedVoiceIds = lockRules.lockedVoiceTrackIds + staffLockedVoiceIds
        val lockedSpans = score.voiceTracks.values.flatMap { voice ->
            voice.events.toList().filterNot { it.isGrace || it.isRest }.flatMap { event ->
                val onset = timeMap.absolute(event.onset)
                val end = onset + event.duration.toFraction()
                event.pitches.mapIndexedNotNull { pitchIndex, pitch ->
                    val source = SourceNoteheadId(event.id, pitchIndex)
                    val dynamicallyLocked = voice.id in continuouslyLockedVoiceIds
                    if (!dynamicallyLocked && source !in lockRules.lockedNoteheads) null else LockedPracticeSpan(
                        source, voice.id, onset, end, pitch,
                        source in lockRules.explicitChordTones,
                    )
                }
            }
        }
        val scopeStart = slots.first().onset
        val scopeEnd = slots.last().onset + slots.last().duration
        val boundaries = buildSet {
            add(scopeStart)
            add(scopeEnd)
            slots.forEach { slot -> add(slot.onset); add(slot.onset + slot.duration) }
            lockedSpans.forEach { span ->
                if (span.onset > scopeStart && span.onset < scopeEnd) add(span.onset)
                if (span.end > scopeStart && span.end < scopeEnd) add(span.end)
            }
        }.sorted()
        val segments = boundaries.zipWithNext().mapNotNull { (onset, end) ->
            val sourceSlot = slots.firstOrNull { onset >= it.onset && onset < it.onset + it.duration }
                ?: return@mapNotNull null
            PracticeWritingSegment(
                id = HarmonySlotId("${sourceSlot.id.value}@${onset.numerator}_${onset.denominator}"),
                workspaceSlotId = sourceSlot.id,
                onset = onset,
                duration = end - onset,
            )
        }
        val segmentSlots = segments.map { segment -> slotsById.getValue(segment.workspaceSlotId) }
        val readingsBySlot = segmentSlots.map { slot ->
            workspace.harmonicTonalReadings(slot).ifEmpty {
                listOf(WorkspaceChordTonalReading.of(fallbackKey))
            }
        }
        val targetCatalogByKey = (
            workspace.tonalLayouts.map { it.key } +
                workspace.slots.flatMap { slot ->
                    workspace.harmonicTonalReadings(slot).map(WorkspaceChordTonalReading::key)
                } + fallbackKey
            ).distinct().associateWith(ChordSelectionTargetCatalog::targets)
        val allTargets = targetCatalogByKey.values.flatten()
        val targetsByWorkspaceSlot = workspace.slots.mapNotNull { slot ->
            val choice = slot.chordChoice ?: return@mapNotNull null
            val readings = workspace.harmonicTonalReadings(slot).ifEmpty {
                listOf(WorkspaceChordTonalReading.of(fallbackKey))
            }
            slot.id to readings.flatMap { reading ->
                targetCatalogByKey[reading.key].orEmpty().matchingWorkspaceChordTargets(
                    key = reading.key,
                    choice = choice,
                    interpretationRef = if (slot.tonality != null) {
                        reading.interpretationRef
                    } else {
                        choice.pinnedInterpretationRef
                    },
                )
            }.distinctBy(ChordTarget::identityKey)
        }.toMap()
        val allowed = segmentSlots.map { targetsByWorkspaceSlot[it.id].orEmpty() }
        if (allowed.any { it.isEmpty() }) {
            val failed = allowed.indexOfFirst { it.isEmpty() }
            return PracticeWindowPreparation.Rejected(
                "和弦槽 ${segmentSlots[failed].id.value} 没有可用的求解解释。",
            )
        }
        val pins = mutableListOf<VoicePitchPin>()
        val participations = mutableListOf<VoiceParticipationSpan>()
        val pinnedBySegment = mutableListOf<Map<TrackId, LockedPracticeSpan>>()
        for ((index, segment) in segments.withIndex()) {
            val active = lockedSpans.filter {
                it.onset <= segment.onset && it.end >= segment.onset + segment.duration
            }
            val byVoice = active.groupBy { it.voiceId }
            if (byVoice.any { (_, spans) -> spans.map { it.pitch.midiNumber }.distinct().size > 1 }) {
                return PracticeWindowPreparation.Rejected(
                    "A locked voice contains multiple simultaneous pitches in one writing segment",
                )
            }
            val resolved = byVoice.mapValues { it.value.first() }
            val choice = requireNotNull(segmentSlots[index].chordChoice)
            resolved.forEach { (voiceId, span) ->
                if (span.explicitlyChordTone && span.pitch.pitchClass.value !in choice.pitchClasses) {
                    return PracticeWindowPreparation.Rejected(
                        "An explicit chord-tone mark conflicts with the selected chord",
                    )
                }
                pins += VoicePitchPin(index, voiceId, span.pitch)
                if (span.pitch.pitchClass.value !in choice.pitchClasses) {
                    participations += VoiceParticipationSpan(
                        SlotWindow(index, index),
                        voiceId,
                        HarmonicVoiceParticipation.Sustained(span.pitch),
                    )
                }
            }
            pinnedBySegment += resolved
        }
        val bassVoiceId = workspace.voices.maxByOrNull { it.order }?.id
        val relaxedEdges = linkedMapOf<Int, MutableSet<TrackId>>()
        for (index in 1 until pinnedBySegment.size) {
            val previous = pinnedBySegment[index - 1]
            pinnedBySegment[index].forEach { (voiceId, span) ->
                val before = previous[voiceId] ?: return@forEach
                if (voiceId != bassVoiceId && kotlin.math.abs(
                        span.pitch.midiNumber - before.pitch.midiNumber,
                    ) > 12
                ) {
                    for (edge in (index - 1)..(index + 1)) {
                        if (edge in 1 until segments.size) {
                            relaxedEdges.getOrPut(edge) { linkedSetOf() } += voiceId
                        }
                    }
                }
            }
        }
        val tonalPlan = TonalPlan(
            readingsBySlot.flatMapIndexed { index, readings ->
                readings.map { reading ->
                    TonalSpan(SlotWindow(index, index), reading.key.chordSelectionTonalContext())
                }
            },
        )
        val teachingConstraints = if (segments.size == slots.size) teachingRuleProjector.project(
            PracticeTeachingRuleRequest(
                workspace = workspace,
                scope = scope,
                targetsBySlotId = targetsByWorkspaceSlot,
                fallbackKey = fallbackKey,
                searchConfig = searchConfig,
            )
        ) else emptyList()
        val request = FreeHarmonyRequest(
            key = readingsBySlot.first().first().key.key,
            tonalPlan = tonalPlan,
            slotCount = segments.size,
            slotSpecs = segments.map { segment ->
                FreeHarmonySlotSpec(
                    id = segment.id,
                    time = HarmonicTimeSpan(timeMap.timeCodeAt(segment.onset), segment.duration),
                )
            },
            vocabulary = allTargets,
            voicePlan = workspace.voicePlan,
            style = FreeHarmonyStyle.CLASSICAL,
            allowedTargetIdentityKeysBySlot = allowed.mapIndexed { index, targets ->
                index to targets.mapTo(linkedSetOf(), ChordTarget::identityKey)
            }.toMap(),
            pitchPins = pins,
            texturePlan = HarmonicTexturePlan(participations),
            additionalConstraints = teachingConstraints,
            searchConfig = searchConfig.copy(
                prefixDiversity = searchConfig.prefixDiversity.copy(enabled = true),
            ),
        )
        val program = try {
            FreeHarmonySolver.compile(request)
        } catch (error: IllegalArgumentException) {
            return PracticeWindowPreparation.Rejected(error.message ?: "写作请求无效。")
        }
        val boundary = resolveBoundary(workspace, projection, scope, fallbackKey, allTargets)
        return PracticeWindowPreparation.Prepared(
            PreparedPracticeWindow(
                slots = slots,
                segments = segments,
                allowedBySlot = allowed,
                program = program,
                projection = projection,
                boundary = boundary,
                relaxationPlan = VoiceLeadingRelaxationPlan(relaxedEdges),
            )
        )
    }

    private fun resolveBoundary(
        workspace: HarmonyWorkspaceState,
        projection: WorkspaceMaterialProjection,
        scope: PracticeWritingScope,
        fallbackKey: ModulationKey,
        allTargets: List<ChordTarget>,
    ): FixedVoiceBoundaryFrame? {
        val id = scope.leftBoundarySlotId ?: return null
        val slot = workspace.slots.firstOrNull { it.id == id } ?: return null
        val pitches = projection.pitchesBySlotAndVoice[id] ?: return null
        val choice = slot.chordChoice ?: return null
        val target = workspace.harmonicTonalReadings(slot)
            .ifEmpty { listOf(WorkspaceChordTonalReading.of(fallbackKey)) }
            .asSequence()
            .flatMap { reading ->
                allTargets.matchingWorkspaceChordTargets(
                    key = reading.key,
                    choice = choice,
                    interpretationRef = if (slot.tonality != null) {
                        reading.interpretationRef
                    } else {
                        choice.pinnedInterpretationRef
                    },
                ).asSequence()
            }
            .toList()
            .firstExplaining(pitches)
            ?: return null
        return FixedVoiceBoundaryFrame(target, pitches)
    }

    private fun validateSolvedTargets(
        outcome: ConstraintSolveOutcome,
        allowedBySlot: List<List<ChordTarget>>,
    ): ConstraintSolveOutcome {
        if (outcome !is ConstraintSolveOutcome.Solved) return outcome
        val allowedIdentityKeys = allowedBySlot.map { targets ->
            targets.mapTo(hashSetOf(), ChordTarget::identityKey)
        }
        val invalid = outcome.solutions.any { solution ->
            solution.voicings.size != allowedIdentityKeys.size ||
                solution.voicings.any { voicing ->
                    voicing.slotIndex !in allowedIdentityKeys.indices ||
                        voicing.target.identityKey() !in allowedIdentityKeys[voicing.slotIndex]
                }
        }
        return if (invalid) {
            ConstraintSolveOutcome.Invalid(
                diagnostics = listOf(
                    ConstraintSolveDiagnostic(
                        ConstraintSolveDiagnosticCode.INVALID_REQUEST,
                        "求解结果包含不属于所选和弦槽目标域的解释。",
                    )
                ),
                trace = outcome.trace,
            )
        } else {
            outcome
        }
    }

    private fun shouldRelaxBoundary(
        outcome: ConstraintSolveOutcome,
        boundary: FixedVoiceBoundaryFrame?,
    ): Boolean {
        if (boundary == null || outcome !is ConstraintSolveOutcome.NoSolution) return false
        val reasons = outcome.trace.entries.flatMap { it.hardViolations }.distinct()
        return reasons.isNotEmpty() && reasons.all {
            it.startsWith(WindowFeasibilityRuleProvider.SIMULTANEOUS_LARGE_LEAPS.value)
        }
    }

    private fun invalid(scope: PracticeWritingScope, message: String) =
        FreePracticeWindowSolveResult(
            scope,
            null,
            ConstraintSolveOutcome.Invalid(
                listOf(
                    ConstraintSolveDiagnostic(
                        ConstraintSolveDiagnosticCode.INVALID_REQUEST,
                        message,
                    )
                )
            ),
        )
}
