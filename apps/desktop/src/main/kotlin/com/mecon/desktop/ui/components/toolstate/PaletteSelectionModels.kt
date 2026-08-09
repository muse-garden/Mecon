package com.mecon.desktop.ui.components.toolstate

import com.mecon.api.primitive.Accidental
import com.mecon.api.primitive.BarlineType
import com.mecon.api.primitive.DurationBase
import com.mecon.api.primitive.KeySignature
import com.mecon.api.primitive.TimeSignature
import com.mecon.api.storage.Articulation
import com.mecon.api.storage.tracks.Clef

data class PaletteSelectionInfo(
    val editable: Boolean = false,
    val durationBase: DurationBase? = null,
    val dots: Int? = null,
    val accidental: Accidental? = null,
    val tieOut: Boolean? = null,
    val voiceNumber: Int? = null,
    val allRests: Boolean = false,
    val tupletCount: Int? = null,
    val effectiveBeamLeft: Boolean? = null,
    val effectiveBeamRight: Boolean? = null,
    val canGroupBeam: Boolean = false,
    val canAddSlur: Boolean = false,
    val articulations: Set<Articulation> = emptySet(),
) {
    companion object {
        val EMPTY = PaletteSelectionInfo()
    }
}

data class ClefSelectionInfo(
    val editable: Boolean = false,
    val clef: Clef? = null,
) {
    companion object {
        val EMPTY = ClefSelectionInfo()
    }
}

data class TimeSignatureSelectionInfo(
    val editable: Boolean = false,
    val timeSignature: TimeSignature? = null,
    val measure: Int? = null,
) {
    companion object {
        val EMPTY = TimeSignatureSelectionInfo()
    }
}

data class KeySignatureSelectionInfo(
    val editable: Boolean = false,
    val keySignature: KeySignature? = null,
    val measure: Int? = null,
) {
    companion object {
        val EMPTY = KeySignatureSelectionInfo()
    }
}

data class BarlineSelectionInfo(
    val editable: Boolean = false,
    val type: BarlineType? = null,
    val repeatCount: Int = 2,
) {
    companion object {
        val EMPTY = BarlineSelectionInfo()
    }
}
