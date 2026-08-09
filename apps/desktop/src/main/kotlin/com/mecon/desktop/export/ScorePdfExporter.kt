package com.mecon.desktop.export

import com.mecon.api.storage.StorageScore
import com.mecon.desktop.service.FrozenScoreRenderer
import com.mecon.renderer.frozen.FrozenScoreBundle
import com.mecon.renderer.geometry.ScaleFactor
import com.mecon.renderer.render.RenderElementType
import com.mecon.renderer.smufl.BravuraFont
import com.mecon.renderer.smufl.BravuraFontLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.util.Matrix
import java.io.File

/**
 * Exports a score to a vector PDF by replaying its **frozen geometry** — one PDF page per
 * [com.mecon.renderer.frozen.FrozenSurface]. The score is engraved through the shared
 * [FrozenScoreRenderer] (the same engine path that packs `.mecon` geometry), so what the PDF shows
 * is exactly what the app renders. Each surface's [com.mecon.renderer.render.RenderCommand]s are
 * translated to PDF path/fill operators by [PdfContentWriter]; music glyphs and text become vector
 * outlines via [AwtGlyphOutliner].
 *
 * Coordinate mapping: the renderer works in design pixels (8 px per staff space, y-down). One staff
 * space is [com.mecon.api.storage.PageLayoutConfig.staffSpaceMm] millimetres of paper, giving a fixed
 * points-per-pixel factor that makes an A4 paginated surface come out at true A4 size. Each page's
 * CTM applies that scale and flips Y into PDF's y-up space.
 */
class ScorePdfExporter(
    private val loadFont: suspend () -> BravuraFont? = { BravuraFontLoader().load() },
) {
    /** Engrave [score] and write it to [file] as a PDF. Returns the number of pages written. */
    suspend fun export(score: StorageScore, file: File): Int {
        val font = loadFont() ?: error("Bravura font unavailable; cannot engrave score for PDF export")
        val otf = loadBravuraOtf() ?: error("Bravura.otf resource not found for PDF glyph outlines")
        val bundle = withContext(Dispatchers.Default) { FrozenScoreRenderer.render(score, font) }
        return withContext(Dispatchers.IO) {
            writePdf(bundle, score.pageLayout.staffSpaceMm, otf, file)
        }
    }

    companion object {
        /** Pixels per staff space the render engine lays out in (see [ScaleFactor.DEFAULT]). */
        private val PX_PER_STAFF_SPACE = ScaleFactor.DEFAULT.pixelsPerStaffSpace

        private const val MM_PER_INCH = 25.4f
        private const val POINTS_PER_INCH = 72f

        /** PDF hard limit on any page dimension (Acrobat: 14400 pt = 200 in). Downscale to fit. */
        private const val MAX_PAGE_POINTS = 14400f

        private fun loadBravuraOtf(): ByteArray? =
            ScorePdfExporter::class.java.classLoader?.getResourceAsStream("fonts/Bravura.otf")?.readBytes()

        /**
         * Write [bundle] to [file] as a PDF, one page per surface. [staffSpaceMm] is the score's
         * rastral size (paper mm per staff space). Pure and testable — no font loading or engine work.
         * Returns the number of pages written.
         */
        fun writePdf(bundle: FrozenScoreBundle, staffSpaceMm: Float, bravuraOtf: ByteArray, file: File): Int {
            val outliner = AwtGlyphOutliner(bravuraOtf)
            val ptPerPixelBase = (staffSpaceMm / PX_PER_STAFF_SPACE) * (POINTS_PER_INCH / MM_PER_INCH)

            // Clamp so no page exceeds the PDF dimension limit (e.g. a tall continuous, unpaginated score).
            val maxDimPx = bundle.surfaces.maxOfOrNull { maxOf(it.width, it.height) } ?: 0f
            val ptPerPixel = if (maxDimPx * ptPerPixelBase > MAX_PAGE_POINTS && maxDimPx > 0f) {
                MAX_PAGE_POINTS / maxDimPx
            } else {
                ptPerPixelBase
            }

            PDDocument().use { doc ->
                for (surface in bundle.surfaces) {
                    val pageWidthPt = surface.width * ptPerPixel
                    val pageHeightPt = surface.height * ptPerPixel
                    val page = PDPage(PDRectangle(pageWidthPt, pageHeightPt))
                    doc.addPage(page)
                    PDPageContentStream(doc, page).use { cs ->
                        // Map design-pixel space (y-down, top-left origin) into PDF points (y-up).
                        cs.transform(Matrix(ptPerPixel, 0f, 0f, -ptPerPixel, 0f, pageHeightPt))
                        val writer = PdfContentWriter(cs, outliner)
                        for (element in surface.elements) {
                            // Editor markers (carets / selection overlays) are transient edit affordances,
                            // never part of the printed engraving.
                            if (element.type == RenderElementType.EDITOR_MARKER) continue
                            writer.write(element.commands)
                        }
                    }
                }
                if (doc.numberOfPages == 0) {
                    // A valid PDF needs at least one page; emit a blank one rather than a corrupt file.
                    doc.addPage(PDPage(PDRectangle.A4))
                }
                doc.save(file)
                return doc.numberOfPages
            }
        }
    }
}
