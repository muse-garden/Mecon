package com.mecon.renderer.snapshot

import com.mecon.renderer.render.RenderElement
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Verifies that the rendering engine produces output identical to the saved snapshots.
 *
 * For every test score that has a corresponding snapshot in test-scores/snapshots/,
 * this test re-renders the score and compares the result JSON byte-for-byte.
 * Any deviation — added, removed, or repositioned elements — causes the test to fail.
 *
 * To update snapshots after an intentional rendering change, run:
 *   ./gradlew :renderer:jvmTest -Pupdate-snapshots
 *
 * This test is silently skipped when the Bravura font assets are unavailable,
 * or when no snapshot files exist yet (first-time setup).
 */
class RenderSnapshotVerifyTest {

    @Test
    fun allSnapshotsMatch() {
        val font = loadFont() ?: return  // font assets not present — skip

        val filesToVerify = allScoreFiles().filter { snapshotFileFor(it).exists() }
        if (filesToVerify.isEmpty()) return  // no snapshots yet — skip

        val failures = mutableListOf<String>()

        for (file in filesToVerify) {
            try {
                val expected = loadSnapshot(snapshotFileFor(file)).commandOrderNormalized()
                val actual = renderScoreFile(file, font).toSnapshot().commandOrderNormalized()

                val expectedJson = snapshotJson.encodeToString(RenderSnapshot.serializer(), expected)
                val actualJson = snapshotJson.encodeToString(RenderSnapshot.serializer(), actual)

                if (expectedJson != actualJson) {
                    failures += buildMismatchMessage(file.name, expected, actual)
                }
            } catch (e: Exception) {
                failures += "${file.name}: ${e.message}"
            }
        }

        assertTrue(
            failures.isEmpty(),
            buildString {
                appendLine("${failures.size} snapshot(s) do not match:")
                appendLine()
                failures.forEach { appendLine(it) }
                appendLine()
                append("Run './gradlew :renderer:jvmTest -Pupdate-snapshots' to update snapshots.")
            }
        )
    }

    private fun buildMismatchMessage(
        fileName: String,
        expected: RenderSnapshot,
        actual: RenderSnapshot
    ): String = buildString {
        appendLine("--- $fileName ---")
        if (expected.elements.size != actual.elements.size) {
            appendLine("  element count: expected ${expected.elements.size}, got ${actual.elements.size}")
        }
        if (expected.bounds != actual.bounds) {
            appendLine("  bounds: expected ${expected.bounds}, got ${actual.bounds}")
        }
        if (expected.firstSystem != actual.firstSystem || expected.lastSystem != actual.lastSystem) {
            appendLine("  systems: expected ${expected.firstSystem}..${expected.lastSystem}, got ${actual.firstSystem}..${actual.lastSystem}")
        }
        if (expected.firstMeasure != actual.firstMeasure || expected.lastMeasure != actual.lastMeasure) {
            appendLine("  measures: expected ${expected.firstMeasure}..${expected.lastMeasure}, got ${actual.firstMeasure}..${actual.lastMeasure}")
        }
        // Report the first differing element, plus how many differ in total. One element out of
        // hundreds is a localised regression; "985 of 985 noteheads" is a renderer-wide change that
        // just needs the goldens regenerated — the two want completely different reactions.
        val minSize = minOf(expected.elements.size, actual.elements.size)
        val differing = (0 until minSize).filter { i ->
            snapshotJson.encodeToString(RenderElement.serializer(), expected.elements[i]) !=
                snapshotJson.encodeToString(RenderElement.serializer(), actual.elements[i])
        }
        val first = differing.firstOrNull() ?: return@buildString
        appendLine(
            "  ${differing.size} of $minSize elements differ; first at elements[$first] " +
                "(type=${expected.elements[first].type}, id=${expected.elements[first].id}):"
        )
        fieldDifferences(expected.elements[first], actual.elements[first]).forEach {
            appendLine("    $it")
        }
    }

    /**
     * Names the fields that actually differ, rather than the first differing *line* of pretty-printed
     * JSON. A newly emitted field makes the line above it grow a trailing comma, so the line-based
     * report showed `"pitchIndex": "0"` vs `"pitchIndex": "0",` — which reads like punctuation noise
     * and hid a real un-regenerated golden (`metadata.noteheadFilled`, added in e3e577df). Do not
     * relax the comparison to tolerate that: the goldens are meant to record everything the renderer
     * emits, and regenerating is one documented command. Make the message say what changed instead.
     */
    private fun fieldDifferences(expected: RenderElement, actual: RenderElement): List<String> {
        val differences = mutableListOf<String>()
        collectJsonDifferences(
            snapshotJson.parseToJsonElement(
                snapshotJson.encodeToString(RenderElement.serializer(), expected)
            ),
            snapshotJson.parseToJsonElement(
                snapshotJson.encodeToString(RenderElement.serializer(), actual)
            ),
            path = "",
            into = differences,
        )
        return differences.ifEmpty { listOf("(no field-level difference found)") }
    }

    private fun collectJsonDifferences(
        expected: JsonElement,
        actual: JsonElement,
        path: String,
        into: MutableList<String>,
        limit: Int = MAX_REPORTED_FIELD_DIFFERENCES,
    ) {
        if (into.size >= limit) return
        when {
            expected is JsonObject && actual is JsonObject -> {
                for (key in LinkedHashSet(expected.keys + actual.keys)) {
                    val child = if (path.isEmpty()) key else "$path.$key"
                    val expectedValue = expected[key]
                    val actualValue = actual[key]
                    when {
                        expectedValue == null -> into += "$child: added in render (${actualValue.brief()})"
                        actualValue == null -> into += "$child: missing from render (was ${expectedValue.brief()})"
                        else -> collectJsonDifferences(expectedValue, actualValue, child, into, limit)
                    }
                    if (into.size >= limit) return
                }
            }
            expected is JsonArray && actual is JsonArray -> {
                if (expected.size != actual.size) {
                    into += "$path: ${expected.size} entries in snapshot, ${actual.size} in render"
                    return
                }
                for (index in expected.indices) {
                    collectJsonDifferences(expected[index], actual[index], "$path[$index]", into, limit)
                    if (into.size >= limit) return
                }
            }
            expected != actual -> into += "$path: ${expected.brief()} -> ${actual.brief()}"
        }
    }

    private fun JsonElement?.brief(): String =
        this?.toString()?.let { if (it.length > 60) it.take(57) + "..." else it } ?: "absent"

    private companion object {
        const val MAX_REPORTED_FIELD_DIFFERENCES = 5
    }
}
