package com.mecon.renderer.snapshot

import com.mecon.api.primitive.*
import com.mecon.api.runtime.RuntimeScore
import com.mecon.api.runtime.events.RuntimePitchEvent
import com.mecon.api.runtime.events.RuntimeVoiceEvent
import com.mecon.api.storage.StorageScore
import com.mecon.core.engine.computeScore
import com.mecon.renderer.geometry.StaffSpace
import com.mecon.renderer.layout.PageGeometry
import com.mecon.renderer.layout.RenderLayoutConfig
import com.mecon.renderer.render.RenderEngine
import com.mecon.renderer.render.RenderResult
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Tests for [RenderEngine.renderSystems] (per-system filtering) and [RenderEngine.renderRange]
 * (range-bounded incremental re-render).
 *
 * Guards three properties:
 *  1. A system-filtered result contains only elements from the requested system.
 *  2. The union of per-system results is command-multiset equivalent to a full render.
 *  3. [renderRange] after a priming full render produces a command-multiset equivalent output
 *     (splice path engaged, no spurious changes introduced).
 */
class RenderSystemFilterTest {

    private val multiSystem = PageGeometry(
        paginated = true,
        lineWidth = StaffSpace(60f),
        pageContentHeight = StaffSpace(400f),
        paperWidth = StaffSpace(70f),
        paperHeight = StaffSpace(420f),
        leftMargin = StaffSpace(2f),
        topMargin = StaffSpace(2f),
    )

    private fun emptyScore() =
        RuntimeScore.fromStorage(StorageScore.create(StorageScore.CreationOptions("", TimeSignature.COMMON, KeySignature.C_MAJOR)))

    private fun RuntimeScore.ptId() = pitchTracks.keys.first()
    private fun RuntimeScore.vtId() = voiceTracks.keys.first()

    private fun RuntimeScore.addNote(tag: String, measure: Int): RuntimeScore {
        val tc = TimeCode.of(measure, Fraction(0, 4))
        val pe = RuntimePitchEvent(EventId("p-$tag"), tc, listOf(Pitch.C4))
        val ve = RuntimeVoiceEvent(EventId(tag), tc, pe, Duration.HALF)
        return addPitchEvent(ptId(), pe).addVoiceEvent(vtId(), ve)
    }

    private fun buildBase(measures: Int): RuntimeScore {
        var base = emptyScore()
        for (m in 1..measures) base = base.addNote("n_$m", m)
        return base
    }

    /**
     * [renderSystems] with a single system index must return fewer elements than a full render, and
     * every element that carries a non-null [systemIndex][com.mecon.renderer.render.RenderElement.systemIndex]
     * must belong to the requested system.
     */
    @Test
    fun systemFilteredResultCoversOnlyRequestedSystem() {
        val font = loadFont() ?: return
        with(font) {
            val runtime = buildBase(40)
            val computed = computeScore(runtime)

            val engine = RenderEngine(RenderLayoutConfig.DEFAULT)
            val fullResult = engine.render(runtime, pageGeometry = multiSystem)
            val systemCount = fullResult.lastSystem + 1
            assertTrue(systemCount >= 2, "Score with 40 measures should span ≥2 systems; got $systemCount")

            // --- system 0 ---
            val sys0 = engine.renderSystems(computed, setOf(0), pageGeometry = multiSystem)
            assertTrue(
                sys0.elements.size < fullResult.elements.size,
                "System-0 render (${sys0.elements.size}) should have fewer elements than full (${fullResult.elements.size})"
            )
            for (el in sys0.elements) {
                val si = el.systemIndex
                assertTrue(si == null || si == 0, "systemIndex=$si found in system-0-only render")
            }

            // --- last system ---
            val lastSys = systemCount - 1
            val sysLast = engine.renderSystems(computed, setOf(lastSys), pageGeometry = multiSystem)
            for (el in sysLast.elements) {
                val si = el.systemIndex
                assertTrue(si == null || si == lastSys, "systemIndex=$si found in system-$lastSys-only render")
            }
        }
    }

    /**
     * Collecting one [renderSystems] call per system and unioning their draw commands must produce
     * a multiset identical (within sub-pixel tolerance) to a full render's commands.
     */
    @Test
    fun perSystemUnionMatchesFullCommandMultiset() {
        val font = loadFont() ?: return
        with(font) {
            val runtime = buildBase(40)
            val computed = computeScore(runtime)

            val fullEngine = RenderEngine(RenderLayoutConfig.DEFAULT)
            val fullResult = fullEngine.render(runtime, pageGeometry = multiSystem)
            val systemCount = fullResult.lastSystem + 1
            assertTrue(systemCount >= 2, "Score with 40 measures should span ≥2 systems; got $systemCount")

            // Use a fresh engine per call so cache state doesn't bleed between systems.
            val partials: List<RenderResult> = (0 until systemCount).map { sysIdx ->
                RenderEngine(RenderLayoutConfig.DEFAULT)
                    .renderSystems(computed, setOf(sysIdx), pageGeometry = multiSystem)
            }

            assertCommandUnionEquivalent(fullResult, partials)
        }
    }

    /**
     * [renderRange] after a priming full render must engage the splice path and return a result whose
     * draw commands are multiset-equivalent to the full render (no actual note edits → no output change).
     */
    @Test
    fun renderRangeSplicedResultMatchesFullRender() {
        val font = loadFont() ?: return
        with(font) {
            val runtime = buildBase(20)
            val computed = computeScore(runtime)

            val engine = RenderEngine(RenderLayoutConfig.DEFAULT)
            val fullResult = engine.render(runtime)

            val rangeResult = engine.renderRange(computed, measureRange = 2..5)

            assertTrue(engine.lastRenderWasSpliced(), "Expected splice path for renderRange after priming full render")
            assertCommandMultisetEquivalent(fullResult, rangeResult)
        }
    }
}
