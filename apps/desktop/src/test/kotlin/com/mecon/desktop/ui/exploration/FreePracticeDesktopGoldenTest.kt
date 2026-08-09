package com.mecon.desktop.ui.exploration

import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.renderComposeScene
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.mecon.api.primitive.Fraction
import com.mecon.theory.KeySignatureMode
import com.mecon.theory.ModulationKey
import com.mecon.theory.freepractice.WorkspaceChordTonalReading
import com.mecon.theory.freepractice.WorkspaceChordTonality
import com.mecon.theory.freepractice.WorkspaceHarmonySlot
import com.mecon.theory.freepractice.WorkspaceIdiomInstance
import com.mecon.theory.freepractice.WorkspaceIdiomInstanceId
import com.mecon.theory.freepractice.WorkspaceSlotId
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertContentEquals
import org.jetbrains.skia.EncodedImageFormat

class FreePracticeDesktopGoldenTest {
    @Test
    fun sharedTimelineAdapterMatchesDesktopGolden() {
        val actual = timelinePng(1f)
        val golden = Path.of(
            "src", "test", "resources", "golden",
            "free-practice-timeline-win32.png",
        )
        if (System.getProperty("freepractice.desktop.golden.write") == "true") {
            Files.createDirectories(golden.parent)
            Files.write(golden, actual)
        }
        assertContentEquals(Files.readAllBytes(golden), actual)
    }

    /**
     * The scene is laid out in density-independent units, so a display scale must enlarge it in
     * both axes. It used to reach the adapter as device pixels: axis-driven widths kept their size
     * while every intrinsic dimension — chord box height above all — shrank by the scale factor.
     */
    @Test
    fun timelineContentScalesWithDisplayDensity() {
        val single = contentExtent(timelinePng(1f))
        val double = contentExtent(timelinePng(2f, scaleSurface = true))
        assertEquals(single.first * 2, double.first, 4)
        assertEquals(single.second * 2, double.second, 4)
    }

    /** Bottom-most and right-most pixel row/column that differs from the surface background. */
    private fun contentExtent(png: ByteArray): Pair<Int, Int> {
        val image = ImageIO.read(ByteArrayInputStream(png))
        val background = image.getRGB(image.width - 1, image.height - 1)
        var bottom = 0
        var right = 0
        for (y in 0 until image.height) {
            for (x in 0 until image.width) {
                if (image.getRGB(x, y) != background) {
                    if (y > bottom) bottom = y
                    if (x > right) right = x
                }
            }
        }
        return bottom to right
    }

    private fun assertEquals(expected: Int, actual: Int, tolerance: Int) {
        kotlin.test.assertTrue(
            kotlin.math.abs(expected - actual) <= tolerance,
            "expected $expected +/- $tolerance but was $actual",
        )
    }

    @OptIn(ExperimentalComposeUiApi::class)
    private fun timelinePng(scale: Float, scaleSurface: Boolean = false): ByteArray {
        val initial = initialWorkspace(4)
        val majorTonic = initial.slots.single()
        val minorTonic = initialWorkspace(
            4,
            ModulationKey(0, KeySignatureMode.MINOR),
        ).slots.single()
        val slots = listOf(
            majorTonic.copy(id = WorkspaceSlotId("slot-1")),
            majorTonic.copy(
                id = WorkspaceSlotId("slot-2"),
                onset = Fraction.QUARTER,
                tonality = WorkspaceChordTonality(
                    primary = WorkspaceChordTonalReading.of(ModulationKey(0, KeySignatureMode.MAJOR)),
                    alternates = listOf(
                        WorkspaceChordTonalReading.of(ModulationKey(1, KeySignatureMode.MAJOR)),
                    ),
                ),
                isPivotChord = true,
            ),
            minorTonic.copy(
                id = WorkspaceSlotId("slot-3"),
                onset = Fraction(1, 2),
                tonalLayoutId = initial.tonalLayouts.single().id,
                tonality = WorkspaceChordTonality(
                    primary = WorkspaceChordTonalReading.of(ModulationKey(0, KeySignatureMode.MINOR)),
                ),
            ),
            emptySlot("slot-4", Fraction(3, 4), initial),
            emptySlot("slot-5", Fraction.ONE, initial),
        )
        val idiomId = WorkspaceIdiomInstanceId("idiom-cadence")
        val workspace = initial.copy(
            slots = slots,
            idiomInstances = listOf(
                WorkspaceIdiomInstance(
                    id = idiomId,
                    definitionId = "authentic-cadence",
                    variantId = "root-position",
                    sourceExerciseId = "golden",
                    sourceChapterId = "golden",
                    slotIds = slots.slice(1..2).map(WorkspaceHarmonySlot::id),
                ),
            ),
        )
        val surfaceScale = if (scaleSurface) scale else 1f
        val image = renderComposeScene(
            (960 * surfaceScale).toInt(),
            (220 * surfaceScale).toInt(),
            Density(scale),
        ) {
            MaterialTheme {
                SharedHarmonicTimeline(
                    workspace = workspace,
                    selectedSlotId = workspace.slots.last().id,
                    selectedIdiomInstanceId = idiomId,
                    onSelectIdiom = {},
                    idiomTitles = mapOf("authentic-cadence" to "正格终止"),
                    toneMode = com.mecon.desktop.uikit.components.ChordToneLabelMode.RELATIVE,
                    beatWidth = 144.dp,
                    onBeatWidthChange = {},
                    gridUnit = Fraction(1, 8),
                    defaultChordDuration = Fraction(1, 4),
                    scrollState = rememberScrollState(),
                    resolvedTimeAxis = null,
                    onSelect = {},
                    onInsertRange = { _, _ -> },
                    onCommitTimelineEdit = { true },
                    onPreviewTimelineEdit = { null },
                    onError = {},
                    onDelete = {},
                    onSelectTonalLayout = {},
                )
            }
        }
        val bytes = requireNotNull(image.encodeToData(EncodedImageFormat.PNG)).bytes
        image.close()
        return bytes
    }

    private fun emptySlot(
        id: String,
        onset: Fraction,
        initial: com.mecon.theory.freepractice.HarmonyWorkspaceState,
    ): WorkspaceHarmonySlot = WorkspaceHarmonySlot(
        id = WorkspaceSlotId(id),
        onset = onset,
        duration = Fraction.QUARTER,
        tonalLayoutId = initial.tonalLayouts.single().id,
    )
}
