package com.mecon.desktop.uikit.plugin

/**
 * Opaque wrapper passed through [com.mecon.api.plugin.PluginInstallContext.registerPanelDescriptor].
 * The desktop host casts back to this type at the panel-rendering site.
 */
data class PluginPanelDescriptor(
    val panel: PluginPanel
)
