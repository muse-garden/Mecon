package com.mecon.theory.textbook

import com.mecon.api.primitive.EventId
import com.mecon.api.primitive.Pitch
import com.mecon.api.primitive.PitchClass
import com.mecon.theory.Chord
import com.mecon.theory.ChordArity
import com.mecon.theory.ChordQuality
import com.mecon.theory.FixedVoice
import com.mecon.theory.FixedVoiceRole
import com.mecon.theory.FixedVoiceScoreEvent
import com.mecon.theory.FixedVoiceTransition
import com.mecon.theory.FixedVoiceVerticality
import com.mecon.theory.FixedVoiceWritingFrame
import com.mecon.theory.Key
import com.mecon.theory.KeySignatureMode
import com.mecon.theory.Mode
import com.mecon.theory.RuleAnchorGroup
import com.mecon.theory.RuleAnchorRole
import com.mecon.theory.RuleConfig
import com.mecon.theory.RuleFinding
import com.mecon.theory.RuleFindingKind
import com.mecon.theory.RuleId
import com.mecon.theory.RuleProfile
import com.mecon.theory.RuleScoreIntent
import com.mecon.theory.RuleSeverity
import com.mecon.theory.VoiceMotionDirection
import com.mecon.theory.degree
import com.mecon.theory.toVerticality
import com.mecon.theory.constraint.ChordTarget
import com.mecon.theory.constraint.ChordTone
import kotlin.math.abs

enum class TextbookSeventhPosition {
    ROOT_POSITION,
    FIRST_INVERSION,
    SECOND_INVERSION,
    THIRD_INVERSION,
}

data class TextbookSeventhChord(
    val key: Key,
    val degree: Int,
    val chord: Chord,
) {
    val root: PitchClass get() = chord.root
    val quality: ChordQuality get() = chord.quality
}

data class TextbookSeventhTarget(
    val chord: TextbookSeventhChord,
    val position: TextbookSeventhPosition,
) : ChordTarget {
    override val key: Key get() = chord.key
    override val sonority: Chord get() = this.chord.chord
    override val degree: Int get() = this.chord.degree
    override val quality: ChordQuality get() = this.chord.quality
    override val inversion: Int get() = position.ordinal
    override val arity: ChordArity get() = ChordArity.SEVENTH
    override val bassPitchClass: PitchClass get() = this.chord.chord.pitchClasses[position.ordinal]

    override fun pitchClassFor(tone: ChordTone): PitchClass? =
        when (tone) {
            ChordTone.ROOT -> this.chord.root
            ChordTone.THIRD -> this.chord.chord.pitchClasses.getOrNull(1)
            ChordTone.FIFTH -> this.chord.chord.pitchClasses.getOrNull(2)
            ChordTone.SEVENTH -> this.chord.chord.pitchClasses.getOrNull(3)
            ChordTone.BASS -> bassPitchClass
        }

    override fun identityKey(): String =
        "seventh:${this.chord.degree}:${this.chord.quality}:${this.chord.chord.pitchClasses.size}:${position.ordinal}"
}

data class DominantSeventhConnection(
    val key: Key,
    val before: TextbookSeventhChord,
    val beforePosition: TextbookSeventhPosition,
    val after: TextbookSeventhChord,
    val afterPosition: TextbookSeventhPosition,
) {
    init {
        require(before.key == key) { "Before chord must belong to the connection key" }
        require(after.key == key) { "After chord must belong to the connection key" }
    }
}

object DominantSeventhRules {
    val SEVENTH_RESOLVES_DOWN = RuleId("textbook.dominant-seventh.seventh-resolves-down")
    val SEVENTH_ASCENDS = RuleId("textbook.dominant-seventh.seventh-ascends")
    val DOMINANT_SEVENTH_QUALITY = RuleId("textbook.dominant-seventh.quality")
    val MINOR_REQUIRES_LEADING_TONE = RuleId("textbook.dominant-seventh.minor-requires-leading-tone")
    val OUTER_LEADING_TONE_RESOLUTION = RuleId("textbook.dominant-seventh.outer-leading-tone-resolution")
    val ROOT_V7_TO_I_OMITTED_FIFTH = RuleId("textbook.dominant-seventh.root-v7-i-omitted-fifth")
    val ROOT_V7_COMPLETE_I_PARALLEL_FIFTH = RuleId("textbook.dominant-seventh.root-v7-complete-i-parallel-fifth")
    val INCOMPLETE_V7_TO_COMPLETE_I = RuleId("textbook.dominant-seventh.incomplete-v7-complete-i")
    val INNER_LEADING_TONE_COMPLETE_I = RuleId("textbook.dominant-seventh.inner-leading-tone-complete-i")
    val DECEPTIVE_RESOLUTION = RuleId("textbook.dominant-seventh.deceptive-resolution")
    val INVERSION_TENDENCY_TONES = RuleId("textbook.dominant-seventh.inversion-tendency-tones")
    val SECOND_INVERSION_PASSING = RuleId("textbook.dominant-seventh.second-inversion-passing")
    val THIRD_INVERSION_TO_I6 = RuleId("textbook.dominant-seventh.third-inversion-to-i6")
    val PREPARATION_SUSPENSION = RuleId("textbook.dominant-seventh.preparation-suspension")
    val PREPARATION_PASSING = RuleId("textbook.dominant-seventh.preparation-passing")
    val PREPARATION_NEIGHBOR = RuleId("textbook.dominant-seventh.preparation-neighbor")
    val PREPARATION_APPOGGIATURA = RuleId("textbook.dominant-seventh.preparation-appoggiatura")
    val PREPARATION_ABOVE_LEAP = RuleId("textbook.dominant-seventh.preparation-above-leap")
    val OMIT_FIFTH_PREFERRED = RuleId("textbook.dominant-seventh.omit-fifth-preferred")
    val OMIT_THIRD_SECONDARY = RuleId("textbook.dominant-seventh.omit-third-secondary")
    val ROOT_OR_SEVENTH_OMITTED = RuleId("textbook.dominant-seventh.root-or-seventh-omitted")
    val SUPERTONIC_TO_DOMINANT = RuleId("textbook.dominant-seventh.supertonic-to-dominant")
    val SUPERTONIC_TO_CADENTIAL_SIX_FOUR = RuleId("textbook.dominant-seventh.supertonic-to-cadential-six-four")
    val SUPERTONIC_TO_LEADING = RuleId("textbook.dominant-seventh.supertonic-to-leading")
    val MAJOR_LEADING_HALF_DIMINISHED = RuleId("textbook.dominant-seventh.major-leading-half-diminished")
    val MINOR_LEADING_DIMINISHED = RuleId("textbook.dominant-seventh.minor-leading-diminished")
    val LEADING_TO_TONIC = RuleId("textbook.dominant-seventh.leading-to-tonic")
    val LEADING_TO_DOMINANT_SEVENTH = RuleId("textbook.dominant-seventh.leading-to-dominant-seventh")
    val LEADING_TONIC_DOUBLES_THIRD = RuleId("textbook.dominant-seventh.leading-tonic-doubles-third")
    val MINOR_LEADING_DIM5_TO_PERF5 = RuleId("textbook.dominant-seventh.minor-leading-dim5-to-perf5")
    val CIRCLE_OF_FIFTHS_SEVENTHS = RuleId("textbook.dominant-seventh.circle-of-fifths-sevenths")
    val CIRCLE_ROOT_POSITION_ALTERNATION = RuleId("textbook.dominant-seventh.circle-root-position-alternation")
    val CIRCLE_FIRST_THIRD_INVERSION = RuleId("textbook.dominant-seventh.circle-first-third-inversion")
    val CIRCLE_SECOND_ROOT_INVERSION = RuleId("textbook.dominant-seventh.circle-second-root-inversion")

    val INTRODUCTORY_PROFILE = RuleProfile(
        id = "textbook.dominant-seventh.introductory",
        overrides = mapOf(
            MelodyTextbookRules.LEADING_TONE_RESOLUTION to RuleConfig(severityOverride = RuleSeverity.HINT),
        ),
    )

    fun dominantSeventhInKey(key: Key): TextbookSeventhChord =
        seventhChordInKey(key, DOMINANT_DEGREE)

    fun seventhChordInKey(key: Key, degree: Int): TextbookSeventhChord {
        require(degree in 1..7) { "degree must be in 1..7" }
        return TextbookSeventhChord(
            key = key,
            degree = degree,
            chord = Chord(
                root = rootPitchClassForDegree(key, degree),
                quality = seventhQualityInKey(key, degree),
            ),
        )
    }

    fun tonicTriadInKey(key: Key): TextbookSeventhChord =
        TextbookSeventhChord(
            key = key,
            degree = TONIC_DEGREE,
            chord = Chord(
                root = key.scale.pitchClasses[TONIC_DEGREE - 1],
                quality = if (key.mode == Mode.IONIAN) ChordQuality.MAJOR else ChordQuality.MINOR,
            ),
        )

    fun submediantTriadInKey(key: Key): TextbookSeventhChord =
        TextbookSeventhChord(
            key = key,
            degree = SUBMEDIANT_DEGREE,
            chord = Chord(
                root = key.scale.pitchClasses[SUBMEDIANT_DEGREE - 1],
                quality = if (key.mode == Mode.IONIAN) ChordQuality.MINOR else ChordQuality.MAJOR,
            ),
        )

    fun leadingSeventhInKey(key: Key): TextbookSeventhChord =
        seventhChordInKey(key, LEADING_TONE_DEGREE)

    /**
     * 某音级上的自然三和弦，包装为 [TextbookSeventhChord]（3 音）。供七和弦进行中的三和弦解决和弦
     * （I / VI / 终止四六的 I⁶₄ 等）使用；[tonicTriadInKey] / [submediantTriadInKey] 是其特例。
     */
    fun triadInKey(key: Key, degree: Int): TextbookSeventhChord {
        require(degree in 1..7) { "degree must be in 1..7" }
        return TextbookSeventhChord(
            key = key,
            degree = degree,
            chord = textbookTriadInKey(key, degree).chord,
        )
    }

    fun checkVerticality(
        verticality: FixedVoiceVerticality,
        target: TextbookSeventhTarget,
        key: Key,
    ): List<RuleFinding<EventId>> {
        val notes = verticality.notes.filterNot { it.isRest }
        if (notes.isEmpty()) return emptyList()
        val chord = target.chord
        return buildList {
            if (chord.degree == DOMINANT_DEGREE) {
                if (chord.quality != ChordQuality.DOMINANT7) {
                    add(
                        RuleFinding(
                            ruleId = DOMINANT_SEVENTH_QUALITY,
                            kind = RuleFindingKind.VIOLATION,
                            severity = RuleSeverity.HARD,
                            message = "属七和弦应是大小七和弦。",
                            anchors = notes.map { it.id },
                        )
                    )
                }
                if (key.mode == Mode.AEOLIAN && leadingTonePitchClass(key) !in chord.chord.pitchClasses) {
                    add(
                        RuleFinding(
                            ruleId = MINOR_REQUIRES_LEADING_TONE,
                            kind = RuleFindingKind.VIOLATION,
                            severity = RuleSeverity.HARD,
                            message = "小调属七必须包含升高的导音；还原 5 不能称作属七。",
                            anchors = notes.map { it.id },
                        )
                    )
                }
            }
            if (chord.degree == LEADING_TONE_DEGREE) {
                addAll(checkLeadingSeventhQuality(notes, chord, key))
            }
            if (chord.chord.pitchClasses.size >= SEVENTH_CHORD_SIZE) {
                addAll(checkSeventhChordOmissions(notes, chord))
            }
        }
    }

    fun checkTransition(
        transition: FixedVoiceTransition,
        connection: DominantSeventhConnection,
        includeConstraintMigratedRules: Boolean = true,
    ): List<RuleFinding<EventId>> {
        val before = connection.before
        val previousNotes = transition.previous.notes.filterNot { it.isRest }
        val currentNotes = transition.current.notes.filterNot { it.isRest }
        val previousByVoice = previousNotes.associateBy { it.voice.id }
        val currentByVoice = currentNotes.associateBy { it.voice.id }
        return buildList {
            if (includeConstraintMigratedRules && before.chord.pitchClasses.size >= SEVENTH_CHORD_SIZE) {
                addAll(checkSeventhResolution(connection.key, before, previousByVoice, currentByVoice))
            }
            if (before.degree == DOMINANT_DEGREE && before.quality == ChordQuality.DOMINANT7) {
                if (includeConstraintMigratedRules) {
                    addAll(checkOuterLeadingToneResolution(connection.key, before, previousByVoice, currentByVoice))
                }
                addAll(checkDominantResolutionPatterns(transition, connection))
            }
            addAll(checkSupertonicResolution(previousNotes, currentNotes, connection))
            addAll(checkLeadingSeventhResolution(transition, previousNotes, currentNotes, connection))
        }.distinctBy { it.ruleId to it.anchors }
    }

    fun checkCircleOfFifthsSequence(
        frames: List<FixedVoiceWritingFrame<TextbookSeventhTarget>>,
        voices: List<FixedVoice>,
    ): List<RuleFinding<EventId>> {
        val degrees = frames.map { it.target.chord.degree }
        if (degrees != CIRCLE_OF_FIFTHS_DEGREES) return emptyList()
        val findings = mutableListOf<RuleFinding<EventId>>()
        val allButFinalAreSevenths = frames.dropLast(1).all { it.target.chord.chord.pitchClasses.size >= SEVENTH_CHORD_SIZE }
        if (allButFinalAreSevenths) {
            findings += scoreFinding(
                CIRCLE_OF_FIFTHS_SEVENTHS,
                "五度圈模进 4-7-3-6-2-5-1 中，除最后主和弦外可全部替换成七和弦。",
                frames,
                voices,
            )
        }
        val leading = frames.dropLast(1)
        if (leading.all { it.target.position == TextbookSeventhPosition.ROOT_POSITION } && completeAndOmittedFifthAlternate(leading, voices)) {
            findings += scoreFinding(
                CIRCLE_ROOT_POSITION_ALTERNATION,
                "原位七和弦五度圈模进中，完全和弦与省略五音和弦交替出现。",
                frames,
                voices,
            )
        }
        val firstThird = listOf(TextbookSeventhPosition.FIRST_INVERSION, TextbookSeventhPosition.THIRD_INVERSION)
        if (leading.map { it.target.position }.allIndexedAlternates(firstThird)) {
            findings += scoreFinding(
                CIRCLE_FIRST_THIRD_INVERSION,
                "转位七和弦五度圈模进可用第一转位与第三转位交替。",
                frames,
                voices,
            )
        }
        val secondRoot = listOf(TextbookSeventhPosition.SECOND_INVERSION, TextbookSeventhPosition.ROOT_POSITION)
        if (leading.map { it.target.position }.allIndexedAlternates(secondRoot)) {
            findings += scoreFinding(
                CIRCLE_SECOND_ROOT_INVERSION,
                "转位七和弦五度圈模进可用第二转位与原位交替。",
                frames,
                voices,
            )
        }
        return findings
    }

    private fun checkLeadingSeventhQuality(
        notes: List<FixedVoiceScoreEvent>,
        chord: TextbookSeventhChord,
        key: Key,
    ): List<RuleFinding<EventId>> {
        val expected = if (key.mode == Mode.IONIAN) ChordQuality.HALF_DIMINISHED7 else ChordQuality.DIMINISHED7
        val ruleId = if (key.mode == Mode.IONIAN) MAJOR_LEADING_HALF_DIMINISHED else MINOR_LEADING_DIMINISHED
        val message = if (key.mode == Mode.IONIAN) {
            "大调导七和弦应是半减七和弦。"
        } else {
            "小调导七和弦包含升导音，应是完全减七和弦。"
        }
        return listOf(
            RuleFinding(
                ruleId = ruleId,
                kind = if (chord.quality == expected) RuleFindingKind.INDICATION else RuleFindingKind.VIOLATION,
                severity = if (chord.quality == expected) RuleSeverity.HINT else RuleSeverity.HARD,
                message = message,
                anchors = notes.map { it.id },
            )
        )
    }

    private fun checkSeventhChordOmissions(
        notes: List<FixedVoiceScoreEvent>,
        chord: TextbookSeventhChord,
    ): List<RuleFinding<EventId>> {
        val chordTones = chord.chord.pitchClasses
        val byPitchClass = notes.groupBy { it.requiredPitch().pitchClass }
        val missingIndices = chordTones.indices.filter { chordTones[it] !in byPitchClass.keys }
        return missingIndices.map { index ->
            when (index) {
                ROOT_INDEX, SEVENTH_INDEX -> RuleFinding(
                    ruleId = ROOT_OR_SEVENTH_OMITTED,
                    kind = RuleFindingKind.VIOLATION,
                    severity = RuleSeverity.HARD,
                    message = "七和弦不应省略根音或七音。",
                    anchors = notes.map { it.id },
                )
                FIFTH_INDEX -> RuleFinding(
                    ruleId = OMIT_FIFTH_PREFERRED,
                    kind = RuleFindingKind.INDICATION,
                    severity = RuleSeverity.HINT,
                    message = "七和弦需要省略和弦音时，省略五音最自然。",
                    anchors = notes.map { it.id },
                    scoreIntent = RuleScoreIntent.EXPLANATORY,
                )
                THIRD_INDEX -> RuleFinding(
                    ruleId = OMIT_THIRD_SECONDARY,
                    kind = RuleFindingKind.HINT,
                    severity = RuleSeverity.HINT,
                    message = "七和弦若必须省略，三音次于五音；根音和七音不可省略。",
                    anchors = notes.map { it.id },
                )
                else -> error("Unexpected seventh chord tone index $index")
            }
        }
    }

    private fun checkSeventhResolution(
        key: Key,
        chord: TextbookSeventhChord,
        previousByVoice: Map<com.mecon.api.primitive.TrackId, FixedVoiceScoreEvent>,
        currentByVoice: Map<com.mecon.api.primitive.TrackId, FixedVoiceScoreEvent>,
    ): List<RuleFinding<EventId>> {
        val seventh = chord.chord.pitchClasses[SEVENTH_INDEX]
        return previousByVoice.values
            .filter { it.requiredPitch().pitchClass == seventh }
            .mapNotNull { before ->
                val after = currentByVoice[before.voice.id] ?: return@mapNotNull null
                val beforePitch = before.requiredPitch()
                val afterPitch = after.requiredPitch()
                when {
                    resolvesDownByStep(beforePitch, afterPitch) -> RuleFinding(
                        ruleId = SEVENTH_RESOLVES_DOWN,
                        kind = RuleFindingKind.INDICATION,
                        severity = RuleSeverity.HINT,
                        message = "七和弦的七音通常下行级进解决。",
                        anchors = listOf(before.id, after.id),
                    )
                    afterPitch.diatonicSteps - beforePitch.diatonicSteps == 1 -> RuleFinding(
                        ruleId = SEVENTH_ASCENDS,
                        kind = RuleFindingKind.VIOLATION,
                        severity = RuleSeverity.SOFT,
                        message = "七音上行解决通常需要特殊语境；本章先作为错误对照。",
                        anchors = listOf(before.id, after.id),
                    )
                    else -> RuleFinding(
                        ruleId = SEVENTH_RESOLVES_DOWN,
                        kind = RuleFindingKind.VIOLATION,
                        severity = RuleSeverity.HARD,
                        message = "七音应下行级进解决。",
                        anchors = listOf(before.id, after.id),
                    )
                }
            }
    }

    private fun checkOuterLeadingToneResolution(
        key: Key,
        chord: TextbookSeventhChord,
        previousByVoice: Map<com.mecon.api.primitive.TrackId, FixedVoiceScoreEvent>,
        currentByVoice: Map<com.mecon.api.primitive.TrackId, FixedVoiceScoreEvent>,
    ): List<RuleFinding<EventId>> {
        val leadingTone = chord.chord.pitchClasses[THIRD_INDEX]
        return previousByVoice.values
            .filter { it.voice.role == FixedVoiceRole.SOPRANO || it.voice.role == FixedVoiceRole.BASS }
            .filter { it.requiredPitch().pitchClass == leadingTone }
            .mapNotNull { before ->
                val after = currentByVoice[before.voice.id] ?: return@mapNotNull null
                val ok = after.requiredPitch().degree(key) == TONIC_DEGREE &&
                    after.requiredPitch().diatonicSteps - before.requiredPitch().diatonicSteps == 1
                RuleFinding(
                    ruleId = OUTER_LEADING_TONE_RESOLUTION,
                    kind = if (ok) RuleFindingKind.INDICATION else RuleFindingKind.VIOLATION,
                    severity = if (ok) RuleSeverity.HINT else RuleSeverity.HARD,
                    message = if (ok) {
                        "外声部导音上行级进解决到主音。"
                    } else {
                        "导音在外声部时必须上行级进解决到主音。"
                    },
                    anchors = listOf(before.id, after.id),
                )
            }
    }

    private fun checkDominantResolutionPatterns(
        transition: FixedVoiceTransition,
        connection: DominantSeventhConnection,
    ): List<RuleFinding<EventId>> {
        val after = connection.after
        val previousNotes = transition.previous.notes.filterNot { it.isRest }
        val currentNotes = transition.current.notes.filterNot { it.isRest }
        return buildList {
            if (after.degree == SUBMEDIANT_DEGREE) {
                add(
                    RuleFinding(
                        ruleId = DECEPTIVE_RESOLUTION,
                        kind = RuleFindingKind.INDICATION,
                        severity = RuleSeverity.HINT,
                        message = "V7 后可以接 VI 构成阻碍进行。",
                        anchors = currentNotes.map { it.id },
                        relatedAnchors = listOf(RuleAnchorGroup(RuleAnchorRole.SOURCE, previousNotes.map { it.id })),
                        scoreIntent = RuleScoreIntent.EXPLANATORY,
                    )
                )
            }
            if (after.degree != TONIC_DEGREE) return@buildList
            if (connection.beforePosition == TextbookSeventhPosition.ROOT_POSITION) {
                addAll(checkRootV7ToI(transition, connection, previousNotes, currentNotes))
            } else {
                val tendencyOk = tendencyTonesResolve(connection.key, transition, connection.before)
                if (tendencyOk) {
                    add(
                        contextualIndication(
                            INVERSION_TENDENCY_TONES,
                            "转位 V7 解决时，三音上行到主音，七音下行到三音。",
                            previousNotes,
                            currentNotes,
                        )
                    )
                }
                if (connection.beforePosition == TextbookSeventhPosition.SECOND_INVERSION && tendencyOk) {
                    add(
                        contextualIndication(
                            SECOND_INVERSION_PASSING,
                            "V7 第二转位常像经过四六一样使用，低音级进，上方声部采用平稳进行。",
                            previousNotes,
                            currentNotes,
                        )
                    )
                }
                if (
                    connection.beforePosition == TextbookSeventhPosition.THIRD_INVERSION &&
                    connection.afterPosition == TextbookSeventhPosition.FIRST_INVERSION
                ) {
                    add(
                        contextualIndication(
                            THIRD_INVERSION_TO_I6,
                            "V7 第三转位因七音在低音下行解决，几乎总是接 I6。",
                            previousNotes,
                            currentNotes,
                        )
                    )
                }
            }
        }
    }

    private fun checkSupertonicResolution(
        previousNotes: List<FixedVoiceScoreEvent>,
        currentNotes: List<FixedVoiceScoreEvent>,
        connection: DominantSeventhConnection,
    ): List<RuleFinding<EventId>> {
        if (connection.before.degree != SUPERTONIC_DEGREE) return emptyList()
        return buildList {
            when (connection.after.degree) {
                DOMINANT_DEGREE -> add(
                    contextualIndication(
                        SUPERTONIC_TO_DOMINANT,
                        "II7 通常进行到 V，构成 II-V-I 的八度圈。",
                        previousNotes,
                        currentNotes,
                    )
                )
                TONIC_DEGREE -> if (connection.afterPosition == TextbookSeventhPosition.SECOND_INVERSION) {
                    add(
                        contextualIndication(
                            SUPERTONIC_TO_CADENTIAL_SIX_FOUR,
                            "II7 到 V 前可插入终止四六。",
                            previousNotes,
                            currentNotes,
                        )
                    )
                }
                LEADING_TONE_DEGREE -> add(
                    contextualIndication(
                        SUPERTONIC_TO_LEADING,
                        "II7 后可用导和弦替换 V。",
                        previousNotes,
                        currentNotes,
                    )
                )
            }
        }
    }

    private fun checkLeadingSeventhResolution(
        transition: FixedVoiceTransition,
        previousNotes: List<FixedVoiceScoreEvent>,
        currentNotes: List<FixedVoiceScoreEvent>,
        connection: DominantSeventhConnection,
    ): List<RuleFinding<EventId>> {
        if (connection.before.degree != LEADING_TONE_DEGREE) return emptyList()
        return buildList {
            if (connection.after.degree == DOMINANT_DEGREE && connection.after.quality == ChordQuality.DOMINANT7) {
                add(
                    contextualIndication(
                        LEADING_TO_DOMINANT_SEVENTH,
                        "导七和弦可将七音下行级进，先到达属七和弦。",
                        previousNotes,
                        currentNotes,
                    )
                )
            }
            if (connection.after.degree == TONIC_DEGREE) {
                add(
                    contextualIndication(
                        LEADING_TO_TONIC,
                        "导七和弦具有属功能，可直接解决到主和弦。",
                        previousNotes,
                        currentNotes,
                    )
                )
                val tonicTones = connection.after.chord.pitchClasses
                val currentByPitchClass = currentNotes.groupBy { it.requiredPitch().pitchClass }
                if (currentByPitchClass[tonicTones[THIRD_INDEX]].orEmpty().size >= 2) {
                    add(
                        contextualIndication(
                            LEADING_TONIC_DOUBLES_THIRD,
                            "导七解决到主和弦时，为避开平行五度，主和弦可重复三音。",
                            previousNotes,
                            currentNotes,
                        )
                    )
                }
                if (hasParallelPerfectFifth(transition)) {
                    add(
                        RuleFinding(
                            ruleId = ROOT_V7_COMPLETE_I_PARALLEL_FIFTH,
                            kind = RuleFindingKind.VIOLATION,
                            severity = RuleSeverity.HARD,
                            message = "导七解决到主和弦时需避免平行五度。",
                            anchors = previousNotes.map { it.id } + currentNotes.map { it.id },
                        )
                    )
                }
                if (connection.key.mode == Mode.AEOLIAN && hasDiminishedFifthToPerfectFifth(transition)) {
                    add(
                        RuleFinding(
                            ruleId = MINOR_LEADING_DIM5_TO_PERF5,
                            kind = RuleFindingKind.VIOLATION,
                            severity = RuleSeverity.SOFT,
                            message = "小调导七到主和弦可能出现减五度到纯五度；古典主义时期通常会避让。",
                            anchors = previousNotes.map { it.id } + currentNotes.map { it.id },
                        )
                    )
                }
            }
        }
    }

    private fun checkRootV7ToI(
        transition: FixedVoiceTransition,
        connection: DominantSeventhConnection,
        previousNotes: List<FixedVoiceScoreEvent>,
        currentNotes: List<FixedVoiceScoreEvent>,
    ): List<RuleFinding<EventId>> {
        val tonic = connection.after.chord.pitchClasses
        val byPitchClass = currentNotes.groupBy { it.requiredPitch().pitchClass }
        val fifthCount = byPitchClass[tonic[FIFTH_INDEX]].orEmpty().size
        return buildList {
            if (fifthCount > 0 && hasParallelPerfectFifth(transition)) {
                add(
                    RuleFinding(
                        ruleId = ROOT_V7_COMPLETE_I_PARALLEL_FIFTH,
                        kind = RuleFindingKind.VIOLATION,
                        severity = RuleSeverity.HARD,
                        message = "若完整 V7-I 为保留完整 I 而造成平行五度，即使导音上行解决也不可接受。",
                        anchors = previousNotes.map { it.id } + currentNotes.map { it.id },
                    )
                )
            }
        }
    }

    private fun tendencyTonesResolve(
        key: Key,
        transition: FixedVoiceTransition,
        chord: TextbookSeventhChord,
    ): Boolean {
        val beforeByVoice = transition.previous.notes.associateBy { it.voice.id }
        val afterByVoice = transition.current.notes.associateBy { it.voice.id }
        val third = chord.chord.pitchClasses[THIRD_INDEX]
        val seventh = chord.chord.pitchClasses[SEVENTH_INDEX]
        val thirdOk = beforeByVoice.values
            .filter { it.requiredPitch().pitchClass == third }
            .all { before ->
                val after = afterByVoice[before.voice.id] ?: return@all false
                after.requiredPitch().degree(key) == TONIC_DEGREE &&
                    after.requiredPitch().diatonicSteps - before.requiredPitch().diatonicSteps == 1
            }
        val seventhOk = beforeByVoice.values
            .filter { it.requiredPitch().pitchClass == seventh }
            .all { before ->
                val after = afterByVoice[before.voice.id] ?: return@all false
                resolvesDownByStep(before.requiredPitch(), after.requiredPitch())
            }
        return thirdOk && seventhOk
    }

    private fun contextualIndication(
        ruleId: RuleId,
        message: String,
        previousNotes: List<FixedVoiceScoreEvent>,
        currentNotes: List<FixedVoiceScoreEvent>,
    ): RuleFinding<EventId> =
        RuleFinding(
            ruleId = ruleId,
            kind = RuleFindingKind.INDICATION,
            severity = RuleSeverity.HINT,
            message = message,
            anchors = currentNotes.map { it.id },
            relatedAnchors = listOf(RuleAnchorGroup(RuleAnchorRole.SOURCE, previousNotes.map { it.id })),
        )

    private fun scoreFinding(
        ruleId: RuleId,
        message: String,
        frames: List<FixedVoiceWritingFrame<TextbookSeventhTarget>>,
        voices: List<FixedVoice>,
    ): RuleFinding<EventId> =
        RuleFinding(
            ruleId = ruleId,
            kind = RuleFindingKind.INDICATION,
            severity = RuleSeverity.HINT,
            message = message,
            anchors = frames.flatMap { frame -> frame.toVerticality(voices).notes.map { it.id } },
        )

    private fun hasParallelPerfectFifth(transition: FixedVoiceTransition): Boolean {
        val previous = transition.previous.notes.filterNot { it.isRest }
        val current = transition.current.notes.filterNot { it.isRest }
        val currentByVoice = current.associateBy { it.voice.id }
        return previous.indices.any { i ->
            ((i + 1) until previous.size).any { j ->
                val a1 = previous[i]
                val b1 = previous[j]
                val a2 = currentByVoice[a1.voice.id] ?: return@any false
                val b2 = currentByVoice[b1.voice.id] ?: return@any false
                val dirA = motionDirection(a1.requiredPitch(), a2.requiredPitch())
                val dirB = motionDirection(b1.requiredPitch(), b2.requiredPitch())
                dirA != VoiceMotionDirection.STATIONARY &&
                    dirA == dirB &&
                    isPerfectFifth(a1.requiredPitch(), b1.requiredPitch()) &&
                    isPerfectFifth(a2.requiredPitch(), b2.requiredPitch())
            }
        }
    }

    private fun hasDiminishedFifthToPerfectFifth(transition: FixedVoiceTransition): Boolean {
        val previous = transition.previous.notes.filterNot { it.isRest }
        val current = transition.current.notes.filterNot { it.isRest }
        val currentByVoice = current.associateBy { it.voice.id }
        return previous.indices.any { i ->
            ((i + 1) until previous.size).any { j ->
                val a1 = previous[i]
                val b1 = previous[j]
                val a2 = currentByVoice[a1.voice.id] ?: return@any false
                val b2 = currentByVoice[b1.voice.id] ?: return@any false
                semitoneInterval(a1.requiredPitch(), b1.requiredPitch()) == TRITONE &&
                    semitoneInterval(a2.requiredPitch(), b2.requiredPitch()) == PERFECT_FIFTH
            }
        }
    }

    private fun isPerfectFifth(a: Pitch, b: Pitch): Boolean =
        abs(a.midiNumber - b.midiNumber).mod(OCTAVE) == PERFECT_FIFTH

    private fun semitoneInterval(a: Pitch, b: Pitch): Int =
        abs(a.midiNumber - b.midiNumber).mod(OCTAVE)

    private fun resolvesDownByStep(from: Pitch, to: Pitch): Boolean =
        to.diatonicSteps - from.diatonicSteps == -1

    private fun leadingTonePitchClass(key: Key): PitchClass =
        if (key.mode == Mode.AEOLIAN) {
            val signatureRoot = KeySignatureMode.MINOR.signatureRootForTonic(key.root)
            com.mecon.theory.Scale.major(signatureRoot).pitchClasses[4].transpose(1)
        } else {
            key.scale.pitchClasses[LEADING_TONE_DEGREE - 1]
        }

    private fun rootPitchClassForDegree(key: Key, degree: Int): PitchClass =
        if (key.mode == Mode.AEOLIAN && degree == LEADING_TONE_DEGREE) {
            leadingTonePitchClass(key)
        } else {
            key.scale.pitchClasses[degree - 1]
        }

    private fun seventhQualityInKey(key: Key, degree: Int): ChordQuality =
        if (key.mode == Mode.IONIAN) {
            when (degree) {
                TONIC_DEGREE, SUBDOMINANT_DEGREE -> ChordQuality.MAJOR7
                SUPERTONIC_DEGREE, MEDIANT_DEGREE, SUBMEDIANT_DEGREE -> ChordQuality.MINOR7
                DOMINANT_DEGREE -> ChordQuality.DOMINANT7
                LEADING_TONE_DEGREE -> ChordQuality.HALF_DIMINISHED7
                else -> error("Unsupported degree $degree")
            }
        } else {
            when (degree) {
                TONIC_DEGREE, SUBDOMINANT_DEGREE -> ChordQuality.MINOR7
                SUPERTONIC_DEGREE -> ChordQuality.HALF_DIMINISHED7
                MEDIANT_DEGREE, SUBMEDIANT_DEGREE -> ChordQuality.MAJOR7
                DOMINANT_DEGREE -> ChordQuality.DOMINANT7
                LEADING_TONE_DEGREE -> ChordQuality.DIMINISHED7
                else -> error("Unsupported degree $degree")
            }
        }

    private fun completeAndOmittedFifthAlternate(
        frames: List<FixedVoiceWritingFrame<TextbookSeventhTarget>>,
        voices: List<FixedVoice>,
    ): Boolean =
        frames.map { frame ->
            val chordTones = frame.target.chord.chord.pitchClasses
            val pcs = voices.map { frame.pitchFor(it).pitchClass }.toSet()
            when {
                chordTones[FIFTH_INDEX] !in pcs -> false
                chordTones.all { it in pcs } -> true
                else -> return false
            }
        }.zipWithNext().all { (before, after) -> before != after }

    private fun List<TextbookSeventhPosition>.allIndexedAlternates(
        pattern: List<TextbookSeventhPosition>,
    ): Boolean =
        isNotEmpty() && withIndex().all { (index, position) -> position == pattern[index.mod(pattern.size)] }

    private fun motionDirection(from: Pitch, to: Pitch): VoiceMotionDirection =
        when {
            to.midiNumber > from.midiNumber -> VoiceMotionDirection.UP
            to.midiNumber < from.midiNumber -> VoiceMotionDirection.DOWN
            else -> VoiceMotionDirection.STATIONARY
        }

    private fun FixedVoiceScoreEvent.requiredPitch(): Pitch =
        pitch ?: error("Expected note event ${id} to have a pitch")

    private const val ROOT_INDEX = 0
    private const val THIRD_INDEX = 1
    private const val FIFTH_INDEX = 2
    private const val SEVENTH_INDEX = 3
    private const val TONIC_DEGREE = 1
    private const val SUPERTONIC_DEGREE = 2
    private const val MEDIANT_DEGREE = 3
    private const val SUBDOMINANT_DEGREE = 4
    private const val DOMINANT_DEGREE = 5
    private const val SUBMEDIANT_DEGREE = 6
    private const val LEADING_TONE_DEGREE = 7
    private const val SEVENTH_CHORD_SIZE = 4
    private const val PERFECT_FIFTH = 7
    private const val TRITONE = 6
    private const val OCTAVE = 12
    private val CIRCLE_OF_FIFTHS_DEGREES = listOf(4, 7, 3, 6, 2, 5, 1)
}
