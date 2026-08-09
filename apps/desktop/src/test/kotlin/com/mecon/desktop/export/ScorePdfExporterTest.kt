package com.mecon.desktop.export

import com.mecon.api.render.RenderColor
import com.mecon.renderer.frozen.FrozenScoreBundle
import com.mecon.renderer.frozen.FrozenSurface
import com.mecon.renderer.geometry.AbsoluteCubicBezier
import com.mecon.renderer.geometry.AbsolutePoint
import com.mecon.renderer.geometry.AbsoluteRect
import com.mecon.renderer.geometry.Pixels
import com.mecon.renderer.render.DrawBezier
import com.mecon.renderer.render.DrawGlyph
import com.mecon.renderer.render.DrawLine
import com.mecon.renderer.render.DrawRect
import com.mecon.renderer.render.DrawText
import com.mecon.renderer.render.RenderCommand
import com.mecon.renderer.render.RenderElement
import com.mecon.renderer.render.RenderElementId
import com.mecon.renderer.render.RenderElementType
import com.mecon.renderer.render.TextAlignment
import com.mecon.renderer.smufl.SmuflGlyphs
import org.apache.pdfbox.pdmodel.PDDocument
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Exercises the PDF vector backend end to end at the geometry level: a hand-built
 * [FrozenScoreBundle] → PDF file → reloaded and inspected. Deliberately bypasses the layout engine
 * so the test stays fast and focuses on command translation, AWT outlining and page geometry.
 */
class ScorePdfExporterTest {

    private fun rect(x: Float, y: Float, w: Float, h: Float) =
        AbsoluteRect(AbsolutePoint(Pixels(x), Pixels(y)), Pixels(w), Pixels(h))

    private fun point(x: Float, y: Float) = AbsolutePoint(Pixels(x), Pixels(y))

    private fun element(id: Long, type: RenderElementType, commands: List<RenderCommand>, box: AbsoluteRect) =
        RenderElement(id = RenderElementId(id), type = type, commands = commands, hitBox = box)

    private fun sampleSurface(index: Int, width: Float, height: Float): FrozenSurface {
        val line = DrawLine(
            start = point(20f, 40f), end = point(width - 20f, 40f),
            thickness = Pixels(1.5f), bounds = rect(20f, 39f, width - 40f, 2f),
        )
        val box = DrawRect(rect = rect(30f, 60f, 80f, 40f), bounds = rect(30f, 60f, 80f, 40f))
        val note = DrawGlyph(
            position = point(150f, 90f), glyph = SmuflGlyphs.noteheadBlack, fontSize = Pixels(32f),
            bounds = rect(150f, 70f, 24f, 24f),
        )
        // Mixed Latin + CJK text — proves the AWT-outline path handles non-Latin glyphs.
        val title = DrawText(
            position = point(width / 2f, 20f), text = "Allegro 快板", fontSize = Pixels(18f),
            alignment = TextAlignment.CENTER, bounds = rect(0f, 5f, width, 20f),
        )
        val slur = DrawBezier(
            curve = AbsoluteCubicBezier(point(200f, 90f), point(230f, 70f), point(270f, 70f), point(300f, 90f)),
            endpointThickness = Pixels(1f), midpointThickness = Pixels(2.5f), filled = true,
            bounds = rect(200f, 70f, 100f, 20f),
        )
        // An editor marker must NOT reach the PDF; include one and rely on the exporter to drop it.
        val marker = element(
            index * 100L + 9, RenderElementType.EDITOR_MARKER,
            listOf(DrawRect(rect = rect(0f, 0f, 5f, 5f), bounds = rect(0f, 0f, 5f, 5f))),
            rect(0f, 0f, 5f, 5f),
        )
        return FrozenSurface(
            index = index, width = width, height = height,
            elements = listOf(
                element(index * 100L + 1, RenderElementType.STAFF_LINE, listOf(line), line.bounds),
                element(index * 100L + 2, RenderElementType.MEASURE, listOf(box), box.bounds),
                element(index * 100L + 3, RenderElementType.NOTEHEAD, listOf(note), note.bounds),
                element(index * 100L + 4, RenderElementType.TEMPO_MARKING, listOf(title), title.bounds),
                element(index * 100L + 5, RenderElementType.SLUR, listOf(slur), slur.bounds),
                marker,
            ),
        )
    }

    private fun bravuraOtf(): ByteArray =
        checkNotNull(javaClass.classLoader?.getResourceAsStream("fonts/Bravura.otf")?.readBytes()) {
            "Bravura.otf not on test classpath"
        }

    @Test
    fun `writes one page per surface at the expected physical size`() {
        val width = 800f
        val height = 600f
        val bundle = FrozenScoreBundle(
            bounds = rect(0f, 0f, width, height),
            paginated = true,
            surfaces = listOf(sampleSurface(0, width, height), sampleSurface(1, width, height)),
        )
        val out = File.createTempFile("mecon-export-test", ".pdf")
        try {
            val staffSpaceMm = 1.8f
            val pages = ScorePdfExporter.writePdf(bundle, staffSpaceMm, bravuraOtf(), out)
            assertEquals(2, pages, "one PDF page per frozen surface")
            assertTrue(out.length() > 0, "PDF file should be non-empty")

            PDDocument.load(out).use { doc ->
                assertEquals(2, doc.numberOfPages)
                val expectedWidthPt = width * (staffSpaceMm / 8f) * (72f / 25.4f)
                val box = doc.getPage(0).mediaBox
                assertEquals(expectedWidthPt, box.width, 0.5f, "page width should map design px → paper points")
            }
        } finally {
            out.delete()
        }
    }

    @Test
    fun `empty bundle still yields a valid single-page pdf`() {
        val bundle = FrozenScoreBundle(bounds = rect(0f, 0f, 1f, 1f), surfaces = emptyList())
        val out = File.createTempFile("mecon-export-empty", ".pdf")
        try {
            val pages = ScorePdfExporter.writePdf(bundle, 1.8f, bravuraOtf(), out)
            assertEquals(1, pages)
            PDDocument.load(out).use { doc -> assertEquals(1, doc.numberOfPages) }
        } finally {
            out.delete()
        }
    }
}
