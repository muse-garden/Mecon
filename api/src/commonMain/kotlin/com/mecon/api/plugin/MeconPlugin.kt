package com.mecon.api.plugin

/**
 * Entry point for a Mecon plugin. Plugins are instantiated by the host (desktop
 * app, headless tools) and have their [install] method called once during
 * startup, before any score is loaded.
 *
 * Plugins contribute through the [PluginInstallContext]: serializers for their
 * storage events, annotation staff providers, and host-specific descriptors
 * (e.g. desktop panels) routed via the opaque descriptor slot.
 */
interface MeconPlugin {
    /** Namespaced plugin id, e.g. `"mecon.chord_analysis"`. */
    val id: String

    /** Called once at startup. Register all contributions on [ctx]. */
    fun install(ctx: PluginInstallContext)
}
