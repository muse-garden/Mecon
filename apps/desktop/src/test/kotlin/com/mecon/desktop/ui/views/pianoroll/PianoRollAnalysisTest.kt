package com.mecon.desktop.ui.views.pianoroll

import com.mecon.api.computed.ComputedEventStore
import com.mecon.api.computed.ComputedPluginEvent
import com.mecon.api.computed.ComputedScore
import com.mecon.api.computed.tracks.ComputedPluginTrack
import com.mecon.api.primitive.Fraction
import com.mecon.api.primitive.TimeCode
import com.mecon.api.primitive.TrackId
import com.mecon.api.runtime.RuntimeScore
import com.mecon.api.runtime.TimeIndexedList
import com.mecon.api.runtime.events.RuntimePluginEvent
import com.mecon.api.storage.StorageScore
import com.mecon.api.storage.events.StoragePluginEvent
import com.mecon.plugins.chord.StorageChordEvent
import com.mecon.theory.ChordQuality
import com.mecon.theory.ChordSymbolDisplayStyle
import kotlin.test.Test
import kotlin.test.assertEquals

class PianoRollAnalysisTest {
    @Test
    fun chordSpansCoverEveryChordPitchClassUntilTheNextSymbol() {
        val runtime = RuntimeScore.fromStorage(
            StorageScore.create(StorageScore.CreationOptions("piano-roll-chords"))
        )
        val cMajor = StorageChordEvent.create(
            onset = TimeCode.ofMeasure(1),
            root = 0,
            quality = ChordQuality.MAJOR,
        )
        val g7 = StorageChordEvent.create(
            onset = TimeCode.of(1, Fraction(1, 4)),
            root = 7,
            quality = ChordQuality.DOMINANT7,
        )
        val track = pluginTrack(StorageChordEvent.TRACK_TYPE, listOf(cMajor, g7))
        val computed = ComputedScore(
            runtime = runtime,
            computedEvents = ComputedEventStore.of(emptyList()),
            pluginTracks = mapOf(track.id to track),
        )

        val spans = buildPianoRollChordSpans(
            runtime = runtime,
            computed = computed,
            timelineEndTicks = 4096,
            ticksPerQuarter = 1024,
            enabled = true,
            symbolStyle = ChordSymbolDisplayStyle.LETTER,
        )

        assertEquals(listOf(0L, 1024L), spans.map { it.onsetTicks })
        assertEquals(listOf(1024L, 4096L), spans.map { it.endTicks })
        assertEquals(listOf(0, 4, 7), spans[0].pitchClasses)
        assertEquals(listOf(7, 11, 2, 5), spans[1].pitchClasses)
        assertEquals(listOf("C", "G7"), spans.map { it.symbol })
    }

    private fun <T : StoragePluginEvent> pluginTrack(
        type: String,
        events: List<T>,
    ): ComputedPluginTrack<T> {
        val computed = events.map { storage ->
            val runtimeEvent = object : RuntimePluginEvent<T> {
                override val id = storage.id
                override val onset = storage.onset
                override val storageEvent = storage
            }
            object : ComputedPluginEvent<T> {
                override val id = storage.id
                override val onset = storage.onset
                override val runtimeEvent = runtimeEvent
            }
        }
        return ComputedPluginTrack(
            id = TrackId(type),
            name = type,
            type = type,
            events = TimeIndexedList.of(computed),
        )
    }
}
