package com.mecon.plugins.chord

import com.mecon.api.computed.ComputedPluginEvent
import com.mecon.api.primitive.EventId
import com.mecon.api.primitive.KeySignature
import com.mecon.api.primitive.TimeCode
import com.mecon.theory.Chord
import com.mecon.theory.ChordSymbol
import com.mecon.theory.ChordSymbolDisplayStyle
import com.mecon.theory.ChordSymbolFormatter

/**
 * Computed view of a chord event. v1 only carries the chord symbol; later passes
 * may add chord-tone analysis, function labels, etc.
 */
data class ComputedChordEvent(
    override val id: EventId,
    override val onset: TimeCode,
    override val runtimeEvent: RuntimeChordEvent
) : ComputedPluginEvent<StorageChordEvent> {

    val chord: Chord get() = runtimeEvent.chord

    /** Default letter symbol (e.g. "C", "Dm", "G7", "C/E"). */
    val symbol: String by lazy { formatSymbol() }

    fun formatSymbol(
        style: ChordSymbolDisplayStyle = ChordSymbolDisplayStyle.LETTER,
        keySignature: KeySignature = KeySignature.C_MAJOR,
    ): String = formattedSymbol(style, keySignature).plainText

    fun formattedSymbol(
        style: ChordSymbolDisplayStyle = ChordSymbolDisplayStyle.LETTER,
        keySignature: KeySignature = KeySignature.C_MAJOR,
    ): ChordSymbol = ChordSymbolFormatter.formatSymbol(chord, style, keySignature)

    companion object {
        fun fromRuntime(event: RuntimeChordEvent): ComputedChordEvent =
            ComputedChordEvent(
                id = event.id,
                onset = event.onset,
                runtimeEvent = event
            )
    }
}
