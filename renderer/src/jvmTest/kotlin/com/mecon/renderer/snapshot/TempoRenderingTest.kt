package com.mecon.renderer.snapshot

import com.mecon.api.primitive.DurationBase
import com.mecon.api.primitive.Fraction
import com.mecon.api.primitive.TimeCode
import com.mecon.api.runtime.RuntimeScore
import com.mecon.api.storage.StorageScore
import com.mecon.api.storage.events.StorageTempoEvent
import com.mecon.api.storage.events.TempoDisplayStyle
import com.mecon.api.storage.events.TempoMarkType
import com.mecon.api.storage.events.TempoTransition
import com.mecon.api.storage.tracks.StorageSystemBreak
import com.mecon.renderer.geometry.StaffSpace
import com.mecon.renderer.layout.PageGeometry
import com.mecon.renderer.layout.RenderLayoutConfig
import com.mecon.renderer.render.DrawGlyph
import com.mecon.renderer.render.DrawLine
import com.mecon.renderer.render.DrawText
import com.mecon.renderer.render.RenderElementType
import com.mecon.renderer.render.RenderEngine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TempoRenderingTest {
    @Test
    fun rendersPointTextMetricGradualAndEditorOnlyKeyframes() {
        val font = loadFont() ?: return
        val opening = StorageTempoEvent.create(
            TimeCode.of(1, Fraction.ZERO), 120f,
            markType = TempoMarkType.KEYFRAME,
            displayStyle = TempoDisplayStyle.HIDDEN,
        )
        val text = StorageTempoEvent.create(
            TimeCode.of(1, Fraction.ZERO), 138f,
            text = "più mosso",
            markType = TempoMarkType.PIU_MOSSO,
            displayStyle = TempoDisplayStyle.TEXT,
            referenceEventId = opening.id,
            referenceRatio = 1.15f,
        )
        val metric = StorageTempoEvent.create(
            TimeCode.of(2, Fraction.ZERO), 276f,
            markType = TempoMarkType.METRIC_MODULATION,
            displayStyle = TempoDisplayStyle.METRIC_MODULATION,
            beatUnit = DurationBase.QUARTER,
            equivalentBeatUnit = DurationBase.HALF,
        )
        val gradual = StorageTempoEvent.create(
            TimeCode.of(3, Fraction.ZERO), 120f,
            text = "rit.",
            markType = TempoMarkType.RITARDANDO,
            displayStyle = TempoDisplayStyle.GRADUAL_TEXT,
            transitionToNext = TempoTransition.LINEAR,
        )
        val endpoint = StorageTempoEvent.create(
            TimeCode.of(4, Fraction.ZERO), 96f,
            markType = TempoMarkType.KEYFRAME,
            displayStyle = TempoDisplayStyle.HIDDEN,
        )
        val storage = StorageScore.create().let { score ->
            score.copy(globalTrack = score.globalTrack.copy(
                tempoEvents = listOf(opening, text, metric, gradual, endpoint),
            ))
        }
        val result = with(font) {
            RenderEngine(RenderLayoutConfig.DEFAULT).render(RuntimeScore.fromStorage(storage))
        }
        val tempoElements = result.elements.filter { it.type == RenderElementType.TEMPO_MARKING }
        val editorDots = result.elements.filter {
            it.type == RenderElementType.EDITOR_MARKER && it.metadata["editorMarker"] == "tempoKeyframe"
        }

        assertEquals(3, tempoElements.size)
        assertEquals(2, editorDots.size)
        assertTrue(tempoElements.flatMap { it.commands }.filterIsInstance<DrawText>()
            .any { it.text == "più mosso" })
        assertTrue(tempoElements.any { element -> element.commands.count { it is DrawGlyph } >= 2 })
        assertTrue(tempoElements.any { element ->
            element.commands.any { it is DrawText && it.text == "rit." } &&
                element.commands.any { it is DrawLine }
        })
    }

    @Test
    fun pointTempoIsNotRepeatedAtLaterSystemStarts() {
        val font = loadFont() ?: return
        val opening = StorageTempoEvent.create(
            TimeCode.of(1, Fraction.ZERO), 120f,
            markType = TempoMarkType.METRONOME,
            displayStyle = TempoDisplayStyle.METRONOME,
        )
        val laterKeyframe = StorageTempoEvent.create(
            TimeCode.of(3, Fraction.ZERO), 108f,
            markType = TempoMarkType.KEYFRAME,
            displayStyle = TempoDisplayStyle.HIDDEN,
        )
        val storage = StorageScore.create(StorageScore.CreationOptions(measureCount = 3)).let { score ->
            score.copy(globalTrack = score.globalTrack.copy(
                events = score.globalTrack.events +
                    StorageSystemBreak(TimeCode.of(2, Fraction.ZERO)) +
                    StorageSystemBreak(TimeCode.of(3, Fraction.ZERO)),
                tempoEvents = listOf(opening, laterKeyframe),
            ))
        }
        val page = PageGeometry(
            paginated = true,
            lineWidth = StaffSpace(60f),
            pageContentHeight = StaffSpace(200f),
            paperWidth = StaffSpace(70f),
            paperHeight = StaffSpace(220f),
            leftMargin = StaffSpace(2f),
            topMargin = StaffSpace(2f),
        )
        val result = with(font) {
            RenderEngine(RenderLayoutConfig.DEFAULT).render(RuntimeScore.fromStorage(storage), pageGeometry = page)
        }

        val tempos = result.elements.filter { it.type == RenderElementType.TEMPO_MARKING }
        assertTrue(result.lastSystem >= 2, "fixture must produce at least three systems")
        assertEquals(1, tempos.size, "a point tempo belongs only to its own TimeCode")
    }

    @Test
    fun everyPaintedTempoPartUsesTheVisibleCommandBoundsForPicking() {
        val font = loadFont() ?: return
        val tempo = StorageTempoEvent.create(
            TimeCode.of(1, Fraction.ZERO), 132f,
            text = "Allegro",
            markType = TempoMarkType.METRONOME,
            displayStyle = TempoDisplayStyle.TEXT_AND_METRONOME,
        )
        val storage = StorageScore.create().let { score ->
            score.copy(globalTrack = score.globalTrack.copy(tempoEvents = listOf(tempo)))
        }
        val result = with(font) {
            RenderEngine(RenderLayoutConfig.DEFAULT).render(RuntimeScore.fromStorage(storage))
        }
        val element = result.elements.single { it.type == RenderElementType.TEMPO_MARKING }

        element.commands.forEach { command ->
            assertTrue(
                result.hitTest(command.bounds.center).ofType(RenderElementType.TEMPO_MARKING).isNotEmpty(),
                "painted tempo part should be clickable at ${command.bounds.center}; command=$command",
            )
        }
    }
}
