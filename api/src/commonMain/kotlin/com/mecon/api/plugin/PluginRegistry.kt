package com.mecon.api.plugin

import com.mecon.api.storage.events.StoragePluginEvent
import kotlinx.serialization.KSerializer
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlin.reflect.KClass

/**
 * Definition-time singleton holding everything plugins contribute at startup:
 * polymorphic serializers, [AnnotationStaffProvider]s, and host-specific
 * panel descriptors.
 *
 * Call [installAll] once during app bootstrap, then [buildSerializersModule]
 * to feed `ScoreSerializer.installSerializersModule(...)`. The registry is
 * stateless w.r.t. any particular score — per-score state belongs in the
 * score / Compose state, not here.
 */
object PluginRegistry {

    private data class EventSerializerEntry<T : StoragePluginEvent>(
        val kClass: KClass<T>,
        val serializer: KSerializer<T>
    )

    private val eventSerializers = mutableListOf<EventSerializerEntry<*>>()
    private val annotationProviders = mutableListOf<AnnotationStaffProvider>()
    private val noteStyleProviders = mutableListOf<NoteStyleProvider>()
    private val panelDescriptors = mutableListOf<Any>()
    private val installedPluginIds = mutableSetOf<String>()

    /** Install every plugin in order. Idempotent per plugin id. */
    fun installAll(plugins: List<MeconPlugin>) {
        for (plugin in plugins) {
            if (!installedPluginIds.add(plugin.id)) continue
            plugin.install(InstallContextImpl)
        }
    }

    fun annotationStaffProviders(): List<AnnotationStaffProvider> = annotationProviders.toList()

    fun noteStyleProviders(): List<NoteStyleProvider> = noteStyleProviders.toList()

    fun panelDescriptors(): List<Any> = panelDescriptors.toList()

    /**
     * Compose a [SerializersModule] declaring [StoragePluginEvent] as the
     * polymorphic base, with every registered subclass registered.
     */
    fun buildSerializersModule(): SerializersModule = SerializersModule {
        polymorphic(StoragePluginEvent::class) {
            eventSerializers.forEach { entry -> registerEntry(this, entry) }
        }
    }

    /** Reset everything. Intended for tests only. */
    fun resetForTesting() {
        eventSerializers.clear()
        annotationProviders.clear()
        noteStyleProviders.clear()
        panelDescriptors.clear()
        installedPluginIds.clear()
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T : StoragePluginEvent> registerEntry(
        builder: kotlinx.serialization.modules.PolymorphicModuleBuilder<StoragePluginEvent>,
        entry: EventSerializerEntry<T>
    ) {
        builder.subclass(entry.kClass, entry.serializer)
    }

    private object InstallContextImpl : PluginInstallContext {
        override fun <T : StoragePluginEvent> registerEventSerializer(
            kClass: KClass<T>,
            serializer: KSerializer<T>
        ) {
            eventSerializers += EventSerializerEntry(kClass, serializer)
        }

        override fun registerAnnotationStaffProvider(provider: AnnotationStaffProvider) {
            annotationProviders += provider
        }

        override fun registerNoteStyleProvider(provider: NoteStyleProvider) {
            noteStyleProviders += provider
        }

        override fun registerPanelDescriptor(descriptor: Any) {
            panelDescriptors += descriptor
        }
    }
}
