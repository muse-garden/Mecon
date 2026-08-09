package com.mecon.theory.constraint

import com.mecon.api.primitive.Pitch
import com.mecon.theory.FixedVoice
import com.mecon.theory.FixedVoiceRole
import com.mecon.theory.FixedVoiceWritingFrame

/** 和弦族无关的固定四声部排布；具体章节展示模型在边界处按 [target] 还原。 */
data class ChordVoicing(
    val slotIndex: Int,
    val target: ChordTarget,
    val soprano: Pitch,
    val alto: Pitch,
    val tenor: Pitch,
    val bass: Pitch,
)

internal fun FixedVoiceWritingFrame<ChordTarget>.toChordVoicing(
    voices: List<FixedVoice>,
): ChordVoicing =
    ChordVoicing(
        slotIndex = slotIndex,
        target = target,
        soprano = pitchForRole(FixedVoiceRole.SOPRANO, voices),
        alto = pitchForRole(FixedVoiceRole.ALTO, voices),
        tenor = pitchForRole(FixedVoiceRole.TENOR, voices),
        bass = pitchForRole(FixedVoiceRole.BASS, voices),
    )

