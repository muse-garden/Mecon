package com.mecon.audio.model

import com.mecon.api.primitive.TrackId
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.serialization.Serializable

/**
 * MIDI track - a collection of MIDI events for a single instrument/channel.
 */
@Serializable
data class MidiTrack(
    val id: TrackId,
    val name: String,
    val channel: MidiChannel = MidiChannel.DEFAULT,
    val events: ImmutableList<MidiEvent> = persistentListOf()
) {
    /**
     * Get all note on events.
     */
    fun getNoteOnEvents(): List<MidiNoteOnEvent> =
        events.filterIsInstance<MidiNoteOnEvent>()

    /**
     * Get all note off events.
     */
    fun getNoteOffEvents(): List<MidiNoteOffEvent> =
        events.filterIsInstance<MidiNoteOffEvent>()

    /**
     * Get events sorted by absolute ticks.
     */
    fun getEventsSorted(): List<MidiEvent> =
        events.sortedBy { it.absoluteTicks }

    /**
     * Get the last tick position in this track.
     */
    fun getLastTick(): Long =
        events.maxOfOrNull { it.absoluteTicks } ?: 0L

    companion object {
        fun create(
            name: String,
            channel: MidiChannel = MidiChannel.DEFAULT,
            events: List<MidiEvent> = emptyList()
        ) = MidiTrack(
            id = TrackId.generate(),
            name = name,
            channel = channel,
            events = events.toImmutableList()
        )
    }
}

/**
 * MIDI score - complete MIDI representation of a score.
 *
 * Contains multiple tracks and global tempo information.
 */
@Serializable
data class MidiScore(
    val tracks: ImmutableList<MidiTrack>,
    val tempoTrack: ImmutableList<MidiTempoEvent>,
    val ticksPerQuarter: Int = DEFAULT_TICKS_PER_QUARTER
) {
    /**
     * Get all events across all tracks, sorted by time.
     */
    fun getAllEventsSorted(): List<MidiEvent> {
        val allEvents = mutableListOf<MidiEvent>()
        allEvents.addAll(tempoTrack)
        tracks.forEach { track ->
            allEvents.addAll(track.events)
        }
        return allEvents.sortedBy { it.absoluteTicks }
    }

    /**
     * Get the initial tempo (BPM).
     */
    fun getInitialTempo(): Float =
        tempoTrack.minByOrNull { it.absoluteTicks }?.bpm ?: DEFAULT_TEMPO

    /**
     * Get the total duration in ticks.
     */
    fun getTotalTicks(): Long {
        val trackMax = tracks.maxOfOrNull { it.getLastTick() } ?: 0L
        val tempoMax = tempoTrack.maxOfOrNull { it.absoluteTicks } ?: 0L
        return maxOf(trackMax, tempoMax)
    }

    companion object {
        /** Default ticks per quarter note - matches DurationBase.QUARTER.ticks */
        const val DEFAULT_TICKS_PER_QUARTER = 1024

        /** Default tempo in BPM */
        const val DEFAULT_TEMPO = 120f

        fun create(
            tracks: List<MidiTrack> = emptyList(),
            tempoTrack: List<MidiTempoEvent> = emptyList(),
            ticksPerQuarter: Int = DEFAULT_TICKS_PER_QUARTER
        ) = MidiScore(
            tracks = tracks.toImmutableList(),
            tempoTrack = tempoTrack.toImmutableList(),
            ticksPerQuarter = ticksPerQuarter
        )

        /**
         * Create an empty MIDI score with default tempo.
         */
        fun empty(tempo: Float = DEFAULT_TEMPO) = MidiScore(
            tracks = persistentListOf(),
            tempoTrack = persistentListOf(
                MidiTempoEvent(absoluteTicks = 0, bpm = tempo)
            ),
            ticksPerQuarter = DEFAULT_TICKS_PER_QUARTER
        )
    }
}
