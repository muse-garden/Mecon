package com.mecon.core.container

import com.mecon.api.storage.StorageScore

/**
 * The in-memory logical contents of a `.mecon` container: its [manifest], every [StorageScore] it
 * holds, and every pluggable [MeconModuleEntry].
 *
 * This is intentionally *not* `@Serializable` — a `.mecon` file is a zip of independently
 * serialized entries, not one object graph, and frozen geometry (a renderer type) is written /
 * read at the platform edge rather than carried here. See [MeconBundleCodec] for turning a document
 * into the archive's text entries and back, and the desktop packager for the zip + geometry glue.
 *
 * Loading only needs the manifest + scores + modules; geometry is re-derived by the desktop when it
 * renders, and read directly by a viewer. Keeping geometry out of this model is what lets the whole
 * document layer stay renderer-free.
 */
data class MeconDocument(
    val manifest: MeconManifest,
    val scores: List<StorageScore>,
    val modules: List<MeconModuleEntry> = emptyList(),
) {
    /** The score the workspace should focus on open: [MeconManifest.activeScoreId] or the first. */
    val activeScore: StorageScore?
        get() = manifest.activeScoreId
            ?.let { active -> scores.firstOrNull { it.id.value == active } }
            ?: scores.firstOrNull()

    fun module(id: String): MeconModuleEntry? = modules.firstOrNull { it.id == id }

    fun modulesOfType(type: String): List<MeconModuleEntry> = modules.filter { it.type == type }
}
