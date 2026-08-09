package com.mecon.desktop.ui.dialogs

import androidx.compose.runtime.*
import com.mecon.desktop.service.ScoreFileService
import java.io.File
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Result of a file dialog operation.
 */
sealed class FileDialogResult {
    data class Selected(val file: File) : FileDialogResult()
    data object Cancelled : FileDialogResult()
}

/**
 * State holder for file dialogs.
 */
class FileDialogState {
    var isOpen by mutableStateOf(false)
        private set

    var lastDirectory: File? by mutableStateOf(null)
        private set

    fun open() {
        isOpen = true
    }

    fun close(selectedDirectory: File? = null) {
        isOpen = false
        if (selectedDirectory != null) {
            lastDirectory = selectedDirectory
        }
    }
}

@Composable
fun rememberFileDialogState(): FileDialogState {
    return remember { FileDialogState() }
}

/**
 * Predefined file filter options for open/save dialogs.
 */
object FileFilters {
    /** Native Mecon container (.mecon) — the default save format. */
    val NATIVE_ONLY = FileFilterConfig(
        description = "Mecon Container",
        extensions = listOf(ScoreFileService.CONTAINER_EXTENSION)
    )

    /** Legacy single-score YAML text (.yaml, .yml, .mscore.yaml). */
    val YAML_TEXT = FileFilterConfig(
        description = "Mecon Score (YAML)",
        extensions = ScoreFileService.YAML_EXTENSIONS
    )

    /** MusicXML format only (.xml, .musicxml) */
    val MUSICXML_ONLY = FileFilterConfig(
        description = "MusicXML Files",
        extensions = ScoreFileService.MUSICXML_EXTENSIONS
    )

    /** Vector PDF export (.pdf). */
    val PDF_ONLY = FileFilterConfig(
        description = "PDF Document",
        extensions = listOf("pdf")
    )

    /** All supported score files */
    val ALL_SCORES = FileFilterConfig(
        description = "All Score Files",
        extensions = ScoreFileService.ALL_EXTENSIONS
    )

    /** Default filter for open dialogs - all supported files */
    val DEFAULT_OPEN = ALL_SCORES

    /** Default filter for save dialogs - native format */
    val DEFAULT_SAVE = NATIVE_ONLY
}

/**
 * Configuration for a file filter.
 */
data class FileFilterConfig(
    val description: String,
    val extensions: List<String>
) {
    fun toSwingFilter(): FileNameExtensionFilter {
        val extPattern = extensions.joinToString(", ") { "*.$it" }
        return FileNameExtensionFilter(
            "$description ($extPattern)",
            *extensions.toTypedArray()
        )
    }
}

/**
 * Show a file open dialog with multiple filter options.
 *
 * @param title Dialog title
 * @param filters List of file filters to offer (first is default)
 * @param initialDirectory Starting directory
 */
suspend fun showOpenDialog(
    title: String = "Open Score",
    filters: List<FileFilterConfig> = listOf(FileFilters.ALL_SCORES, FileFilters.NATIVE_ONLY, FileFilters.YAML_TEXT, FileFilters.MUSICXML_ONLY),
    initialDirectory: File? = null
): FileDialogResult = withContext(Dispatchers.IO) {
    val chooser = JFileChooser().apply {
        dialogTitle = title
        fileSelectionMode = JFileChooser.FILES_ONLY
        isAcceptAllFileFilterUsed = false

        // Add all filters
        for (filterConfig in filters) {
            addChoosableFileFilter(filterConfig.toSwingFilter())
        }

        // Set first filter as default
        filters.firstOrNull()?.let { fileFilter = it.toSwingFilter() }

        // Set initial directory
        initialDirectory?.let { currentDirectory = it }
    }

    val result = chooser.showOpenDialog(null)

    when (result) {
        JFileChooser.APPROVE_OPTION -> FileDialogResult.Selected(chooser.selectedFile)
        else -> FileDialogResult.Cancelled
    }
}

/**
 * Show a file open dialog with simple extension list.
 */
suspend fun showOpenDialogWithExtensions(
    title: String = "Open Score",
    extensions: List<String>,
    initialDirectory: File? = null
): FileDialogResult = showOpenDialog(
    title = title,
    filters = listOf(FileFilterConfig("Score Files", extensions)),
    initialDirectory = initialDirectory
)

/**
 * Show a file save dialog with multiple filter options.
 *
 * @param title Dialog title
 * @param filters List of file filters to offer (first is default, determines extension)
 * @param initialDirectory Starting directory
 * @param suggestedName Suggested file name
 */
suspend fun showSaveDialog(
    title: String = "Save Score",
    filters: List<FileFilterConfig> = listOf(FileFilters.NATIVE_ONLY, FileFilters.YAML_TEXT, FileFilters.MUSICXML_ONLY),
    initialDirectory: File? = null,
    suggestedName: String? = null
): FileDialogResult = withContext(Dispatchers.IO) {
    val chooser = JFileChooser().apply {
        dialogTitle = title
        fileSelectionMode = JFileChooser.FILES_ONLY
        isAcceptAllFileFilterUsed = false

        // Add all filters
        for (filterConfig in filters) {
            addChoosableFileFilter(filterConfig.toSwingFilter())
        }

        // Set first filter as default
        filters.firstOrNull()?.let { fileFilter = it.toSwingFilter() }

        // Set initial directory
        initialDirectory?.let { currentDirectory = it }

        // Set suggested file name
        suggestedName?.let { selectedFile = File(currentDirectory, it) }
    }

    val result = chooser.showSaveDialog(null)

    when (result) {
        JFileChooser.APPROVE_OPTION -> {
            var file = chooser.selectedFile

            // Determine valid extensions from selected filter
            val selectedSwingFilter = chooser.fileFilter as? FileNameExtensionFilter
            val validExtensions = selectedSwingFilter?.extensions?.toList()
                ?: filters.first().extensions

            // Add extension if not present
            if (!validExtensions.any { file.extension.equals(it, ignoreCase = true) }) {
                file = File(file.parentFile, "${file.name}.${validExtensions.first()}")
            }
            FileDialogResult.Selected(file)
        }
        else -> FileDialogResult.Cancelled
    }
}

/**
 * Show a file save dialog with simple extension list.
 */
suspend fun showSaveDialogWithExtensions(
    title: String = "Save Score",
    extensions: List<String>,
    initialDirectory: File? = null,
    suggestedName: String? = null
): FileDialogResult = showSaveDialog(
    title = title,
    filters = listOf(FileFilterConfig("Score Files", extensions)),
    initialDirectory = initialDirectory,
    suggestedName = suggestedName
)

/**
 * Show export dialog with MusicXML as primary option.
 */
suspend fun showExportMusicXmlDialog(
    title: String = "Export as MusicXML",
    initialDirectory: File? = null,
    suggestedName: String? = null
): FileDialogResult = showSaveDialog(
    title = title,
    filters = listOf(FileFilters.MUSICXML_ONLY),
    initialDirectory = initialDirectory,
    suggestedName = suggestedName
)

/**
 * Show export dialog for a vector PDF.
 */
suspend fun showExportPdfDialog(
    title: String = "Export as PDF",
    initialDirectory: File? = null,
    suggestedName: String? = null
): FileDialogResult = showSaveDialog(
    title = title,
    filters = listOf(FileFilters.PDF_ONLY),
    initialDirectory = initialDirectory,
    suggestedName = suggestedName
)

/**
 * Show import dialog for MusicXML files.
 */
suspend fun showImportMusicXmlDialog(
    title: String = "Import MusicXML",
    initialDirectory: File? = null
): FileDialogResult = showOpenDialog(
    title = title,
    filters = listOf(FileFilters.MUSICXML_ONLY),
    initialDirectory = initialDirectory
)
