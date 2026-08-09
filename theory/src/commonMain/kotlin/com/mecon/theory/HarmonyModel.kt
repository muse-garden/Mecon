package com.mecon.theory

import kotlin.jvm.JvmInline

import com.mecon.api.primitive.Accidental
import com.mecon.api.primitive.KeySignature
import com.mecon.api.primitive.NoteName
import com.mecon.api.primitive.Pitch
import com.mecon.api.primitive.PitchClass
import com.mecon.api.primitive.TrackId

@JvmInline
value class TonalContextId(val value: String) {
    init { require(value.isNotBlank()) { "TonalContextId must not be blank" } }
    override fun toString(): String = value
}

@JvmInline
value class ScaleDefinitionId(val value: String) {
    init { require(value.isNotBlank()) { "ScaleDefinitionId must not be blank" } }
    override fun toString(): String = value
}

@JvmInline
value class ChordDefinitionId(val value: String) {
    init { require(value.isNotBlank()) { "ChordDefinitionId must not be blank" } }
    override fun toString(): String = value
}

@JvmInline
value class ChordMemberId(val value: String) {
    init { require(value.isNotBlank()) { "ChordMemberId must not be blank" } }
    override fun toString(): String = value
}

/**
 * Octave-independent written pitch. Unlike [PitchClass], enharmonic spelling is retained.
 */
data class SpelledPitchClass(
    val noteName: NoteName,
    val chromaticOffset: Int = 0,
) {
    val accidental: Accidental get() = Accidental.fromOffset(chromaticOffset)
    val pitchClass: PitchClass
        get() = PitchClass((noteName.toSemitone() + chromaticOffset).mod(OCTAVE_SEMITONES))

    fun pitchAt(octave: Int): Pitch =
        Pitch.of(noteName, octave, Accidental.fromOffset(chromaticOffset)).let { pitch ->
            if (pitch.chromaticOffset == chromaticOffset) pitch
            else Pitch(pitch.diatonicSteps, chromaticOffset)
        }

    fun displayName(): String = noteName.name + when {
        chromaticOffset > 0 -> "♯".repeat(chromaticOffset)
        chromaticOffset < 0 -> "♭".repeat(-chromaticOffset)
        else -> ""
    }

    override fun toString(): String =
        noteName.name + accidental.asciiSymbol

    companion object {
        fun fromPitch(pitch: Pitch): SpelledPitchClass =
            SpelledPitchClass(pitch.noteName, pitch.chromaticOffset)
    }
}

data class ScaleDegreeDefinition(
    val number: Int,
    val diatonicOffset: Int,
    val semitones: Int,
) {
    init {
        require(number > 0) { "Scale degree number must be positive" }
        require(diatonicOffset >= 0) { "Scale degree diatonicOffset must be non-negative" }
        require(semitones >= 0) { "Scale degree semitones must be non-negative" }
    }
}

data class ScaleDefinition(
    val id: ScaleDefinitionId,
    val degrees: List<ScaleDegreeDefinition>,
) {
    init {
        require(degrees.isNotEmpty()) { "A scale definition must contain at least one degree" }
        require(degrees.map { it.number }.toSet().size == degrees.size) {
            "Scale degree numbers must be unique"
        }
    }

    fun degree(number: Int): ScaleDegreeDefinition? =
        degrees.firstOrNull { it.number == number }

    companion object {
        fun fromMode(mode: Mode): ScaleDefinition =
            ScaleDefinition(
                id = ScaleDefinitionId("mode.${mode.name.lowercase()}"),
                degrees = mode.semitones().mapIndexed { index, semitones ->
                    ScaleDegreeDefinition(
                        number = index + 1,
                        diatonicOffset = index,
                        semitones = semitones,
                    )
                },
            )
    }
}

data class NotationalKeySignature(
    val fifths: Int,
) {
    init { require(fifths in -7..7) { "Key signature fifths must be in -7..7" } }

    companion object {
        fun from(keySignature: KeySignature): NotationalKeySignature =
            NotationalKeySignature(keySignature.fifths)
    }
}

data class TonalContext(
    val id: TonalContextId,
    val tonic: SpelledPitchClass,
    val scale: ScaleDefinition,
    val keySignature: NotationalKeySignature? = null,
) {
    fun pitchClassForDegree(degree: Int, alteration: Int = 0): PitchClass {
        val scaleDegree = scale.degree(degree)
            ?: error("Scale ${scale.id} has no degree $degree")
        return tonic.pitchClass.transpose(scaleDegree.semitones + alteration)
    }

    fun spellDegree(degree: Int, alteration: Int = 0): SpelledPitchClass {
        val scaleDegree = scale.degree(degree)
            ?: error("Scale ${scale.id} has no degree $degree")
        val noteName = NoteName.fromIndex(tonic.noteName.ordinal + scaleDegree.diatonicOffset)
        val target = pitchClassForDegree(degree, alteration)
        return SpelledPitchClass(
            noteName = noteName,
            chromaticOffset = centeredPitchClassDelta(noteName.toSemitone(), target.value),
        )
    }

    companion object {
        fun fromKey(
            key: Key,
            tonicSpelling: SpelledPitchClass = defaultSpelling(key.root),
            id: TonalContextId = TonalContextId("key.${key.root.value}.${key.mode.name.lowercase()}"),
        ): TonalContext =
            TonalContext(
                id = id,
                tonic = tonicSpelling,
                scale = ScaleDefinition.fromMode(key.mode),
            )
    }
}

enum class ChordMemberRole {
    STRUCTURAL,
    AVAILABLE_TENSION,
    SUSPENSION,
    AVOID_TONE,
    OPTIONAL_COLOR,
}

data class ChordMember(
    val id: ChordMemberId,
    val diatonicNumber: Int,
    val semitones: Int,
    val role: ChordMemberRole,
    val omissionPriority: Int = 0,
) {
    init {
        require(diatonicNumber > 0) { "Chord member diatonicNumber must be positive" }
        require(semitones >= 0) { "Chord member semitones must be non-negative" }
        require(omissionPriority >= 0) { "Chord member omissionPriority must be non-negative" }
    }

    fun spellAbove(root: SpelledPitchClass): SpelledPitchClass {
        val noteName = NoteName.fromIndex(root.noteName.ordinal + diatonicNumber - 1)
        val target = root.pitchClass.transpose(semitones)
        return SpelledPitchClass(
            noteName = noteName,
            chromaticOffset = centeredPitchClassDelta(noteName.toSemitone(), target.value),
        )
    }
}

data class ChordDefinition(
    val id: ChordDefinitionId,
    val members: List<ChordMember>,
    val compatibilityQuality: ChordQuality = ChordQuality.CUSTOM,
) {
    init {
        require(members.isNotEmpty()) { "A chord definition must contain at least one member" }
        require(members.map { it.id }.toSet().size == members.size) {
            "Chord member ids must be unique"
        }
        require(members.map { it.semitones.mod(OCTAVE_SEMITONES) }.toSet().size == members.size) {
            "Chord members must have distinct pitch classes"
        }
    }

    fun member(id: ChordMemberId): ChordMember? =
        members.firstOrNull { it.id == id }

    fun instantiate(
        root: SpelledPitchClass,
        bassMemberId: ChordMemberId = members.first().id,
    ): DefinedSonority {
        require(member(bassMemberId) != null) { "Bass member $bassMemberId is not part of $id" }
        return DefinedSonority(this, root, bassMemberId)
    }
}

data class DefinedSonority(
    val definition: ChordDefinition,
    val spelledRoot: SpelledPitchClass,
    val bassMemberId: ChordMemberId,
) : Sonority {
    override val root: PitchClass get() = spelledRoot.pitchClass
    override val pitchClasses: List<PitchClass>
        get() = definition.members.map { root.transpose(it.semitones) }

    val spelledMembers: Map<ChordMemberId, SpelledPitchClass>
        get() = definition.members.associate { it.id to it.spellAbove(spelledRoot) }

    val bassPitchClass: PitchClass
        get() = memberPitchClass(bassMemberId)

    fun memberPitchClass(memberId: ChordMemberId): PitchClass {
        val member = definition.member(memberId)
            ?: error("Chord member $memberId is not part of ${definition.id}")
        return root.transpose(member.semitones)
    }
}

data class TonalSpan(
    val window: SlotWindow,
    val context: TonalContext,
)

/**
 * Tonality is selected per slot independently from chord vocabulary. Overlapping spans permit
 * pivot/common-chord interpretations without changing the sounding chord target.
 */
data class TonalPlan(
    val spans: List<TonalSpan>,
) {
    init { require(spans.isNotEmpty()) { "A tonal plan must contain at least one span" } }

    fun contextsAt(slot: Int): List<TonalContext> =
        spans.filter { it.window.contains(slot) }.map { it.context }.distinctBy { it.id }
}

data class VoiceSpec(
    val id: TrackId,
    val order: Int,
    val boundary: VoiceBoundary,
    val range: VoiceRange,
    val label: String? = null,
    /** Compatibility label for textbook SATB rules; generic rules use [boundary] and [order]. */
    val legacyRole: FixedVoiceRole? = null,
)

enum class VoiceBoundary {
    UPPER_OUTER,
    INNER,
    LOWER_OUTER,
}

data class VoicePlan(
    val voices: List<VoiceSpec>,
) {
    init {
        require(voices.isNotEmpty()) { "A voice plan must contain at least one voice" }
        require(voices.map { it.id }.toSet().size == voices.size) { "Voice ids must be unique" }
        require(voices.map { it.order }.toSet().size == voices.size) { "Voice order values must be unique" }
        require(voices.count { it.boundary == VoiceBoundary.UPPER_OUTER } <= 1) {
            "A voice plan can contain at most one upper outer voice"
        }
        require(voices.count { it.boundary == VoiceBoundary.LOWER_OUTER } <= 1) {
            "A voice plan can contain at most one lower outer voice"
        }
    }

    val orderedHighToLow: List<VoiceSpec> get() = voices.sortedBy { it.order }

    companion object {
        fun standardFourPart(
            rangeProfile: VoiceRangeProfile = VoiceRangeProfile.humanFourPart(),
        ): VoicePlan =
            VoicePlan(
                listOf(
                    VoiceSpec(
                        TrackId("solver-soprano"),
                        0,
                        VoiceBoundary.UPPER_OUTER,
                        rangeProfile.rangeFor(FixedVoiceRole.SOPRANO)
                            ?: VoiceRange(Pitch.fromName("C4"), Pitch.fromName("G5")),
                        "Soprano",
                        FixedVoiceRole.SOPRANO,
                    ),
                    VoiceSpec(
                        TrackId("solver-alto"),
                        1,
                        VoiceBoundary.INNER,
                        rangeProfile.rangeFor(FixedVoiceRole.ALTO)
                            ?: VoiceRange(Pitch.fromName("G3"), Pitch.fromName("D5")),
                        "Alto",
                        FixedVoiceRole.ALTO,
                    ),
                    VoiceSpec(
                        TrackId("solver-tenor"),
                        2,
                        VoiceBoundary.INNER,
                        rangeProfile.rangeFor(FixedVoiceRole.TENOR)
                            ?: VoiceRange(Pitch.fromName("C3"), Pitch.fromName("E4")),
                        "Tenor",
                        FixedVoiceRole.TENOR,
                    ),
                    VoiceSpec(
                        TrackId("solver-bass"),
                        3,
                        VoiceBoundary.LOWER_OUTER,
                        rangeProfile.rangeFor(FixedVoiceRole.BASS)
                            ?: VoiceRange(Pitch.fromName("E2"), Pitch.fromName("C4")),
                        "Bass",
                        FixedVoiceRole.BASS,
                    ),
                )
            )
    }
}

data class PolyphonicVoicing<T>(
    val slotIndex: Int,
    val target: T,
    val pitchesByVoiceId: Map<TrackId, Pitch>,
) {
    init { require(pitchesByVoiceId.isNotEmpty()) { "A voicing must contain at least one pitch" } }
}

object BuiltInChordDefinitions {
    private fun member(
        id: String,
        number: Int,
        semitones: Int,
        role: ChordMemberRole = ChordMemberRole.STRUCTURAL,
        omissionPriority: Int = 0,
    ) = ChordMember(ChordMemberId(id), number, semitones, role, omissionPriority)

    private val root = member("root", 1, 0)
    private val perfectFifth = member("fifth", 5, 7, omissionPriority = 3)
    private val minorSeventh = member("seventh", 7, 10)

    private fun definition(
        quality: ChordQuality,
        vararg members: ChordMember,
    ): ChordDefinition =
        ChordDefinition(
            id = ChordDefinitionId("quality.${quality.name.lowercase()}"),
            members = members.toList(),
            compatibilityQuality = quality,
        )

    private val definitions: Map<ChordQuality, ChordDefinition> = mapOf(
        ChordQuality.MAJOR to definition(ChordQuality.MAJOR, root, member("third", 3, 4), perfectFifth),
        ChordQuality.MINOR to definition(ChordQuality.MINOR, root, member("third", 3, 3), perfectFifth),
        ChordQuality.DIMINISHED to definition(
            ChordQuality.DIMINISHED,
            root,
            member("third", 3, 3),
            member("fifth", 5, 6),
        ),
        ChordQuality.AUGMENTED to definition(
            ChordQuality.AUGMENTED,
            root,
            member("third", 3, 4),
            member("fifth", 5, 8),
        ),
        ChordQuality.SUS2 to definition(
            ChordQuality.SUS2,
            root,
            member("second", 2, 2, ChordMemberRole.SUSPENSION),
            perfectFifth,
        ),
        ChordQuality.SUS4 to definition(
            ChordQuality.SUS4,
            root,
            member("fourth", 4, 5, ChordMemberRole.SUSPENSION),
            perfectFifth,
        ),
        ChordQuality.MAJOR7 to seventh(ChordQuality.MAJOR7, 4, 11),
        ChordQuality.MINOR7 to seventh(ChordQuality.MINOR7, 3, 10),
        ChordQuality.DOMINANT7 to seventh(ChordQuality.DOMINANT7, 4, 10),
        ChordQuality.DIMINISHED7 to definition(
            ChordQuality.DIMINISHED7,
            root,
            member("third", 3, 3),
            member("fifth", 5, 6),
            member("seventh", 7, 9),
        ),
        ChordQuality.HALF_DIMINISHED7 to definition(
            ChordQuality.HALF_DIMINISHED7,
            root,
            member("third", 3, 3),
            member("fifth", 5, 6),
            minorSeventh,
        ),
        ChordQuality.MINOR_MAJOR7 to seventh(ChordQuality.MINOR_MAJOR7, 3, 11),
        ChordQuality.AUGMENTED7 to definition(
            ChordQuality.AUGMENTED7,
            root,
            member("third", 3, 4),
            member("fifth", 5, 8),
            minorSeventh,
        ),
        ChordQuality.ADD9 to definition(
            ChordQuality.ADD9,
            root,
            member("third", 3, 4),
            perfectFifth,
            member("ninth", 9, 14, ChordMemberRole.OPTIONAL_COLOR, 4),
        ),
        ChordQuality.ADD11 to definition(
            ChordQuality.ADD11,
            root,
            member("third", 3, 4),
            perfectFifth,
            member("eleventh", 11, 17, ChordMemberRole.OPTIONAL_COLOR, 4),
        ),
        ChordQuality.MAJOR9 to extended(ChordQuality.MAJOR9, 4, 11, 14),
        ChordQuality.MINOR9 to extended(ChordQuality.MINOR9, 3, 10, 14),
        ChordQuality.DOMINANT9 to extended(ChordQuality.DOMINANT9, 4, 10, 14),
        ChordQuality.MAJOR11 to eleventh(ChordQuality.MAJOR11, 4, 11),
        ChordQuality.MINOR11 to eleventh(ChordQuality.MINOR11, 3, 10),
        ChordQuality.DOMINANT11 to eleventh(ChordQuality.DOMINANT11, 4, 10),
        ChordQuality.MAJOR13 to thirteenth(ChordQuality.MAJOR13, 4, 11),
        ChordQuality.MINOR13 to thirteenth(ChordQuality.MINOR13, 3, 10),
        ChordQuality.DOMINANT13 to thirteenth(ChordQuality.DOMINANT13, 4, 10),
        ChordQuality.DOMINANT7_FLAT5 to alteredDominant(ChordQuality.DOMINANT7_FLAT5, fifth = 6),
        ChordQuality.DOMINANT7_SHARP5 to alteredDominant(ChordQuality.DOMINANT7_SHARP5, fifth = 8),
        ChordQuality.DOMINANT7_FLAT9 to alteredDominant(ChordQuality.DOMINANT7_FLAT9, ninth = 13),
        ChordQuality.DOMINANT7_SHARP9 to alteredDominant(ChordQuality.DOMINANT7_SHARP9, ninth = 15),
        ChordQuality.DOMINANT7_SHARP11 to definition(
            ChordQuality.DOMINANT7_SHARP11,
            root,
            member("third", 3, 4),
            perfectFifth,
            minorSeventh,
            member("sharp-eleventh", 11, 18, ChordMemberRole.AVAILABLE_TENSION, 4),
        ),
        ChordQuality.ALTERED to definition(
            ChordQuality.ALTERED,
            root,
            member("third", 3, 4),
            member("flat-fifth", 5, 6, ChordMemberRole.OPTIONAL_COLOR, 3),
            member("sharp-fifth", 5, 8, ChordMemberRole.OPTIONAL_COLOR, 3),
            minorSeventh,
            member("flat-ninth", 9, 13, ChordMemberRole.AVAILABLE_TENSION, 4),
            member("sharp-ninth", 9, 15, ChordMemberRole.AVAILABLE_TENSION, 4),
        ),
        ChordQuality.CUSTOM to definition(ChordQuality.CUSTOM, root),
    )

    fun forQuality(quality: ChordQuality): ChordDefinition =
        definitions.getValue(quality)

    private fun seventh(quality: ChordQuality, third: Int, seventh: Int): ChordDefinition =
        definition(
            quality,
            root,
            member("third", 3, third),
            perfectFifth,
            member("seventh", 7, seventh),
        )

    private fun extended(
        quality: ChordQuality,
        third: Int,
        seventh: Int,
        ninth: Int,
    ): ChordDefinition =
        definition(
            quality,
            root,
            member("third", 3, third),
            perfectFifth,
            member("seventh", 7, seventh),
            member("ninth", 9, ninth, ChordMemberRole.AVAILABLE_TENSION, 4),
        )

    private fun eleventh(quality: ChordQuality, third: Int, seventh: Int): ChordDefinition =
        definition(
            quality,
            *extended(quality, third, seventh, 14).members.toTypedArray(),
            member("eleventh", 11, 17, ChordMemberRole.AVAILABLE_TENSION, 5),
        )

    private fun thirteenth(quality: ChordQuality, third: Int, seventh: Int): ChordDefinition =
        definition(
            quality,
            *eleventh(quality, third, seventh).members.toTypedArray(),
            member("thirteenth", 13, 21, ChordMemberRole.AVAILABLE_TENSION, 6),
        )

    private fun alteredDominant(
        quality: ChordQuality,
        fifth: Int = 7,
        ninth: Int? = null,
    ): ChordDefinition =
        definition(
            quality,
            *buildList {
                add(root)
                add(member("third", 3, 4))
                add(member("fifth", 5, fifth, omissionPriority = 3))
                add(minorSeventh)
                ninth?.let {
                    add(member("ninth", 9, it, ChordMemberRole.AVAILABLE_TENSION, 4))
                }
            }.toTypedArray(),
        )
}

private fun defaultSpelling(pitchClass: PitchClass): SpelledPitchClass =
    SpelledPitchClass.fromPitch(Pitch.fromMidi(MIDDLE_C_MIDI + pitchClass.value))

private fun centeredPitchClassDelta(naturalPitchClass: Int, targetPitchClass: Int): Int {
    val normalized = (targetPitchClass - naturalPitchClass).mod(OCTAVE_SEMITONES)
    return if (normalized > OCTAVE_SEMITONES / 2) normalized - OCTAVE_SEMITONES else normalized
}

private const val OCTAVE_SEMITONES = 12
private const val MIDDLE_C_MIDI = 60
