package com.mecon.theory.textbook

import com.mecon.api.primitive.EventId
import com.mecon.api.primitive.Pitch
import com.mecon.api.primitive.PitchClass
import com.mecon.theory.ChordQuality
import com.mecon.theory.FixedVoiceRole
import com.mecon.theory.FixedVoiceScoreEvent
import com.mecon.theory.FixedVoiceTransition
import com.mecon.theory.FixedVoiceVerticality
import com.mecon.theory.Key
import com.mecon.theory.KeySignatureMode
import com.mecon.theory.MinorAlteration
import com.mecon.theory.Mode
import com.mecon.theory.NaturalTriad
import com.mecon.theory.NaturalTriads
import com.mecon.theory.RuleApplicability
import com.mecon.theory.RuleAnchorGroup
import com.mecon.theory.RuleAnchorRole
import com.mecon.theory.RuleConfig
import com.mecon.theory.RuleFinding
import com.mecon.theory.RuleFindingKind
import com.mecon.theory.RuleProfile
import com.mecon.theory.RuleId
import com.mecon.theory.RuleSeverity
import com.mecon.theory.RuleSuppression
import com.mecon.theory.VoiceMotionDirection
import com.mecon.theory.degree
import kotlin.math.abs

data class RootPositionTriadConnection(
    val key: Key,
    val before: NaturalTriad,
    val after: NaturalTriad,
    val afterIsFinal: Boolean = false,
) {
    init {
        require(before.key == key) { "Before triad must belong to the connection key" }
        require(after.key == key) { "After triad must belong to the connection key" }
    }
}

object RootPositionTriadRules {
    val NON_CHORD_TONE = RuleId("textbook.root-position-triad.non-chord-tone")
    val MISSING_CHORD_TONE = RuleId("textbook.root-position-triad.missing-chord-tone")
    val EXPECT_ROOT_DOUBLING = RuleId("textbook.root-position-triad.expect-root-doubling")
    val LEADING_TONE_DOUBLED = RuleId("textbook.root-position-triad.leading-tone-doubled")
    val FINAL_TONIC_SPACING = RuleId("textbook.root-position-triad.final-tonic-spacing")
    val SAME_CHORD_REPETITION = RuleId("textbook.root-position-triad.same-chord-repetition")
    val FOURTH_FIFTH_COMMON_TONE = RuleId("textbook.root-position-triad.fourth-fifth-common-tone")
    val FOURTH_FIFTH_NO_COMMON_TONE = RuleId("textbook.root-position-triad.fourth-fifth-no-common-tone")
    val FOURTH_FIFTH_OPEN_CLOSE_SHIFT = RuleId("textbook.root-position-triad.fourth-fifth-open-close-shift")
    val INNER_LEADING_TONE_LEAP = RuleId("textbook.root-position-triad.inner-leading-tone-leap")
    val THIRD_SIXTH_COMMON_TONES = RuleId("textbook.root-position-triad.third-sixth-common-tones")
    val SECOND_SEVENTH_CONTRARY_SMOOTH = RuleId("textbook.root-position-triad.second-seventh-contrary-smooth")
    val MAJOR_DOMINANT_TO_SIXTH_INNER_LEADING_TONE = RuleId(
        "textbook.root-position-triad.major-dominant-sixth-inner-leading-tone"
    )
    val MINOR_RAISED_FIFTH_TO_FOURTH = RuleId("textbook.root-position-triad.minor-raised-fifth-to-fourth")

    val INTRODUCTORY_PROFILE = RuleProfile(
        id = "textbook.root-position-triad.introductory",
        overrides = mapOf(
            MelodyTextbookRules.LEADING_TONE_RESOLUTION to RuleConfig(
                severityOverride = RuleSeverity.HINT,
            )
        ),
        suppressions = listOf(
            RuleSuppression(
                dominantRuleId = INNER_LEADING_TONE_LEAP,
                suppressedRuleId = MelodyTextbookRules.LEADING_TONE_RESOLUTION,
            ),
            RuleSuppression(
                dominantRuleId = MAJOR_DOMINANT_TO_SIXTH_INNER_LEADING_TONE,
                suppressedRuleId = MelodyTextbookRules.LEADING_TONE_RESOLUTION,
            )
        ),
    )

    fun triadInKey(key: Key, degree: Int, quality: ChordQuality? = null): NaturalTriad =
        NaturalTriads.inKey(key).firstOrNull { triad ->
            triad.degree == degree && (quality == null || triad.quality == quality)
        } ?: error("No natural triad for degree $degree in $key")

    fun checkTransition(
        transition: FixedVoiceTransition,
        connection: RootPositionTriadConnection,
    ): List<RuleFinding<EventId>> =
        if (!applicability(transition, connection).applies) {
            emptyList()
        } else {
            checkApplicableTransition(transition, connection)
        }

    fun applicability(
        transition: FixedVoiceTransition,
        connection: RootPositionTriadConnection,
    ): RuleApplicability {
        val previousBass = lowVoice(transition.previous.notes)
        val currentBass = lowVoice(transition.current.notes)
        return when {
            previousBass == null || currentBass == null ->
                RuleApplicability.notApplicable("缺少低音声部，无法应用原位三和弦连接规则。")
            previousBass.requiredPitch().pitchClass != connection.before.root ->
                RuleApplicability.notApplicable(
                    reason = "前一和弦不是原位三和弦。",
                    suggestedRuleSet = "inverted-triad",
                )
            currentBass.requiredPitch().pitchClass != connection.after.root ->
                RuleApplicability.notApplicable(
                    reason = "后一和弦不是原位三和弦。",
                    suggestedRuleSet = "inverted-triad",
                )
            else -> RuleApplicability.APPLIES
        }
    }

    private fun checkApplicableTransition(
        transition: FixedVoiceTransition,
        connection: RootPositionTriadConnection,
    ): List<RuleFinding<EventId>> =
        buildList {
            addAll(
                checkVerticality(
                    transition.previous,
                    connection.before,
                    connection.key,
                    isFinal = false,
                )
            )
            addAll(
                checkVerticality(
                    transition.current,
                    connection.after,
                    connection.key,
                    isFinal = connection.afterIsFinal,
                )
            )
            addAll(checkConnectionPattern(transition, connection))
            addAll(checkMinorRaisedFifthToFourth(transition, connection))
        }.distinctBy { finding ->
            finding.ruleId to finding.anchors
        }

    fun checkVerticality(
        verticality: FixedVoiceVerticality,
        triad: NaturalTriad,
        key: Key,
        isFinal: Boolean = false,
    ): List<RuleFinding<EventId>> =
        buildList {
            val notes = verticality.notes.filterNot { it.isRest }
            if (notes.isEmpty()) return@buildList
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
            addAll(checkOmissionsAndDoubling(notes, triad, key, isFinal))
        }

    private fun checkOmissionsAndDoubling(
        notes: List<FixedVoiceScoreEvent>,
        triad: NaturalTriad,
        key: Key,
        isFinal: Boolean,
    ): List<RuleFinding<EventId>> {
        val chordToneEvents = notes.filter { it.requiredPitch().pitchClass in triad.chord.pitchClasses }
        val byPitchClass = chordToneEvents.groupBy { it.requiredPitch().pitchClass }
        val missing = triad.chord.pitchClasses.filterNot { it in byPitchClass.keys }
        val rootEvents = byPitchClass[triad.root].orEmpty()
        val voiceCount = notes.size
        return buildList {
            if (voiceCount >= 4) {
                if (!isFinal || triad.degree != TONIC_DEGREE) {
                    missing.forEach { pitchClass ->
                        add(
                            RuleFinding(
                                ruleId = MISSING_CHORD_TONE,
                                kind = RuleFindingKind.VIOLATION,
                                severity = RuleSeverity.SOFT,
                                message = "四声部及以上原位三和弦一般不省略和弦音。",
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
                } else if (voiceCount == 4) {
                    val third = triad.chord.pitchClasses[1]
                    val allowedFinal = rootEvents.size == 3 &&
                        byPitchClass[third].orEmpty().size == 1 &&
                        byPitchClass.keys.all { it == triad.root || it == third }
                    if (!allowedFinal && missing.isNotEmpty()) {
                        add(
                            RuleFinding(
                                ruleId = FINAL_TONIC_SPACING,
                                kind = RuleFindingKind.VIOLATION,
                                severity = RuleSeverity.SOFT,
                                message = "四声部最后的 1 级和弦省略五音时，应保留一个三音和三个根音。",
                                anchors = chordToneEvents.map { it.id },
                            )
                        )
                    }
                }
            } else if (voiceCount == 3) {
                val fifth = triad.chord.pitchClasses[2]
                val missingOtherThanFifth = missing.filterNot { it == fifth }
                missingOtherThanFifth.forEach {
                    add(
                        RuleFinding(
                            ruleId = MISSING_CHORD_TONE,
                            kind = RuleFindingKind.VIOLATION,
                            severity = RuleSeverity.SOFT,
                            message = "三声部原位三和弦可省略五音，但不应省略根音或三音。",
                            anchors = chordToneEvents.map { event -> event.id },
                        )
                    )
                }
                if (isFinal && triad.degree == TONIC_DEGREE && rootEvents.size != 3) {
                    add(
                        RuleFinding(
                            ruleId = FINAL_TONIC_SPACING,
                            kind = RuleFindingKind.VIOLATION,
                            severity = RuleSeverity.SOFT,
                            message = "三声部最后的 1 级和弦只可包含三次出现的根音。",
                            anchors = chordToneEvents.map { it.id },
                        )
                    )
                }
            }
            if (
                rootEvents.size < 2 &&
                !isFinalThreeVoiceTonic(voiceCount, triad, isFinal)
            ) {
                add(
                    RuleFinding(
                        ruleId = EXPECT_ROOT_DOUBLING,
                        kind = RuleFindingKind.VIOLATION,
                        severity = RuleSeverity.SOFT,
                        message = "入门阶段原位三和弦通常重复根音。",
                        anchors = chordToneEvents.map { it.id },
                    )
                )
            }
            leadingTonePitchClasses(key, triad).forEach { leadingTone ->
                val leadingToneEvents = byPitchClass[leadingTone].orEmpty()
                if (leadingToneEvents.size > 1) {
                    add(
                        RuleFinding(
                            ruleId = LEADING_TONE_DOUBLED,
                            kind = RuleFindingKind.VIOLATION,
                            severity = RuleSeverity.SOFT,
                            message = "7 级音几乎永远不重复。",
                            anchors = leadingToneEvents.map { it.id },
                        )
                    )
                }
            }
        }
    }

    private fun checkConnectionPattern(
        transition: FixedVoiceTransition,
        connection: RootPositionTriadConnection,
    ): List<RuleFinding<EventId>> =
        when (degreeDistance(connection.before.degree, connection.after.degree)) {
            0 -> checkSameChordRepetition(transition, connection)
            1 -> checkSecondSeventhRelation(transition, connection)
            2 -> checkThirdSixthRelation(transition, connection)
            3 -> checkFourthFifthRelation(transition, connection)
            else -> emptyList()
        }

    private fun checkSameChordRepetition(
        transition: FixedVoiceTransition,
        connection: RootPositionTriadConnection,
    ): List<RuleFinding<EventId>> {
        val bassMotion = bassMotion(transition) ?: return emptyList()
        val sameChord = connection.before.chord.pitchClasses.toSet() == connection.after.chord.pitchClasses.toSet()
        if (!sameChord) return emptyList()
        return listOf(
            RuleFinding(
                ruleId = SAME_CHORD_REPETITION,
                kind = RuleFindingKind.INDICATION,
                severity = RuleSeverity.HINT,
                message = if (bassMotion.isOctaveDisplacement) {
                    "同和弦反复中，低音部可作八度变换，上方声部可在一般写作原则内自由变换。"
                } else {
                    "同和弦反复，上方声部可在一般写作原则内自由变换。"
                },
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

    private fun checkFourthFifthRelation(
        transition: FixedVoiceTransition,
        connection: RootPositionTriadConnection,
    ): List<RuleFinding<EventId>> {
        val upperMotions = upperVoiceMotions(transition)
        if (upperMotions.size != 3) return emptyList()
        val bassDirection = bassMotion(transition)?.direction ?: return emptyList()
        val common = upperMotions.filter { it.isHeldCommonTone }
        val moving = upperMotions.filterNot { it.isHeldCommonTone }
        val findings = mutableListOf<RuleFinding<EventId>>()
        if (
            common.size == 1 &&
            moving.size == 2 &&
            moving.all { it.isStep && it.direction == moving.first().direction } &&
            moving.first().direction.isOppositeOf(bassDirection)
        ) {
            findings += indication(
                FOURTH_FIFTH_COMMON_TONE,
                "根音四（五）度关系：共同音保持，其他两音与低音反向同向级进。",
                upperMotions,
            )
        }
        if (
            common.isEmpty() &&
            upperMotions.all { it.direction == upperMotions.first().direction && it.diatonicDistance in 1..2 } &&
            upperMotions.first().direction.isOppositeOf(bassDirection)
        ) {
            findings += indication(
                FOURTH_FIFTH_NO_COMMON_TONE,
                "根音四（五）度关系：不保持共同音，三个上方声部与低音反向进行，跳进不超过三度。",
                upperMotions,
            )
        }
        if (
            common.size == 1 &&
            moving.size == 2 &&
            moving.map { it.direction }.toSet().size == 2 &&
            moving.any { it.isStep } &&
            moving.any { it.diatonicDistance > 1 }
        ) {
            findings += indication(
                FOURTH_FIFTH_OPEN_CLOSE_SHIFT,
                "根音四（五）度关系：共同音保持，一音级进，一音反向跳进，可用于开放与密集排列转换。",
                upperMotions,
            )
        }
        findings += checkInnerLeadingToneLeap(upperMotions, connection.key)
        return findings
    }

    private fun checkThirdSixthRelation(
        transition: FixedVoiceTransition,
        connection: RootPositionTriadConnection,
    ): List<RuleFinding<EventId>> {
        val upperMotions = upperVoiceMotions(transition)
        if (upperMotions.size != 3) return emptyList()
        val common = upperMotions.filter { it.isHeldCommonTone }
        val moving = upperMotions.filterNot { it.isHeldCommonTone }
        return if (common.size == 2 && moving.singleOrNull()?.isStep == true) {
            listOf(
                indication(
                    THIRD_SIXTH_COMMON_TONES,
                    "根音三（六）度关系：两个共同音保持，另一音级进。",
                    upperMotions,
                )
            )
        } else {
            emptyList()
        }
    }

    private fun checkSecondSeventhRelation(
        transition: FixedVoiceTransition,
        connection: RootPositionTriadConnection,
    ): List<RuleFinding<EventId>> {
        val upperMotions = upperVoiceMotions(transition)
        if (upperMotions.size != 3) return emptyList()
        val bassDirection = bassMotion(transition)?.direction ?: return emptyList()
        val findings = mutableListOf<RuleFinding<EventId>>()
        if (
            upperMotions.all {
                it.direction.isOppositeOf(bassDirection) && it.diatonicDistance in 1..2
            }
        ) {
            findings += indication(
                SECOND_SEVENTH_CONTRARY_SMOOTH,
                "根音二（七）度关系：低音级进，上方三声部反向作较平稳进行。",
                upperMotions,
            )
        }
        if (
            connection.key.mode == Mode.IONIAN &&
            connection.before.degree == DOMINANT_DEGREE &&
            connection.after.degree == SIXTH_DEGREE
        ) {
            val leadingToSixth = upperMotions.filter { motion ->
                motion.beforePitch.degree(connection.key) == LEADING_TONE_DEGREE &&
                    motion.afterPitch.degree(connection.key) == SIXTH_DEGREE &&
                    motion.before.voice.role != FixedVoiceRole.SOPRANO
            }
            if (leadingToSixth.isNotEmpty()) {
                findings += RuleFinding(
                    ruleId = MAJOR_DOMINANT_TO_SIXTH_INNER_LEADING_TONE,
                    kind = RuleFindingKind.INDICATION,
                    severity = RuleSeverity.HINT,
                    message = "大调 V-vi 中，内声部 7 级音可级进到 6 级。",
                    anchors = leadingToSixth.flatMap { listOf(it.before.id, it.after.id) },
                )
            }
        }
        return findings
    }

    private fun checkInnerLeadingToneLeap(
        upperMotions: List<UpperVoiceMotion>,
        key: Key,
    ): List<RuleFinding<EventId>> =
        upperMotions
            .filter { motion ->
                motion.beforePitch.degree(key) == LEADING_TONE_DEGREE &&
                    motion.diatonicDistance > 1 &&
                    motion.before.voice.role != FixedVoiceRole.SOPRANO
            }
            .map { motion ->
                RuleFinding(
                    ruleId = INNER_LEADING_TONE_LEAP,
                    kind = RuleFindingKind.INDICATION,
                    severity = RuleSeverity.HINT,
                    message = "导音 7 在内声部作跳进进行时可接受。",
                    anchors = listOf(motion.before.id, motion.after.id),
                )
            }

    private fun checkMinorRaisedFifthToFourth(
        transition: FixedVoiceTransition,
        connection: RootPositionTriadConnection,
    ): List<RuleFinding<EventId>> {
        if (connection.key.mode.signatureTonicDegree != 6) return emptyList()
        if (MinorAlteration.RAISED_5 !in connection.before.minorAlterations) return emptyList()
        val signatureRoot = KeySignatureMode.MINOR.signatureRootForTonic(connection.key.root)
        val signatureScale = com.mecon.theory.Scale.major(signatureRoot).pitchClasses
        val raisedFifth = signatureScale[4].transpose(1)
        val fourth = signatureScale[3]
        return upperVoiceMotions(transition)
            .filter { motion ->
                motion.beforePitch.pitchClass == raisedFifth && motion.afterPitch.pitchClass == fourth
            }
            .map { motion ->
                RuleFinding(
                    ruleId = MINOR_RAISED_FIFTH_TO_FOURTH,
                    kind = RuleFindingKind.VIOLATION,
                    severity = RuleSeverity.HARD,
                    message = "小调属和弦到六级时，升 5 不可进行到 4；这会形成不平顺的增二度。",
                    anchors = listOf(motion.before.id, motion.after.id),
                )
            }
    }

    private fun indication(
        ruleId: RuleId,
        message: String,
        motions: List<UpperVoiceMotion>,
    ): RuleFinding<EventId> =
        RuleFinding(
            ruleId = ruleId,
            kind = RuleFindingKind.INDICATION,
            severity = RuleSeverity.HINT,
            message = message,
            anchors = motions.flatMap { listOf(it.before.id, it.after.id) },
            relatedAnchors = listOf(
                RuleAnchorGroup(
                    role = RuleAnchorRole.RELATED,
                    anchors = motions.flatMap { listOf(it.before.id, it.after.id) },
                )
            ),
        )

    private fun leadingTonePitchClasses(key: Key, triad: NaturalTriad): Set<PitchClass> =
        buildSet {
            key.scale.pitchClasses.getOrNull(LEADING_TONE_DEGREE - 1)?.let { add(it) }
            if (MinorAlteration.RAISED_5 in triad.minorAlterations) {
                val signatureRoot = KeySignatureMode.MINOR.signatureRootForTonic(key.root)
                add(com.mecon.theory.Scale.major(signatureRoot).pitchClasses[4].transpose(1))
            }
        }

    private fun upperVoiceMotions(transition: FixedVoiceTransition): List<UpperVoiceMotion> {
        val previousByVoice = transition.previous.notes.associateBy { it.voice.id }
        return transition.current.notes.mapNotNull { after ->
            if (after.voice.role.isLowVoiceRole()) return@mapNotNull null
            val before = previousByVoice[after.voice.id] ?: return@mapNotNull null
            if (before.isRest || after.isRest) return@mapNotNull null
            UpperVoiceMotion(before, after)
        }
    }

    private fun bassMotion(transition: FixedVoiceTransition): BassMotion? {
        val before = lowVoice(transition.previous.notes) ?: return null
        val after = transition.current.notes.firstOrNull { it.voice.id == before.voice.id } ?: return null
        if (before.isRest || after.isRest) return null
        return BassMotion(before.requiredPitch(), after.requiredPitch())
    }

    private fun lowVoice(notes: List<FixedVoiceScoreEvent>): FixedVoiceScoreEvent? =
        notes.firstOrNull { it.voice.role.isLowVoiceRole() }
            ?: notes.minByOrNull { it.requiredPitch().midiNumber }

    private data class BassMotion(
        val beforePitch: Pitch,
        val afterPitch: Pitch,
    ) {
        val direction: VoiceMotionDirection = motionDirection(beforePitch, afterPitch)
        val isOctaveDisplacement: Boolean =
            beforePitch.pitchClass == afterPitch.pitchClass &&
                abs(afterPitch.midiNumber - beforePitch.midiNumber) % 12 == 0 &&
                beforePitch.midiNumber != afterPitch.midiNumber
    }

    private data class UpperVoiceMotion(
        val before: FixedVoiceScoreEvent,
        val after: FixedVoiceScoreEvent,
    ) {
        val beforePitch: Pitch = before.requiredPitch()
        val afterPitch: Pitch = after.requiredPitch()
        val direction: VoiceMotionDirection = motionDirection(beforePitch, afterPitch)
        val diatonicDistance: Int = abs(afterPitch.diatonicSteps - beforePitch.diatonicSteps)
        val isStep: Boolean = diatonicDistance == 1
        val isHeldCommonTone: Boolean =
            direction == VoiceMotionDirection.STATIONARY && beforePitch.pitchClass == afterPitch.pitchClass
    }

    private fun motionDirection(from: Pitch, to: Pitch): VoiceMotionDirection =
        when {
            to.midiNumber > from.midiNumber -> VoiceMotionDirection.UP
            to.midiNumber < from.midiNumber -> VoiceMotionDirection.DOWN
            else -> VoiceMotionDirection.STATIONARY
        }

    private fun VoiceMotionDirection.isOppositeOf(other: VoiceMotionDirection): Boolean =
        (this == VoiceMotionDirection.UP && other == VoiceMotionDirection.DOWN) ||
            (this == VoiceMotionDirection.DOWN && other == VoiceMotionDirection.UP)

    private fun FixedVoiceScoreEvent.requiredPitch(): Pitch =
        pitch ?: error("Expected note event ${id} to have a pitch")

    private fun FixedVoiceRole?.isLowVoiceRole(): Boolean =
        this == FixedVoiceRole.BASS || this == FixedVoiceRole.BARITONE

    private fun isFinalThreeVoiceTonic(voiceCount: Int, triad: NaturalTriad, isFinal: Boolean): Boolean =
        voiceCount == 3 && isFinal && triad.degree == TONIC_DEGREE

    private fun degreeDistance(before: Int, after: Int): Int {
        val up = (after - before).mod(DIATONIC_STEPS)
        return minOf(up, DIATONIC_STEPS - up)
    }

    private const val TONIC_DEGREE = 1
    private const val DOMINANT_DEGREE = 5
    private const val SIXTH_DEGREE = 6
    private const val LEADING_TONE_DEGREE = 7
    private const val DIATONIC_STEPS = 7
}
