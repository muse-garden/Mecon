package com.mecon.renderer.render

import com.mecon.api.computed.ComputedScore
import com.mecon.api.interaction.EventSectionId
import com.mecon.api.interaction.VoiceNoteSection
import com.mecon.api.plugin.NoteStyleProvider
import com.mecon.api.plugin.PluginRegistry
import com.mecon.api.primitive.EventId
import com.mecon.api.interaction.StyleOverride
import com.mecon.renderer.interaction.StyleOverrideManager
import com.mecon.renderer.interaction.StyleTrackImpl

internal class NoteStyleCoordinator(
    private val styleOverrideManager: StyleOverrideManager,
) {
    private var track: StyleTrackImpl? = null
    private var cachedProviders: List<NoteStyleProvider> = emptyList()
    private val providerOutputs =
        mutableMapOf<NoteStyleProvider, MutableMap<Pair<EventId, Int>, StyleOverride>>()

    fun reset() {
        track?.clear()
        track = null
        cachedProviders = emptyList()
        providerOutputs.clear()
    }

    fun apply(computedScore: ComputedScore) {
        val providers = PluginRegistry.noteStyleProviders()
        val activeTrack = track ?: (styleOverrideManager.createTrack(1) as StyleTrackImpl).also {
            track = it
        }
        if (providers != cachedProviders) {
            replaceAll(activeTrack, providers, computedScore)
            return
        }

        val dirtyKeys = linkedSetOf<Pair<EventId, Int>>()
        providers.forEach { provider ->
            val output = providerOutputs.getOrPut(provider) { mutableMapOf() }
            val patch = provider.computeStylePatch(computedScore)
            if (patch == null) {
                val fresh = provider.computeStyles(computedScore)
                dirtyKeys += output.keys
                dirtyKeys += fresh.keys
                output.clear()
                output.putAll(fresh)
            } else {
                dirtyKeys += patch.upserts.keys
                dirtyKeys += patch.removes
                patch.removes.forEach(output::remove)
                output.putAll(patch.upserts)
            }
        }

        val upserts = linkedMapOf<EventSectionId, StyleOverride>()
        val removes = linkedSetOf<EventSectionId>()
        dirtyKeys.forEach { key ->
            val event = computedScore.computedEvents[key.first]
            val sectionId = event?.let { VoiceNoteSection(it, key.second).id } ?: return@forEach
            val effective = providers.asReversed().firstNotNullOfOrNull { providerOutputs[it]?.get(key) }
            if (effective == null) removes += sectionId else upserts[sectionId] = effective
        }
        activeTrack.applyPatchBySectionId(upserts, removes)
        activeTrack.submit()
    }

    private fun replaceAll(
        activeTrack: StyleTrackImpl,
        providers: List<NoteStyleProvider>,
        computedScore: ComputedScore,
    ) {
        activeTrack.clear()
        providerOutputs.clear()
        cachedProviders = providers
        providers.forEach { provider ->
            providerOutputs[provider] = provider.computeStyles(computedScore).toMutableMap()
        }
        val merged = linkedMapOf<Pair<EventId, Int>, StyleOverride>()
        providers.forEach { provider -> merged.putAll(providerOutputs[provider].orEmpty()) }
        merged.forEach { (key, override) ->
            val event = computedScore.computedEvents[key.first] ?: return@forEach
            activeTrack.setStyleBySectionId(VoiceNoteSection(event, key.second).id, override)
        }
        activeTrack.submit()
    }
}
