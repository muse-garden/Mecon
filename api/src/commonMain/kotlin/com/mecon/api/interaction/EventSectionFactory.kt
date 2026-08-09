package com.mecon.api.interaction

import com.mecon.api.computed.ComputedVoiceEvent
import com.mecon.api.computed.ComputedBarline
import com.mecon.api.computed.ComputedClef
import com.mecon.api.computed.ComputedKeySignature
import com.mecon.api.computed.ComputedTimeSignature
import com.mecon.api.computed.BeamGroupId

object EventSectionFactory {
    fun ComputedVoiceEvent.toEventSection(): VoiceEventSection = VoiceEventSection(this)
    fun ComputedVoiceEvent.toNoteSection(pitchIndex: Int): VoiceNoteSection = VoiceNoteSection(this, pitchIndex)
    fun ComputedVoiceEvent.toStemSection(): VoiceStemSection = VoiceStemSection(this)
    fun ComputedVoiceEvent.toFlagSection(): VoiceFlagSection = VoiceFlagSection(this)
    fun List<ComputedVoiceEvent>.toBeamSection(groupId: BeamGroupId): VoiceBeamSection = VoiceBeamSection(this, groupId)
    fun ComputedBarline.toSection(): BarlineSection = BarlineSection(this)
    fun ComputedClef.toSection(): ClefSection = ClefSection(this)
    fun ComputedKeySignature.toSection(): KeySignatureSection = KeySignatureSection(this)
    fun ComputedTimeSignature.toSection(): TimeSignatureSection = TimeSignatureSection(this)

    // ===== ComputedVoiceEvent Extensions =====

    /**
     * Get the VoiceNoteSection for a specific pitch in this event.
     */
    fun ComputedVoiceEvent.toNoteSection(pitch: com.mecon.api.primitive.Pitch): VoiceNoteSection? {
        val index = this.pitchData.indexOfFirst { it.pitch == pitch }
        if (index == -1) return null
        return toNoteSection(index)
    }

    /**
     * Get all VoiceNoteSections for all pitches in this event.
     */
    fun ComputedVoiceEvent.toAllNoteSections(): List<VoiceNoteSection> {
        return pitchData.indices.map { toNoteSection(it) }
    }

    /**
     * Get all EventSections directly associated with this event (event, notes, stem, flag).
     * Note: BeamSection requires the full group, so it is not included here.
     */
    fun ComputedVoiceEvent.toAllSections(): List<EventSection> {
        val sections = mutableListOf<EventSection>(toEventSection(), toStemSection(), toFlagSection())
        sections.addAll(toAllNoteSections())
        return sections
    }

    // ===== RuntimePitchEvent Extensions =====

    /**
     * Find all runtime voice events that use this pitch event.
     */
    private fun com.mecon.api.runtime.events.RuntimePitchEvent.findVoiceEvents(): List<com.mecon.api.runtime.events.RuntimeVoiceEvent> {
        val runtimeScore = com.mecon.api.state.GlobalScoreState.currentState.runtimeScore
        return runtimeScore.getAllVoiceEvents().filter { it.pitchEvent.id == this.id }
    }

    /**
     * Get all VoiceNoteSections for a specific pitch across all voice events referencing this pitch event.
     */
    fun com.mecon.api.runtime.events.RuntimePitchEvent.toNoteSections(
        pitch: com.mecon.api.primitive.Pitch
    ): List<VoiceNoteSection> {
        val voiceEvents = findVoiceEvents()
        val computedScore = com.mecon.api.state.GlobalScoreState.currentState.computedScore
        return voiceEvents.mapNotNull {
            computedScore.getComputedEvent(it.id)?.toNoteSection(pitch)
        }
    }

    /**
     * Get all VoiceNoteSections for a specific pitch index across all voice events referencing this pitch event.
     */
    fun com.mecon.api.runtime.events.RuntimePitchEvent.toNoteSections(
        pitchIndex: Int
    ): List<VoiceNoteSection> {
        val voiceEvents = findVoiceEvents()
        val computedScore = com.mecon.api.state.GlobalScoreState.currentState.computedScore
        return voiceEvents.mapNotNull {
            val ce = computedScore.getComputedEvent(it.id)
            if (ce != null && pitchIndex in ce.pitchData.indices) ce.toNoteSection(pitchIndex) else null
        }
    }

    /**
     * Get all VoiceNoteSections for all pitches across all voice events referencing this pitch event.
     */
    fun com.mecon.api.runtime.events.RuntimePitchEvent.toAllNoteSections(): List<VoiceNoteSection> {
        val voiceEvents = findVoiceEvents()
        val computedScore = com.mecon.api.state.GlobalScoreState.currentState.computedScore
        return voiceEvents.flatMap {
            computedScore.getComputedEvent(it.id)?.toAllNoteSections() ?: emptyList()
        }
    }

    /**
     * Get all EventSections (event, notes, stem, flag) for all voice events referencing this pitch event.
     */
    fun com.mecon.api.runtime.events.RuntimePitchEvent.toAllSections(): List<EventSection> {
        val voiceEvents = findVoiceEvents()
        val computedScore = com.mecon.api.state.GlobalScoreState.currentState.computedScore
        return voiceEvents.flatMap {
            computedScore.getComputedEvent(it.id)?.toAllSections() ?: emptyList()
        }
    }
}
