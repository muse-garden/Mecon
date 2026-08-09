package com.mecon.desktop.service

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Low-level zip I/O for the `.mecon` container: a flat path→bytes map ⇆ file. Format-agnostic — it
 * knows nothing of manifests, scores or geometry; the container semantics live in
 * [com.mecon.core.container.MeconBundleCodec] and [MeconDocumentService].
 *
 * This is the platform (JVM) edge the porting doc reserves for container/file I/O: a future web /
 * native port supplies its own archive reader and reuses the shared codec above it.
 */
object MeconArchive {

    /** Write every entry as a deflated zip file, overwriting [file]. Entry iteration order is preserved. */
    fun write(file: File, entries: Map<String, ByteArray>) {
        ZipOutputStream(BufferedOutputStream(file.outputStream())).use { zos ->
            for ((path, bytes) in entries) {
                zos.putNextEntry(ZipEntry(path))
                zos.write(bytes)
                zos.closeEntry()
            }
        }
    }

    /** Read all (non-directory) entries into a path→bytes map. */
    fun read(file: File): Map<String, ByteArray> {
        val out = LinkedHashMap<String, ByteArray>()
        ZipInputStream(BufferedInputStream(file.inputStream())).use { zis ->
            var entry: ZipEntry? = zis.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) out[entry.name] = zis.readBytes()
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
        return out
    }

    /**
     * Cheap sniff for the zip local-file-header magic (`PK\x03\x04`), so an ambiguous / mislabelled
     * file can be routed to the container path vs. the legacy YAML/JSON text path by content.
     */
    fun looksLikeZip(file: File): Boolean {
        if (!file.isFile || file.length() < 4) return false
        return file.inputStream().use { input ->
            val header = ByteArray(4)
            if (input.read(header) < 4) return false
            header[0] == 0x50.toByte() && header[1] == 0x4B.toByte() &&
                header[2] == 0x03.toByte() && header[3] == 0x04.toByte()
        }
    }
}
