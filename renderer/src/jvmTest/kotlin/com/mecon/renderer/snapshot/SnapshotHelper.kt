package com.mecon.renderer.snapshot

import com.mecon.api.runtime.RuntimeScore
import com.mecon.core.engine.computeScore
import com.mecon.core.serializer.ScoreSerializer
import com.mecon.renderer.geometry.AbsoluteRect
import com.mecon.renderer.layout.RenderLayoutConfig
import com.mecon.renderer.render.RenderCommand
import com.mecon.renderer.render.RenderElement
import com.mecon.renderer.render.RenderEngine
import com.mecon.renderer.render.RenderElementType
import com.mecon.renderer.render.RenderHelpers
import com.mecon.renderer.render.RenderResult
import com.mecon.renderer.smufl.BravuraFont
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

@OptIn(ExperimentalSerializationApi::class)
val snapshotJson = Json {
    prettyPrint = true
    prettyPrintIndent = "  "
}

@Serializable
data class RenderSnapshot(
    val elements: List<RenderElement>,
    val bounds: AbsoluteRect,
    val firstSystem: Int,
    val lastSystem: Int,
    val firstMeasure: Int,
    val lastMeasure: Int
)

fun loadFont(): BravuraFont? {
    val base = File("../apps/desktop/src/main/resources/bravura")
    val meta = File(base, "bravuraMetadata.json")
    val names = File(base, "glyphnames.json")
    if (!meta.exists() || !names.exists()) return null
    return BravuraFont.fromJson(meta.readText(), names.readText())
}

fun testScoreDir(): File = File("../test-scores")

fun snapshotDir(): File = File("../test-scores/snapshots")

/** Paginated test scores live in their own folder so line/page-break behaviour is exercised. */
fun paginatedScoreDir(): File = File("../test-scores/paginated")

/** Native single-score fixture extension (was `.mecon`; freed up for the new zip container format). */
const val SCORE_FIXTURE_SUFFIX = ".mscore.yaml"

fun scoreFiles(): List<File> =
    testScoreDir().listFiles { f -> f.name.endsWith(SCORE_FIXTURE_SUFFIX) }
        ?.sortedBy { it.name }
        ?: emptyList()

fun paginatedScoreFiles(): List<File> =
    paginatedScoreDir().listFiles { f -> f.name.endsWith(SCORE_FIXTURE_SUFFIX) }
        ?.sortedBy { it.name }
        ?: emptyList()

/** Continuous (top-level) + paginated scores. */
fun allScoreFiles(): List<File> = scoreFiles() + paginatedScoreFiles()

/** Fixture base name without the `.mscore.yaml` double extension (e.g. `01_durations`). */
fun scoreBaseName(scoreFile: File): String = scoreFile.name.removeSuffix(SCORE_FIXTURE_SUFFIX)

/** Snapshot lives in a `snapshots/` dir beside the score (so paginated scores nest correctly). */
fun snapshotFileFor(scoreFile: File): File =
    File(File(scoreFile.parentFile, "snapshots"), "${scoreBaseName(scoreFile)}.json")

fun renderScoreFile(file: File, font: BravuraFont): RenderResult {
    val storage = ScoreSerializer.fromYaml(file.readText())
    val runtime = RuntimeScore.fromStorage(storage)
    return with(font) {
        RenderEngine(RenderLayoutConfig.DEFAULT).render(runtime)
    }
}

fun RenderResult.toSnapshot(): RenderSnapshot {
    // Measure numbers are an optional final-pass display overlay. Geometry goldens intentionally
    // exclude them so toggling a presentation preference does not rewrite every snapshot.
    val snapshotElements = elements.filter { it.type != RenderElementType.MEASURE }
    return RenderSnapshot(
        elements = snapshotElements.mapIndexed { index, element ->
            // IDs and system ownership are runtime index metadata. Canonicalise them so geometry goldens stay
            // stable; dedicated index/system tests cover those fields directly.
            element.copy(
                id = com.mecon.renderer.render.RenderElementId.global(index.toLong()),
                systemIndex = null,
                staffIndex = if (element.metadata.keys.any { it.startsWith("mecon.splice.") }) {
                    null
                } else {
                    element.staffIndex
                },
                metadata = element.metadata - setOf(
                    com.mecon.renderer.render.ALWAYS_REGENERATED_STRUCTURE,
                    com.mecon.renderer.render.REUSABLE_SYSTEM_STRUCTURE,
                    com.mecon.renderer.render.REUSABLE_LINE_START_HEADER,
                    "barlineTime",
                ),
            )
        },
        bounds = RenderHelpers.calculateBounds(snapshotElements),
        firstSystem = firstSystem,
        lastSystem = lastSystem,
        firstMeasure = firstMeasure,
        lastMeasure = lastMeasure,
    )
}

/**
 * Returns a copy of the snapshot in which each element's render commands are sorted
 * into a canonical order (by their serialized form).
 *
 * The set of commands that draws an element is order-independent — reordering them
 * produces the same picture. Normalizing both sides before comparison means a snapshot
 * whose commands differ only in ordering still verifies as a match.
 */
fun RenderSnapshot.commandOrderNormalized(): RenderSnapshot = copy(
    elements = elements.map { element ->
        element.copy(
            commands = element.commands.sortedBy {
                snapshotJson.encodeToString(RenderCommand.serializer(), it)
            }
        )
    }
)

private val commandNumberRegex = Regex("-?\\d+\\.\\d+(?:[eE]-?\\d+)?|-?\\d+")

/** The non-numeric "shape" of a command's toString (numbers blanked) — its structural identity. */
private fun commandShape(cmd: RenderCommand): String = commandNumberRegex.replace(cmd.toString(), "#")

private fun commandNumbers(s: String): List<Float> =
    commandNumberRegex.findAll(s).map { it.value.toFloat() }.toList()

private val numberVectorComparator = Comparator<List<Float>> { a, b ->
    for (j in 0 until minOf(a.size, b.size)) {
        val c = a[j].compareTo(b[j]); if (c != 0) return@Comparator c
    }
    a.size.compareTo(b.size)
}

/**
 * Assert [full] and [inc] hold the same **multiset** of draw commands within [eps] pixels, ignoring
 * order — plus equal element count and bounds. The element-level incremental splice reuses / translates
 * cached elements and regenerates only the edited window, so its draw-command *order* differs from a full
 * render; command-order parity (the §5.2 golden rule for the layout stage) no longer applies, but the
 * picture must be identical. Commands are grouped by structural shape; within each shape the numeric
 * vectors are sorted and paired (distinct elements sit whole staff-spaces apart, so ~3e-5 px float drift
 * never reorders the pairing).
 */
fun assertCommandMultisetEquivalent(full: RenderResult, inc: RenderResult, eps: Float = 0.02f) {
    kotlin.test.assertEquals(full.elements.size, inc.elements.size, "element count differs")

    val fByShape = full.allCommands().groupBy(::commandShape)
    val iByShape = inc.allCommands().groupBy(::commandShape)
    kotlin.test.assertEquals(fByShape.keys, iByShape.keys, "command structural shapes differ")

    for (shape in fByShape.keys) {
        val fv = fByShape.getValue(shape).map { commandNumbers(it.toString()) }.sortedWith(numberVectorComparator)
        val iv = iByShape.getValue(shape).map { commandNumbers(it.toString()) }.sortedWith(numberVectorComparator)
        kotlin.test.assertEquals(fv.size, iv.size, "command count differs for shape: $shape")
        for (k in fv.indices) {
            val a = fv[k]; val b = iv[k]
            for (j in a.indices) {
                kotlin.test.assertTrue(kotlin.math.abs(a[j] - b[j]) <= eps,
                    "shape [$shape] command #$k number $j: full=${a[j]} inc=${b[j]}")
            }
        }
    }

    val fb = commandNumbers(full.bounds.toString())
    val ib = commandNumbers(inc.bounds.toString())
    for (j in fb.indices) kotlin.test.assertTrue(kotlin.math.abs(fb[j] - ib[j]) <= eps,
        "bounds number $j: ${fb[j]} vs ${ib[j]}")
}

/**
 * Assert that the union of draw commands across [partials] is multiset-equivalent (within [eps]) to
 * the commands in [full]. Unlike [assertCommandMultisetEquivalent], this does **not** require equal
 * element counts — it works at the raw command level, intended for per-system union tests where
 * several partial [RenderResult]s together cover the same drawing as a single full render.
 */
/**
 * Like [assertCommandMultisetEquivalent] but matches commands by **greedy nearest-neighbour within [eps]**
 * instead of a positional sort. The sort-then-pair comparison is fragile when many commands share a
 * coordinate (e.g. barlines at the same X on different systems, as with uniform measure widths): the
 * incremental splice's element reuse carries a few sub-pixel-drifting coordinates (independent full-solve
 * fp differences, ~1e-3 px), which can flip two coincident commands' sort order and mis-pair them. This
 * variant is order- and pairing-independent: it asserts every [full] command has a distinct [inc] command
 * matching within [eps] in every number (equal counts guarantee the reverse). Use for reuse-heavy splice
 * parity (e.g. reflow) where drift + coordinate coincidence is expected; the strict version stays the
 * default elsewhere.
 */
fun assertCommandsWithinEps(full: RenderResult, inc: RenderResult, eps: Float = 0.05f) {
    kotlin.test.assertEquals(full.elements.size, inc.elements.size, "element count differs")
    val fByShape = full.allCommands().groupBy(::commandShape)
    val iByShape = inc.allCommands().groupBy(::commandShape)
    kotlin.test.assertEquals(fByShape.keys, iByShape.keys, "command structural shapes differ")
    for (shape in fByShape.keys) {
        val fv = fByShape.getValue(shape).map { commandNumbers(it.toString()) }
        val iv = iByShape.getValue(shape).map { commandNumbers(it.toString()) }.toMutableList()
        kotlin.test.assertEquals(fv.size, iv.size, "command count differs for shape: $shape")
        for (a in fv) {
            val idx = iv.indexOfFirst { b ->
                a.size == b.size && a.indices.all { kotlin.math.abs(a[it] - b[it]) <= eps }
            }
            kotlin.test.assertTrue(idx >= 0, "no inc command within $eps of full $a for shape [$shape]")
            iv.removeAt(idx)
        }
    }
    val fb = commandNumbers(full.bounds.toString())
    val ib = commandNumbers(inc.bounds.toString())
    for (j in fb.indices) kotlin.test.assertTrue(kotlin.math.abs(fb[j] - ib[j]) <= eps,
        "bounds number $j: ${fb[j]} vs ${ib[j]}")
}

fun assertCommandUnionEquivalent(full: RenderResult, partials: List<RenderResult>, eps: Float = 0.02f) {
    val fullCommands = full.allCommands()
    val unionCommands = partials.flatMap { it.allCommands() }

    val fByShape = fullCommands.groupBy(::commandShape)
    val uByShape = unionCommands.groupBy(::commandShape)
    kotlin.test.assertEquals(fByShape.keys, uByShape.keys, "command structural shapes differ between full and union")

    for (shape in fByShape.keys) {
        val fv = fByShape.getValue(shape).map { commandNumbers(it.toString()) }.sortedWith(numberVectorComparator)
        val uv = uByShape.getValue(shape).map { commandNumbers(it.toString()) }.sortedWith(numberVectorComparator)
        kotlin.test.assertEquals(fv.size, uv.size, "command count differs for shape: $shape")
        for (k in fv.indices) {
            val a = fv[k]; val b = uv[k]
            for (j in a.indices) {
                kotlin.test.assertTrue(kotlin.math.abs(a[j] - b[j]) <= eps,
                    "shape [$shape] command #$k number $j: full=${a[j]} union=${b[j]}")
            }
        }
    }
}

fun loadSnapshot(file: File): RenderSnapshot =
    snapshotJson.decodeFromString(file.readText())

fun saveSnapshot(file: File, snapshot: RenderSnapshot) {
    file.writeText(snapshotJson.encodeToString(RenderSnapshot.serializer(), snapshot))
}
