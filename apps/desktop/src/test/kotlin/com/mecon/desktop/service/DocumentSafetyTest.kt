package com.mecon.desktop.service

import com.mecon.api.runtime.RuntimeScore
import com.mecon.api.storage.StorageScore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DocumentSafetyTest {
    @Test
    fun autosaveRoundTripsMetadataPayloadHashAndDeletion() = runBlocking {
        val root = Files.createTempDirectory("mecon-autosave-test").toFile()
        try {
            val original = File(root, "Original.mecon").apply { writeText("manual version") }
            val repository = AutosaveRepository { root }
            val score = StorageScore.create(StorageScore.CreationOptions(measureCount = 2))
            val document = MeconDocumentService.buildDocument(score, null, now = 1234L)
            val manualHash = repository.hash(original)
            assertEquals(64, manualHash?.length)

            repository.write(
                id = "recovery-1",
                document = document,
                fileName = original.name,
                originalFile = original,
                originalFileHash = manualHash,
                savedAt = 5678L,
            )

            val entry = repository.list().single()
            assertEquals(5678L, entry.savedAt)
            assertEquals(original.absolutePath, entry.originalPath)
            assertEquals(manualHash, entry.originalFileHash)
            val preview = repository.load(entry)
            assertEquals(score, preview.document.activeScore)
            assertEquals(score.id, preview.runtimeScore.id)

            repository.delete(entry)
            assertTrue(repository.list().isEmpty())
            assertFalse(entry.payloadFile.exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun dirtyStateTracksTheSavedHistoryFrameAcrossUndo() = runBlocking {
        val desktopScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val session = ScoreSession(desktopScope)
            val storage = StorageScore.create(StorageScore.CreationOptions(measureCount = 2))
            session.replaceDocument(storage, file = null, fileName = "Untitled.mecon")
            assertFalse(session.isModified)

            val firstEdit = assertNotNull(session.runtimeScore).copy(defaultTempo = 96f)
            session.applyRuntimeEdit(firstEdit)
            await { session.isModified }
            session.markCurrentStateSaved()
            assertFalse(session.isModified)

            val secondEdit = assertNotNull(session.runtimeScore).copy(defaultTempo = 108f)
            session.applyRuntimeEdit(secondEdit)
            await { session.isModified }
            session.undo()
            await { !session.isModified }
            assertEquals(96f, session.runtimeScore?.defaultTempo)
        } finally {
            desktopScope.cancel()
        }
    }

    @Test
    fun asynchronousEditFailureKeepsLastCompleteStateAndReportsRecovery() = runBlocking {
        val desktopScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val session = ScoreSession(desktopScope)
            val storage = StorageScore.create(StorageScore.CreationOptions(measureCount = 1))
            session.replaceDocument(storage, file = null, fileName = "Stable.mecon")
            val before = session.runtimeScore

            session.launchRecovering("测试编辑") { error("synthetic failure") }.join()

            assertTrue(session.analysisMessage.orEmpty().contains("已保留最近一次完整乐谱状态"))
            assertTrue(session.runtimeScore === before)
            assertFalse(session.isModified)
        } finally {
            desktopScope.cancel()
        }
    }

    private suspend fun await(predicate: () -> Boolean) {
        repeat(200) {
            if (predicate()) return
            delay(10)
        }
        error("Timed out waiting for document state")
    }
}
