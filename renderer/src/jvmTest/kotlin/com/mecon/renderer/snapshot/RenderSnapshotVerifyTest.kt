package com.mecon.renderer.snapshot

import com.mecon.renderer.render.RenderElement
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
        // Report first differing element
        val minSize = minOf(expected.elements.size, actual.elements.size)
        for (i in 0 until minSize) {
            val expJson = snapshotJson.encodeToString(RenderElement.serializer(), expected.elements[i])
            val actJson = snapshotJson.encodeToString(RenderElement.serializer(), actual.elements[i])
            if (expJson != actJson) {
                appendLine("  first diff at elements[$i] (type=${expected.elements[i].type}, id=${expected.elements[i].id}):")
                val expLines = expJson.lines()
                val actLines = actJson.lines()
                for (j in 0 until minOf(expLines.size, actLines.size)) {
                    if (expLines[j] != actLines[j]) {
                        appendLine("    expected: ${expLines[j].trim()}")
                        appendLine("    actual:   ${actLines[j].trim()}")
                        break
                    }
                }
                break
            }
        }
    }
}
