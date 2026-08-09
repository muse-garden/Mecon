package com.mecon.desktop.service

import com.mecon.api.interaction.EventSection
import com.mecon.api.interaction.VoiceEventSection
import com.mecon.api.interaction.VoiceFlagSection
import com.mecon.api.interaction.VoiceNoteSection
import com.mecon.api.interaction.VoiceStemSection
import com.mecon.api.runtime.RuntimeScore

/** Auditions a completed edit only when the selection identifies one computed voice event. */
internal fun PlaybackController.auditionSingleEditedEvent(
    selection: Set<EventSection>,
    score: RuntimeScore?,
) {
    val event = selection.mapNotNull { section ->
        when (section) {
            is VoiceNoteSection -> section.event
            is VoiceStemSection -> section.event
            is VoiceFlagSection -> section.event
            is VoiceEventSection -> section.event
            else -> null
        }
    }.distinctBy { it.id }.singleOrNull() ?: return
    audition(score, event)
}
