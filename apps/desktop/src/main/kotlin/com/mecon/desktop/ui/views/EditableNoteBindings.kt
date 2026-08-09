package com.mecon.desktop.ui.views

import com.mecon.api.interaction.EventSection
import com.mecon.desktop.service.EditableNoteHost

/**
 * Direct-manipulation callbacks common to every editable notation surface.
 *
 * Callers may [copy] the result to add document-only geometry operations such as beam or attachment
 * movement without reimplementing note transpose/rest movement.
 */
internal fun EditableNoteHost.noteMovementActions(
    onAfterEdit: (Set<EventSection>) -> Unit,
): RenderedScoreEventMoveActions = RenderedScoreEventMoveActions(
    onTranspose = { targets, delta -> applyNoteTranspose(targets, delta, onAfterEdit) },
    onMoveRest = { targets -> applyRestMove(targets, onAfterEdit) },
)
