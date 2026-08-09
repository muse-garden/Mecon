package com.mecon.renderer.snapshot

import kotlin.test.Test

/**
 * Generates render snapshots for test scores and saves them as JSON reference files.
 *
 * This test only runs when the system property `update-snapshots=true` is set.
 * Use the Gradle property to activate it:
 *
 *   ./gradlew :renderer:jvmTest -Pupdate-snapshots            # regenerate all
 *   ./gradlew :renderer:jvmTest -Pupdate-snapshots -Pfilter=01 # partial (name match)
 *
 * Snapshots are saved to test-scores/snapshots/<name>.json and should be committed
 * to version control so that RenderSnapshotVerifyTest can compare against them in CI.
 */
class GenerateSnapshotsTest {

    @Test
    fun generateSnapshots() {
        if (System.getProperty("update-snapshots") != "true") return

        val font = loadFont()
            ?: error(
                "Bravura font assets not found.\n" +
                "Expected at: ../apps/desktop/src/main/resources/bravura/"
            )

        val filter = System.getProperty("snapshot-filter")
        val files = allScoreFiles().let { all ->
            if (filter != null) all.filter { it.name.contains(filter) } else all
        }

        if (files.isEmpty()) {
            if (filter != null) error("No test score files matching filter: '$filter'")
            println("No test score files found in ${testScoreDir().canonicalPath}")
            return
        }

        var generated = 0
        var skipped = 0

        for (file in files) {
            try {
                print("  ${file.name} ... ")
                val result = renderScoreFile(file, font)
                val snapshotFile = snapshotFileFor(file)
                snapshotFile.parentFile.mkdirs()
                saveSnapshot(snapshotFile, result.toSnapshot())
                println("${result.elements.size} elements")
                generated++
            } catch (e: Exception) {
                println("skipped (${e.message?.take(80)})")
                skipped++
            }
        }

        println("\nGenerated $generated snapshot(s), skipped $skipped -> ${snapshotDir().canonicalPath}")
    }
}
