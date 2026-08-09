package com.mecon.api.model

import kotlinx.serialization.Serializable
import kotlin.jvm.JvmInline

@JvmInline
@Serializable
value class Pitch(val midiNumber: Int) {
    val pitchClass: Int get() = midiNumber % 12
    val octave: Int get() = midiNumber / 12 - 1

    companion object {
        private val NOTE_NAMES = listOf("c", "c#", "d", "eb", "e", "f", "f#", "g", "ab", "a", "bb", "b")
        private val NOTE_NAMES_DISPLAY = listOf("C", "C#", "D", "Eb", "E", "F", "F#", "G", "Ab", "A", "Bb", "B")

        /**
         * Parse a key string like "c/4" or "eb/3" into a Pitch
         */
        fun fromKey(key: String): Pitch? {
            val parts = key.lowercase().split("/")
            if (parts.size != 2) return null
            val noteName = parts[0]
            val octave = parts[1].toIntOrNull() ?: return null
            val noteIndex = NOTE_NAMES.indexOf(noteName)
            if (noteIndex == -1) return null
            return Pitch((octave + 1) * 12 + noteIndex)
        }

        fun getDisplayName(pitchClass: Int, relative: Boolean = false): String {
            val safePitch = ((pitchClass % 12) + 12) % 12
            return if (relative) {
                listOf("1", "#1", "2", "b3", "3", "4", "#4", "5", "b6", "6", "b7", "7")[safePitch]
            } else {
                NOTE_NAMES_DISPLAY[safePitch]
            }
        }
    }

    fun toNoteName(): String {
        return "${NOTE_NAMES_DISPLAY[pitchClass]}$octave"
    }

    fun toKey(): String {
        return "${NOTE_NAMES[pitchClass]}/$octave"
    }
}
