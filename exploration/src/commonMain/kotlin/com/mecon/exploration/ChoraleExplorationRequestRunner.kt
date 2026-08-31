package com.mecon.exploration

import com.mecon.api.primitive.Fraction
import com.mecon.api.primitive.TimeCode
import com.mecon.theory.ConstraintSlot
import com.mecon.theory.FixedVoiceRole
import com.mecon.theory.HarmonicTimeSpan
import com.mecon.theory.HarmonySlotId
import com.mecon.theory.Key
import com.mecon.theory.MeterPlan
import com.mecon.theory.NaturalTriads
import com.mecon.theory.NonChordToneType
import com.mecon.theory.SlotWindow
import com.mecon.theory.chorale.ChoraleContourDirection
import com.mecon.theory.chorale.ChoraleContourRequest
import com.mecon.theory.chorale.ChoraleFigurationRequest
import com.mecon.theory.chorale.ChoraleHarmonizer
import com.mecon.theory.chorale.ChoraleRhythmPattern
import com.mecon.theory.chorale.ChoraleTask
import com.mecon.theory.chorale.ChoraleVoicePlan
import com.mecon.theory.constraint.ConstraintProgram
import com.mecon.theory.constraint.SlotDomain
import com.mecon.theory.textbook.TextbookTriadPosition
import com.mecon.theory.textbook.TextbookTriadTarget

internal object ChoraleExplorationRequestRunner {

    fun run(request: ChoraleHarmonizationRequest): CellOutput {
        val key = request.key.toTheoryKey()
        val program = runCatching { request.toConstraintProgram(key) }.getOrElse { error ->
            return diagnosticOutput(
                request,
                listOf(Diagnostics.constraintInvalid(error.message ?: "Invalid chorale request")),
            )
        }
        val task = runCatching { request.toTask(program) }.getOrElse { error ->
            return diagnosticOutput(
                request,
                listOf(Diagnostics.constraintInvalid(error.message ?: "Invalid chorale request")),
            )
        }
        val result = ChoraleHarmonizer.harmonize(task)
        if (result.realizations.isEmpty()) {
            // The engine always says why; surface that instead of a bare "no solution".
            return diagnosticOutput(
                request,
                listOf(
                    Diagnostics.constraintInvalid(
                        result.diagnostics.joinToString("；") { it.message }
                            .ifBlank { "没有可用的实现方案。" }
                    )
                ),
            )
        }
        return CellOutput(
            fingerprint = ExplorationRequestRunner.fingerprint(request),
            candidates = result.realizations.map { realization ->
                OutputCandidate(
                    score = ChoraleScoreAssembler.assemble(
                        realization = realization,
                        program = program,
                        keySignature = request.key.toApiKeySignature(),
                        title = title(request, key),
                    ),
                    totalScore = realization.breakdown.total,
                    findings = realization.breakdown.findings
                        .sortedBy { it.ruleId.value }
                        .map { it.toStoredFinding(null) },
                    breakdownEntries = realization.tensionArcs
                        .filter { it.arc > 0.0 }
                        .map { arc ->
                            StoredScoreEntry(
                                ruleId = "chorale.tension-arc",
                                amount = arc.arc,
                                reason = "第 ${arc.slot} 槽张力拱形 ${format(arc.arc)}（峰值 ${format(arc.peak)}）",
                            )
                        },
                )
            },
        )
    }

    private fun title(request: ChoraleHarmonizationRequest, key: Key): String =
        "圣咏配和声 · " + request.slots.joinToString("–") { romanNumeral(it.degree, key) }

    private fun romanNumeral(degree: Int, key: Key): String {
        val triad = NaturalTriads.inKey(key).first { it.degree == degree }
        val numeral = ROMAN[degree - 1]
        return when (triad.quality) {
            com.mecon.theory.ChordQuality.MINOR -> numeral.lowercase()
            com.mecon.theory.ChordQuality.DIMINISHED -> numeral.lowercase() + "°"
            com.mecon.theory.ChordQuality.AUGMENTED -> numeral + "+"
            else -> numeral
        }
    }

    /**
     * The user's progression becomes the slot domains; only the vertical arrangement stays open.
     *
     * Slot onsets are laid out end to end in quarter notes, so a chord's `beats` is exactly how
     * long it sounds and stage two can subdivide it.
     */
    private fun ChoraleHarmonizationRequest.toConstraintProgram(key: Key): ConstraintProgram {
        val triads = NaturalTriads.inKey(key)
        val domains = slots.map { slot ->
            val triad = triads.firstOrNull { it.degree == slot.degree }
                ?: error("音级 ${slot.degree} 不在当前调内")
            val positions = when (slot.inversion) {
                0 -> listOf(TextbookTriadPosition.ROOT_POSITION)
                1 -> listOf(TextbookTriadPosition.FIRST_INVERSION)
                else -> listOf(TextbookTriadPosition.ROOT_POSITION, TextbookTriadPosition.FIRST_INVERSION)
            }
            SlotDomain(positions.map { TextbookTriadTarget(triad, it) })
        }
        val meterPlan = MeterPlan.FOUR_FOUR
        var measure = 1
        var beat = Fraction.ZERO
        val measureLength = meterPlan.timeSignatureAt(1).measureDuration()
        val constraintSlots = slots.mapIndexed { index, slot ->
            val duration = Fraction(slot.beats, 4).simplified()
            val result = ConstraintSlot(
                id = HarmonySlotId("chorale-slot-$index"),
                time = HarmonicTimeSpan(TimeCode.of(measure, beat), duration),
                domain = domains[index],
            )
            beat += duration
            while (beat >= measureLength) {
                beat -= measureLength
                measure++
            }
            result
        }
        return ConstraintProgram(
            key = key,
            slotDomains = domains,
            slots = constraintSlots,
            meterPlan = meterPlan,
            searchConfig = search.toSearchConfig(),
        )
    }

    private fun ChoraleHarmonizationRequest.toTask(program: ConstraintProgram): ChoraleTask =
        ChoraleTask(
            skeleton = program,
            voices = voices.map { voice ->
                ChoraleVoicePlan(
                    role = voice.role.toRole(),
                    patterns = voice.patterns.map { it.toPattern() }.distinctBy { it.id },
                )
            },
            figuration = figuration.map { spec ->
                ChoraleFigurationRequest(
                    slot = spec.slot,
                    types = setOf(spec.type.toType()),
                    role = spec.role?.toRole(),
                )
            },
            contour = contour.map { spec ->
                ChoraleContourRequest(
                    role = spec.role.toRole(),
                    window = SlotWindow(spec.startSlot, spec.endSlot),
                    direction = spec.direction.toDirection(),
                    weight = spec.weight,
                )
            },
            search = search.toSearchConfig(),
        )

    private fun ChoraleVoiceRoleSpec.toRole(): FixedVoiceRole = when (this) {
        ChoraleVoiceRoleSpec.SOPRANO -> FixedVoiceRole.SOPRANO
        ChoraleVoiceRoleSpec.ALTO -> FixedVoiceRole.ALTO
        ChoraleVoiceRoleSpec.TENOR -> FixedVoiceRole.TENOR
        ChoraleVoiceRoleSpec.BASS -> FixedVoiceRole.BASS
    }

    private fun ChoraleRhythmSpec.toPattern(): ChoraleRhythmPattern = when (this) {
        ChoraleRhythmSpec.SUSTAINED -> ChoraleRhythmPattern.SUSTAINED
        ChoraleRhythmSpec.HALVES -> ChoraleRhythmPattern.HALVES
        ChoraleRhythmSpec.QUARTERS -> ChoraleRhythmPattern.QUARTERS
        ChoraleRhythmSpec.LONG_SHORT -> ChoraleRhythmPattern.LONG_SHORT
    }

    private fun ChoraleFigurationTypeSpec.toType(): NonChordToneType = when (this) {
        ChoraleFigurationTypeSpec.SUSPENSION -> NonChordToneType.SUSPENSION
        ChoraleFigurationTypeSpec.RETARDATION -> NonChordToneType.RETARDATION
        ChoraleFigurationTypeSpec.PASSING -> NonChordToneType.PASSING
        ChoraleFigurationTypeSpec.NEIGHBOR -> NonChordToneType.NEIGHBOR
        ChoraleFigurationTypeSpec.ANTICIPATION -> NonChordToneType.ANTICIPATION
    }

    private fun ChoraleContourDirectionSpec.toDirection(): ChoraleContourDirection = when (this) {
        ChoraleContourDirectionSpec.ASCENDING -> ChoraleContourDirection.ASCENDING
        ChoraleContourDirectionSpec.DESCENDING -> ChoraleContourDirection.DESCENDING
        ChoraleContourDirectionSpec.ARCH -> ChoraleContourDirection.ARCH
        ChoraleContourDirectionSpec.VALLEY -> ChoraleContourDirection.VALLEY
        ChoraleContourDirectionSpec.STATIC -> ChoraleContourDirection.STATIC
    }

    private fun format(value: Double): String =
        ((value * 100).toInt() / 100.0).toString()

    private val ROMAN = listOf("I", "II", "III", "IV", "V", "VI", "VII")
}
