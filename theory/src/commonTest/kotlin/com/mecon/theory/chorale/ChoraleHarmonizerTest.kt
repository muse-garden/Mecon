package com.mecon.theory.chorale

import com.mecon.api.primitive.Fraction
import com.mecon.api.primitive.PitchClass
import com.mecon.api.primitive.TimeCode
import com.mecon.theory.ConstraintSlot
import com.mecon.theory.FixedVoiceRole
import com.mecon.theory.HarmonicTimeSpan
import com.mecon.theory.HarmonySlotId
import com.mecon.theory.Key
import com.mecon.theory.MeterPlan
import com.mecon.theory.NaturalTriads
import com.mecon.theory.NonChordToneType
import com.mecon.theory.SearchConfig
import com.mecon.theory.SlotWindow
import com.mecon.theory.constraint.ConstraintProgram
import com.mecon.theory.constraint.SlotDomain
import com.mecon.theory.textbook.TextbookTriadPosition
import com.mecon.theory.textbook.TextbookTriadTarget
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private val KEY = Key.major(PitchClass.C)
private val ROLES = listOf(
    FixedVoiceRole.SOPRANO,
    FixedVoiceRole.ALTO,
    FixedVoiceRole.TENOR,
    FixedVoiceRole.BASS,
)

/** One chord per bar, so a decorated span is a whole note that can be subdivided. */
internal fun programOf(degrees: List<Int>): ConstraintProgram {
    val triads = NaturalTriads.inKey(KEY)
    val domains = degrees.map { degree ->
        SlotDomain(
            listOf(
                TextbookTriadTarget(
                    triads.first { it.degree == degree },
                    TextbookTriadPosition.ROOT_POSITION,
                )
            )
        )
    }
    return ConstraintProgram(
        key = KEY,
        slotDomains = domains,
        slots = domains.mapIndexed { index, domain ->
            ConstraintSlot(
                id = HarmonySlotId("slot-$index"),
                time = HarmonicTimeSpan(TimeCode.of(index + 1, Fraction.ZERO), Fraction.ONE),
                domain = domain,
            )
        },
        meterPlan = MeterPlan.FOUR_FOUR,
        searchConfig = SearchConfig(maxResults = 8, beamWidth = 64),
    )
}

private fun voices(
    patterns: List<ChoraleRhythmPattern>,
    sopranoPatterns: List<ChoraleRhythmPattern> = patterns,
): List<ChoraleVoicePlan> = ROLES.map { role ->
    ChoraleVoicePlan(role, if (role == FixedVoiceRole.SOPRANO) sopranoPatterns else patterns)
}

class ChoraleHarmonizerTest {

    @Test
    fun sustainedVoicesReproduceTheSkeletonExactly() {
        val task = ChoraleTask(
            skeleton = programOf(listOf(1, 4, 5, 1)),
            voices = voices(listOf(ChoraleRhythmPattern.SUSTAINED)),
            search = SearchConfig(maxResults = 3, beamWidth = 16),
        )
        val result = ChoraleHarmonizer.harmonize(task)
        val realization = result.realizations.firstOrNull()
        assertNotNull(realization, "sustained voices must always be realizable: ${result.diagnostics}")

        realization.lines.forEach { line ->
            assertEquals(4, line.notes.size, "${line.role} should hold one note per span")
            assertTrue(line.nonChordTones.isEmpty(), "${line.role} must not decorate anything")
            line.notes.forEachIndexed { slot, note ->
                assertEquals(
                    realization.skeleton[slot].pitchOf(line.role),
                    note.pitch,
                    "${line.role} slot $slot must sound the skeleton pitch",
                )
                assertEquals(TimeCode.of(slot + 1, Fraction.ZERO), note.onset)
                assertEquals(Fraction.ONE, note.duration)
            }
        }
        assertTrue(
            realization.tensionArcs.all { it.arc == 0.0 },
            "an undecorated span adds no tension: ${realization.tensionArcs}",
        )
    }

    @Test
    fun aRequestedSuspensionIsPreparedHeldAndResolvedDownward() {
        val task = ChoraleTask(
            // IV prepares the dissonance that sounds over V.
            skeleton = programOf(listOf(1, 4, 5, 1)),
            voices = voices(
                patterns = listOf(ChoraleRhythmPattern.SUSTAINED),
                sopranoPatterns = listOf(ChoraleRhythmPattern.SUSTAINED, ChoraleRhythmPattern.HALVES),
            ),
            figuration = listOf(
                ChoraleFigurationRequest(
                    slot = 2,
                    types = setOf(NonChordToneType.SUSPENSION),
                    role = FixedVoiceRole.SOPRANO,
                )
            ),
        )
        val result = ChoraleHarmonizer.harmonize(task)
        val realization = result.realizations.firstOrNull()
        assertNotNull(realization, "the cadential suspension must be realizable: ${result.diagnostics}")

        val soprano = realization.line(FixedVoiceRole.SOPRANO)
        val inSpan = soprano.notes.filter { it.slot == 2 }
        assertEquals(2, inSpan.size, "the span must split into dissonance and resolution")
        val dissonance = inSpan.first()
        val resolution = inSpan.last()

        assertEquals(NonChordToneType.SUSPENSION, dissonance.nonChordTone)
        assertEquals(null, resolution.nonChordTone)
        // Preparation: the held pitch is literally the previous chord's soprano pitch.
        assertEquals(
            realization.skeleton[1].pitchOf(FixedVoiceRole.SOPRANO),
            dissonance.pitch,
            "the suspension must be prepared by the previous chord",
        )
        // Resolution: a step down onto the skeleton pitch of the chord it clashed with.
        assertEquals(realization.skeleton[2].pitchOf(FixedVoiceRole.SOPRANO), resolution.pitch)
        assertEquals(-1, resolution.pitch.diatonicSteps - dissonance.pitch.diatonicSteps)
        // The dissonance is foreign to the chord sounding under it.
        assertTrue(
            realization.skeleton[2].target.sonority.pitchClasses
                .none { it.value == dissonance.pitch.pitchClass.value },
        )
    }

    @Test
    fun theTensionCurveShowsAnArcExactlyWhereTheConflictWasRequested() {
        val decorated = ChoraleHarmonizer.harmonize(
            ChoraleTask(
                skeleton = programOf(listOf(1, 4, 5, 1)),
                voices = voices(
                    patterns = listOf(ChoraleRhythmPattern.SUSTAINED),
                    sopranoPatterns = listOf(ChoraleRhythmPattern.SUSTAINED, ChoraleRhythmPattern.HALVES),
                ),
                figuration = listOf(
                    ChoraleFigurationRequest(
                        slot = 2,
                        types = setOf(NonChordToneType.SUSPENSION),
                        role = FixedVoiceRole.SOPRANO,
                    )
                ),
            )
        ).realizations.first()

        val arc = decorated.tensionArcs.single { it.slot == 2 }
        assertTrue(arc.arc > 0.0, "the suspension must add tension the bare dominant does not have")
        assertEquals(
            arc,
            decorated.tensionArcs.maxBy { it.arc },
            "the requested conflict must be the strongest one in the piece",
        )
        // A suspension puts its dissonance on the downbeat and releases after it — the opposite
        // shape from a passing tone, and the reason the arc is measured against the bare chord.
        assertTrue(
            decorated.tensionCurve.first { it.structural && it.slot == 2 }.tension >
                decorated.tensionCurve.last { it.slot == 2 }.tension,
        )
    }

    @Test
    fun everyNonChordToneIsNamedByTheSharedClassifier() {
        val result = ChoraleHarmonizer.harmonize(
            ChoraleTask(
                skeleton = programOf(listOf(1, 4, 5, 1)),
                voices = voices(listOf(ChoraleRhythmPattern.SUSTAINED, ChoraleRhythmPattern.HALVES)),
                search = SearchConfig(maxResults = 6, beamWidth = 32),
            )
        )
        assertTrue(result.realizations.isNotEmpty(), "${result.diagnostics}")
        assertTrue(
            result.realizations.any { it.lines.any { line -> line.nonChordTones.isNotEmpty() } },
            "at least one candidate must actually decorate, or this proves nothing about naming",
        )
        result.realizations.forEach { realization ->
            realization.lines.forEach { line ->
                line.notes.forEach { note ->
                    val chordTone = realization.skeleton[note.slot].target.sonority.pitchClasses
                        .any { it.value == note.pitch.pitchClass.value }
                    assertEquals(
                        chordTone,
                        note.nonChordTone == null,
                        "${line.role} at ${note.onset}: a tone is either a chord tone or a named figure",
                    )
                }
                // The skeleton's vertical is what sounds first in every span.
                (0..3).forEach { slot ->
                    val spanNotes = line.notes.filter { it.slot == slot }
                    val firstChordTone = spanNotes.first { it.nonChordTone == null }
                    assertEquals(realization.skeleton[slot].pitchOf(line.role), firstChordTone.pitch)
                }
            }
        }
    }

    @Test
    fun candidatesDifferInTheirFigurationNotJustInPitchDetail() {
        val result = ChoraleHarmonizer.harmonize(
            ChoraleTask(
                skeleton = programOf(listOf(1, 4, 5, 1)),
                voices = voices(listOf(ChoraleRhythmPattern.SUSTAINED, ChoraleRhythmPattern.HALVES)),
                search = SearchConfig(maxResults = 4, beamWidth = 48),
            )
        )
        assertTrue(result.realizations.size >= 2, "expected several ways to decorate the same skeleton")
        assertEquals(
            result.realizations.size,
            result.realizations.map { it.figurationSignature }.distinct().size,
            "candidates must differ in decoration, not only in pitch",
        )
    }

    @Test
    fun withNothingRequestedThePlainestSettingIsOfferedFirst() {
        // Regression: consonant chordal skips used to cost nothing, so every busier realization
        // tied with the plain one and an arbitrary tie-break decided which survived the beam —
        // which lost the plain answer entirely and made the whole task look unrealizable.
        val result = ChoraleHarmonizer.harmonize(
            ChoraleTask(
                skeleton = programOf(listOf(1, 4, 5, 1)),
                voices = voices(listOf(ChoraleRhythmPattern.SUSTAINED, ChoraleRhythmPattern.HALVES)),
                search = SearchConfig(maxResults = 5, beamWidth = 32),
            )
        )
        val best = result.realizations.first()
        assertTrue(
            best.lines.all { line -> line.notes.size == 4 },
            "unasked, every voice should simply hold its skeleton pitch: ${best.figurationSignature}",
        )
        assertTrue(result.realizations.drop(1).any { candidate ->
            candidate.lines.any { it.notes.size > 4 }
        }, "busier settings must still be offered, just not preferred")
    }

    @Test
    fun anUnpreparableSuspensionReportsADiagnosticInsteadOfAnEmptyResult() {
        val result = ChoraleHarmonizer.harmonize(
            ChoraleTask(
                // I -> I cannot suspend: every tone of the "new" chord is already consonant.
                skeleton = programOf(listOf(1, 1)),
                voices = voices(listOf(ChoraleRhythmPattern.SUSTAINED, ChoraleRhythmPattern.HALVES)),
                figuration = listOf(
                    ChoraleFigurationRequest(
                        slot = 1,
                        types = setOf(NonChordToneType.SUSPENSION),
                        role = FixedVoiceRole.SOPRANO,
                    )
                ),
            )
        )
        assertTrue(result.realizations.isEmpty())
        assertEquals(
            listOf("chorale.skeleton-unrealizable"),
            result.diagnostics.map { it.code },
        )
    }

    @Test
    fun contourRequestsSteerButDoNotConstrain() {
        fun sopranoLine(direction: ChoraleContourDirection): List<Int> =
            ChoraleHarmonizer.harmonize(
                ChoraleTask(
                    skeleton = programOf(listOf(1, 4, 5, 1)),
                    voices = voices(listOf(ChoraleRhythmPattern.SUSTAINED)),
                    contour = listOf(
                        ChoraleContourRequest(
                            role = FixedVoiceRole.SOPRANO,
                            window = SlotWindow(0, 3),
                            direction = direction,
                            weight = 4.0,
                        )
                    ),
                    search = SearchConfig(maxResults = 1, beamWidth = 16),
                )
            ).realizations.first().skeleton.map { it.pitchOf(FixedVoiceRole.SOPRANO).midiNumber }

        val ascending = sopranoLine(ChoraleContourDirection.ASCENDING)
        val descending = sopranoLine(ChoraleContourDirection.DESCENDING)
        assertTrue(
            ascending != descending,
            "opposite contour requests must not produce the same soprano: $ascending",
        )
    }
}
