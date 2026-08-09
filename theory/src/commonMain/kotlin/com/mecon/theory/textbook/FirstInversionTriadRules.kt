package com.mecon.theory.textbook

import com.mecon.api.primitive.EventId
import com.mecon.api.primitive.Pitch
import com.mecon.api.primitive.PitchClass
import com.mecon.theory.ChordQuality
import com.mecon.theory.FixedVoiceScoreEvent
import com.mecon.theory.FixedVoiceTransition
import com.mecon.theory.FixedVoiceVerticality
import com.mecon.theory.Key
import com.mecon.theory.KeySignatureMode
import com.mecon.theory.MinorAlteration
import com.mecon.theory.Mode
import com.mecon.theory.NaturalTriad
import com.mecon.theory.RuleAnchorGroup
import com.mecon.theory.RuleAnchorRole
import com.mecon.theory.RuleConfig
import com.mecon.theory.RuleFinding
import com.mecon.theory.RuleFindingKind
import com.mecon.theory.RuleId
import com.mecon.theory.RuleProfile
import com.mecon.theory.RuleSeverity

data class FirstInversionTriadConnection(
    val key: Key,
    val before: NaturalTriad,
    val after: NaturalTriad,
) {
    init {
        require(before.key == key) { "Before triad must belong to the connection key" }
        require(after.key == key) { "After triad must belong to the connection key" }
    }
}

object FirstInversionTriadRules {
    val FIRST_INVERSION_BASS_LINE = RuleId("textbook.first-inversion-triad.bass-line-enrichment")
    val DIMINISHED_TRIAD_FIRST_INVERSION = RuleId(
        "textbook.first-inversion-triad.diminished-triad-first-inversion"
    )
    val MAJOR_ROOT_DOMINANT_TO_MINOR_SIXTH = RuleId(
        "textbook.first-inversion-triad.major-root-dominant-to-minor-sixth"
    )
    val NON_CHORD_TONE = RuleId("textbook.first-inversion-triad.non-chord-tone")
    val MISSING_CHORD_TONE = RuleId("textbook.first-inversion-triad.missing-chord-tone")
    val LEADING_TONE_DOUBLED = RuleId("textbook.first-inversion-triad.leading-tone-doubled")

    val INTRODUCTORY_PROFILE = RuleProfile(
        id = "textbook.first-inversion-triad.introductory",
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
        val bassIsFirstInversion = bass.requiredPitch().pitchClass == firstInversionBassPitchClass(triad)
        if (!bassIsFirstInversion) return emptyList()
        return buildList {
            add(
                RuleFinding(
                    ruleId = FIRST_INVERSION_BASS_LINE,
                    kind = RuleFindingKind.INDICATION,
                    severity = RuleSeverity.HINT,
                    message = "三和弦第一转位可用于丰富低音线条；当低音旋律不够顺畅时，应考虑加入转位和弦。",
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

    fun checkDiminishedTriadPosition(
        verticality: FixedVoiceVerticality,
        triad: NaturalTriad,
    ): List<RuleFinding<EventId>> {
        if (triad.quality != ChordQuality.DIMINISHED) return emptyList()
        val notes = verticality.notes.filterNot { it.isRest }
        val bass = lowVoice(notes) ?: return emptyList()
        if (bass.requiredPitch().pitchClass == firstInversionBassPitchClass(triad)) {
            return listOf(
                RuleFinding(
                    ruleId = DIMINISHED_TRIAD_FIRST_INVERSION,
                    kind = RuleFindingKind.INDICATION,
                    severity = RuleSeverity.HINT,
                    message = "减三和弦在古典主义时期通常采用第一转位。",
                    anchors = notes.map { it.id },
                )
            )
        }
        return listOf(
            RuleFinding(
                ruleId = DIMINISHED_TRIAD_FIRST_INVERSION,
                kind = RuleFindingKind.VIOLATION,
                severity = RuleSeverity.HARD,
                message = "古典主义时期减三和弦几乎只使用第一转位。",
                anchors = notes.map { it.id },
                relatedAnchors = listOf(
                    RuleAnchorGroup(
                        role = RuleAnchorRole.CONTEXT,
                        anchors = listOf(bass.id),
                        label = "低音应为三音 ${firstInversionBassPitchClass(triad).toNoteName()}",
                    )
                ),
            )
        )
    }

    fun checkTransition(
        transition: FixedVoiceTransition,
        connection: FirstInversionTriadConnection,
    ): List<RuleFinding<EventId>> =
        checkMajorRootDominantToMinorSixth(transition, connection)

    fun firstInversionBassPitchClass(triad: NaturalTriad): PitchClass =
        triad.chord.pitchClasses[1]

    private fun checkCompletenessAndDoubling(
        notes: List<FixedVoiceScoreEvent>,
        triad: NaturalTriad,
        key: Key,
    ): List<RuleFinding<EventId>> {
        val chordToneEvents = notes.filter { it.requiredPitch().pitchClass in triad.chord.pitchClasses }
        val byPitchClass = chordToneEvents.groupBy { it.requiredPitch().pitchClass }
        val missing = triad.chord.pitchClasses.filterNot { it in byPitchClass.keys }
        return buildList {
            if (notes.size >= 4) {
                missing.forEach { pitchClass ->
                    add(
                        RuleFinding(
                            ruleId = MISSING_CHORD_TONE,
                            kind = RuleFindingKind.VIOLATION,
                            severity = RuleSeverity.SOFT,
                            message = "四声部第一转位三和弦通常保留完整和弦音；重复音可依据音响效果自由选择。",
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
            leadingTonePitchClasses(key, triad).forEach { leadingTone ->
                val leadingToneEvents = byPitchClass[leadingTone].orEmpty()
                if (leadingToneEvents.size > 1) {
                    add(
                        RuleFinding(
                            ruleId = LEADING_TONE_DOUBLED,
                            kind = RuleFindingKind.VIOLATION,
                            severity = RuleSeverity.SOFT,
                            message = "第一转位三和弦可自由选择重复音，但仍不应重复导音。",
                            anchors = leadingToneEvents.map { it.id },
                        )
                    )
                }
            }
        }
    }

    private fun checkMajorRootDominantToMinorSixth(
        transition: FixedVoiceTransition,
        connection: FirstInversionTriadConnection,
    ): List<RuleFinding<EventId>> {
        if (connection.key.mode != Mode.IONIAN) return emptyList()
        if (connection.before.degree != DOMINANT_DEGREE || connection.before.quality != ChordQuality.MAJOR) {
            return emptyList()
        }
        if (connection.after.degree != SIXTH_DEGREE || connection.after.quality != ChordQuality.MINOR) {
            return emptyList()
        }
        val previousBass = lowVoice(transition.previous.notes) ?: return emptyList()
        val currentBass = lowVoice(transition.current.notes) ?: return emptyList()
        if (previousBass.requiredPitch().pitchClass != connection.before.root) return emptyList()
        if (currentBass.requiredPitch().pitchClass != firstInversionBassPitchClass(connection.after)) return emptyList()
        return listOf(
            RuleFinding(
                ruleId = MAJOR_ROOT_DOMINANT_TO_MINOR_SIXTH,
                kind = RuleFindingKind.VIOLATION,
                severity = RuleSeverity.HARD,
                message = "大调中，原位属和弦之后不能接六级小和弦。",
                anchors = transition.current.notes.map { it.id },
                relatedAnchors = listOf(
                    RuleAnchorGroup(
                        role = RuleAnchorRole.SOURCE,
                        anchors = transition.previous.notes.map { it.id },
                    )
                ),
            )
        )
    }

    private fun leadingTonePitchClasses(key: Key, triad: NaturalTriad): Set<PitchClass> =
        buildSet {
            key.scale.pitchClasses.getOrNull(LEADING_TONE_DEGREE - 1)?.let { add(it) }
            if (MinorAlteration.RAISED_5 in triad.minorAlterations) {
                val signatureRoot = KeySignatureMode.MINOR.signatureRootForTonic(key.root)
                add(com.mecon.theory.Scale.major(signatureRoot).pitchClasses[4].transpose(1))
            }
        }

    private fun lowVoice(notes: List<FixedVoiceScoreEvent>): FixedVoiceScoreEvent? =
        notes.firstOrNull { it.voice.role == com.mecon.theory.FixedVoiceRole.BASS }
            ?: notes.minByOrNull { it.requiredPitch().midiNumber }

    private fun FixedVoiceScoreEvent.requiredPitch(): Pitch =
        pitch ?: error("Expected note event ${id} to have a pitch")

    private const val DOMINANT_DEGREE = 5
    private const val SIXTH_DEGREE = 6
    private const val LEADING_TONE_DEGREE = 7
}
