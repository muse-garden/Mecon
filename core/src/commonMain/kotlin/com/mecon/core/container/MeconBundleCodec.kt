package com.mecon.core.container

import com.mecon.api.storage.StorageScore
import com.mecon.core.serializer.ScoreSerializer
import kotlinx.serialization.json.Json

/**
 * Constants and zip-path conventions for the `.mecon` container format.
 *
 * A `.mecon` file is a zip whose entries follow these fixed paths; the [MeconManifest] records the
 * actual paths it used, so readers resolve entries through the manifest rather than assuming a
 * layout — these helpers keep writers consistent.
 */
object MeconFormat {
    /** Container layout version. Bump only on an incompatible change to the archive shape. */
    const val FORMAT_VERSION: Int = 1

    /** Fallback engine tag when a caller doesn't supply a real build version. */
    const val ENGINE_VERSION: String = "mecon"

    /** New-format container extension (the old single-YAML `.mecon` fixtures are now `.mscore.yaml`). */
    const val EXTENSION: String = "mecon"

    const val MANIFEST_PATH: String = "manifest.json"

    fun scorePath(scoreId: String): String = "scores/$scoreId.json"
    fun geometryPath(scoreId: String): String = "geometry/$scoreId.json"
    fun modulePath(moduleId: String): String = "modules/$moduleId.json"
}

/**
 * JSON codec + entry assembly for the `.mecon` container's **text** parts: the manifest, the score
 * entries and the module entries. Frozen geometry (`geometry/<id>.json`) is *not* handled here — it
 * is a renderer type, so the desktop packager encodes it and adds it to the archive alongside these
 * text entries. This split is what keeps `core` free of any renderer dependency.
 *
 * The physical zip I/O (`Map<String, ByteArray>` ⇆ file) is a platform concern and lives in the app
 * layer; this object only turns a [MeconDocument] into named JSON strings and back.
 */
object MeconBundleCodec {

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = false
    }

    // ---- Individual parts -------------------------------------------------

    fun encodeManifest(manifest: MeconManifest): String =
        json.encodeToString(MeconManifest.serializer(), manifest)

    fun decodeManifest(text: String): MeconManifest =
        json.decodeFromString(MeconManifest.serializer(), text)

    fun encodeModule(entry: MeconModuleEntry): String =
        json.encodeToString(MeconModuleEntry.serializer(), entry)

    fun decodeModule(text: String): MeconModuleEntry =
        json.decodeFromString(MeconModuleEntry.serializer(), text)

    /** Scores reuse the shared [ScoreSerializer] JSON codec so a `.mecon` score entry is exactly a
     * standalone score JSON — the same bytes a `.json` export would produce. */
    fun encodeScore(score: StorageScore): String = ScoreSerializer.toJson(score, pretty = true)

    fun decodeScore(text: String): StorageScore = ScoreSerializer.fromJson(text)

    // ---- Whole-document assembly (text entries only) ----------------------

    /**
     * Serialize the manifest, every score and every module into a path→JSON map, exactly matching
     * the paths recorded in [MeconDocument.manifest]. The caller (platform packager) adds any
     * `geometry/<id>.json` entries and writes the whole map into the zip.
     *
     * Scores/modules are keyed by their manifest ref paths so the archive and manifest can never
     * disagree; a score present in [MeconDocument.scores] but absent from the manifest is ignored,
     * keeping the manifest authoritative.
     */
    fun writeTextEntries(document: MeconDocument): Map<String, String> {
        val entries = LinkedHashMap<String, String>()
        entries[MeconFormat.MANIFEST_PATH] = encodeManifest(document.manifest)

        val scoreById = document.scores.associateBy { it.id.value }
        for (ref in document.manifest.scores) {
            val score = scoreById[ref.id] ?: continue
            entries[ref.path] = encodeScore(score)
        }

        val moduleById = document.modules.associateBy { it.id }
        for (ref in document.manifest.modules) {
            val module = moduleById[ref.id] ?: continue
            entries[ref.path] = encodeModule(module)
        }
        return entries
    }

    /**
     * Rebuild a [MeconDocument] from all text entries of an archive (a path→JSON map). Geometry
     * entries, if present, are ignored: the desktop re-renders and a viewer reads them separately.
     *
     * @throws IllegalArgumentException if the manifest is missing or references an absent entry.
     */
    fun readDocument(entries: Map<String, String>): MeconDocument {
        val manifestText = entries[MeconFormat.MANIFEST_PATH]
            ?: throw IllegalArgumentException("Not a .mecon container: missing ${MeconFormat.MANIFEST_PATH}")
        val manifest = decodeManifest(manifestText)

        val scores = manifest.scores.map { ref ->
            val text = entries[ref.path]
                ?: throw IllegalArgumentException("Manifest references missing score entry: ${ref.path}")
            decodeScore(text)
        }
        val modules = manifest.modules.map { ref ->
            val text = entries[ref.path]
                ?: throw IllegalArgumentException("Manifest references missing module entry: ${ref.path}")
            decodeModule(text)
        }
        return MeconDocument(manifest = manifest, scores = scores, modules = modules)
    }
}
