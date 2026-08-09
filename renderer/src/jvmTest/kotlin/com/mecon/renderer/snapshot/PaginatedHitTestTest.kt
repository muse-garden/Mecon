package com.mecon.renderer.snapshot

import com.mecon.api.interaction.BarlineSection
import com.mecon.api.interaction.BarlineVisualPlacement
import com.mecon.api.interaction.ClefSection
import com.mecon.api.interaction.KeySignatureSection
import com.mecon.api.interaction.LayoutBreakSection
import com.mecon.api.primitive.TimeCode
import com.mecon.api.runtime.RuntimeScore
import com.mecon.core.serializer.ScoreSerializer
import com.mecon.renderer.layout.RenderLayoutConfig
import com.mecon.renderer.render.RenderEngine
import com.mecon.renderer.render.RenderElementType
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.assertEquals
import java.io.File

/**
 * Regression test for hit-testing in paginated (line-broken) mode.
 *
 * The spatial index used to be built as a single continuous system, so any element
 * drawn on a system after the first (lower Y band, reset X) was unreachable by
 * [com.mecon.renderer.render.RenderResult.hitTest]. This verifies that notes across
 * the whole page — including the lower systems — resolve to a hit at their own centre.
 */
class PaginatedHitTestTest {

    @Test
    fun lineStartAndEndBarlinesOwnSelectableSections() {
        val font = loadFont() ?: return
        val file = File(paginatedScoreDir(), "31_pagination_forced_breaks.mscore.yaml")
        val result = renderScoreFile(file, font)
        assertTrue(result.lastSystem > 0, "fixture must contain more than one system")

        val structuralBarlines = result.elements.filter {
            it.type == RenderElementType.BARLINE &&
                it.metadata[com.mecon.renderer.render.ALWAYS_REGENERATED_STRUCTURE] == "true"
        }
        val sections = structuralBarlines.associateWith { result.sectionIndex.sectionsFor(it.id) }

        assertTrue(
            sections.any { (_, owned) ->
                owned.filterIsInstance<BarlineSection>().any {
                    it.visualPlacement == BarlineVisualPlacement.SYSTEM_START
                }
            },
            "later-system start barline must be selectable",
        )
        assertTrue(
            sections.any { (_, owned) ->
                owned.filterIsInstance<BarlineSection>().any {
                    it.visualPlacement == BarlineVisualPlacement.SYSTEM_END
                }
            },
            "non-final system end barline must be selectable",
        )
    }

    @Test
    fun forcedBreaksProduceLastPassSelectableEditorMarkers() {
        val font = loadFont() ?: return
        val file = File(paginatedScoreDir(), "31_pagination_forced_breaks.mscore.yaml")
        val result = renderScoreFile(file, font)
        val markers = result.elements.filter { it.type == RenderElementType.EDITOR_MARKER }
        assertEquals(2, markers.size)
        val postLayout = result.elements.filter {
            it.type == RenderElementType.EDITOR_MARKER || it.type == RenderElementType.MEASURE
        }
        assertEquals(postLayout, result.elements.takeLast(postLayout.size), "post-layout elements must overlay completed engraving")
        assertTrue(
            postLayout.take(markers.size).all { it.type == RenderElementType.EDITOR_MARKER },
            "editor markers must be emitted before the final measure-number overlay",
        )
        assertEquals(
            2,
            markers.sumOf { result.sectionIndex.sectionsFor(it.id).filterIsInstance<LayoutBreakSection>().size },
        )
    }

    @Test
    fun measureNumbersAreOptionalFinalPassElements() {
        val font = loadFont() ?: return
        val file = File(paginatedScoreDir(), "31_pagination_forced_breaks.mscore.yaml")
        val runtime = RuntimeScore.fromStorage(ScoreSerializer.fromYaml(file.readText()))

        val shown = with(font) { RenderEngine(RenderLayoutConfig.DEFAULT).render(runtime) }
        val numbers = shown.elements.filter { it.type == RenderElementType.MEASURE }
        assertEquals(shown.lastSystem + 1, numbers.size)
        assertEquals(
            (0..shown.lastSystem).toList(),
            numbers.map { it.systemIndex },
            "one measure number should be emitted at each system start",
        )
        assertEquals(
            numbers.map { it.measureNumber },
            numbers.map { it.commands.single().let { command -> (command as com.mecon.renderer.render.DrawText).text.toInt() } },
        )

        val hiddenRuntime = runtime.copy(
            viewPreferences = runtime.viewPreferences.copy(showMeasureNumbers = false),
        )
        val hidden = with(font) { RenderEngine(RenderLayoutConfig.DEFAULT).render(hiddenRuntime) }
        assertTrue(hidden.elements.none { it.type == RenderElementType.MEASURE })
    }

    @Test
    fun measureNumbersClearLineStartSquareBrackets() {
        val font = loadFont() ?: return
        val file = File(paginatedScoreDir(), "33_pagination_complex.mscore.yaml")
        val result = renderScoreFile(file, font)
        val numbersBySystem = result.elements
            .filter { it.type == RenderElementType.MEASURE }
            .associateBy { it.systemIndex }
        val squareBrackets = result.elements.filter { it.type == RenderElementType.SYSTEM_BRACKET }

        assertTrue(squareBrackets.isNotEmpty(), "fixture must render line-start square brackets")
        for (bracket in squareBrackets) {
            val number = numbersBySystem[bracket.systemIndex]
                ?: error("missing measure number for system ${bracket.systemIndex}")
            assertTrue(
                number.hitBox.origin.x.value + number.hitBox.width.value <= bracket.hitBox.origin.x.value,
                "system ${bracket.systemIndex}: measure number ${number.hitBox} overlaps bracket ${bracket.hitBox}",
            )
        }
    }

    @Test
    fun notesAreHittableAcrossAllSystems() {
        val font = loadFont() ?: return // skip if the font isn't available in this checkout
        val files = paginatedScoreFiles()
        assertTrue(files.isNotEmpty(), "expected paginated test scores under test-scores/paginated")

        for (file in files) {
            val result = renderScoreFile(file, font)
            assertTrue(result.lastSystem > 0, "${file.name} should break into multiple systems")

            val notes = result.elements.filter { it.type == RenderElementType.NOTEHEAD }
            assertTrue(notes.isNotEmpty(), "${file.name} has no NOTEHEAD elements")

            // Bucket notes by Y so we can confirm the lower systems are exercised, not
            // just the first. Each system is a distinct Y band (rows stack downward).
            val distinctYBands = notes.map { (it.center.y.value / 20f).toInt() }.distinct().size
            assertTrue(distinctYBands > 1, "${file.name}: notes occupy a single Y band — not paginated?")

            val misses = notes.filter { result.hitTest(it.center).isEmpty }
            assertTrue(
                misses.isEmpty(),
                "${file.name}: ${misses.size}/${notes.size} note centres hit nothing " +
                    "(e.g. ${misses.take(3).map { it.center }})"
            )
        }
    }

    @Test
    fun lineStartHeadersAreHittableAfterFirstSystem() {
        val font = loadFont() ?: return // skip if the font isn't available in this checkout
        val files = paginatedScoreFiles()
        assertTrue(files.isNotEmpty(), "expected paginated test scores under test-scores/paginated")

        var checkedClefs = 0
        var checkedKeys = 0
        for (file in files) {
            val result = renderScoreFile(file, font)
            assertTrue(result.lastSystem > 0, "${file.name} should break into multiple systems")

            val headerTypes = setOf(RenderElementType.CLEF, RenderElementType.KEY_SIGNATURE)
            val lowerSystemHeaders = result.elements
                .filter { it.type in headerTypes }
                .filter { element ->
                    val rel = result.transformerSnapshot.toRelative(element.center)
                    result.spatialIndex.allSystems().any { system ->
                        system.systemIndex > 0 && rel.y >= system.topY && rel.y <= system.bottomY
                    }
                }

            for (element in lowerSystemHeaders) {
                val hits = result.hitTest(element.center)
                val matchingHits = hits.ofType(element.type)
                assertTrue(
                    matchingHits.isNotEmpty(),
                    "${file.name}: ${element.type} on a later system is not hittable at ${element.center}"
                )
                val hasSelectableSection = matchingHits.any { hit ->
                    hit.sections.any { section ->
                        when (element.type) {
                            RenderElementType.CLEF -> section is ClefSection
                            RenderElementType.KEY_SIGNATURE -> section is KeySignatureSection
                            else -> false
                        }
                    }
                }
                assertTrue(
                    hasSelectableSection,
                    "${file.name}: ${element.type} on a later system has no selectable section"
                )
                val usesLineLocalSection = matchingHits.any { hit ->
                    hit.sections.any { section ->
                        when (section) {
                            is ClefSection -> section.clef.time != TimeCode.ZERO
                            is KeySignatureSection -> section.keySignature.time != TimeCode.ZERO
                            else -> false
                        }
                    }
                }
                assertTrue(
                    usesLineLocalSection,
                    "${file.name}: ${element.type} on a later system reuses the score-opening section"
                )
                when (element.type) {
                    RenderElementType.CLEF -> checkedClefs++
                    RenderElementType.KEY_SIGNATURE -> checkedKeys++
                    else -> Unit
                }
            }
        }

        assertTrue(checkedClefs > 0, "expected at least one line-start clef after the first system")
        assertTrue(checkedKeys > 0, "expected at least one line-start key signature after the first system")
    }
}
