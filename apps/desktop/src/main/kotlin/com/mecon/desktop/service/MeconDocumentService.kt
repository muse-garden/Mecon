package com.mecon.desktop.service

import com.mecon.api.storage.StorageScore
import com.mecon.core.container.MeconBundleCodec
import com.mecon.core.container.MeconDocument
import com.mecon.core.container.MeconFormat
import com.mecon.core.container.MeconManifest
import com.mecon.core.container.MeconModuleEntry
import com.mecon.core.container.MeconModuleRef
import com.mecon.core.container.MeconScoreRef
import com.mecon.core.container.WorkspacePreferences
import com.mecon.renderer.frozen.FrozenScoreCodec
import com.mecon.renderer.frozen.FrozenScoreProjector
import com.mecon.renderer.render.RenderEngine
import com.mecon.renderer.smufl.BravuraFont
import com.mecon.renderer.smufl.BravuraFontLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Reads and writes the `.mecon` container: a zip of a [MeconManifest], one score JSON per score,
 * pluggable module JSONs, and one **frozen geometry** JSON per score.
 *
 * The frozen geometry is produced here by actually running the [RenderEngine] over each score at
 * save time (see [FrozenScoreProjector]); it is what lets a lightweight web viewer replay the
 * engraving without the layout engine (`docs/multiplatform-porting.md` §4.1). Loading does **not**
 * read geometry back — the desktop re-renders — so [MeconBundleCodec.readDocument] ignores it.
 *
 * The physical zip lives in [MeconArchive]; the platform-agnostic manifest/score/module codec lives
 * in `core`. This service is the desktop seam that joins them and adds the render step.
 */
class MeconDocumentService(
    private val loadFont: suspend () -> BravuraFont? = { BravuraFontLoader().load() },
) {

    /** Read a `.mecon` container into a [MeconDocument] (manifest + scores + modules). */
    suspend fun load(file: File): MeconDocument = withContext(Dispatchers.IO) {
        val entries = MeconArchive.read(file)
        val textEntries = entries.mapValues { it.value.decodeToString() }
        MeconBundleCodec.readDocument(textEntries)
    }

    /**
     * Write [document] to [file] as a `.mecon` zip. Text entries (manifest / scores / modules) come
     * from the shared codec; a `geometry/<id>.json` entry is added for every score whose manifest
     * ref declares a [MeconScoreRef.geometryPath], by rendering that score here.
     *
     * If the Bravura font can't be loaded, the file is still written — just without geometry — so a
     * save never fails solely because geometry couldn't be produced.
     */
    suspend fun save(document: MeconDocument, file: File) {
        val entries = LinkedHashMap<String, ByteArray>()
        MeconBundleCodec.writeTextEntries(document).forEach { (path, text) ->
            entries[path] = text.encodeToByteArray()
        }

        val font = loadFont()
        if (font != null) {
            val fingerprint = FrozenScoreRenderer.fingerprint(font)
            val scoreById = document.scores.associateBy { it.id.value }
            for (ref in document.manifest.scores) {
                val geometryPath = ref.geometryPath ?: continue
                val score = scoreById[ref.id] ?: continue
                val bundle = withContext(Dispatchers.Default) {
                    FrozenScoreRenderer.render(score, font, fingerprint)
                }
                entries[geometryPath] = FrozenScoreCodec.encode(bundle).encodeToByteArray()
            }
        }

        withContext(Dispatchers.IO) { MeconArchive.write(file, entries) }
    }

    companion object {
        /**
         * Build a container document around a single edited [active] score, preserving the rest of a
         * previously loaded container ([base]) — its other scores, its modules and its workspace
         * preferences — so a load→edit→save of a multi-score / module-bearing `.mecon` never drops
         * the parts the desktop isn't currently editing.
         *
         * Every score is given a `geometry/<id>.json` ref so [save] renders and stores geometry for
         * all of them. [now] is epoch millis for `modifiedAt`; `createdAt` is carried from [base].
         */
        fun buildDocument(
            active: StorageScore,
            base: MeconDocument?,
            now: Long,
            activeModule: MeconModuleEntry? = null,
        ): MeconDocument {
            val activeId = active.id.value
            val scores = when {
                base == null -> listOf(active)
                base.scores.any { it.id.value == activeId } ->
                    base.scores.map { if (it.id.value == activeId) active else it }
                else -> base.scores + active
            }
            val scoreRefs = scores.map { score ->
                MeconScoreRef(
                    id = score.id.value,
                    title = score.metadata.title,
                    path = MeconFormat.scorePath(score.id.value),
                    geometryPath = MeconFormat.geometryPath(score.id.value),
                )
            }
            val modules = when {
                activeModule == null -> base?.modules.orEmpty()
                base?.modules?.any { it.id == activeModule.id } == true ->
                    base.modules.map { if (it.id == activeModule.id) activeModule else it }
                else -> base?.modules.orEmpty() + activeModule
            }
            val baseModuleRefs = base?.manifest?.modules.orEmpty().associateBy { it.id }
            val moduleRefs = modules.map { module ->
                MeconModuleRef(
                    id = module.id,
                    type = module.type,
                    path = baseModuleRefs[module.id]?.path ?: MeconFormat.modulePath(module.id),
                    scoreId = module.scoreId,
                )
            }
            val baseWorkspace = base?.manifest?.workspace ?: WorkspacePreferences()
            val manifest = MeconManifest(
                formatVersion = MeconFormat.FORMAT_VERSION,
                engineVersion = MeconFormat.ENGINE_VERSION,
                createdAt = base?.manifest?.createdAt?.takeIf { it != 0L } ?: now,
                modifiedAt = now,
                scores = scoreRefs,
                modules = moduleRefs,
                activeScoreId = activeId,
                workspace = baseWorkspace.copy(
                    activeModuleId = activeModule?.id ?: baseWorkspace.activeModuleId,
                ),
            )
            return MeconDocument(
                manifest = manifest,
                scores = scores,
                modules = modules,
            )
        }
    }
}
