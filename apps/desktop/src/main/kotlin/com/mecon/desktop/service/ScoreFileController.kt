package com.mecon.desktop.service

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.mecon.api.storage.StorageScore
import com.mecon.core.container.MeconDocument
import com.mecon.desktop.export.ScorePdfExporter
import com.mecon.desktop.ui.dialogs.FileDialogResult
import com.mecon.desktop.ui.dialogs.showExportMusicXmlDialog
import com.mecon.desktop.ui.dialogs.showExportPdfDialog
import com.mecon.desktop.ui.dialogs.showOpenDialog
import com.mecon.desktop.ui.dialogs.showSaveDialog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

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
    private val playback: PlaybackController
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

    /** Install a freshly built document (from the New Score dialog) as the current one. */
    fun newScore(storage: StorageScore) {
        documentLoadTracker.begin()
        scope.launch {
            runCatching {
                // A brand-new document has no source container: save will pack it as a fresh single-score .mecon.
                session.loadedContainer = null
                install(storage, file = null, fileName = "${storage.metadata.title}.mecon")
                onStandaloneDocumentOpened?.invoke()
            }.onFailure { error ->
                documentLoadTracker.cancel()
                loadError = error.message ?: "Failed to create score"
                error.printStackTrace()
            }
        }
    }

    /** Prompt for a file, load it (format auto-detected), and install it. */
    fun openFile() {
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
                    session.loadedContainer = document
                    install(active, result.file, result.file.name)
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
                session.loadedContainer = null
                install(storage, result.file, result.file.name)
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
        scope.launch {
            val explorationActive = isExplorationActive?.invoke() == true
            val practiceActive = isFreePracticeActive?.invoke() == true
            if (explorationActive && !practiceActive) {
                loadError = "Only the free-practice workspace can currently be saved."
                return@launch
            }
            val practiceSnapshot = activeFreePracticeSnapshot?.invoke()
            if (practiceActive && practiceSnapshot == null) {
                loadError = "Free-practice document is still preparing; try saving again."
                return@launch
            }
            // storageScoreForSave folds in the latest rendered slur / articulation geometry so the
            // file persists it (StorageScore.geometry); auto-laid-out scores capture it on first render.
            val scoreToSave = practiceSnapshot?.score ?: session.storageScoreForSave ?: return@launch
            val file: File = (
                practiceSnapshot?.let { activeFreePracticeFile?.invoke() }
                    ?: if (practiceSnapshot == null) session.currentFile else null
                )
                ?: run {
                    val result = showSaveDialog(
                        initialDirectory = lastDirectory,
                        suggestedName = if (practiceSnapshot != null) {
                            "Free Practice.mecon"
                        } else {
                            session.currentFileName
                        }
                    )
                    if (result !is FileDialogResult.Selected) return@launch
                    lastDirectory = result.file.parentFile
                    if (practiceSnapshot == null) {
                        session.markSavedAs(result.file, result.file.name)
                    }
                    result.file
                }
            persist(scoreToSave, file, practiceSnapshot)
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
    ) {
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
            fileService.saveContainer(document, file)
                .onSuccess {
                    if (practiceSnapshot != null) {
                        // Keep the dormant main-score session aligned with the module score. If the
                        // user later leaves Exploration and saves from the score surface, it must
                        // not replace the just-saved practice score with the pre-edit snapshot.
                        session.replaceDocument(scoreToSave, file, file.name)
                        onFreePracticeSaved?.invoke(document, file)
                    }
                    session.loadedContainer = document
                }
                .onFailure { loadError = "Save failed: ${it.message}" }
        } else {
            fileService.saveAuto(scoreToSave, file)
                .onFailure { loadError = "Save failed: ${it.message}" }
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
