package com.mecon.plugins.chord

import com.mecon.api.plugin.AnnotationAlignment
import com.mecon.api.plugin.AnnotationElement
import com.mecon.api.plugin.AnnotationLayoutContext
import com.mecon.api.plugin.AnnotationStaffProvider
import com.mecon.api.plugin.PluginStaffId
import com.mecon.api.plugin.StaffAnchor
import com.mecon.api.render.FormattedText
import com.mecon.theory.ChordSymbolDisplayStyle
import com.mecon.theory.KeySignatureMode

/**
 * Reads chord plugin tracks from the computed score and emits one text annotation
 * per chord event, placed at the staff's vertical center.
 */
object ChordAnnotationProvider : AnnotationStaffProvider {
    /** Base font size for on-staff chord symbols; roomy enough that the 0.75× quality suffix stays legible. */
    private const val CHORD_SYMBOL_FONT_SIZE = 17f

    override val staffId: PluginStaffId = PluginStaffId(StorageChordEvent.TRACK_TYPE)

    override val anchor: StaffAnchor = StaffAnchor.BelowAllStaves

    override val pluginTrackTypes: Set<String> = setOf(StorageChordEvent.TRACK_TYPE)

    override fun layout(ctx: AnnotationLayoutContext): List<AnnotationElement> {
        if (ChordSymbolDisplaySettings.scoreDisplayMode != ChordAnalysisScoreDisplayMode.CLASSIC) {
            return emptyList()
        }
        return ctx.computedScore
            .pluginTracks
            .values
            .filter { it.type == StorageChordEvent.TRACK_TYPE }
            .flatMap { track ->
                track.events.toList().mapNotNull { event ->
                    val storageChord = event.runtimeEvent.storageEvent as? StorageChordEvent
                        ?: return@mapNotNull null
                    val computedChord = ComputedChordEvent.fromRuntime(RuntimeChordEvent.fromStorage(storageChord))
                    val keySignature = ctx.computedScore.runtime.getKeySignatureAt(storageChord.onset.measure)
                    val style = ChordSymbolDisplaySettings.style
                    val tonalKeys = PolyphonyTonalContextResolver.keysAt(
                        ctx.computedScore,
                        storageChord.onset,
                    )
                    val content = if (
                        style == ChordSymbolDisplayStyle.SCALE_DEGREE && tonalKeys.size > 1
                    ) {
                        FormattedText.plain(
                            tonalKeys.joinToString(" · ") { key ->
                                "${key.displayName}${
                                    if (key.mode == KeySignatureMode.MINOR) "m" else ""
                                }:" + computedChord.formattedSymbol(
                                    style = style,
                                    keySignature = key.keySignature,
                                ).plainText
                            }
                        )
                    } else {
                        computedChord.formattedSymbol(
                            style = style,
                            keySignature = tonalKeys.singleOrNull()?.keySignature ?: keySignature,
                        ).toFormattedText(style)
                    }
                    AnnotationElement.Text(
                        time = storageChord.onset,
                        relativeY = 0f,
                        sourceEventId = storageChord.id,
                        trackId = track.id,
                        content = content,
                        fontSize = CHORD_SYMBOL_FONT_SIZE,
                        alignment = AnnotationAlignment.LEFT
                    )
                }
            }
    }
}
