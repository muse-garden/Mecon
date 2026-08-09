package com.mecon.core.serializer

import com.charleskorn.kaml.PolymorphismStyle
import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlConfiguration
import com.mecon.api.primitive.EventId
import com.mecon.api.primitive.TimeCode
import com.mecon.api.primitive.TrackId
import com.mecon.api.storage.StorageScore
import com.mecon.api.storage.events.StoragePluginEvent
import com.mecon.api.storage.tracks.StoragePluginTrack
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.fail

/**
 * Spike: verify polymorphic StoragePluginEvent round-trip via JSON and YAML.
 *
 * Outcome drives the plugin event serialization strategy. If kaml polymorphic
 * fails, plugin events ship JSON-only with a TODO.
 */
class ScorePluginRoundTripSpike {

    @Serializable
    @SerialName("spike.chord")
    data class StorageChordEventSpike(
        override val id: EventId,
        override val onset: TimeCode,
        val symbol: String
    ) : StoragePluginEvent()

    private val module = SerializersModule {
        polymorphic(StoragePluginEvent::class) {
            subclass(StorageChordEventSpike::class, StorageChordEventSpike.serializer())
        }
    }

    private val json = Json {
        prettyPrint = false
        ignoreUnknownKeys = true
        encodeDefaults = false
        serializersModule = module
    }

    private val yamlProperty = Yaml(
        serializersModule = module,
        configuration = YamlConfiguration(
            encodeDefaults = false,
            strictMode = false,
            polymorphismStyle = PolymorphismStyle.Property
        )
    )

    private val yamlTagged = Yaml(
        serializersModule = module,
        configuration = YamlConfiguration(
            encodeDefaults = false,
            strictMode = false,
            polymorphismStyle = PolymorphismStyle.Tag
        )
    )

    private fun buildScore(): StorageScore {
        val baseScore = StorageScore.create(StorageScore.CreationOptions(title = "Spike"))
        val track = StoragePluginTrack(
            id = TrackId("spike.chord_track"),
            name = "Chord Plugin",
            type = "spike.chord",
            events = listOf(
                StorageChordEventSpike(
                    id = EventId("spike.evt.1"),
                    onset = TimeCode.ZERO,
                    symbol = "C"
                )
            )
        )
        return baseScore.addPluginTrack(track)
    }

    @Test
    fun jsonRoundTrip() {
        val original = buildScore()
        val text = json.encodeToString(StorageScore.serializer(), original)
        println("JSON spike output:\n$text")
        val decoded = json.decodeFromString(StorageScore.serializer(), text)
        assertEquals(original.pluginTracks.size, decoded.pluginTracks.size)
        val decodedTrack = decoded.pluginTracks.values.first()
        assertEquals(1, decodedTrack.events.size)
        val event = decodedTrack.events.first() as StorageChordEventSpike
        assertEquals("C", event.symbol)
        // NOTE: TimeCode round-trip has a separate (pre-existing) bug where decoded
        // value gains an extra component. Out of scope for this spike — polymorphism
        // is the question being answered here.
    }

    @Test
    fun yamlPropertyStyleRoundTrip() {
        val original = buildScore()
        val text = try {
            yamlProperty.encodeToString(StorageScore.serializer(), original)
        } catch (e: Throwable) {
            fail("YAML property-style encode failed: ${e.message}")
        }
        println("YAML (property) spike output:\n$text")
        val decoded = try {
            yamlProperty.decodeFromString(StorageScore.serializer(), text)
        } catch (e: Throwable) {
            fail("YAML property-style decode failed: ${e.message}")
        }
        assertEquals(original.pluginTracks.size, decoded.pluginTracks.size)
        val event = decoded.pluginTracks.values.first().events.first() as StorageChordEventSpike
        assertEquals("C", event.symbol)
    }

    @Test
    fun yamlTaggedStyleRoundTrip() {
        val original = buildScore()
        val text = try {
            yamlTagged.encodeToString(StorageScore.serializer(), original)
        } catch (e: Throwable) {
            fail("YAML tagged-style encode failed: ${e.message}")
        }
        println("YAML (tagged) spike output:\n$text")
        val decoded = try {
            yamlTagged.decodeFromString(StorageScore.serializer(), text)
        } catch (e: Throwable) {
            fail("YAML tagged-style decode failed: ${e.message}")
        }
        assertEquals(original.pluginTracks.size, decoded.pluginTracks.size)
        val event = decoded.pluginTracks.values.first().events.first() as StorageChordEventSpike
        assertEquals("C", event.symbol)
    }
}
