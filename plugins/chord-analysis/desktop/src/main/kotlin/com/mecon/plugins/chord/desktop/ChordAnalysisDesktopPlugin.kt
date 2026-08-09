package com.mecon.plugins.chord.desktop

import com.mecon.api.plugin.PluginInstallContext
import com.mecon.desktop.uikit.plugin.PluginPanelDescriptor
import com.mecon.plugins.chord.ChordAnalysisPlugin

/**
 * Desktop variant of the chord analysis plugin. Composes the UI-free
 * registrations from [ChordAnalysisPlugin] (serializer + annotation provider)
 * and additionally installs the right-panel descriptor and i18n bundle.
 */
class ChordAnalysisDesktopPlugin : ChordAnalysisPlugin() {

    override fun install(ctx: PluginInstallContext) {
        super.install(ctx)
        ChordAnalysisStrings.install()
        ctx.registerPanelDescriptor(PluginPanelDescriptor(ChordAnalysisPanel))
    }
}
