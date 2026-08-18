package com.mecon.plugins.chord

import com.mecon.api.plugin.MeconPlugin
import com.mecon.api.plugin.PluginInstallContext

/**
 * Core (UI-free) registrations for the chord analysis plugin:
 * - Polymorphic serializer for [StorageChordEvent].
 * - [ChordAnnotationProvider] for the chord-symbol annotation staff.
 *
 * Desktop UI registrations live in `:plugins:chord-analysis:desktop` and compose
 * on top of this plugin.
 */
open class ChordAnalysisPlugin : MeconPlugin {
    override val id: String = "mecon.chord_analysis"

    override fun install(ctx: PluginInstallContext) {
        ctx.registerEventSerializer(StorageChordEvent::class, StorageChordEvent.serializer())
        ctx.registerEventSerializer(StorageNonChordToneEvent::class, StorageNonChordToneEvent.serializer())
        ctx.registerEventSerializer(StorageTonalRegionEvent::class, StorageTonalRegionEvent.serializer())
        ctx.registerAnnotationStaffProvider(ChordAnnotationProvider)
        ctx.registerAnnotationStaffProvider(ChordTimelineAnnotationProvider)
        ctx.registerAnnotationStaffProvider(PolyphonyAnnotationProvider)
        ctx.registerNoteStyleProvider(ChordToneStyleProvider)
    }
}
