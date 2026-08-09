package com.mecon.theory.constraint

import com.mecon.api.primitive.PitchClass
import com.mecon.theory.ChordArity
import com.mecon.theory.ChordMemberId
import com.mecon.theory.ChordQuality
import com.mecon.theory.DefinedSonority
import com.mecon.theory.Key
import com.mecon.theory.SpelledPitchClass
import com.mecon.theory.harmony.ChordCatalogEntry
import com.mecon.theory.harmony.ChordInterpretation
import com.mecon.theory.harmony.FunctionalToneRole
import com.mecon.theory.harmony.SonorityToneId

/**
 * Search target with exactly one selected interpretation.
 *
 * Multiple interpretations of the same catalog entry are separate SlotDomain branches, while their
 * voicing enumeration can be cached by [realizationIdentityKey].
 */
data class InterpretedChordTarget(
    override val key: Key,
    val entry: ChordCatalogEntry,
    val interpretation: ChordInterpretation,
    val bassToneId: SonorityToneId,
) : ChordTarget {
    private val bassMemberId: ChordMemberId? =
        entry.sonority.definition.members.firstOrNull { member ->
            entry.sonority.toneIdForMember(member.id) == bassToneId
        }?.id

    init {
        require(interpretation in entry.interpretations) {
            "Interpretation ${interpretation.id} does not belong to ${entry.sonority.id}"
        }
        require(bassToneId in entry.sonority.toneIds) {
            "Bass tone $bassToneId does not belong to ${entry.sonority.id}"
        }
        require(bassToneId in interpretation.structuralToneOrder) {
            "Bass tone $bassToneId is not a structural member of ${interpretation.id}"
        }
        require(bassMemberId != null) {
            "The compatibility ChordTarget projection requires bass to be a structural member"
        }
    }

    override val sonority: DefinedSonority =
        entry.sonority.definition.instantiate(
            entry.sonority.spelledRoot,
            bassMemberId ?: entry.sonority.definition.members.first().id,
        )

    override val bassPitchClass: PitchClass =
        entry.sonority.tone(bassToneId)!!.spelling.pitchClass

    override val degree: Int get() = interpretation.symbol.degree
    override val quality: ChordQuality get() = interpretation.symbol.quality
    override val arity: ChordArity get() = interpretation.symbol.arity

    override val inversion: Int =
        interpretation.structuralToneOrder.indexOf(bassToneId)

    override fun pitchClassFor(tone: ChordTone): PitchClass? {
        val role = when (tone) {
            ChordTone.BASS -> return bassPitchClass
            ChordTone.ROOT -> FunctionalToneRole.STRUCTURAL_ROOT
            ChordTone.THIRD -> FunctionalToneRole.STRUCTURAL_THIRD
            ChordTone.FIFTH -> FunctionalToneRole.STRUCTURAL_FIFTH
            ChordTone.SEVENTH -> FunctionalToneRole.CHORDAL_SEVENTH
        }
        return interpretation.toneRoles[role]
            ?.let(entry.sonority::tone)
            ?.spelling
            ?.pitchClass
    }

    fun pitchClassFor(role: FunctionalToneRole): PitchClass? =
        interpretation.toneRoles[role]
            ?.let(entry.sonority::tone)
            ?.spelling
            ?.pitchClass

    fun spellingFor(role: FunctionalToneRole): SpelledPitchClass? =
        interpretation.toneRoles[role]
            ?.let(entry.sonority::tone)
            ?.spelling

    override fun spellingFor(pitchClass: PitchClass): SpelledPitchClass? =
        entry.sonority.tones.firstOrNull { it.spelling.pitchClass == pitchClass }?.spelling

    override fun sonorityIdentityKey(): String = entry.sonority.id.value

    override fun interpretationIdentityKey(): String = interpretation.id.value

    override fun realizationIdentityKey(): String =
        "${entry.sonority.id.value}/${bassToneId.value}"

    override fun identityKey(): String =
        "${interpretation.id.value}/${bassToneId.value}"

    override fun tonalContextIds() =
        interpretation.compatibleContextIds + interpretation.lens.contextId

    override fun primaryTonalContextId() = interpretation.lens.contextId

    override fun chordDefinitionId() = entry.sonority.definition.id
}
