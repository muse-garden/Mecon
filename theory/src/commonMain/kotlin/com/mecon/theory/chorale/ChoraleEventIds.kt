package com.mecon.theory.chorale

import com.mecon.api.primitive.EventId
import com.mecon.api.primitive.TimeCode
import com.mecon.theory.FixedVoiceRole

/**
 * Deterministic note identity shared by the engine and the score assembler.
 *
 * Findings are produced while searching, long before a score exists, so both sides derive the same
 * id from the music itself. A voice sounds at most one note at a time, so (role, onset) is unique.
 */
object ChoraleEventIds {
    fun note(role: FixedVoiceRole, onset: TimeCode): EventId =
        EventId("chorale-${role.name.lowercase()}-${onset.format().replace(Regex("[:/]"), "-")}")

    fun pitch(role: FixedVoiceRole, onset: TimeCode): EventId =
        EventId(note(role, onset).value + "-pitch")
}
