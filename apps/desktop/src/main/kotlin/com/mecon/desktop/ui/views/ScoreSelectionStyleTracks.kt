package com.mecon.desktop.ui.views

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import com.mecon.api.interaction.EventSection
import com.mecon.api.interaction.EventSectionId
import com.mecon.api.interaction.MeasureStaffSection
import com.mecon.api.interaction.VoiceEventSection
import com.mecon.api.primitive.EventId
import com.mecon.api.runtime.RuntimeScore
import com.mecon.api.interaction.StyleOverride
import com.mecon.renderer.interaction.StyleTrackImpl
import com.mecon.renderer.render.RenderEngine
import com.mecon.renderer.render.RenderResult

/**
 * Paint the current selection (and any caller-supplied per-event styles) through the engine's style
 * override tracks, so selection colour survives into the recorded page pictures.
 *
 * Selection styling is keyed by *stable section IDs*, not by the frame: a transpose replaces the
 * embedded `ComputedVoiceEvent` but keeps the same IDs, so rebuilding an identical track would only
 * invalidate and re-record the page's notes Picture for nothing. Whole-measure selection is the one
 * exception — it expands through the current frame's section index and therefore really does depend
 * on the frame.
 */
@Composable
internal fun ScoreSelectionStyleTracks(
    selection: Set<EventSection>,
    localEventStyles: Map<EventId, StyleOverride>,
    score: RuntimeScore?,
    engine: RenderEngine?,
    result: RenderResult?,
    resultIdentityKey: Long,
    scoreIdentityKey: Long,
) {
    val selectionSectionIds = selection.mapTo(LinkedHashSet()) { it.id }
    val selectionVoiceColorKey = selection.map { it.id to it.voiceNumber(score) }
    val measureSelection = selection.any { it is MeasureStaffSection }
    val selectionTrack = remember(
        selectionSectionIds,
        selectionVoiceColorKey,
        engine,
        if (measureSelection) resultIdentityKey else 0L,
        if (measureSelection) scoreIdentityKey else 0L,
    ) {
        if (selection.isEmpty() || engine == null) return@remember null
        // One highest-priority track carries a voice-specific fill for every selected section.
        // Multiple setStyle calls share the track (keyed by sectionId) and submit once.
        val track = engine.getStyleOverrideManager().createTrack(Int.MAX_VALUE)
        val styledSections = LinkedHashSet<EventSection>()
        selection.forEach { section ->
            if (section !is MeasureStaffSection) {
                styledSections += section
                return@forEach
            }
            val voiceIds = score?.staffTracks?.get(section.staffTrackId)?.voiceTracks
                ?.mapTo(HashSet()) { it.id }.orEmpty()
            result?.sectionIndex?.allOfType<VoiceEventSection>()
                ?.filter { eventSection ->
                    eventSection.event.onset.measure == section.measureNumber &&
                        (eventSection.event.originVoiceTrackId ?: score?.voiceTracks?.entries
                            ?.firstOrNull { (_, voice) -> voice.events.toList().any { it.id == eventSection.event.id } }
                            ?.key) in voiceIds
                }
                ?.forEach { styledSections += it }
        }
        styledSections.forEach { section ->
            track.setStyle(
                section,
                StyleOverride(fillColor = voiceSelectionRenderColor(section.voiceNumber(score) ?: 1)),
            )
        }
        track.submit()
        track
    }

    DisposableEffect(selectionTrack, engine) {
        onDispose {
            selectionTrack?.let { engine?.getStyleOverrideManager()?.removeTrack(it) }
        }
    }

    val localStyleTrack = remember(localEventStyles, engine) {
        if (localEventStyles.isEmpty() || engine == null) return@remember null
        val track = engine.getStyleOverrideManager().createTrack(Int.MAX_VALUE - 10) as StyleTrackImpl
        localEventStyles.forEach { (eventId, override) ->
            track.setStyleBySectionId(EventSectionId.voiceEvent(eventId), override)
            track.setStyleBySectionId(EventSectionId.voiceNote(eventId, 0), override)
            track.setStyleBySectionId(EventSectionId.voiceStem(eventId), override)
            track.setStyleBySectionId(EventSectionId.voiceFlag(eventId), override)
        }
        track.submit()
        track
    }

    DisposableEffect(localStyleTrack, engine) {
        onDispose {
            localStyleTrack?.let { engine?.getStyleOverrideManager()?.removeTrack(it) }
        }
    }
}
