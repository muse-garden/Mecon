package com.mecon.desktop.debug

import com.mecon.api.runtime.RuntimeScore
import com.mecon.desktop.service.ScoreFileService
import com.mecon.renderer.geometry.StaffSpace
import com.mecon.renderer.render.RenderEngine
import com.mecon.renderer.smufl.BravuraFontLoader
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.system.exitProcess

/**
 * Debug entry point to render a score file and print the result.
 * Usage: ./gradlew :apps:desktop:runRenderDebug --args="path/to/score.mecon"
 */
object RenderDebug {
    @JvmStatic
    fun main(args: Array<String>) {
        if (args.isEmpty()) {
            println("Usage: RenderDebug <path_to_score_file>")
            exitProcess(1)
        }

        val filePath = args[0]
        val file = File(filePath)

        if (!file.exists()) {
            println("Error: File not found: $filePath")
            exitProcess(1)
        }

        println("Loading score: $filePath")

        runBlocking {
            // 1. Load Font
            println("Loading Bravura font...")
            val fontLoader = BravuraFontLoader()
            val font = fontLoader.load()
            if (font == null) {
                println("Error: Failed to load Bravura font")
                exitProcess(1)
            }

            // 2. Load Score
            println("Loading score file...")
            val loadResult = ScoreFileService.instance.loadAuto(file)
            val storageScore = loadResult.getOrElse {
                println("Error: Failed to load score: ${it.message}")
                it.printStackTrace()
                exitProcess(1)
            }
            println("Score loaded successfully. Type: ${storageScore::class.simpleName}")

            // 3. Convert to Runtime
            println("Converting to RuntimeScore...")
            val runtimeScore = RuntimeScore.fromStorage(storageScore)

            // 4. Render
            println("Rendering...")
            val result = with(font) {
                val renderEngine = RenderEngine.default()
                renderEngine.render(
                    score = runtimeScore,
                    pageWidth = StaffSpace(100f), // Default width
                    pageHeight = StaffSpace(80f)  // Default height
                )
            }

            // 5. Output Result
            println("\n=== Render Result ===")
            println("Bounds: ${result.bounds}")
            println("Systems: ${result.firstSystem} to ${result.lastSystem}")
            println("Measures: ${result.firstMeasure} to ${result.lastMeasure}")
            println("Total Elements: ${result.elements.size}")

            println("\n--- Element Counts by Type ---")
            val byType = result.elements.groupingBy { it.type }.eachCount()
            byType.entries.sortedByDescending { it.value }.forEach { (type, count) ->
                println("${type.name.padEnd(20)}: $count")
            }

            println("\n--- First 10 Elements ---")
            result.elements.take(10).forEach { elem ->
                println("Type: ${elem.type.name.padEnd(15)} ID: ${elem.id.debugString().padEnd(10)} Track: ${elem.trackId}  Measure: ${elem.measureNumber}")
            }
        }
    }
}
