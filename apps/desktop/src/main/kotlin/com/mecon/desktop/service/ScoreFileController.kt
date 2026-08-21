package com.mecon.desktop.service

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.mecon.api.storage.StorageScore
import com.mecon.api.runtime.toStorage
import com.mecon.core.container.MeconDocument
import com.mecon.desktop.AppSettings
import com.mecon.desktop.export.ScorePdfExporter
import com.mecon.desktop.ui.dialogs.FileDialogResult
import com.mecon.desktop.ui.dialogs.showExportMusicXmlDialog
import com.mecon.desktop.ui.dialogs.showExportPdfDialog
import com.mecon.desktop.ui.dialogs.showOpenDialog
import com.mecon.desktop.ui.dialogs.showSaveDialog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.UUID

/**
 * Orchestrates the toolbar's document-level file actions: new / open / save.
 *
 * Bridges the native file dialogs and [ScoreFileService] with the in-memory
 * [ScoreSession], stopping playback before any document swap. The remembered
 * directory is internal; only the last error is observable so the UI can show a
 * dismissable banner.
 */
class ScoreFileController(
    private val scope: CoroutineScope,
    private val fileService: ScoreFileService,
    private val session: ScoreSession,
    private val playback: PlaybackController,
    private val autosaves: AutosaveRepository = AutosaveRepository { AppSettings.autosaveDirectory },
    private val autosaveIntervalMillis: Long = DEFAULT_AUTOSAVE_INTERVAL_MILLIS,
) {
    /**
     * Optional free-practice integration installed by App. Keeping the file dialog and archive
     * orchestration here prevents the exploration surface from growing a second file stack.
     */
    var activeFreePracticeSnapshot: (() -> FreePracticeFileSnapshot?)? = null
    var activeFreePracticeFile: (() -> File?)? = null
    var isExplorationActive: (() -> Boolean)? = null
    var isFreePracticeActive: (() -> Boolean)? = null
    /** Return true when a module surface took over and the main-score ready-frame tracker can stop. */
    var onContainerOpened: ((MeconDocument, File) -> Boolean)? = null
    var onStandaloneDocumentOpened: (() -> Unit)? = null
    var onFreePracticeSaved: ((MeconDocument, File) -> Unit)? = null
    var onRecoveredFreePractice: ((MeconDocument, File?) -> Unit)? = null

    /** Recovery-center UI state. */
    var recoveryEntries by mutableStateOf<List<AutosaveEntry>>(emptyList())
        private set
    var selectedRecovery by mutableStateOf<AutosaveEntry?>(null)
        private set
    var recoveryPreview by mutableStateOf<AutosavePreview?>(null)
        private set
    var recoveryPreviewLoading by mutableStateOf(false)
        private set
    var showRecoveryStartupPrompt by mutableStateOf(false)
    var showRecoveryCenter by mutableStateOf(false)
    var pendingRecoveryDelete by mutableStateOf<AutosaveEntry?>(null)
    var pendingDivergedRecovery by mutableStateOf<AutosaveEntry?>(null)
    var autosaveDirectory by mutableStateOf(AppSettings.autosaveDirectory)
        private set

    /** Save/discard/cancel prompt shared by new, open and application close. */
    var showUnsavedChangesPrompt by mutableStateOf(false)
        private set
    private var pendingDocumentAction: (() -> Unit)? = null

    private var activeAutosaveId: String? = null
    private var lastManualFileHash: String? = null
    private var freePracticeModified by mutableStateOf(false)
    private var savedPracticeSnapshot: FreePracticeFileSnapshot? = null
    private var lastAutosavedMainFrame: Any? = null
    private var lastAutosavedPracticeSnapshot: FreePracticeFileSnapshot? = null
    private var autosaveJob: Job? = null
    private val fileOperationMutex = Mutex()

    val hasUnsavedChanges: Boolean
        get() = if (isFreePracticeActive?.invoke() == true) freePracticeModified else session.isModified

    /** Last load/save error, shown as a dismissable banner. null = no error. */
    var loadError by mutableStateOf<String?>(null)

    private val documentLoadTracker = DocumentLoadTracker()

    private val pdfExporter = ScorePdfExporter()

    /** True while an export is running, so the UI can disable the export menu. */
    var exporting by mutableStateOf(false)
        private set

    /**
     * Transient export status shown as a banner: "in progress" while writing, then a success line
     * that auto-dismisses. Failures go to [loadError] (the red bar) instead. null = no banner.
     */
    var exportMessage by mutableStateOf<String?>(null)

    private var exportMessageClearJob: Job? = null

    /** True from accepted open/new intent until the new score has rendered an interaction-ready frame. */
    val documentLoading: Boolean get() = documentLoadTracker.isLoading
    val loadingDocumentVersion: Long? get() = documentLoadTracker.targetDocumentVersion

    private var lastDirectory: File? = null

    /** Scan crash remnants and start the Word-style periodic, dirty-only autosave loop. */
    fun start() {
        if (autosaveJob != null) return
        autosaveJob = scope.launch {
            refreshRecoveryEntries(showStartupPrompt = true)
            while (true) {
                delay(autosaveIntervalMillis)
                runCatching { autosaveIfNeeded() }
                    .onFailure { error ->
                        if (error is CancellationException) throw error
                        // Autosave is intentionally silent; a failure must not take down editing.
                        error.printStackTrace()
                    }
            }
        }
    }

    /** New is a two-stage action: protect the existing document before opening its setup dialog. */
    fun requestNewScore(openNewScoreDialog: () -> Unit) =
        requestDocumentReplacement(openNewScoreDialog)

    /** Window close hook. A normal exit removes this document's transient recovery file. */
    fun requestExit(exitApplication: () -> Unit) = requestDocumentReplacement {
        scope.launch {
            fileOperationMutex.withLock { deleteActiveAutosave() }
            exitApplication()
        }
    }

    fun cancelPendingDocumentAction() {
        pendingDocumentAction = null
        showUnsavedChangesPrompt = false
    }

    fun discardAndContinue() {
        val action = pendingDocumentAction ?: return
        pendingDocumentAction = null
        showUnsavedChangesPrompt = false
        action()
    }

    fun saveAndContinue() {
        val action = pendingDocumentAction ?: return
        scope.launch {
            if (saveCurrent()) {
                pendingDocumentAction = null
                showUnsavedChangesPrompt = false
                action()
            }
        }
    }

    fun noteFreePracticeChanged(snapshot: FreePracticeFileSnapshot?) {
        if (snapshot == null) return
        freePracticeModified = snapshot != savedPracticeSnapshot
    }

    fun markFreePracticeOpened(snapshot: FreePracticeFileSnapshot?) {
        freePracticeModified = false
        savedPracticeSnapshot = snapshot
        lastAutosavedPracticeSnapshot = snapshot
        // Normal open has already hashed the source file before invoking the platform callback.
    }

    fun openRecoveryCenter() {
        showRecoveryStartupPrompt = false
        showRecoveryCenter = true
        scope.launch { refreshRecoveryEntries() }
    }

    fun selectRecovery(entry: AutosaveEntry) {
        selectedRecovery = entry
        recoveryPreview = null
        recoveryPreviewLoading = true
        scope.launch {
            runCatching { autosaves.load(entry) }
                .onSuccess { preview ->
                    if (selectedRecovery?.id == entry.id) recoveryPreview = preview
                }
                .onFailure { loadError = "无法读取自动保存文件：${it.message}" }
            if (selectedRecovery?.id == entry.id) recoveryPreviewLoading = false
        }
    }

    fun requestDeleteRecovery(entry: AutosaveEntry) {
        pendingRecoveryDelete = entry
    }

    fun confirmDeleteRecovery() {
        val entry = pendingRecoveryDelete ?: return
        pendingRecoveryDelete = null
        scope.launch {
            runCatching { fileOperationMutex.withLock { autosaves.delete(entry) } }
                .onSuccess {
                    if (activeAutosaveId == entry.id) activeAutosaveId = null
                    refreshRecoveryEntries()
                }
                .onFailure { loadError = "删除自动保存文件失败：${it.message}" }
        }
    }

    fun requestRestoreRecovery(entry: AutosaveEntry) {
        scope.launch {
            val currentHash = autosaves.hash(entry.originalPath?.let(::File))
            val diverged = entry.originalFileHash != null && currentHash != null &&
                currentHash != entry.originalFileHash
            if (diverged) pendingDivergedRecovery = entry else queueRecoveryRestore(entry)
        }
    }

    fun confirmDivergedRecovery() {
        val entry = pendingDivergedRecovery ?: return
        pendingDivergedRecovery = null
        queueRecoveryRestore(entry)
    }

    fun changeAutosaveDirectory(directory: File) {
        scope.launch {
            // The active entry belongs to this normal session, so clean it in the old directory
            // before switching future writes. Older crash remnants remain user-managed.
            fileOperationMutex.withLock { deleteActiveAutosave() }
            AppSettings.autosaveDirectory = directory
            autosaveDirectory = AppSettings.autosaveDirectory
            lastAutosavedMainFrame = null
            lastAutosavedPracticeSnapshot = null
            refreshRecoveryEntries()
        }
    }

    /** Best-effort last-chance snapshot used by the process-wide uncaught-exception hook. */
    fun emergencyAutosaveBlocking() {
        if (!hasUnsavedChanges) return
        if (!fileOperationMutex.tryLock()) return
        try {
            runBlocking(Dispatchers.IO) { runCatching { writeAutosaveLocked(force = true) } }
        } finally {
            fileOperationMutex.unlock()
        }
    }

    private fun requestDocumentReplacement(action: () -> Unit) {
        if (hasUnsavedChanges) {
            pendingDocumentAction = action
            showUnsavedChangesPrompt = true
        } else {
            action()
        }
    }

    private fun queueRecoveryRestore(entry: AutosaveEntry) = requestDocumentReplacement {
        scope.launch { restoreRecovery(entry) }
    }

    private suspend fun restoreRecovery(entry: AutosaveEntry) {
        documentLoadTracker.begin()
        try {
            val recovered = autosaves.load(entry)
            val originalFile = entry.originalPath?.let(::File)
            fileOperationMutex.withLock {
                session.loadedContainer = recovered.document
                install(
                    storage = recovered.document.activeScore
                        ?: error("Autosave contains no active score"),
                    file = originalFile,
                    fileName = entry.fileName,
                )
                if (activeAutosaveId != entry.id) deleteActiveAutosave()
            }
            session.markRecoveredAsModified()
            activeAutosaveId = entry.id
            lastManualFileHash = entry.originalFileHash
            lastAutosavedMainFrame = session.state?.runtimeScore
            val practice = recovered.document.activeFreePracticeSnapshot()
            if (practice != null) {
                freePracticeModified = true
                savedPracticeSnapshot = null
                lastAutosavedPracticeSnapshot = practice
                onRecoveredFreePractice?.invoke(recovered.document, originalFile)
                documentLoadTracker.cancel()
            } else {
                freePracticeModified = false
                savedPracticeSnapshot = null
                onStandaloneDocumentOpened?.invoke()
            }
            showRecoveryCenter = false
            showRecoveryStartupPrompt = false
            showTransientExportMessage("已恢复 ${entry.fileName}；请手动保存以确认恢复结果")
        } catch (cancellation: CancellationException) {
            documentLoadTracker.cancel()
            throw cancellation
        } catch (error: Throwable) {
            documentLoadTracker.cancel()
            loadError = "恢复自动保存文件失败：${error.message}"
            error.printStackTrace()
        }
    }

    private suspend fun autosaveIfNeeded() {
        if (!hasUnsavedChanges) return
        writeAutosave(force = false)
    }

    private suspend fun writeAutosave(force: Boolean) = fileOperationMutex.withLock {
        writeAutosaveLocked(force)
    }

    private suspend fun writeAutosaveLocked(force: Boolean) {
        val practice = activeFreePracticeSnapshot?.invoke()
        val practiceActive = isFreePracticeActive?.invoke() == true
        // A pending shared workspace commit has no atomic snapshot yet. Keep the preceding autosave
        // instead of accidentally serializing the dormant main-score session under its file name.
        if (practiceActive && practice == null) return
        val mainFrame = session.state?.runtimeScore
        if (!force) {
            if (practiceActive && practice != null && practice === lastAutosavedPracticeSnapshot) return
            if (!practiceActive && mainFrame === lastAutosavedMainFrame) return
        }
        val score = practice?.score ?: withContext(Dispatchers.Default) {
            mainFrame
                ?.copy(geometry = session.lastRenderedGeometry ?: mainFrame.geometry)
                ?.toStorage()
        } ?: return
        val originalFile = if (practice != null) activeFreePracticeFile?.invoke() else session.currentFile
        val document = MeconDocumentService.buildDocument(
            active = score,
            base = session.loadedContainer,
            now = System.currentTimeMillis(),
            activeModule = practice?.moduleEntry(),
        )
        val id = activeAutosaveId ?: UUID.randomUUID().toString().also { activeAutosaveId = it }
        autosaves.write(
            id = id,
            document = document,
            fileName = originalFile?.name ?: if (practice != null) "Free Practice.mecon"
                else session.currentFileName,
            originalFile = originalFile,
            originalFileHash = lastManualFileHash,
        )
        lastAutosavedMainFrame = mainFrame
        lastAutosavedPracticeSnapshot = practice
        refreshRecoveryEntries()
    }

    private suspend fun refreshRecoveryEntries(showStartupPrompt: Boolean = false) {
        recoveryEntries = autosaves.list()
        val selectedId = selectedRecovery?.id
        selectedRecovery = recoveryEntries.firstOrNull { it.id == selectedId }
        if (selectedRecovery == null) recoveryPreview = null
        if (showStartupPrompt && recoveryEntries.isNotEmpty()) showRecoveryStartupPrompt = true
    }

    private suspend fun deleteActiveAutosave() {
        val id = activeAutosaveId ?: return
        val entry = recoveryEntries.firstOrNull { it.id == id }
            ?: autosaves.list().firstOrNull { it.id == id }
        if (entry != null) autosaves.delete(entry)
        activeAutosaveId = null
        lastAutosavedMainFrame = null
        lastAutosavedPracticeSnapshot = null
        refreshRecoveryEntries()
    }

    private fun resetAutosaveTracking(manualHash: String?) {
        activeAutosaveId = null
        lastManualFileHash = manualHash
        freePracticeModified = false
        savedPracticeSnapshot = null
        lastAutosavedMainFrame = session.state?.runtimeScore
        lastAutosavedPracticeSnapshot = null
    }

    /** Install a freshly built document (from the New Score dialog) as the current one. */
    fun newScore(storage: StorageScore) {
        documentLoadTracker.begin()
        scope.launch {
            runCatching {
                // A brand-new document has no source container: save will pack it as a fresh single-score .mecon.
                fileOperationMutex.withLock {
                    session.loadedContainer = null
                    install(storage, file = null, fileName = "${storage.metadata.title}.mecon")
                    deleteActiveAutosave()
                    session.markRecoveredAsModified()
                    resetAutosaveTracking(manualHash = null)
                }
                onStandaloneDocumentOpened?.invoke()
            }.onFailure { error ->
                documentLoadTracker.cancel()
                loadError = error.message ?: "Failed to create score"
                error.printStackTrace()
            }
        }
    }

    /** Prompt for a file, load it (format auto-detected), and install it. */
    fun openFile() = requestDocumentReplacement(::openFileNow)

    private fun openFileNow() {
        scope.launch {
            val result = showOpenDialog(initialDirectory = lastDirectory)
            if (result !is FileDialogResult.Selected) return@launch
            lastDirectory = result.file.parentFile
            documentLoadTracker.begin()

            if (fileService.isContainerFile(result.file)) {
                val document = fileService.loadContainer(result.file).getOrElse { error ->
                    documentLoadTracker.cancel()
                    loadError = error.message ?: "Failed to load file"
                    error.printStackTrace()
                    return@launch
                }
                val active = document.activeScore
                if (active == null) {
                    documentLoadTracker.cancel()
                    loadError = "Container has no scores"
                    return@launch
                }
                runCatching {
                    fileOperationMutex.withLock {
                        session.loadedContainer = document
                        install(active, result.file, result.file.name)
                        deleteActiveAutosave()
                        resetAutosaveTracking(autosaves.hash(result.file))
                    }
                    if (onContainerOpened?.invoke(document, result.file) == true) {
                        documentLoadTracker.cancel()
                    }
                }.onFailure { error ->
                    documentLoadTracker.cancel()
                    loadError = error.message ?: "Failed to load file"
                    error.printStackTrace()
                }
                return@launch
            }

            val storage = fileService.loadAuto(result.file).getOrElse { error ->
                documentLoadTracker.cancel()
                loadError = error.message ?: "Failed to load file"
                error.printStackTrace()
                return@launch
            }
            runCatching {
                // Imported single-score text/MusicXML: no source container to preserve on save.
                fileOperationMutex.withLock {
                    session.loadedContainer = null
                    install(storage, result.file, result.file.name)
                    deleteActiveAutosave()
                    resetAutosaveTracking(autosaves.hash(result.file))
                }
                onStandaloneDocumentOpened?.invoke()
            }.onFailure { error ->
                documentLoadTracker.cancel()
                loadError = error.message ?: "Failed to load file"
                error.printStackTrace()
            }
        }
    }

    /** Save to the current file, or prompt for a location when the document is untitled. */
    fun saveFile() {
        scope.launch { saveCurrent() }
    }

    private suspend fun saveCurrent(): Boolean {
        val explorationActive = isExplorationActive?.invoke() == true
        val practiceActive = isFreePracticeActive?.invoke() == true
        if (explorationActive && !practiceActive) {
            loadError = "Only the free-practice workspace can currently be saved."
            return false
        }
        val practiceSnapshot = activeFreePracticeSnapshot?.invoke()
        if (practiceActive && practiceSnapshot == null) {
            loadError = "Free-practice document is still preparing; try saving again."
            return false
        }
        // Fold the most recent stable geometry into manual saves. Autosaves use the same snapshot.
        val runtimeFrameToSave = if (practiceSnapshot == null) session.state?.runtimeScore else null
        val renderedGeometry = session.lastRenderedGeometry
        val scoreToSave = practiceSnapshot?.score ?: withContext(Dispatchers.Default) {
            runtimeFrameToSave
                ?.copy(geometry = renderedGeometry ?: runtimeFrameToSave.geometry)
                ?.toStorage()
        } ?: return false
        val existingFile = practiceSnapshot?.let { activeFreePracticeFile?.invoke() }
            ?: if (practiceSnapshot == null) session.currentFile else null
        val file = existingFile ?: run {
            val result = showSaveDialog(
                initialDirectory = lastDirectory,
                suggestedName = if (practiceSnapshot != null) "Free Practice.mecon"
                else session.currentFileName,
            )
            if (result !is FileDialogResult.Selected) return false
            lastDirectory = result.file.parentFile
            result.file
        }

        return try {
            fileOperationMutex.withLock {
                val saved = persist(scoreToSave, file, practiceSnapshot)
                if (!saved) return@withLock false
                lastManualFileHash = autosaves.hash(file)
                if (practiceSnapshot == null) {
                    session.markRuntimeStateSaved(runtimeFrameToSave, file, file.name)
                } else {
                    savedPracticeSnapshot = practiceSnapshot
                    lastAutosavedPracticeSnapshot = practiceSnapshot
                    freePracticeModified = activeFreePracticeSnapshot?.invoke() != practiceSnapshot
                }
                deleteActiveAutosave()
                loadError = null
                true
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            loadError = "Save failed: ${error.message}"
            error.printStackTrace()
            false
        }
    }

    /**
     * Export the current score to a vector PDF. Prompts for a location (defaulting the name to the
     * document's, with a `.pdf` extension) and engraves via [ScorePdfExporter], which replays the
     * score's frozen geometry — one page per surface. Uses [ScoreSession.storageScoreForSave] so the
     * export reflects the latest manual slur / articulation geometry, exactly like a save.
     */
    fun exportPdf() = runExport(
        format = "PDF",
        suggestedExtension = "pdf",
        pickFile = { showExportPdfDialog(initialDirectory = lastDirectory, suggestedName = it) },
    ) { score, file -> pdfExporter.export(score, file) }

    /** Export the current score as a MusicXML interchange file, with the same progress banners. */
    fun exportMusicXml() = runExport(
        format = "MusicXML",
        suggestedExtension = "musicxml",
        pickFile = { showExportMusicXmlDialog(initialDirectory = lastDirectory, suggestedName = it) },
    ) { score, file -> fileService.saveMusicXml(score, file).getOrThrow() }

    /**
     * Shared export flow: pick a destination, then run [write] off the UI thread while showing an
     * "exporting…" banner, resolving to a success banner (auto-dismissed) or the [loadError] bar on
     * failure. [format] labels the banners; [suggestedExtension] seeds the save-dialog file name.
     */
    private fun runExport(
        format: String,
        suggestedExtension: String,
        pickFile: suspend (suggestedName: String) -> FileDialogResult,
        write: suspend (StorageScore, File) -> Unit,
    ) {
        if (exporting) return
        scope.launch {
            val score = session.storageScoreForSave ?: return@launch
            val baseName = session.currentFileName.substringBeforeLast('.', session.currentFileName)
            val result = pickFile("$baseName.$suggestedExtension")
            if (result !is FileDialogResult.Selected) return@launch
            lastDirectory = result.file.parentFile

            exporting = true
            exportMessageClearJob?.cancel()
            exportMessage = "正在导出 $format…"
            runCatching { write(score, result.file) }
                .onSuccess {
                    loadError = null
                    showTransientExportMessage("已导出 $format：${result.file.name}")
                }
                .onFailure { error ->
                    exportMessage = null
                    loadError = "$format 导出失败：${error.message}"
                    error.printStackTrace()
                }
            exporting = false
        }
    }

    /** Show [message] as the export banner and auto-clear it after a few seconds. */
    private fun showTransientExportMessage(message: String) {
        exportMessage = message
        exportMessageClearJob?.cancel()
        exportMessageClearJob = scope.launch {
            delay(4000)
            if (exportMessage == message) exportMessage = null
        }
    }

    /**
     * Write [scoreToSave] to [file]. A `.mecon` target is packed as a full container (re-packing any
     * loaded modules / sibling scores and rendering frozen geometry, see [MeconDocumentService]);
     * every other extension takes the legacy single-score text/MusicXML path.
     */
    private suspend fun persist(
        scoreToSave: StorageScore,
        file: File,
        practiceSnapshot: FreePracticeFileSnapshot? = null,
    ): Boolean {
        if (fileService.isContainerFile(file)) {
            val base = if (
                practiceSnapshot != null && activeFreePracticeFile?.invoke() == null
            ) {
                null
            } else {
                session.loadedContainer
            }
            val document = MeconDocumentService.buildDocument(
                active = scoreToSave,
                base = base,
                now = System.currentTimeMillis(),
                activeModule = practiceSnapshot?.moduleEntry(),
            )
            val saveResult = withContext(Dispatchers.IO) {
                fileService.saveContainer(document, file)
            }
            return saveResult
                .fold(
                onSuccess = {
                    if (practiceSnapshot != null) {
                        // Keep the dormant main-score session aligned with the module score. If the
                        // user later leaves Exploration and saves from the score surface, it must
                        // not replace the just-saved practice score with the pre-edit snapshot.
                        session.replaceDocument(scoreToSave, file, file.name)
                        onFreePracticeSaved?.invoke(document, file)
                    }
                    session.loadedContainer = document
                    true
                },
                onFailure = {
                    loadError = "Save failed: ${it.message}"
                    false
                },
            )
        } else {
            return fileService.saveAuto(scoreToSave, file).fold(
                onSuccess = { true },
                onFailure = {
                    loadError = "Save failed: ${it.message}"
                    false
                },
            )
        }
    }

    /** Stop playback, then swap the in-memory document. */
    private suspend fun install(storage: StorageScore, file: File?, fileName: String) {
        playback.stopAndUnload()
        session.replaceDocument(storage, file, fileName)
        documentLoadTracker.documentInstalled(session.documentVersion)
        playback.preloadScore(session.runtimeScore)
        loadError = null
    }

    /** Called by the main score view after the installed document's first fully cached frame draws. */
    fun onDocumentInteractive(documentVersion: Long) {
        documentLoadTracker.frameDisplayed(documentVersion)
    }

    companion object {
        /** Word-like periodic safety net: only dirty documents are written. */
        const val DEFAULT_AUTOSAVE_INTERVAL_MILLIS = 2 * 60 * 1000L
    }
}

/** Keeps stale/old frames from completing a newer asynchronous document load. */
internal class DocumentLoadTracker {
    var isLoading by mutableStateOf(false)
        private set

    var targetDocumentVersion by mutableStateOf<Long?>(null)
        private set

    fun begin() {
        targetDocumentVersion = null
        isLoading = true
    }

    fun documentInstalled(documentVersion: Long) {
        if (isLoading) targetDocumentVersion = documentVersion
    }

    fun frameDisplayed(documentVersion: Long) {
        if (isLoading && targetDocumentVersion == documentVersion) {
            targetDocumentVersion = null
            isLoading = false
        }
    }

    fun cancel() {
        targetDocumentVersion = null
        isLoading = false
    }
}
