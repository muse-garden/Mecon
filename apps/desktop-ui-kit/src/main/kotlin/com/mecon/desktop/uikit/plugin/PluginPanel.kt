package com.mecon.desktop.uikit.plugin

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * Desktop-only plugin contribution that renders an inspector panel section
 * inside the right-hand panel. Plugins register a [PluginPanelDescriptor]
 * wrapping their `PluginPanel`; the host iterates registered descriptors and
 * renders each as a [com.mecon.desktop.uikit.components.ResizablePanelItem].
 */
interface PluginPanel {
    /** Stable namespaced id, e.g. `"mecon.chord_analysis"`. */
    val id: String

    /** i18n key for the panel title (registered via `I18nRegistry`). */
    val titleKey: String

    /** Optional Material icon shown in the panel header. */
    val icon: ImageVector?

    /** Initial expanded height of the panel. */
    val initialHeightDp: Int get() = 200

    /** When true, the kit skips inner padding (Tonnetz-style fullbleed visuals). */
    val noPadding: Boolean get() = false

    /** When true, the kit skips wrapping content in a verticalScroll. */
    val noScroll: Boolean get() = false

    /**
     * When true, the panel content box uses wrap-content height instead of the fixed
     * [initialHeightDp], and the bottom drag-resize handle is hidden. The user can
     * still collapse the panel via the header.
     */
    val wrapContent: Boolean get() = false

    /** Compose body of the panel. Receives the live [PluginPanelContext]. */
    @Composable
    fun Content(ctx: PluginPanelContext)
}
