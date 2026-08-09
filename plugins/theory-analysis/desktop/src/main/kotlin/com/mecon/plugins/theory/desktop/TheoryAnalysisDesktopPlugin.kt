package com.mecon.plugins.theory.desktop

import com.mecon.api.plugin.MeconPlugin
import com.mecon.api.plugin.PluginInstallContext
import com.mecon.desktop.uikit.plugin.PluginPanelDescriptor

class TheoryAnalysisDesktopPlugin : MeconPlugin {
    override val id: String = "mecon.theory_analysis.desktop"

    override fun install(ctx: PluginInstallContext) {
        TheoryAnalysisStrings.install()
        ctx.registerAnnotationStaffProvider(TheoryAnalysisAnnotationProvider)
        ctx.registerNoteStyleProvider(TheoryAnalysisNoteStyleProvider)
        ctx.registerPanelDescriptor(PluginPanelDescriptor(TheoryAnalysisPanel))
    }
}
