package com.mecon.api.plugin

import com.mecon.api.storage.events.StoragePluginEvent
import kotlinx.serialization.KSerializer
import kotlin.reflect.KClass

/**
 * Registration sink passed to [MeconPlugin.install]. Plugins call the
 * `registerXxx` methods to contribute serializers, annotation providers, and
 * host-specific descriptors.
 *
 * The opaque [registerPanelDescriptor] slot accepts `Any` so the SPI in `:api`
 * stays UI-free; UI hosts (e.g. `:apps:desktop-ui-kit`) define their own
 * descriptor type and downcast at consumption time.
 */
interface PluginInstallContext {
    /**
     * Register a polymorphic serializer for a [StoragePluginEvent] subclass.
     * The registry assembles these into a [kotlinx.serialization.modules.SerializersModule]
     * that the score serializer installs once all plugins have run.
     */
    fun <T : StoragePluginEvent> registerEventSerializer(
        kClass: KClass<T>,
        serializer: KSerializer<T>
    )

    /** Register an annotation staff provider. */
    fun registerAnnotationStaffProvider(provider: AnnotationStaffProvider)

    /** Register a note style provider for per-notehead coloring. */
    fun registerNoteStyleProvider(provider: NoteStyleProvider)

    /**
     * Register a host-specific descriptor (e.g. a desktop panel). The host
     * narrows the [Any] back to its concrete descriptor type.
     */
    fun registerPanelDescriptor(descriptor: Any)
}
