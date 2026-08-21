package com.mecon.desktop.ui.views

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.mecon.api.computed.ComputedScore
import com.mecon.api.interaction.EventSection
import com.mecon.api.interaction.NavigationMarkSection
import com.mecon.api.interaction.StaffAttachmentSection
import com.mecon.api.interaction.VoiceBeamSection
import com.mecon.api.interaction.VoltaEndingSection
import com.mecon.api.plugin.NoteSelectionLabel
import com.mecon.api.plugin.PluginRegistry
import com.mecon.desktop.ui.views.drag.BeamControlPoints
import com.mecon.desktop.ui.views.drag.findBeamControlPoints
import com.mecon.renderer.render.NoteheadCenterMarker
import com.mecon.renderer.render.NoteheadCenterMarkerComputer
import com.mecon.renderer.render.RenderElement

/**
 * Everything the canvas needs to decorate the current selection, resolved from the immutable render
 * frame rather than in the draw hot path.
 *
 * Only *singly* selected objects expose editor handles: a beam's endpoint controls, a volta's outer
 * edge, an attachment's anchors. Selecting two of a kind is a valid multi-selection for transforms,
 * but there is no meaningful single handle to drag, so those stay null here.
 */
internal class ScoreSelectionOverlayState(
    val beamSection: VoiceBeamSection?,
    val beamControls: BeamControlPoints?,
    val voltaSection: VoltaEndingSection?,
    val voltaElements: List<RenderElement>,
    val navigationElements: List<RenderElement>,
    val attachmentSection: StaffAttachmentSection?,
    val attachmentElements: List<RenderElement>,
    val noteheadCenterMarkers: List<NoteheadCenterMarker>,
    val noteSelectionLabels: List<NoteSelectionLabel>,
)

@Composable
internal fun rememberScoreSelectionOverlay(
    frame: ScoreRenderFrame,
    computed: ComputedScore?,
    selection: Set<EventSection>,
    selectionConfig: RenderedScoreSelectionConfig,
    display: RenderedScoreDisplayConfig,
): ScoreSelectionOverlayState {
    val beamSection = selection.filterIsInstance<VoiceBeamSection>().singleOrNull()
    val attachmentSection = selection.filterIsInstance<StaffAttachmentSection>().singleOrNull()
    val voltaSection = selection.filterIsInstance<VoltaEndingSection>().singleOrNull()
    val navigationSection = selection.filterIsInstance<NavigationMarkSection>().singleOrNull()
    return ScoreSelectionOverlayState(
        beamSection = beamSection,
        // Endpoint controls are editor chrome derived from stem tips, never renderer output.
        beamControls = remember(frame.identityKey, beamSection?.id) {
            val result = frame.result
            if (result != null && beamSection != null) findBeamControlPoints(result, beamSection)
            else null
        },
        voltaSection = voltaSection,
        voltaElements = frame.rememberElementsFor(voltaSection),
        navigationElements = frame.rememberElementsFor(navigationSection),
        attachmentSection = attachmentSection,
        attachmentElements = frame.rememberElementsFor(attachmentSection),
        // Lock markers are session overlays: resolve their stable note references once per frame.
        noteheadCenterMarkers = remember(
            frame.identityKey,
            selectionConfig.noteheadCenterMarkerNotes,
        ) {
            frame.result?.let { result ->
                NoteheadCenterMarkerComputer.compute(
                    result.elements,
                    selectionConfig.noteheadCenterMarkerNotes,
                )
            }.orEmpty()
        },
        noteSelectionLabels = remember(
            frame.computedIdentityKey,
            selection,
            display.selectionOverlayRefreshKey,
            display.showPluginSelectionLabels,
        ) {
            computed?.takeIf { display.showPluginSelectionLabels }?.let { target ->
                PluginRegistry.noteSelectionLabelProviders().flatMap { it.labels(target, selection) }
            }.orEmpty()
        },
    )
}

/** The rendered elements of a singly-selected section, resolved once per frame. */
@Composable
private fun ScoreRenderFrame.rememberElementsFor(section: EventSection?): List<RenderElement> =
    remember(identityKey, section?.id) {
        val rendered = result
        if (rendered == null || section == null) emptyList()
        else rendered.sectionIndex.elementsFor(section).elementIds.mapNotNull(rendered::elementById)
    }
