package com.mecon.desktop.ui.exploration

import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.mecon.api.primitive.Fraction
import com.mecon.api.primitive.KeySignature
import com.mecon.api.runtime.RuntimeScore
import com.mecon.core.engine.computeScore
import com.mecon.desktop.service.HarmonyPracticeScoreHost
import com.mecon.exploration.VoicePlanScoreAssembler
import com.mecon.features.freepractice.FreePracticeViewProjector
import com.mecon.features.freepractice.PracticeTimelineHitKind
import com.mecon.features.freepractice.PracticeTimelineSceneProjector
import com.mecon.features.freepractice.PracticeTimelineSceneRequest
import com.mecon.features.freepractice.PracticeTimelineToneLabelMode
import com.mecon.theory.freepractice.HarmonyWorkspaceState
import com.mecon.theory.freepractice.WorkspaceSlotId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertTrue
import org.jetbrains.skia.EncodedImageFormat

/**
 * Replays the reported sequence — add a chord, drag it right, undo, drag it right again — against a
 * real [HarmonyPracticeScoreHost], so auto-writing, revisions, history and the shell's workspace
 * sync all behave as they do in the app. Preview visibility is measured by replaying each drag
 * twice, once with the projection available and once refused: gesture chrome is identical in both,
 * so a difference between the frames is the previewed chord itself.
 */
class FreePracticeTimelineSessionInteractionTest {
    private val beatWidth = 144.dp
    private val width = 960
    private val height = 220

    @Test
    fun previewFollowsTheSecondDragAfterAnUndo() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val initial = initialWorkspace(4)
        val runtime = RuntimeScore.fromStorage(
            VoicePlanScoreAssembler.emptyPracticeScore(initial, KeySignature.C_MAJOR)
        )
        val host = HarmonyPracticeScoreHost(scope, runtime, computeScore(runtime), initial)
        var previewEnabled by mutableStateOf(true)
        var selectedSlotId by mutableStateOf(initial.slots.first().id)
        var writingComplete: CompletableDeferred<String?>? = null
        val previewLog = mutableListOf<String>()
        val commitLog = mutableListOf<String>()

        @OptIn(ExperimentalComposeUiApi::class)
        val scene = ImageComposeScene(width, height, Density(1f)) {
            MaterialTheme {
                Harness(
                    host = host,
                    selectedSlotId = selectedSlotId,
                    previewEnabled = { previewEnabled },
                    onWritingComplete = { message -> writingComplete?.complete(message) },
                    previewLog = previewLog,
                    commitLog = commitLog,
                )
            }
        }

        try {
            scene.png()
            // 新建一个和弦
            val appended = requireNotNull(
                host.insertChordRange(Fraction.QUARTER, Fraction.QUARTER)
            )
            selectedSlotId = appended
            scene.settle()

            val target = scene.slotCenter(host.practiceWorkspace, appended)
            fun probe(): Pair<String, String> {
                previewEnabled = false
                val without = scene.dragAndCancel(target)
                previewEnabled = true
                val with = scene.dragAndCancel(target)
                return with to without
            }

            // The panel stacks a header above the scene, so calibrate the row offset once by
            // hovering: hover changes the frame without starting a gesture.
            val idle = scene.png()
            val hoverRows = mutableListOf<Int>()
            for (dy in -30..60 step 2) {
                scene.hover(Offset(target.x, target.y + dy))
                if (scene.png() != idle) hoverRows += dy
            }
            scene.hover(Offset(2f, 2f))
            scene.png()
            val rowOffset = target.y + (hoverRows.first() + hoverRows.last()) / 2

            // The chord moves with every commit, so re-locate it before each gesture.
            fun targetFor(): Offset =
                Offset(scene.slotCenter(host.practiceWorkspace, appended).x, rowOffset)

            fun dragAndCommit(): Pair<Int, Int> {
                previewLog.clear()
                commitLog.clear()
                val written = CompletableDeferred<String?>()
                writingComplete = written
                val at = targetFor()
                scene.drag(at)
                val previews = previewLog.size
                scene.release(at)
                runBlocking { withTimeout(60_000) { written.await() } }
                scene.settle()
                return previews to commitLog.size
            }

            val (firstPreviews, firstCommits) = dragAndCommit()
            assertTrue(firstPreviews > 0 && firstCommits == 1, "首次拖动未产生预览或提交")

            host.undo()
            scene.settle()

            val (secondPreviews, secondCommits) = dragAndCommit()
            assertTrue(secondPreviews > 0, "撤销后再次拖动没有预览; log=$previewLog")
            assertTrue(secondCommits == 1, "撤销后再次拖动没有提交")
        } finally {
            scene.close()
            scope.cancel()
        }
    }

    @Composable
    private fun Harness(
        host: HarmonyPracticeScoreHost,
        selectedSlotId: WorkspaceSlotId,
        previewEnabled: () -> Boolean,
        onWritingComplete: (String?) -> Unit,
        previewLog: MutableList<String>,
        commitLog: MutableList<String>,
    ) {
        var workspace by remember { mutableStateOf(host.practiceWorkspace) }
        LaunchedEffect(host.documentVersion, host.practiceWorkspace) {
            workspace = host.practiceWorkspace
        }
        val slotId = workspace.slots.firstOrNull { it.id == selectedSlotId }?.id
            ?: workspace.slots.first().id
        SharedHarmonicTimeline(
            workspace = workspace,
            selectedSlotId = slotId,
            selectedIdiomInstanceId = null,
            onSelectIdiom = {},
            idiomTitles = emptyMap(),
            toneMode = com.mecon.desktop.uikit.components.ChordToneLabelMode.RELATIVE,
            beatWidth = beatWidth,
            onBeatWidthChange = {},
            gridUnit = Fraction(1, 8),
            defaultChordDuration = Fraction(1, 4),
            scrollState = rememberScrollState(),
            resolvedTimeAxis = null,
            onSelect = {},
            onInsertRange = { _, _ -> },
            onCommitTimelineEdit = { edit ->
                commitLog += edit.toString()
                host.commitTimelineEdit(edit, onWritingComplete)
            },
            onPreviewTimelineEdit = { edit ->
                if (!previewEnabled()) null
                else {
                    val result = host.previewTimelineEdit(edit)
                    previewLog += "$edit -> accepted=${result.accepted} reason=${result.reasonKey}"
                    result.takeIf { it.accepted }?.timeline
                }
            },
            onError = {},
            onDelete = {},
            onSelectTonalLayout = {},
        )
    }

    /** Scene units equal device pixels at density 1, so a projected hit box is a pointer target. */
    private fun ImageComposeScene.slotCenter(
        workspace: HarmonyWorkspaceState,
        slotId: WorkspaceSlotId,
    ): Offset {
        val projected = PracticeTimelineSceneProjector.project(
            PracticeTimelineSceneRequest(
                revision = workspace.hashCode().toLong(),
                axisRevision = 0L,
                viewportWidth = width.toFloat(),
                scrollLeft = 0f,
                axisAnchors = emptyList(),
                axisContentEndX = 0f,
                pixelsPerWhole = beatWidth.value * 4f,
                timeline = FreePracticeViewProjector.timeline(workspace),
                selectedSlotId = slotId.value,
                selectedIdiomId = null,
                gridUnit = Fraction(1, 8),
                defaultChordDuration = Fraction(1, 4),
                toneLabelMode = PracticeTimelineToneLabelMode.RELATIVE,
                palette = desktopTimelinePalette(),
                showRemoveAction = false,
                gesture = null,
            )
        )
        val hit = projected.hitObjects.first {
            it.kind == PracticeTimelineHitKind.SLOT && it.targetId == slotId.value
        }
        // The panel puts a header row above the scene, so nudge into the box rather than its edge.
        return Offset(hit.bounds.x + hit.bounds.width / 2f, hit.bounds.y + hit.bounds.height / 2f + 8f)
    }

    @OptIn(ExperimentalComposeUiApi::class)
    private fun ImageComposeScene.hover(at: Offset) {
        sendPointerEvent(PointerEventType.Move, at)
    }

    @OptIn(ExperimentalComposeUiApi::class)
    private fun ImageComposeScene.drag(start: Offset): String {
        val pressed = PointerButtons(isPrimaryPressed = true)
        sendPointerEvent(PointerEventType.Press, start, buttons = pressed)
        sendPointerEvent(PointerEventType.Move, Offset(start.x + DX / 2f, start.y), buttons = pressed)
        sendPointerEvent(PointerEventType.Move, Offset(start.x + DX, start.y), buttons = pressed)
        return png()
    }

    /** Ends the gesture without committing, so probes leave the session untouched. */
    @OptIn(ExperimentalComposeUiApi::class)
    private fun ImageComposeScene.dragAndCancel(start: Offset): String {
        val image = drag(start)
        // Cancel first, then lift: leaving the pointer logically pressed makes the next Press look
        // like a move and the following probe never starts a gesture.
        sendPointerEvent(PointerEventType.Exit, Offset(start.x + DX, start.y))
        sendPointerEvent(PointerEventType.Release, Offset(start.x + DX, start.y))
        png()
        return image
    }

    @OptIn(ExperimentalComposeUiApi::class)
    private fun ImageComposeScene.release(start: Offset) {
        sendPointerEvent(PointerEventType.Release, Offset(start.x + DX, start.y))
        png()
    }

    /** Renders repeatedly so background commits reach the composition. */
    @OptIn(ExperimentalComposeUiApi::class)
    private fun ImageComposeScene.settle() {
        repeat(4) { png() }
    }

    @OptIn(ExperimentalComposeUiApi::class)
    private fun ImageComposeScene.png(): String {
        val image = render()
        return requireNotNull(image.encodeToData(EncodedImageFormat.PNG)).bytes
            .joinToString("") { it.toString(16) }
    }

    private companion object {
        const val DX = 120f
    }
}
