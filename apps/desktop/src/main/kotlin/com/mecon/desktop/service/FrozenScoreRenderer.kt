package com.mecon.desktop.service

import com.mecon.api.runtime.RuntimeScore
import com.mecon.api.storage.StorageScore
import com.mecon.core.container.MeconFormat
import com.mecon.renderer.frozen.FrozenScoreBundle
import com.mecon.renderer.frozen.FrozenScoreProjector
import com.mecon.renderer.layout.RenderLayoutConfig
import com.mecon.renderer.render.RenderEngine
import com.mecon.renderer.smufl.BravuraFont

/**
 * Renders a [StorageScore] into a portable [FrozenScoreBundle] by actually running the layout
 * engine. This is the single seam that turns a score into engine-independent geometry; both the
 * `.mecon` container packer ([MeconDocumentService]) and the PDF exporter
 * ([com.mecon.desktop.export.ScorePdfExporter]) go through it so they never disagree on how a
 * score is engraved.
 */
object FrozenScoreRenderer {

    /** Bravura identity string embedded in the bundle for diagnostics / cache invalidation. */
    fun fingerprint(font: BravuraFont): String =
        "${font.metadata.fontName}-${font.metadata.fontVersion}"

    /**
     * Compute + lay out + render [score] against [font], projecting the live [RenderResult] into a
     * frozen bundle. Runs on the caller's thread (callers dispatch to [kotlinx.coroutines.Dispatchers.Default]).
     */
    fun render(score: StorageScore, font: BravuraFont, fingerprint: String = fingerprint(font)): FrozenScoreBundle {
        val runtime = RuntimeScore.fromStorage(score)
        val result = with(font) { RenderEngine(RenderLayoutConfig.DEFAULT).render(runtime) }
        return FrozenScoreProjector.project(
            result = result,
            engineVersion = MeconFormat.ENGINE_VERSION,
            fontFingerprint = fingerprint,
        )
    }
}
