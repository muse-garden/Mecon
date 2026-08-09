package com.mecon.desktop.service

import com.mecon.api.computed.ComputedScore
import com.mecon.api.primitive.EventId

internal fun ComputedScore.voiceEventSections(
    eventIds: Iterable<EventId>,
): Set<com.mecon.api.interaction.EventSection> =
    eventIds.mapNotNull(::getComputedEvent)
        .mapTo(LinkedHashSet()) { com.mecon.api.interaction.VoiceEventSection(it) }

internal fun ComputedScore.movedEventSections(
    movedEvents: Iterable<Pair<EventId, Set<Int>?>>,
): Set<com.mecon.api.interaction.EventSection> =
    movedEvents.flatMapTo(LinkedHashSet()) { (eventId, pitchIndices) ->
        val event = getComputedEvent(eventId) ?: return@flatMapTo emptyList()
        if (pitchIndices == null) {
            listOf(com.mecon.api.interaction.VoiceEventSection(event))
        } else {
            pitchIndices.filter { it in event.pitchData.indices }
                .map { com.mecon.api.interaction.VoiceNoteSection(event, it) }
        }
    }
