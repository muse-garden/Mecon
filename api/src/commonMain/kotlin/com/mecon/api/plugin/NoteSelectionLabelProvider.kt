package com.mecon.api.plugin

import com.mecon.api.computed.ComputedScore
import com.mecon.api.interaction.EventSection
import com.mecon.api.primitive.EventId

/** A short, non-spacing label attached to one selected notehead. */
data class NoteSelectionLabel(
    val eventId: EventId,
    val pitchIndex: Int,
    val text: String,
)

/**
 * Plugin SPI for selection-dependent note labels.
 *
 * Unlike an [AnnotationStaffProvider], these labels are painted by the host as a transient overlay:
 * they do not enter score layout, pagination, hit testing, or persisted score state. Implementations
 * must inspect only the supplied selection and small plugin projections; they run on the UI thread
 * whenever the selection changes and therefore must not scan the complete score.
 */
interface NoteSelectionLabelProvider {
    val id: String

    fun labels(score: ComputedScore, selection: Set<EventSection>): List<NoteSelectionLabel>
}
