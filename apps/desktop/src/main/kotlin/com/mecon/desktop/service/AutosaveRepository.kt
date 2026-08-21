package com.mecon.desktop.service

import com.mecon.api.runtime.RuntimeScore
import com.mecon.core.container.MeconBundleCodec
import com.mecon.core.container.MeconDocument
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Properties

/** Metadata kept beside an autosaved `.mecon` payload. */
data class AutosaveEntry(
    val id: String,
    val savedAt: Long,
    val fileName: String,
    val originalPath: String?,
    val originalFileHash: String?,
    val payloadFile: File,
)

data class AutosavePreview(
    val entry: AutosaveEntry,
    val document: MeconDocument,
    val runtimeScore: RuntimeScore,
)

/**
 * Crash-safe autosave store. Payload and metadata are first written to sibling temporary files and
 * then replaced atomically where the filesystem supports it. Frozen render geometry is deliberately
 * omitted: recovery only needs the authoritative score/module data and the desktop re-renders it.
 */
class AutosaveRepository(private val directory: () -> File) {

    suspend fun list(): List<AutosaveEntry> = withContext(Dispatchers.IO) {
        val root = directory()
        if (!root.isDirectory) return@withContext emptyList()
        root.listFiles { file -> file.isFile && file.extension == META_EXTENSION }
            .orEmpty()
            .mapNotNull(::readEntry)
            .sortedByDescending(AutosaveEntry::savedAt)
    }

    suspend fun write(
        id: String,
        document: MeconDocument,
        fileName: String,
        originalFile: File?,
        originalFileHash: String?,
        savedAt: Long = System.currentTimeMillis(),
    ): AutosaveEntry = withContext(Dispatchers.IO) {
        val root = directory().absoluteFile
        check(root.exists() || root.mkdirs()) { "Cannot create autosave directory: $root" }
        check(root.isDirectory) { "Autosave path is not a directory: $root" }

        val payload = File(root, "$id.$PAYLOAD_EXTENSION")
        val metadata = File(root, "$id.$META_EXTENSION")
        val payloadTemp = File(root, "$id.$PAYLOAD_EXTENSION.tmp")
        val metadataTemp = File(root, "$id.$META_EXTENSION.tmp")

        val entries = MeconBundleCodec.writeTextEntries(document)
            .mapValuesTo(LinkedHashMap()) { (_, value) -> value.encodeToByteArray() }
        MeconArchive.write(payloadTemp, entries)
        atomicReplace(payloadTemp, payload)

        val properties = Properties().apply {
            setProperty(KEY_VERSION, FORMAT_VERSION.toString())
            setProperty(KEY_ID, id)
            setProperty(KEY_SAVED_AT, savedAt.toString())
            setProperty(KEY_FILE_NAME, fileName)
            originalFile?.absolutePath?.let { setProperty(KEY_ORIGINAL_PATH, it) }
            originalFileHash?.let { setProperty(KEY_ORIGINAL_HASH, it) }
        }
        metadataTemp.outputStream().buffered().use { properties.store(it, "Mecon autosave") }
        atomicReplace(metadataTemp, metadata)

        AutosaveEntry(
            id = id,
            savedAt = savedAt,
            fileName = fileName,
            originalPath = originalFile?.absolutePath,
            originalFileHash = originalFileHash,
            payloadFile = payload,
        )
    }

    suspend fun load(entry: AutosaveEntry): AutosavePreview = withContext(Dispatchers.IO) {
        val document = MeconDocumentService().load(entry.payloadFile)
        val score = requireNotNull(document.activeScore) { "Autosave contains no active score" }
        AutosavePreview(entry, document, RuntimeScore.fromStorage(score))
    }

    suspend fun delete(entry: AutosaveEntry) = withContext(Dispatchers.IO) {
        Files.deleteIfExists(entry.payloadFile.toPath())
        Files.deleteIfExists(metadataFile(entry).toPath())
    }

    suspend fun hash(file: File?): String? = withContext(Dispatchers.IO) {
        file?.takeIf(File::isFile)?.let(::sha256)
    }

    private fun readEntry(metadata: File): AutosaveEntry? = runCatching {
        val properties = Properties().also { props ->
            metadata.inputStream().buffered().use(props::load)
        }
        if (properties.getProperty(KEY_VERSION)?.toIntOrNull() != FORMAT_VERSION) return null
        val id = properties.getProperty(KEY_ID)?.takeIf { it.isNotBlank() } ?: return null
        val payload = File(metadata.parentFile, "$id.$PAYLOAD_EXTENSION")
        if (!payload.isFile) return null
        AutosaveEntry(
            id = id,
            savedAt = properties.getProperty(KEY_SAVED_AT)?.toLongOrNull() ?: payload.lastModified(),
            fileName = properties.getProperty(KEY_FILE_NAME).orEmpty().ifBlank { "Untitled.mecon" },
            originalPath = properties.getProperty(KEY_ORIGINAL_PATH)?.takeIf(String::isNotBlank),
            originalFileHash = properties.getProperty(KEY_ORIGINAL_HASH)?.takeIf(String::isNotBlank),
            payloadFile = payload,
        )
    }.getOrNull()

    private fun metadataFile(entry: AutosaveEntry): File =
        File(entry.payloadFile.parentFile, "${entry.id}.$META_EXTENSION")

    private fun atomicReplace(source: File, target: File) {
        try {
            Files.move(
                source.toPath(), target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    companion object {
        private const val FORMAT_VERSION = 1
        private const val PAYLOAD_EXTENSION = "mecon"
        private const val META_EXTENSION = "autosave"
        private const val KEY_VERSION = "version"
        private const val KEY_ID = "id"
        private const val KEY_SAVED_AT = "savedAt"
        private const val KEY_FILE_NAME = "fileName"
        private const val KEY_ORIGINAL_PATH = "originalPath"
        private const val KEY_ORIGINAL_HASH = "originalFileHash"

        internal fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().buffered().use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            return digest.digest().joinToString("") { byte ->
                "%02x".format(byte.toInt() and 0xff)
            }
        }
    }
}
