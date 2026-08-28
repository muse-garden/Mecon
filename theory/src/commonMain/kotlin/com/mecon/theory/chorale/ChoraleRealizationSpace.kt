package com.mecon.theory.chorale

import com.mecon.api.primitive.EventId
import com.mecon.api.primitive.Pitch
import com.mecon.api.primitive.TimeCode
import com.mecon.theory.FixedVoiceRole
import com.mecon.theory.RuleFinding
import com.mecon.theory.RuleFindingKind
import com.mecon.theory.RuleId
import com.mecon.theory.RuleSeverity
import com.mecon.theory.ScoreBreakdown
import com.mecon.theory.ScoredCandidateSpace
import com.mecon.theory.VoiceBoundary
import com.mecon.theory.VoiceRange
import com.mecon.theory.WritingTask
import com.mecon.theory.constraint.ChordVoicing
import com.mecon.theory.voiceleading.StandardVoiceLeadingUniverses
import com.mecon.theory.voiceleading.VoiceLeadingTension
import kotlin.math.abs
import kotlin.math.max

internal object ChoraleRules {
    val FIGURATION = RuleId("chorale.figuration")
    val REQUESTED_CONFLICT = RuleId("chorale.requested-conflict")
    val MISSING_CONFLICT = RuleId("chorale.missing-conflict")
    val UNREQUESTED_TENSION = RuleId("chorale.unrequested-tension")
    val SURFACE_PARALLEL = RuleId("chorale.surface-parallel")
    val CONTOUR = RuleId("chorale.contour")
    val DENSITY = RuleId("chorale.density")
}

internal data class ChoraleState(
    val spanIndex: Int,
    val fillsByRole: Map<FixedVoiceRole, List<ChoraleSpanFill>>,
) {
    fun notes(role: FixedVoiceRole): List<ChoraleNote> =
        fillsByRole.getValue(role).flatMap { it.notes }
}

internal data class ChoraleSpanChoice(
    val byRole: Map<FixedVoiceRole, ChoraleSpanFill>,
)

/**
 * Stage two: choose, span by span, how every voice fills the harmonic span the skeleton fixed.
 *
 * The state advances one harmonic span at a time and decides all voices together, because the
 * surface checks (parallels, the tension curve) are joint even though the fillings are enumerated
 * per voice.
 */
internal class ChoraleRealizationSpace(
    private val task: ChoraleTask,
    private val skeleton: List<ChordVoicing>,
    private val ranges: Map<FixedVoiceRole, VoiceRange>,
) : ScoredCandidateSpace<ChoraleState, ChoraleSpanChoice> {

    private val universe = StandardVoiceLeadingUniverses.TERTIAN_WITH_SUSPENSIONS
    private val roles = task.voices.map { it.role }
    private val suspensionRequests = task.figuration.filter { it.requiresSuspension }

    override fun initial(task: WritingTask): ChoraleState =
        ChoraleState(0, roles.associateWith { emptyList() })

    override fun isComplete(state: ChoraleState, task: WritingTask): Boolean =
        state.spanIndex >= skeleton.size

    override fun candidates(state: ChoraleState, task: WritingTask): List<ChoraleSpanChoice> {
        val slot = state.spanIndex
        val perRole = roles.map { role ->
            role to ChoraleSpanFilling.fillings(
                context = spanContext(role, slot),
                patterns = this.task.voices.first { it.role == role }.patterns,
                limit = this.task.maxFillingsPerVoiceSpan,
            )
        }
        if (perRole.any { it.second.isEmpty() }) return emptyList()
        var combinations = listOf(emptyMap<FixedVoiceRole, ChoraleSpanFill>())
        perRole.forEach { (role, fills) ->
            combinations = combinations.flatMap { partial ->
                fills.map { fill -> partial + (role to fill) }
            }
            if (combinations.size > MAX_SPAN_COMBINATIONS) {
                combinations = combinations
                    .sortedBy { partial -> partial.values.sumOf { it.figurationCount } }
                    .take(MAX_SPAN_COMBINATIONS)
            }
        }
        return combinations
            .map(::ChoraleSpanChoice)
            .filter { choice -> withinSpanDensity(choice) }
    }

    override fun apply(state: ChoraleState, candidate: ChoraleSpanChoice): ChoraleState =
        ChoraleState(
            spanIndex = state.spanIndex + 1,
            fillsByRole = state.fillsByRole.mapValues { (role, fills) ->
                fills + candidate.byRole.getValue(role)
            },
        )

    override fun score(state: ChoraleState, task: WritingTask): ScoreBreakdown {
        val findings = mutableListOf<RuleFinding<EventId>>()
        var total = 0.0
        val policy = this.task.scoringPolicy

        state.fillsByRole.forEach { (role, fills) ->
            val figures = fills.flatMap { it.notes }.filter { it.nonChordTone != null }
            if (figures.size > this.task.density.maxPerVoice) {
                findings += RuleFinding(
                    ruleId = ChoraleRules.DENSITY,
                    kind = RuleFindingKind.VIOLATION,
                    severity = RuleSeverity.HARD,
                    message = "${role.name} 的装饰音超过密度预算 ${this.task.density.maxPerVoice}。",
                )
            }
            figures.filter { note -> answersARequest(role, note) }.forEach { note ->
                total -= policy.requestedFigurationBonus
                findings += RuleFinding(
                    ruleId = ChoraleRules.FIGURATION,
                    kind = RuleFindingKind.INDICATION,
                    severity = RuleSeverity.HINT,
                    message = "${role.name} 在第 ${note.slot} 槽写出了要求的" +
                        "${note.nonChordTone?.abbreviation}。",
                )
            }
            fills.forEach { fill ->
                val answered = fill.notes.count { answersARequest(role, it) }
                total += policy.activityCost * (fill.notes.size - 1 - answered).coerceAtLeast(0)
            }
        }

        val curve = tensionCurve(state)
        val arcs = tensionArcs(curve)
        val requestedSlots = this.task.figuration.mapTo(hashSetOf()) { it.slot }
        arcs.forEach { arc ->
            if (arc.slot in requestedSlots) {
                if (arc.arc > 0.0) {
                    total -= policy.requestedArcBonus * arc.arc
                } else {
                    total += policy.missingArcPenalty
                    findings += RuleFinding(
                        ruleId = ChoraleRules.MISSING_CONFLICT,
                        kind = RuleFindingKind.VIOLATION,
                        severity = RuleSeverity.SOFT,
                        message = "第 ${arc.slot} 槽被标为冲突位，但张力没有形成拱形。",
                    )
                }
            } else if (arc.arc > 0.0) {
                total += policy.unrequestedArcPenalty * arc.arc
                findings += RuleFinding(
                    ruleId = ChoraleRules.UNREQUESTED_TENSION,
                    kind = RuleFindingKind.HINT,
                    severity = RuleSeverity.HINT,
                    message = "第 ${arc.slot} 槽出现未被要求的张力起伏。",
                )
            }
        }

        // The surface can only be judged once every voice has finished; a partial line has no
        // parallels and no contour yet.
        if (isComplete(state, task)) {
            this.task.figuration.filter { it.required }.forEach { request ->
                if (!satisfies(state, request)) {
                    findings += RuleFinding(
                        ruleId = ChoraleRules.REQUESTED_CONFLICT,
                        kind = RuleFindingKind.VIOLATION,
                        severity = RuleSeverity.HARD,
                        message = "第 ${request.slot} 槽要求的外音没有写出来。",
                    )
                }
            }
            surfaceParallels(state).forEach { message ->
                findings += RuleFinding(
                    ruleId = ChoraleRules.SURFACE_PARALLEL,
                    kind = RuleFindingKind.VIOLATION,
                    severity = RuleSeverity.HARD,
                    message = message,
                )
            }
        }
        // Contour is a property of the skeleton, which stage two cannot change; it is scored when
        // the skeleton is chosen (ChoraleHarmonizer) so that it can actually steer something.
        return ScoreBreakdown(total = total, findings = findings)
    }

    override fun diversityKey(state: ChoraleState): String =
        state.fillsByRole.entries.sortedBy { it.key.name }.joinToString("|") { (role, fills) ->
            role.name.first() + fills.joinToString(",") { fill ->
                fill.signature + fill.notes.joinToString("+") { it.pitch.midiNumber.toString() }
            }
        }

    override fun diversityGroupKey(state: ChoraleState): String =
        state.fillsByRole.entries.sortedBy { it.key.name }.joinToString("|") { (role, fills) ->
            role.name.first() + fills.joinToString(",") { it.signature }
        }

    fun realization(state: ChoraleState, breakdown: ScoreBreakdown): ChoraleRealization {
        val curve = tensionCurve(state)
        return ChoraleRealization(
            skeleton = skeleton,
            lines = roles.map { role -> ChoraleLine(role, state.notes(role)) },
            tensionCurve = curve,
            tensionArcs = tensionArcs(curve),
            breakdown = breakdown,
        )
    }

    // ---- context ----------------------------------------------------------------------------

    private fun spanContext(role: FixedVoiceRole, slot: Int): ChoraleSpanContext {
        val program = task.skeleton
        val constraintSlot = program.slots[slot]
        return ChoraleSpanContext(
            slot = slot,
            onset = constraintSlot.time.onset,
            duration = constraintSlot.time.duration,
            meterPlan = program.meterPlan,
            key = program.key,
            range = ranges.getValue(role),
            boundary = when (role) {
                FixedVoiceRole.SOPRANO -> VoiceBoundary.UPPER_OUTER
                FixedVoiceRole.BASS -> VoiceBoundary.LOWER_OUTER
                else -> null
            },
            current = skeleton[slot].pitchOf(role),
            previous = skeleton.getOrNull(slot - 1)?.pitchOf(role),
            next = skeleton.getOrNull(slot + 1)?.pitchOf(role),
            chord = skeleton[slot].target.sonority,
            previousChord = skeleton.getOrNull(slot - 1)?.target?.sonority,
            nextChord = skeleton.getOrNull(slot + 1)?.target?.sonority,
            suspensionRequired = suspensionRequests.any { it.slot == slot && it.role == role },
        )
    }

    // ---- scoring helpers --------------------------------------------------------------------

    private fun withinSpanDensity(choice: ChoraleSpanChoice): Boolean =
        choice.byRole.values.sumOf { it.figurationCount } <= task.density.maxPerSpan

    private fun answersARequest(role: FixedVoiceRole, note: ChoraleNote): Boolean =
        task.figuration.any { request ->
            request.slot == note.slot &&
                (request.role == null || request.role == role) &&
                note.nonChordTone in request.types
        }

    private fun satisfies(state: ChoraleState, request: ChoraleFigurationRequest): Boolean =
        state.fillsByRole.any { (role, fills) ->
            if (request.role != null && request.role != role) return@any false
            fills.flatMap { it.notes }.any { note ->
                note.slot == request.slot && note.nonChordTone in request.types
            }
        }

    /**
     * Tension of every sounding vertical.
     *
     * Voices attack at different moments once they have their own rhythms, so the vertical is
     * rebuilt at each attack point from whatever every voice is holding at that moment.
     */
    private fun tensionCurve(state: ChoraleState): List<ChoraleTensionPoint> {
        val notesByRole = roles.associateWith { state.notes(it) }
        if (notesByRole.values.any { it.isEmpty() }) return emptyList()
        val structural = task.skeleton.slots.take(state.spanIndex).mapTo(hashSetOf()) { it.time.onset }
        val onsets = notesByRole.values.flatten().map { it.onset }.distinct().sorted()
        return onsets.map { onset ->
            val sounding = roles.mapNotNull { role -> soundingAt(notesByRole.getValue(role), onset) }
            val pitchClasses = sounding.map { it.pitch.pitchClass.value }.distinct().sorted()
            ChoraleTensionPoint(
                onset = onset,
                slot = sounding.minOf { it.slot },
                pitchClasses = pitchClasses,
                tension = VoiceLeadingTension.tension(pitchClasses, universe, task.tensionPolicy),
                structural = onset in structural,
            )
        }
    }

    /**
     * How much tension the decoration added over the bare chord, per span.
     *
     * The baseline is the skeleton's own sonority rather than the neighbouring downbeats: a
     * suspension puts its dissonance *on* the downbeat, so an arc measured between downbeats would
     * report the defining figure of the whole module as no tension at all.
     */
    private fun tensionArcs(curve: List<ChoraleTensionPoint>): List<ChoraleTensionArc> =
        curve.groupBy { it.slot }.entries.sortedBy { it.key }.map { (slot, points) ->
            // The baseline is the skeleton's own four pitches, not the nominal chord: an omitted
            // fifth is a real part of how the bare chord sounds, and an undecorated span must
            // measure exactly zero.
            val baseline = VoiceLeadingTension.tension(
                roles.map { skeleton[slot].pitchOf(it).pitchClass.value }.distinct().sorted(),
                universe,
                task.tensionPolicy,
            )
            val peak = points.maxOf { it.tension }
            ChoraleTensionArc(slot = slot, peak = peak, arc = peak - baseline)
        }

    /**
     * Surface-level parallel fifths and octaves.
     *
     * Stage one already cleared the skeleton, but decorations create new attack points, and a
     * passing tone can carry two voices into a parallel the skeleton never had.
     */
    private fun surfaceParallels(state: ChoraleState): List<String> {
        val notesByRole = roles.associateWith { state.notes(it) }
        val onsets = notesByRole.values.flatten().map { it.onset }.distinct().sorted()
        val verticals = onsets.map { onset ->
            onset to roles.mapNotNull { role ->
                soundingAt(notesByRole.getValue(role), onset)?.let { role to it.pitch }
            }.toMap()
        }
        val messages = mutableListOf<String>()
        verticals.zipWithNext { (_, before), (onset, after) ->
            roles.forEachIndexed { index, upper ->
                roles.drop(index + 1).forEach { lower ->
                    val a1 = before[upper] ?: return@forEach
                    val b1 = before[lower] ?: return@forEach
                    val a2 = after[upper] ?: return@forEach
                    val b2 = after[lower] ?: return@forEach
                    if (a1 == a2 && b1 == b2) return@forEach
                    val first = abs(a1.midiNumber - b1.midiNumber).mod(12)
                    val second = abs(a2.midiNumber - b2.midiNumber).mod(12)
                    if (first != second || first !in PERFECT_INTERVALS) return@forEach
                    val upperMotion = a2.midiNumber - a1.midiNumber
                    val lowerMotion = b2.midiNumber - b1.midiNumber
                    if (upperMotion == 0 || lowerMotion == 0) return@forEach
                    if (upperMotion > 0 != lowerMotion > 0) return@forEach
                    messages += "${upper.name} 与 ${lower.name} 在 $onset 形成表面平行" +
                        (if (first == 0) "八度" else "五度") + "。"
                }
            }
        }
        return messages.distinct()
    }

    private companion object {
        const val MAX_SPAN_COMBINATIONS = 96
        val PERFECT_INTERVALS = setOf(0, 7)
    }
}

internal fun soundingAt(notes: List<ChoraleNote>, onset: TimeCode): ChoraleNote? =
    notes.lastOrNull { it.onset <= onset }

/**
 * How badly a skeleton misses the contour the user asked for.
 *
 * Soft by construction: the user described a direction, not a line, so this only ranks stage-one
 * candidates and never rejects one.
 */
internal fun contourPenalty(skeleton: List<ChordVoicing>, task: ChoraleTask): Double =
    task.contour.sumOf { request ->
        val pitches = skeleton.indices
            .filter { request.window.contains(it) }
            .map { skeleton[it].pitchOf(request.role).midiNumber }
        if (pitches.size < 2) return@sumOf 0.0
        val fit = when (request.direction) {
            ChoraleContourDirection.ASCENDING -> monotonicFit(pitches, ascending = true)
            ChoraleContourDirection.DESCENDING -> monotonicFit(pitches, ascending = false)
            ChoraleContourDirection.STATIC ->
                1.0 - (pitches.max() - pitches.min()).coerceAtMost(12) / 12.0
            ChoraleContourDirection.ARCH -> extremeFit(pitches, peak = true)
            ChoraleContourDirection.VALLEY -> extremeFit(pitches, peak = false)
        }
        task.scoringPolicy.contourPenalty * request.weight * (1.0 - fit)
    }

private fun monotonicFit(pitches: List<Int>, ascending: Boolean): Double {
    val steps = pitches.zipWithNext { a, b -> b - a }
    if (steps.isEmpty()) return 1.0
    val agreeing = steps.count { if (ascending) it > 0 else it < 0 }
    val neutral = steps.count { it == 0 }
    return (agreeing + neutral * 0.5) / steps.size
}

private fun extremeFit(pitches: List<Int>, peak: Boolean): Double {
    val index = if (peak) pitches.indexOf(pitches.max()) else pitches.indexOf(pitches.min())
    if (index == 0 || index == pitches.lastIndex) return 0.0
    val rising = monotonicFit(pitches.subList(0, index + 1), ascending = peak)
    val falling = monotonicFit(pitches.subList(index, pitches.size), ascending = !peak)
    return (rising + falling) / 2.0
}

internal fun ChordVoicing.pitchOf(role: FixedVoiceRole): Pitch = when (role) {
    FixedVoiceRole.SOPRANO -> soprano
    FixedVoiceRole.ALTO -> alto
    FixedVoiceRole.TENOR -> tenor
    FixedVoiceRole.BASS -> bass
    else -> error("Chorale realization supports standard SATB roles only, got $role")
}
