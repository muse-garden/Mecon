package com.mecon.api.model

import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.serialization.Serializable

/**
 * Represents a musical score containing a list of notes/chords.
 */
@Serializable
data class Score(
    val notes: ImmutableList<Note>
) {
    fun findNoteById(id: NoteId): Note? = notes.find { it.id == id }

    fun findNoteIndex(id: NoteId): Int = notes.indexOfFirst { it.id == id }

    fun addNote(note: Note): Score = copy(notes = (notes + note).toImmutableList())

    fun removeNote(id: NoteId): Score = copy(notes = notes.filter { it.id != id }.toImmutableList())

    fun updateNote(id: NoteId, transform: (Note) -> Note): Score {
        return copy(
            notes = notes.map { if (it.id == id) transform(it) else it }.toImmutableList()
        )
    }

    companion object {
        fun empty(): Score = Score(persistentListOf())

        /**
         * Create a demo score for testing
         */
        fun createDemo(): Score {
            return Score(
                listOf(
                    Note.create(listOf("c/4", "e/4", "g/4"), "q"),  // C Major
                    Note.create(listOf("a/3", "c/4", "e/4"), "q"),  // A minor
                    Note.create(listOf("f/3", "a/3", "c/4"), "q"),  // F Major
                    Note.create(listOf("g/3", "b/3", "d/4"), "q")   // G Major
                ).toImmutableList()
            )
        }
    }
}
