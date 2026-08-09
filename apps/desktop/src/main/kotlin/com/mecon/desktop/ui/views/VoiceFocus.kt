package com.mecon.desktop.ui.views

import com.mecon.api.interaction.EventSection
import com.mecon.api.interaction.StyleOverride
import com.mecon.api.primitive.EventId
import com.mecon.api.primitive.TrackId
import com.mecon.api.render.RenderColor
import com.mecon.api.storage.StorageScore
import com.mecon.desktop.belongsToVoice
import com.mecon.desktop.onlyVoice
import com.mecon.desktop.voiceIdForEvent
import com.mecon.desktop.voiceEventIds

/**
 * Shared projection of an active notation voice into score selection and rendering controls.
 *
 * Analysis/reduction editors may allow [activeVoiceId] to be null, meaning all voices. Editors
 * with a monodic input contract set [allowAllVoices] to false and keep one target active.
 */
class VoiceFocus private constructor(
    private val score: StorageScore,
    private val selectableVoiceIds: Set<TrackId>,
    val activeVoiceId: TrackId?,
    val localEventStyles: Map<EventId, StyleOverride>,
    val staffSelectors: RenderedScoreStaffSelectorConfig,
) {
    fun filterSelection(selection: Set<EventSection>): Set<EventSection> =
        selection.onlyVoice(score, activeVoiceId)

    fun canSelect(section: EventSection): Boolean =
        activeVoiceId?.let { section.belongsToVoice(score, it) }
            ?: section.voiceEventIds().isNotEmpty()

    fun canSelectAnyVoice(section: EventSection): Boolean {
        val eventIds = section.voiceEventIds()
        return eventIds.isNotEmpty() &&
            eventIds.all { score.voiceIdForEvent(it) in selectableVoiceIds }
    }

    /**
     * Makes a click in another voice both switch the edit target and constrain the resulting
     * selection to that voice. The newly added section wins, so Shift-click never creates a
     * cross-voice selection in monodic editors.
     */
    fun resolveSelection(
        previous: Set<EventSection>,
        candidate: Set<EventSection>,
    ): VoiceFocusSelectionUpdate {
        val requestedVoiceId = (candidate - previous)
            .asSequence()
            .flatMap { it.voiceEventIds().asSequence() }
            .mapNotNull(score::voiceIdForEvent)
            .firstOrNull { it in selectableVoiceIds }
        val resolvedVoiceId = requestedVoiceId ?: activeVoiceId
        return VoiceFocusSelectionUpdate(
            activeVoiceId = resolvedVoiceId,
            selection = candidate.onlyVoice(score, resolvedVoiceId),
        )
    }

    companion object {
        private val INACTIVE_VOICE_STYLE =
            StyleOverride(fillColor = RenderColor.rgb(155, 160, 168))

        fun create(
            score: StorageScore,
            targets: List<VoiceFocusTarget>,
            activeVoiceId: TrackId?,
            allowAllVoices: Boolean,
            onActiveVoiceChange: (TrackId?) -> Unit,
        ): VoiceFocus {
            require(allowAllVoices || activeVoiceId != null) {
                "A required voice focus must have an active voice"
            }
            require(activeVoiceId == null || targets.any { it.voiceId == activeVoiceId }) {
                "The active voice must be one of the available voice-focus targets"
            }

            val localEventStyles = buildMap {
                if (activeVoiceId != null) {
                    score.voiceTracks.forEach { (voiceId, voice) ->
                        if (voiceId != activeVoiceId) {
                            voice.events.forEach { event ->
                                put(event.id, INACTIVE_VOICE_STYLE)
                            }
                        }
                    }
                }
            }
            val selectors = RenderedScoreStaffSelectorConfig(
                choicesByStaffId = targets
                    .groupBy { it.staffId }
                    .mapValues { (_, staffTargets) ->
                        staffTargets.map { target ->
                            RenderedScoreStaffSelectorChoice(
                                key = target.voiceId.value,
                                label = target.label,
                                selected = activeVoiceId == null || target.voiceId == activeVoiceId,
                            )
                        }
                    },
                onSelect = { key ->
                    targets.firstOrNull { it.voiceId.value == key }?.let { target ->
                        val nextVoiceId =
                            if (allowAllVoices && target.voiceId == activeVoiceId) null
                            else target.voiceId
                        onActiveVoiceChange(nextVoiceId)
                    }
                },
            )
            return VoiceFocus(
                score = score,
                selectableVoiceIds = targets.mapTo(linkedSetOf()) { it.voiceId },
                activeVoiceId = activeVoiceId,
                localEventStyles = localEventStyles,
                staffSelectors = selectors,
            )
        }
    }
}

data class VoiceFocusSelectionUpdate(
    val activeVoiceId: TrackId?,
    val selection: Set<EventSection>,
)

data class VoiceFocusTarget(
    val staffId: TrackId,
    val voiceId: TrackId,
    val label: String,
)
