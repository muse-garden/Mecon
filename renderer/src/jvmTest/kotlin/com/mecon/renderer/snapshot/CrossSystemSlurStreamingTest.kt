package com.mecon.renderer.snapshot

import com.mecon.api.primitive.Duration
import com.mecon.api.primitive.EventId
import com.mecon.api.primitive.Fraction
import com.mecon.api.primitive.Pitch
import com.mecon.api.primitive.TimeCode
import com.mecon.api.runtime.RuntimeScore
import com.mecon.api.runtime.events.RuntimePitchEvent
import com.mecon.api.runtime.events.RuntimeVoiceEvent
import com.mecon.api.storage.StorageScore
import com.mecon.api.storage.tracks.StorageSystemBreak
import com.mecon.core.engine.computeScore
import com.mecon.renderer.geometry.StaffSpace
import com.mecon.renderer.layout.PageGeometry
import com.mecon.renderer.layout.RenderLayoutConfig
import com.mecon.renderer.render.RenderElementType
import com.mecon.renderer.render.RenderEngine
import com.mecon.renderer.render.RenderPage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CrossSystemSlurStreamingTest {

    @Test
    fun crossSystemSlurOwnsOneStubPerEndpointSystem() {
        val font = loadFont() ?: return
        val runtime = crossSystemSlurScore()
        val computed = computeScore(runtime)
        val slur = computed.slurs.single()
        val streamedPages = mutableListOf<RenderPage>()

        val result = with(font) {
            RenderEngine(RenderLayoutConfig.DEFAULT).renderStreaming(
                computed = computed,
                pageGeometry = separatePageGeometry,
                onPage = { _, page -> streamedPages += page },
            )
        }
        val stubs = result.elements.filter { element ->
            element.type == RenderElementType.SLUR && element.eventId == slur.startEventId
        }
        val streamedStubs = streamedPages
            .flatMap { it.elements }
            .filter { element ->
                element.type == RenderElementType.SLUR && element.eventId == slur.startEventId
            }

        assertTrue(streamedPages.size >= 2, "fixture must place the endpoint systems on separate pages")
        assertEquals(2, stubs.size, "a cross-system slur must render exactly two stubs")
        assertEquals(2, streamedStubs.size, "streaming must emit each cross-system stub exactly once")
        assertEquals(setOf(1, 2), stubs.mapNotNullTo(mutableSetOf()) { it.measureNumber })
        assertEquals(2, stubs.mapNotNull { it.systemIndex }.toSet().size, "each stub must own its endpoint system")
    }

    private fun crossSystemSlurScore(): RuntimeScore {
        val storage = StorageScore.create(StorageScore.CreationOptions(title = "", measureCount = 2)).let { score ->
            score.copy(
                globalTrack = score.globalTrack.copy(
                    events = score.globalTrack.events + StorageSystemBreak(TimeCode.of(2, Fraction.ZERO))
                )
            )
        }
        var runtime = RuntimeScore.fromStorage(storage)
        val voiceTrackId = runtime.voiceTracks.keys.single()
        val pitchTrackId = runtime.pitchTracks.keys.single()

        fun addEvent(
            tag: String,
            onset: TimeCode,
            slurStarts: Int = 0,
            slurEnds: Int = 0,
        ) {
            val pitchEvent = RuntimePitchEvent(EventId("pitch-$tag"), onset, listOf(Pitch.C4))
            val voiceEvent = RuntimeVoiceEvent(
                id = EventId(tag),
                onset = onset,
                pitchEvent = pitchEvent,
                duration = Duration.QUARTER,
                slurStarts = slurStarts,
                slurEnds = slurEnds,
            )
            runtime = runtime.addPitchEvent(pitchTrackId, pitchEvent).addVoiceEvent(voiceTrackId, voiceEvent)
        }

        addEvent("slur-start", TimeCode.of(1, Fraction(3, 4)), slurStarts = 1)
        addEvent("slur-end", TimeCode.of(2, Fraction.ZERO), slurEnds = 1)
        return runtime
    }

    private companion object {
        val separatePageGeometry = PageGeometry(
            paginated = true,
            lineWidth = StaffSpace(70f),
            pageContentHeight = StaffSpace(1f),
            paperWidth = StaffSpace(80f),
            paperHeight = StaffSpace(20f),
            leftMargin = StaffSpace(2f),
            topMargin = StaffSpace(2f),
        )
    }
}
