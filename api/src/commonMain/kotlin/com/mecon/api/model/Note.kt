package com.mecon.api.model

import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import kotlinx.serialization.Serializable

/**
 * Represents a note or chord in the score.
 * A note can have multiple keys (pitches) to represent chords.
 */
@Serializable
data class Note(
    val id: NoteId,
    val keys: ImmutableList<String>,  // e.g., ["c/4", "e/4", "g/4"] for C major chord
    val duration: String  // e.g., "q" for quarter, "h" for half, "w" for whole
) {
    /**
     * Get all pitches of this note/chord
     */
    fun getPitches(): List<Pitch> = keys.mapNotNull { Pitch.fromKey(it) }

    /**
     * Get all pitch classes (0-11) of this note/chord
     */
    fun getPitchClasses(): List<Int> = getPitches().map { it.pitchClass }

    companion object {
        fun create(keys: List<String>, duration: String): Note {
            return Note(
                id = NoteId.generate(),
                keys = keys.toImmutableList(),
                duration = duration
            )
        }
    }
}
