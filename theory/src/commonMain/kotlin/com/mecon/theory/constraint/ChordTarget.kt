package com.mecon.theory.constraint

import com.mecon.api.primitive.PitchClass
import com.mecon.theory.ChordArity
import com.mecon.theory.ChordDefinitionId
import com.mecon.theory.ChordQuality
import com.mecon.theory.Key
import com.mecon.theory.Sonority
import com.mecon.theory.SpelledPitchClass
import com.mecon.theory.TonalContextId
import kotlinx.serialization.Serializable

/**
 * 通用和弦目标能力接口。约束求解器只消费这组能力，新增和弦族时实现接口即可接入候选、
 * 关系约束与规则调度。
 */
interface ChordTarget {
    val key: Key
    val sonority: Sonority
    val bassPitchClass: PitchClass
    val degree: Int
    val quality: ChordQuality
    val inversion: Int
    val arity: ChordArity
    fun pitchClassFor(tone: ChordTone): PitchClass?
    fun spellingFor(pitchClass: PitchClass): SpelledPitchClass? = null
    fun identityKey(): String
    /** Actual spelled sonority, independent of functional interpretation and inversion where available. */
    fun sonorityIdentityKey(): String = identityKey()
    /** Functional reading, independent of inversion where available. */
    fun interpretationIdentityKey(): String = identityKey()
    /** Actual sonority plus bass member; used to cache voicings across interpretations. */
    fun realizationIdentityKey(): String = identityKey()
    /** Tonal lenses in which this interpretation is valid; empty means context-agnostic. */
    fun tonalContextIds(): Set<TonalContextId> = emptySet()
    /** Primary functional lens. Compatibility lenses remain available through [tonalContextIds]. */
    fun primaryTonalContextId(): TonalContextId? = null
    /** Structural definition identity used by free-solver domain filters. */
    fun chordDefinitionId(): ChordDefinitionId? = null
}

@Serializable
enum class ChordIdentityMode {
    SONORITY,
    INTERPRETATION,
    TARGET,
}

fun ChordTarget.identityKey(mode: ChordIdentityMode): String =
    when (mode) {
        ChordIdentityMode.SONORITY -> sonorityIdentityKey()
        ChordIdentityMode.INTERPRETATION -> interpretationIdentityKey()
        ChordIdentityMode.TARGET -> identityKey()
    }

data class TargetSelector(
    val degrees: Set<Int> = emptySet(),
    val qualities: Set<ChordQuality> = emptySet(),
    val inversions: Set<Int> = emptySet(),
    val arities: Set<ChordArity> = emptySet(),
    val requiredPitchClasses: Set<PitchClass> = emptySet(),
    val identityKeys: Set<String> = emptySet(),
    val sonorityIdentityKeys: Set<String> = emptySet(),
    val interpretationIdentityKeys: Set<String> = emptySet(),
    val primaryContextIds: Set<TonalContextId> = emptySet(),
    val compatibleContextIds: Set<TonalContextId> = emptySet(),
) {
    fun matches(target: ChordTarget): Boolean =
        (degrees.isEmpty() || target.degree in degrees) &&
            (qualities.isEmpty() || target.quality in qualities) &&
            (inversions.isEmpty() || target.inversion in inversions) &&
            (arities.isEmpty() || target.arity in arities) &&
            (requiredPitchClasses.isEmpty() || requiredPitchClasses.all { it in target.sonority.pitchClasses }) &&
            (identityKeys.isEmpty() || target.identityKey() in identityKeys) &&
            (sonorityIdentityKeys.isEmpty() || target.sonorityIdentityKey() in sonorityIdentityKeys) &&
            (
                interpretationIdentityKeys.isEmpty() ||
                    target.interpretationIdentityKey() in interpretationIdentityKeys
                ) &&
            (primaryContextIds.isEmpty() || target.primaryTonalContextId() in primaryContextIds) &&
            (compatibleContextIds.isEmpty() || target.tonalContextIds().any { it in compatibleContextIds })
}
