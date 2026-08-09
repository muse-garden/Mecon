package com.mecon.theory.textbook

import com.mecon.api.primitive.EventId
import com.mecon.api.primitive.Pitch
import com.mecon.api.primitive.PitchClass
import com.mecon.theory.FixedVoice
import com.mecon.theory.FixedVoiceRole
import com.mecon.theory.FixedVoiceScoreEvent
import com.mecon.theory.FixedVoiceVerticality
import com.mecon.theory.FixedVoiceWritingFrame
import com.mecon.theory.Key
import com.mecon.theory.NaturalTriad
import com.mecon.theory.RuleAnchorGroup
import com.mecon.theory.RuleAnchorRole
import com.mecon.theory.RuleConfig
import com.mecon.theory.RuleFinding
import com.mecon.theory.RuleFindingKind
import com.mecon.theory.RuleId
import com.mecon.theory.RuleProfile
import com.mecon.theory.RuleSeverity
import com.mecon.theory.constraint.evaluateNamedSixFourConstraints

object SecondInversionTriadRules {
    val SECOND_INVERSION_DECORATION = RuleId("textbook.second-inversion-triad.decorative-use")
    val CADENTIAL_SIX_FOUR = RuleId("textbook.second-inversion-triad.cadential-six-four")
    val PASSING_SIX_FOUR = RuleId("textbook.second-inversion-triad.passing-six-four")
    val PEDAL_SIX_FOUR = RuleId("textbook.second-inversion-triad.pedal-six-four")
    val SAME_CHORD_INVERSION_INSERTION = RuleId("textbook.second-inversion-triad.same-chord-inversion-insertion")
    val UNSUPPORTED_SECOND_INVERSION = RuleId("textbook.second-inversion-triad.unsupported-second-inversion")
    val UPPER_VOICE_LEAP = RuleId("textbook.second-inversion-triad.upper-voice-leap")
    val NON_CHORD_TONE = RuleId("textbook.second-inversion-triad.non-chord-tone")
    val MISSING_CHORD_TONE = RuleId("textbook.second-inversion-triad.missing-chord-tone")
    val EXPECT_BASS_DOUBLING = RuleId("textbook.second-inversion-triad.expect-bass-doubling")
    val LEADING_TONE_DOUBLED = RuleId("textbook.second-inversion-triad.leading-tone-doubled")

    val INTRODUCTORY_PROFILE = RuleProfile(
        id = "textbook.second-inversion-triad.introductory",
        overrides = mapOf(
            MelodyTextbookRules.LEADING_TONE_RESOLUTION to RuleConfig(
                severityOverride = RuleSeverity.HINT,
            )
        ),
    )

    fun checkVerticality(
        verticality: FixedVoiceVerticality,
        triad: NaturalTriad,
        key: Key,
    ): List<RuleFinding<EventId>> {
        val notes = verticality.notes.filterNot { it.isRest }
        if (notes.isEmpty()) return emptyList()
        val bass = lowVoice(notes) ?: return emptyList()
        if (bass.requiredPitch().pitchClass != secondInversionBassPitchClass(triad)) return emptyList()
        return buildList {
            add(
                RuleFinding(
                    ruleId = SECOND_INVERSION_DECORATION,
                    kind = RuleFindingKind.INDICATION,
                    severity = RuleSeverity.HINT,
                    message = "三和弦第二转位在古典主义写作中主要作为装饰性四六和弦使用。",
                    anchors = listOf(bass.id),
                    relatedAnchors = listOf(
                        RuleAnchorGroup(
                            role = RuleAnchorRole.RELATED,
                            anchors = notes.map { it.id },
                        )
                    ),
                )
            )
            notes.filter { it.requiredPitch().pitchClass !in triad.chord.pitchClasses }.forEach { note ->
                add(
                    RuleFinding(
                        ruleId = NON_CHORD_TONE,
                        kind = RuleFindingKind.VIOLATION,
                        severity = RuleSeverity.HARD,
                        message = "当前练习只写三和弦结构，声部中不应出现和弦外音。",
                        anchors = listOf(note.id),
                    )
                )
            }
            addAll(checkCompletenessAndDoubling(notes, triad, key))
        }
    }

    fun checkContextualUses(
        frames: List<FixedVoiceWritingFrame<TextbookTriadTarget>>,
        voices: List<FixedVoice>,
        includeUnsupportedFindings: Boolean = true,
    ): List<RuleFinding<EventId>> =
        evaluateNamedSixFourConstraints(frames, voices, includeUnsupportedFindings)

    fun secondInversionBassPitchClass(triad: NaturalTriad): PitchClass =
        triad.chord.pitchClasses[2]

    private fun checkCompletenessAndDoubling(
        notes: List<FixedVoiceScoreEvent>,
        triad: NaturalTriad,
        key: Key,
    ): List<RuleFinding<EventId>> {
        val chordToneEvents = notes.filter { it.requiredPitch().pitchClass in triad.chord.pitchClasses }
        val byPitchClass = chordToneEvents.groupBy { it.requiredPitch().pitchClass }
        val missing = triad.chord.pitchClasses.filterNot { it in byPitchClass.keys }
        val bassPitchClass = secondInversionBassPitchClass(triad)
        return buildList {
            if (notes.size >= 4) {
                missing.forEach { pitchClass ->
                    add(
                        RuleFinding(
                            ruleId = MISSING_CHORD_TONE,
                            kind = RuleFindingKind.VIOLATION,
                            severity = RuleSeverity.SOFT,
                            message = "四声部四六和弦通常保留完整和弦音。",
                            anchors = chordToneEvents.map { it.id },
                            relatedAnchors = listOf(
                                RuleAnchorGroup(
                                    role = RuleAnchorRole.CONTEXT,
                                    anchors = chordToneEvents.map { it.id },
                                    label = "缺少 ${pitchClass.toNoteName()}",
                                )
                            ),
                        )
                    )
                }
            }
            if (byPitchClass[bassPitchClass].orEmpty().size < 2) {
                add(
                    RuleFinding(
                        ruleId = EXPECT_BASS_DOUBLING,
                        kind = RuleFindingKind.VIOLATION,
                        severity = RuleSeverity.SOFT,
                        message = "四六和弦通常优先重复低音，也就是和弦的五音。",
                        anchors = chordToneEvents.map { it.id },
                    )
                )
            }
            key.scale.pitchClasses.getOrNull(LEADING_TONE_DEGREE - 1)?.let { leadingTone ->
                val leadingToneEvents = byPitchClass[leadingTone].orEmpty()
                if (leadingToneEvents.size > 1) {
                    add(
                        RuleFinding(
                            ruleId = LEADING_TONE_DOUBLED,
                            kind = RuleFindingKind.VIOLATION,
                            severity = RuleSeverity.SOFT,
                            message = "四六和弦通常重复低音，但仍不应重复导音。",
                            anchors = leadingToneEvents.map { it.id },
                        )
                    )
                }
            }
        }
    }

    private fun lowVoice(notes: List<FixedVoiceScoreEvent>): FixedVoiceScoreEvent? =
        notes.firstOrNull { it.voice.role == FixedVoiceRole.BASS }
            ?: notes.minByOrNull { it.requiredPitch().midiNumber }

    private fun FixedVoiceScoreEvent.requiredPitch(): Pitch =
        pitch ?: error("Expected note event ${id} to have a pitch")

    private const val LEADING_TONE_DEGREE = 7
}
