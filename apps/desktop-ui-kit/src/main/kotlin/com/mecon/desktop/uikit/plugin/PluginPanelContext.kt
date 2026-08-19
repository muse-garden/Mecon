package com.mecon.desktop.uikit.plugin

import com.mecon.api.model.NoteId
import com.mecon.api.model.Score
import com.mecon.api.interaction.EventSection
import com.mecon.api.primitive.EventId
import com.mecon.api.primitive.TimeCode
import com.mecon.api.runtime.RuntimeScore
import com.mecon.api.storage.events.StoragePluginEvent

/**
 * State plumbed from the host (the desktop app) into a [PluginPanel] each
 * recomposition. Plugins never see the score-state manager directly.
 */
data class PluginPanelContext(
    val score: Score,
    val selection: NoteId?,
    /** Current score-element selection (noteheads, whole events, rests, etc.). */
    val eventSelection: Set<EventSection> = emptySet(),
    /** EventId of the currently selected annotation element, or null if none. */
    val selectedAnnotationEventId: EventId? = null,
    /** Full runtime score for plugin-track lookups. */
    val runtimeScore: RuntimeScore? = null,
    /** Insertion point for new events — onset of the currently selected note, or null. */
    val targetTimeCode: TimeCode? = null,
    /** Signal to the host to re-apply note style providers without a full re-render. */
    val onRequestNoteStyleRecompute: (() -> Unit)? = null,
    /** Signal to the host that a plugin display preference changed and annotations need re-layout. */
    val onRequestRender: (() -> Unit)? = null,
    /** Signal to repaint transient selection overlays without running score layout. */
    val onRequestSelectionOverlayRefresh: (() -> Unit)? = null,
    /** Whether the host piano roll is currently showing the chord-analysis overlay. */
    val pianoRollChordOverlayEnabled: Boolean = false,
    /** Open the host piano roll and enable or disable its chord-analysis overlay. */
    val onShowPianoRollChords: ((enabled: Boolean) -> Unit)? = null,
    /** Add a new event to the plugin track identified by [trackType]. */
    val onAddPluginEvent: ((trackType: String, event: StoragePluginEvent) -> Unit)? = null,
    /** Atomically replace an existing plugin event with a new one (single undo step). */
    val onUpdatePluginEvent: ((trackType: String, oldEventId: EventId, newEvent: StoragePluginEvent) -> Unit)? = null,
    /** Remove a plugin event from the track identified by [trackType]. */
    val onDeletePluginEvent: ((trackType: String, eventId: EventId) -> Unit)? = null,
    /** Atomically replace one plugin track's events (one undo step). */
    val onReplacePluginEvents: ((trackType: String, events: List<StoragePluginEvent>) -> Unit)? = null,
)
