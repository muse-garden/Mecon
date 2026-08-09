package com.mecon.desktop.ui.views

import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.Modifier
import com.mecon.api.computed.ComputedScore
import com.mecon.api.computed.ComputedVoiceEvent
import com.mecon.api.interaction.*
import com.mecon.api.primitive.EventId
import com.mecon.api.runtime.orderedStaffs
import com.mecon.api.runtime.RuntimeScore
import com.mecon.api.storage.PageArrangement
import com.mecon.api.storage.tracks.Clef
import com.mecon.renderer.geometry.*
import com.mecon.renderer.render.*

internal data class SelectionGestureFrame(
    val resultIdentityKey: Long,
    val arrangement: PageArrangement,
    val result: RenderResult?,
    val paginated: Boolean,
    val pages: List<RenderPage>,
    val pageSlots: List<Offset>,
    val editorMarkers: List<RenderElement>,
)

internal data class SelectionGestureDocument(
    val score: RuntimeScore?,
    val computed: ComputedScore?,
)

internal data class SelectionGestureMode(
    val insertionToolActive: Boolean,
    val showEditorMarkers: Boolean,
)

internal data class SelectionGestureState(
    val offset: () -> Offset,
    val scale: () -> Float,
    val shiftHeld: () -> Boolean,
    val selection: () -> Set<EventSection>,
)

internal data class SelectionGestureActions(
    val selectionChange: (Set<EventSection>) -> Unit,
    val auditionNote: (com.mecon.api.computed.ComputedVoiceEvent, Set<Int>?, Set<Int>?, Int) -> Unit,
    val selectAnnotationEvent: (com.mecon.api.primitive.EventId?) -> Unit,
)

internal data class SelectionGestureRequest(
    val frame: SelectionGestureFrame,
    val document: SelectionGestureDocument,
    val mode: SelectionGestureMode,
    val state: SelectionGestureState,
    val actions: SelectionGestureActions,
)

internal fun Modifier.scoreSelectionGestures(request: SelectionGestureRequest): Modifier {
    val renderResultIdentityKey = request.frame.resultIdentityKey
    val arrangement = request.frame.arrangement
    val insertionToolActive = request.mode.insertionToolActive
    val showEditorMarkers = request.mode.showEditorMarkers
    val renderResult = request.frame.result
    val paginatedView = request.frame.paginated
    val pages = request.frame.pages
    val pageSlots = request.frame.pageSlots
    val editorMarkers = request.frame.editorMarkers
    val currentScore = request.document.score
    val currentComputed = request.document.computed
    val currentOnSelectionChange = request.actions.selectionChange
    val currentOnAuditionNote = request.actions.auditionNote
    val onSelectAnnotationEvent = request.actions.selectAnnotationEvent
    val offset by LiveValue(request.state.offset)
    val scale by LiveValue(request.state.scale)
    val shiftHeld by LiveValue(request.state.shiftHeld)
    val currentSelection by LiveValue(request.state.selection)
    return this
    .pointerInput(renderResultIdentityKey, arrangement, insertionToolActive, showEditorMarkers) {
        // When an insertion tool is active, its handler below owns clicks.
        if (insertionToolActive) return@pointerInput
        val result = renderResult ?: return@pointerInput
        detectTapGestures { tapOffset ->
                                // Tap → canvas design-space point (undo pan offset, zoom, density).
            val designX = (tapOffset.x - offset.x) / scale / density
            val designY = (tapOffset.y - offset.y) / scale / density
            // In paginated mode the design point lives in the laid-out page
            // grid; map it back to the global score space the hit index uses.
                                // Outside any page → no hit.
            val point = if (paginatedView) {
                designToGlobal(Offset(designX, designY), pages, pageSlots)
                    ?: run {
                        // Clicked off every page: clear (shift preserves the set).
                        if (!shiftHeld) currentOnSelectionChange(emptySet())
                        onSelectAnnotationEvent(null)
                        return@detectTapGestures
                    }
            } else {
                AbsolutePoint(Pixels(designX), Pixels(designY))
            }
            if (showEditorMarkers) {
                val marker = editorMarkers.firstOrNull { point in it.hitBox }
                val section = marker?.let { element ->
                    result.sectionIndex.sectionsFor(element.id)
                        .firstOrNull {
                            it is LayoutBreakSection || it is HiddenStaffSection || it is StaffAttachmentSection
                        }
                }
                if (section != null) {
                    currentOnSelectionChange(if (shiftHeld && section in currentSelection) {
                        currentSelection - section
                    } else if (shiftHeld) currentSelection + section else setOf(section))
                    onSelectAnnotationEvent(null)
                    return@detectTapGestures
                }
            }
            // Repeat/navigation overlays deliberately win over the barline beneath
            // their anchor. Otherwise the broad structural barline target makes
            // these comparatively small marks frustrating to acquire.
            val hitResult = result.hitTest(point)
            val repeatOverlay = hitResult.allSections()
                .filter {
                    it is VoltaEndingSection || it is NavigationMarkSection
                }
                .selectByPriority()
            if (repeatOverlay != null) {
                val next = if (shiftHeld) {
                    if (repeatOverlay in currentSelection) currentSelection - repeatOverlay
                    else currentSelection + repeatOverlay
                } else setOf(repeatOverlay)
                currentOnSelectionChange(next)
                onSelectAnnotationEvent(null)
                return@detectTapGestures
            }
            // Resolve the actual barline-time-slot boundary first. This includes
            // the structural closing rule used at a paginated system ending.
            val visualBarlineHit = result.barlineHitAt(point)
            val visualBarline = visualBarlineHit?.let { hit ->
                currentComputed?.barlines?.firstOrNull {
                    it.measureNumber == hit.measureNumber
                }
            }
            if (visualBarline != null) {
                val section = BarlineSection(
                    visualBarline,
                    visualBarlineHit?.systemIndex,
                    visualBarlineHit?.placement ?: BarlineVisualPlacement.INLINE,
                )
                val next = if (shiftHeld) {
                    if (section in currentSelection) currentSelection - section
                    else currentSelection + section
                } else setOf(section)
                currentOnSelectionChange(next)
                onSelectAnnotationEvent(null)
                return@detectTapGestures
            }

            val picked = hitResult.allSections().selectByPriority()
            if (picked != null) {
                // Shift toggles the hit section within the existing set; a plain
                // click replaces the whole selection with just that section.
                val wasSelected = picked in currentSelection
                val next = if (shiftHeld) {
                    if (picked in currentSelection) currentSelection - picked
                    else currentSelection + picked
                } else setOf(picked)
                currentOnSelectionChange(next)
                // Plain clicks always audition. Shift only auditions an addition;
                // toggling an existing section off is intentionally silent.
                if (!shiftHeld || !wasSelected) {
                    picked.auditionTarget()?.let { target ->
                        currentOnAuditionNote(
                            target.event,
                            target.soundingPitchIndices,
                            target.transposedPitchIndices,
                            0,
                        )
                    }
                }
                onSelectAnnotationEvent(null)
            } else {
                // An empty hit inside a staff cell selects that complete measure on
                // this staff. Element hits above deliberately win, preserving note /
                // clef / barline precision.
                val measureHit = result.measureStaffAt(point)
                val staffTrackId = measureHit?.let { hit ->
                    currentScore?.orderedStaffs()?.getOrNull(hit.second)?.id
                }
                if (measureHit != null && staffTrackId != null) {
                    val measureSection = MeasureStaffSection(staffTrackId, measureHit.first)
                    val next = if (shiftHeld) {
                        if (measureSection in currentSelection) currentSelection - measureSection
                        else currentSelection + measureSection
                    } else setOf(measureSection)
                    currentOnSelectionChange(next)
                    onSelectAnnotationEvent(null)
                    return@detectTapGestures
                }
                // Check annotation elements (chord symbols, etc.)
                val annotationHit = renderResult?.elements
                    ?.filter { it.type == RenderElementType.TEXT_ANNOTATION }
                    ?.firstOrNull { point in it.hitBox }
                if (annotationHit != null) {
                    currentOnSelectionChange(emptySet())
                    onSelectAnnotationEvent(annotationHit.eventId)
                } else {
                    // Empty space: a plain click clears; shift+click keeps the set.
                    if (!shiftHeld) currentOnSelectionChange(emptySet())
                    onSelectAnnotationEvent(null)
                }
            }
        }
    }
}
