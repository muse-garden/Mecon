package com.mecon.desktop.bootstrap

import com.mecon.api.plugin.MeconPlugin
import com.mecon.api.plugin.PluginRegistry
import com.mecon.core.serializer.ScoreSerializer
import com.mecon.plugins.chord.desktop.ChordAnalysisDesktopPlugin
import com.mecon.plugins.theory.desktop.TheoryAnalysisDesktopPlugin

/**
 * Install every bundled plugin and hand the resulting polymorphic
 * [SerializersModule][kotlinx.serialization.modules.SerializersModule] to
 * [ScoreSerializer] before any score is read or written.
 *
 * Must be called exactly once during desktop startup, before [com.mecon.desktop.App]
 * is composed and before any save / load path runs.
 */
fun bootstrapPlugins() {
    PluginRegistry.installAll(
        listOf<MeconPlugin>(
            ChordAnalysisDesktopPlugin(),
            TheoryAnalysisDesktopPlugin(),
        )
    )
    ScoreSerializer.installSerializersModule(PluginRegistry.buildSerializersModule())
}
