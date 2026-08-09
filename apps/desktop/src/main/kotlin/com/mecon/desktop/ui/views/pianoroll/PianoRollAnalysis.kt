package com.mecon.desktop.ui.views.pianoroll

import com.mecon.api.computed.ComputedScore
import com.mecon.api.computed.pluginEventsOf
import com.mecon.api.primitive.Pitch
import com.mecon.api.runtime.RuntimeScore
import com.mecon.audio.converter.ScoreToMidiConverter
import com.mecon.plugins.chord.ChordSymbolDisplaySettings
import com.mecon.plugins.chord.ComputedChordEvent
import com.mecon.plugins.chord.PolyphonyDegreeFormatter
import com.mecon.plugins.chord.PolyphonyTonalContextResolver
import com.mecon.plugins.chord.RuntimeChordEvent
import com.mecon.plugins.chord.StorageChordEvent
import com.mecon.plugins.chord.StorageTonalRegionEvent
import com.mecon.theory.ChordSymbolDisplayStyle
import com.mecon.theory.KeySignatureMode

data class PianoRollChordSpan(
    val onsetTicks: Long,
    val endTicks: Long,
    val symbol: String,
    /** Ordered root/third/fifth/extension pitch classes for stable background colors. */
    val pitchClasses: List<Int>,
)

internal fun addPianoRollDegreeLabels(
    notes: List<PianoRollNote>,
    score: ComputedScore?,
    enabled: Boolean,
): List<PianoRollNote> {
    if (!enabled || score == null) return notes
    val tonalRegions = score.pluginEventsOf<StorageTonalRegionEvent>(
        StorageTonalRegionEvent.TRACK_TYPE
    )
    return notes.map { note ->
        val onset = note.scoreOnset ?: return@map note
        val keys = PolyphonyTonalContextResolver.keysAt(score, onset, tonalRegions)
        note.copy(
            degreeLabel = PolyphonyDegreeFormatter.format(keys, Pitch.fromMidi(note.midi))
        )
    }
}

internal fun buildPianoRollChordSpans(
    runtime: RuntimeScore,
    computed: ComputedScore?,
    timelineEndTicks: Long,
    ticksPerQuarter: Int,
    enabled: Boolean,
    symbolStyle: ChordSymbolDisplayStyle = ChordSymbolDisplaySettings.style,
): List<PianoRollChordSpan> {
    if (!enabled || computed == null) return emptyList()
    val events = computed.pluginEventsOf<StorageChordEvent>(StorageChordEvent.TRACK_TYPE)
        .sortedWith(compareBy<StorageChordEvent> { it.onset }.thenBy { it.id.value })
    if (events.isEmpty()) return emptyList()

    val tonalRegions = computed.pluginEventsOf<StorageTonalRegionEvent>(
        StorageTonalRegionEvent.TRACK_TYPE
    )
    return events.mapIndexed { index, event ->
        val chord = ComputedChordEvent.fromRuntime(RuntimeChordEvent.fromStorage(event))
        val tonalKeys = PolyphonyTonalContextResolver.keysAt(computed, event.onset, tonalRegions)
        val nativeKey = runtime.getKeySignatureAt(event.onset.measure)
        val symbol = if (
            symbolStyle == ChordSymbolDisplayStyle.SCALE_DEGREE && tonalKeys.size > 1
        ) {
            tonalKeys.joinToString(" · ") { key ->
                "${key.displayName}${if (key.mode == KeySignatureMode.MINOR) "m" else ""}:" +
                    chord.formatSymbol(symbolStyle, key.keySignature)
            }
        } else {
            chord.formatSymbol(
                symbolStyle,
                tonalKeys.singleOrNull()?.keySignature ?: nativeKey,
            )
        }
        val onsetTicks = ScoreToMidiConverter.timeCodeToTicks(
            event.onset,
            runtime,
            ticksPerQuarter,
        )
        val nextTicks = events.getOrNull(index + 1)?.let {
            ScoreToMidiConverter.timeCodeToTicks(it.onset, runtime, ticksPerQuarter)
        } ?: timelineEndTicks
        PianoRollChordSpan(
            onsetTicks = onsetTicks,
            endTicks = nextTicks.coerceAtLeast(onsetTicks + 1L),
            symbol = symbol,
            pitchClasses = chord.chord.pitchClasses.map { it.value },
        )
    }
}
