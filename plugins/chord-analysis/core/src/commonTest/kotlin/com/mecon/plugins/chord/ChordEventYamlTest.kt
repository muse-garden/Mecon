package com.mecon.plugins.chord

import com.charleskorn.kaml.PolymorphismStyle
import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlConfiguration
import com.mecon.api.primitive.EventId
import com.mecon.api.primitive.Fraction
import com.mecon.api.primitive.TimeCode
import com.mecon.api.storage.StorageScore
import com.mecon.api.storage.events.StoragePluginEvent
import com.mecon.api.storage.tracks.StoragePluginTrack
import com.mecon.theory.ChordQuality
import com.mecon.theory.KeySignatureMode
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Validates that 11_chord_analysis.mecon loads without error and that
 * StorageChordEvent polymorphic YAML round-trips correctly using the
 * PolymorphismStyle.Property format produced by ScoreSerializer.
 */
class ChordEventYamlTest {

    private val module = SerializersModule {
        polymorphic(StoragePluginEvent::class) {
            subclass(StorageChordEvent::class, StorageChordEvent.serializer())
            subclass(StorageNonChordToneEvent::class, StorageNonChordToneEvent.serializer())
            subclass(StorageTonalRegionEvent::class, StorageTonalRegionEvent.serializer())
        }
    }

    private val yaml = Yaml(
        serializersModule = module,
        configuration = YamlConfiguration(
            encodeDefaults = false,
            strictMode = false,
            polymorphismStyle = PolymorphismStyle.Property
        )
    )

    /** Minimal inline YAML mirroring the plugin track section of 11_chord_analysis.mecon */
    private val yamlSnippet = """
        id: "test-chord-analysis-001"
        metadata:
          title: "Chord Analysis Test"
        defaultTimeSignature:
          numerator: 4
          denominator: 4
        pluginTracks:
          "chord-analysis-track":
            id: "chord-analysis-track"
            name: "Chord Analysis"
            type: "mecon.chord_analysis"
            events:
              - type: "mecon.chord_analysis.chord"
                id: "chord-evt-001"
                onset:
                  measure: 1
                  beat:
                    numerator: 0
                    denominator: 1
                root: 0
                quality: MAJOR
              - type: "mecon.chord_analysis.chord"
                id: "chord-evt-002"
                onset:
                  measure: 2
                  beat:
                    numerator: 0
                    denominator: 1
                root: 9
                quality: MINOR
              - type: "mecon.chord_analysis.chord"
                id: "chord-evt-007"
                onset:
                  measure: 7
                  beat:
                    numerator: 0
                    denominator: 1
                root: 0
                quality: MAJOR7
    """.trimIndent()

    @Test
    fun pluginTrackDeserializesFromYaml() {
        val score = yaml.decodeFromString(StorageScore.serializer(), yamlSnippet)
        val trackId = com.mecon.api.primitive.TrackId("chord-analysis-track")
        val track = score.pluginTracks[trackId]
        assertNotNull(track, "chord-analysis-track must be present")
        assertEquals("mecon.chord_analysis", track.type)
        assertEquals(3, track.events.size)

        val first = track.events[0] as StorageChordEvent
        assertEquals(0, first.root)
        assertEquals(ChordQuality.MAJOR, first.quality)

        val second = track.events[1] as StorageChordEvent
        assertEquals(9, second.root)
        assertEquals(ChordQuality.MINOR, second.quality)

        val seventh = track.events[2] as StorageChordEvent
        assertEquals(0, seventh.root)
        assertEquals(ChordQuality.MAJOR7, seventh.quality)
    }

    @Test
    fun pluginTrackRoundTrips() {
        val original = yaml.decodeFromString(StorageScore.serializer(), yamlSnippet)
        val reEncoded = yaml.encodeToString(StorageScore.serializer(), original)
        val decoded = yaml.decodeFromString(StorageScore.serializer(), reEncoded)
        val tid = com.mecon.api.primitive.TrackId("chord-analysis-track")
        assertEquals(
            original.pluginTracks[tid]!!.events.size,
            decoded.pluginTracks[tid]!!.events.size
        )
        val e1 = decoded.pluginTracks[tid]!!.events[0] as StorageChordEvent
        assertEquals(ChordQuality.MAJOR, e1.quality)
    }

    @Test
    fun polyphonyIntervalEventsRoundTrip() {
        val nonChordTone = StorageNonChordToneEvent.create(
            onset = TimeCode.of(1, Fraction.ZERO),
            endOnset = TimeCode.of(1, Fraction(1, 4)),
            voiceEventId = EventId("voice-event"),
            pitchIndex = 1,
        )
        val cMajor = PolyphonyTonalKey(0, KeySignatureMode.MAJOR)
        val gMajor = PolyphonyTonalKey(1, KeySignatureMode.MAJOR)
        val region = StorageTonalRegionEvent.create(
            onset = TimeCode.of(1, Fraction.ZERO),
            endOnset = TimeCode.of(3, Fraction.ZERO),
            keys = listOf(cMajor, gMajor),
            resolvedKey = gMajor,
        )
        val nonChordTrack = StoragePluginTrack
            .create(StorageNonChordToneEvent.TRACK_TYPE)
            .copy(events = listOf(nonChordTone))
        val regionTrack = StoragePluginTrack
            .create(StorageTonalRegionEvent.TRACK_TYPE)
            .copy(events = listOf(region))
        val original = StorageScore.create(
            StorageScore.CreationOptions("polyphony-round-trip")
        ).copy(
            pluginTracks = mapOf(
                nonChordTrack.id to nonChordTrack,
                regionTrack.id to regionTrack,
            )
        )

        val encoded = yaml.encodeToString(StorageScore.serializer(), original)
        val decoded = yaml.decodeFromString(StorageScore.serializer(), encoded)

        assertEquals(
            nonChordTone,
            decoded.pluginTracks.getValue(nonChordTrack.id).events.single(),
        )
        assertEquals(
            region,
            decoded.pluginTracks.getValue(regionTrack.id).events.single(),
        )
    }
}
