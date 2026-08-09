package com.mecon.renderer.snapshot

import com.mecon.api.runtime.RuntimeScore
import com.mecon.core.serializer.ScoreSerializer
import com.mecon.renderer.layout.RenderLayoutConfig
import com.mecon.renderer.layout.PageGeometry
import com.mecon.renderer.render.DrawText
import com.mecon.renderer.render.RenderEngine
import com.mecon.renderer.render.RenderResult
import com.mecon.renderer.smufl.BravuraFont
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Verifies the score title block (title / subtitle / composer) is rendered in
 * paginated mode and omitted in continuous mode.
 */
class TitleBlockTest {

    private fun render(file: File, font: BravuraFont, paginated: Boolean): RenderResult {
        val storage = ScoreSerializer.fromYaml(file.readText())
        val runtime = RuntimeScore.fromStorage(storage)
        val geometry =
            if (paginated) PageGeometry.from(runtime.pageLayout.copy(paginated = true))
            else PageGeometry.continuous()
        return with(font) {
            RenderEngine(RenderLayoutConfig.DEFAULT).render(runtime, pageGeometry = geometry)
        }
    }

    private fun drawnTexts(result: RenderResult): List<String> =
        result.elements.flatMap { it.commands }.filterIsInstance<DrawText>().map { it.text }

    @Test
    fun titleSubtitleComposerRenderedInPaginatedMode() {
        val font = loadFont() ?: return // skip if font assets unavailable
        // This paginated score declares title + subtitle + composer.
        val file = File(paginatedScoreDir(), "32_key_signatures_paginated.mscore.yaml")
        if (!file.exists()) return

        val storage = ScoreSerializer.fromYaml(file.readText())
        val meta = storage.metadata

        val texts = drawnTexts(render(file, font, paginated = true))
        assertTrue(texts.contains(meta.title), "title '${meta.title}' not rendered; drawn texts=$texts")
        meta.subtitle?.let { assertTrue(texts.contains(it), "subtitle '$it' not rendered") }
        meta.composer?.let { assertTrue(texts.contains(it), "composer '$it' not rendered") }
    }

    @Test
    fun titleBlockOmittedInContinuousMode() {
        val font = loadFont() ?: return
        val file = File(paginatedScoreDir(), "32_key_signatures_paginated.mscore.yaml")
        if (!file.exists()) return

        val storage = ScoreSerializer.fromYaml(file.readText())
        val title = storage.metadata.title

        val texts = drawnTexts(render(file, font, paginated = false))
        assertTrue(!texts.contains(title), "title should not render in continuous mode; drawn texts=$texts")
    }
}
