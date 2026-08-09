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
import com.mecon.desktop.ui.exploration.initialWorkspace
import com.mecon.exploration.FreePracticeDocument
import com.mecon.exploration.FreePracticeDocumentCodec
import com.mecon.exploration.FreePracticeSettings
import com.mecon.exploration.KeyModeSpec
import com.mecon.exploration.KeySpec
import com.mecon.theory.writing.GrandStaffVoiceLayout
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MeconDocumentServiceTest {

    private fun demo(title: String): StorageScore =
        StorageScore.createDemo().let { it.copy(metadata = it.metadata.copy(title = title)) }

    /** The shared JSON codec doesn't stably round-trip the `currentTimeMillis()`-defaulted metadata
     * timestamps; canonicalise them so score equality reflects musical content, not save timing. */
    private fun StorageScore.canonicalTimestamps(): StorageScore =
        copy(metadata = metadata.copy(createdAt = 0L, modifiedAt = 0L))

    @Test
    fun archiveRoundTripsEntryBytes() {
        val entries = mapOf(
            "manifest.json" to """{"a":1}""".encodeToByteArray(),
            "scores/x.json" to "hello".encodeToByteArray(),
            "geometry/x.json" to ByteArray(3) { it.toByte() },
        )
        val file = File.createTempFile("mecon-archive", ".mecon").apply { deleteOnExit() }
        MeconArchive.write(file, entries)

        assertTrue(MeconArchive.looksLikeZip(file), "written container should sniff as a zip")
        val read = MeconArchive.read(file)
        assertEquals(entries.keys, read.keys)
        for ((k, v) in entries) assertTrue(v.contentEquals(read.getValue(k)), "entry $k bytes differ")
    }

    @Test
    fun buildDocumentPreservesSiblingScoresModulesAndWorkspace() {
        val a = demo("Edited")
        val b = demo("Sibling")
        val module = MeconModuleEntry(
            id = "exp-1",
            type = "exploration",
            payload = buildJsonObject { put("q", JsonPrimitive("ii-V-I")) },
        )
        val base = MeconDocument(
            manifest = MeconManifest(
                createdAt = 100L,
                scores = listOf(a, b).map { MeconScoreRef(it.id.value, it.metadata.title, MeconFormat.scorePath(it.id.value)) },
                modules = listOf(MeconModuleRef(module.id, module.type, MeconFormat.modulePath(module.id))),
                workspace = WorkspacePreferences(activeModuleId = "exp-1"),
            ),
            scores = listOf(a, b),
            modules = listOf(module),
        )

        val editedA = a.copy(metadata = a.metadata.copy(title = "Edited*"))
        val built = MeconDocumentService.buildDocument(active = editedA, base = base, now = 999L)

        // The edited score replaces its old self; the sibling is retained.
        assertEquals(listOf("Edited*", "Sibling"), built.scores.map { it.metadata.title })
        assertEquals(listOf(module), built.modules)
        assertEquals(WorkspacePreferences(activeModuleId = "exp-1"), built.manifest.workspace)
        assertEquals(editedA.id.value, built.manifest.activeScoreId)
        assertEquals(100L, built.manifest.createdAt, "createdAt carried from base")
        assertEquals(999L, built.manifest.modifiedAt)
        assertTrue(built.manifest.scores.all { it.geometryPath != null }, "every score gets a geometry ref")
    }

    @Test
    fun buildDocumentAtomicallyReplacesActiveFreePracticeScoreAndModule() {
        val original = demo("Practice")
        val sibling = demo("Sibling")
        val oldModule = MeconModuleEntry(
            id = "free-practice",
            type = "exploration.free-practice",
            scoreId = original.id.value,
            payload = JsonPrimitive("old"),
        )
        val unknownModule = MeconModuleEntry(
            id = "future",
            type = "future.module",
            payload = JsonPrimitive("keep"),
        )
        val base = MeconDocument(
            manifest = MeconManifest(
                scores = listOf(original, sibling).map {
                    MeconScoreRef(it.id.value, it.metadata.title, MeconFormat.scorePath(it.id.value))
                },
                modules = listOf(oldModule, unknownModule).map {
                    MeconModuleRef(it.id, it.type, MeconFormat.modulePath(it.id), it.scoreId)
                },
                workspace = WorkspacePreferences(activeModuleId = oldModule.id),
            ),
            scores = listOf(original, sibling),
            modules = listOf(oldModule, unknownModule),
        )
        val editedScore = original.copy(metadata = original.metadata.copy(title = "Practice*"))
        val editedModule = oldModule.copy(payload = JsonPrimitive("new"))

        val built = MeconDocumentService.buildDocument(
            active = editedScore,
            base = base,
            now = 999L,
            activeModule = editedModule,
        )

        assertEquals(listOf("Practice*", "Sibling"), built.scores.map { it.metadata.title })
        assertEquals(listOf(editedModule, unknownModule), built.modules)
        assertEquals(editedModule.id, built.manifest.workspace.activeModuleId)
        assertEquals(editedScore.id.value, built.manifest.activeScoreId)
        assertEquals(
            listOf(editedModule.id, unknownModule.id),
            built.manifest.modules.map { it.id },
        )
    }

    @Test
    fun saveWithoutFontThenLoadRoundTripsDocument() = runBlocking {
        // Font unavailable → geometry is skipped, but the container (manifest + scores + modules) must
        // still write and read back intact. Load never depends on geometry entries existing.
        val service = MeconDocumentService(loadFont = { null })
        val score = demo("Persisted")
        val document = MeconDocumentService.buildDocument(active = score, base = null, now = 1L)

        val file = File.createTempFile("mecon-doc", ".mecon").apply { deleteOnExit() }
        service.save(document, file)

        // No geometry entry was written despite the manifest declaring a geometryPath.
        val raw = MeconArchive.read(file)
        assertNull(raw[MeconFormat.geometryPath(score.id.value)], "no geometry without a font")
        assertTrue(raw.containsKey(MeconFormat.MANIFEST_PATH))

        val loaded = service.load(file)
        assertEquals(score.canonicalTimestamps(), loaded.scores.single().canonicalTimestamps())
        assertEquals(score.id.value, loaded.manifest.activeScoreId)
    }

    @Test
    fun freePracticeModuleAndScoreRoundTripAsActiveWorkspace() = runBlocking {
        val service = MeconDocumentService(loadFont = { null })
        val score = demo("Free Practice")
        val payload = FreePracticeDocument(
            settings = FreePracticeSettings(
                voiceCount = 4,
                initialKey = KeySpec(fifths = 2, mode = KeyModeSpec.MINOR),
                selectedPatternIds = listOf("deceptive", "cadence"),
            ),
            workspace = initialWorkspace(4),
        )
        val snapshot = FreePracticeFileSnapshot(payload, score)
        val document = MeconDocumentService.buildDocument(
            active = score,
            base = null,
            now = 123L,
            activeModule = snapshot.moduleEntry(),
        )
        val file = File.createTempFile("free-practice", ".mecon").apply { deleteOnExit() }

        service.save(document, file)
        val restored = service.load(file)
        val restoredSnapshot = restored.activeFreePracticeSnapshot()

        assertEquals(payload, restoredSnapshot?.document)
        assertEquals(score.id, restoredSnapshot?.score?.id)
        assertEquals(snapshot.moduleId, restored.manifest.workspace.activeModuleId)
        assertEquals(score.id.value, restored.manifest.activeScoreId)
    }

    @Test
    fun legacyFreePracticeMigratesPlaybackTempoAndKeepsAutoWritingOff() {
        val score = demo("Legacy Practice").copy(defaultTempo = 137.6f)
        val payload = FreePracticeDocument(
            settings = FreePracticeSettings(
                polyphonyLimit = 4,
                staffVoices = GrandStaffVoiceLayout.defaultFor(4),
            ),
            workspace = initialWorkspace(4),
        )
        val module = MeconModuleEntry(
            id = "free-practice",
            type = "exploration.free-practice",
            schemaVersion = 5,
            scoreId = score.id.value,
            payload = FreePracticeDocumentCodec.encode(payload),
        )
        val document = MeconDocument(
            manifest = MeconManifest(
                scores = listOf(
                    MeconScoreRef(score.id.value, score.metadata.title, MeconFormat.scorePath(score.id.value)),
                ),
                modules = listOf(
                    MeconModuleRef(module.id, module.type, MeconFormat.modulePath(module.id), score.id.value),
                ),
                workspace = WorkspacePreferences(activeModuleId = module.id),
            ),
            scores = listOf(score),
            modules = listOf(module),
        )

        val writing = requireNotNull(document.activeFreePracticeSnapshot()).document.settings.writing

        assertFalse(writing.autoWritingEnabled)
        assertEquals(138, writing.playbackTempoBpm)
    }

    @Test
    fun bundleCodecAndArchiveComposeIntoAReadableContainer() = runBlocking {
        // End-to-end at the byte level: codec text entries → zip → archive read → codec readDocument.
        val service = MeconDocumentService(loadFont = { null })
        val a = demo("One")
        val b = demo("Two")
        val doc = MeconDocument(
            manifest = MeconManifest(
                scores = listOf(a, b).map { MeconScoreRef(it.id.value, it.metadata.title, MeconFormat.scorePath(it.id.value)) },
                activeScoreId = b.id.value,
            ),
            scores = listOf(a, b),
        )
        val file = File.createTempFile("mecon-multi", ".mecon").apply { deleteOnExit() }
        service.save(doc, file)

        val reloaded = service.load(file)
        assertEquals(listOf("One", "Two"), reloaded.scores.map { it.metadata.title })
        assertEquals("Two", reloaded.activeScore?.metadata?.title)
        // Sanity: the text codec alone reconstructs the same document from the archive bytes.
        val viaCodec = MeconBundleCodec.readDocument(MeconArchive.read(file).mapValues { it.value.decodeToString() })
        assertEquals(reloaded.manifest, viaCodec.manifest)
    }
}
