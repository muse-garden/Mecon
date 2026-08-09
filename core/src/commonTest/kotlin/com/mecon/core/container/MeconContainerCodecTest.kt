package com.mecon.core.container

import com.mecon.api.storage.StorageScore
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Contract tests for the `.mecon` container's text layer: multiple scores, pluggable modules,
 * workspace preferences and forward-compatibility all survive a write→read cycle through
 * [MeconBundleCodec] (the platform zip glue is exercised separately in the desktop tests).
 */
class MeconContainerCodecTest {

    private fun demoScore(title: String): StorageScore =
        StorageScore.createDemo().let { it.copy(metadata = it.metadata.copy(title = title)) }

    private fun documentWith(scores: List<StorageScore>, modules: List<MeconModuleEntry>): MeconDocument {
        val manifest = MeconManifest(
            createdAt = 111L,
            modifiedAt = 222L,
            scores = scores.map {
                MeconScoreRef(
                    id = it.id.value,
                    title = it.metadata.title,
                    path = MeconFormat.scorePath(it.id.value),
                    geometryPath = MeconFormat.geometryPath(it.id.value),
                )
            },
            modules = modules.map {
                MeconModuleRef(id = it.id, type = it.type, path = MeconFormat.modulePath(it.id), scoreId = it.scoreId)
            },
            activeScoreId = scores.getOrNull(1)?.id?.value,
            workspace = WorkspacePreferences(activeModuleId = modules.firstOrNull()?.id),
        )
        return MeconDocument(manifest, scores, modules)
    }

    @Test
    fun multiScoreAndModulesRoundTrip() {
        val a = demoScore("First")
        val b = demoScore("Second")
        val explorationModule = MeconModuleEntry(
            id = "exp-1",
            type = "exploration",
            scoreId = a.id.value,
            payload = buildJsonObject { put("query", JsonPrimitive("ii-V-I")) },
        )
        val document = documentWith(listOf(a, b), listOf(explorationModule))

        val entries = MeconBundleCodec.writeTextEntries(document)
        // manifest + 2 scores + 1 module
        assertEquals(4, entries.size)
        assertNotNull(entries[MeconFormat.MANIFEST_PATH])

        val restored = MeconBundleCodec.readDocument(entries)
        assertEquals(document.manifest, restored.manifest)
        assertEquals(listOf("First", "Second"), restored.scores.map { it.metadata.title })
        // activeScore honours the manifest's activeScoreId (the second score here).
        assertEquals("Second", restored.activeScore?.metadata?.title)
        assertEquals(explorationModule, restored.module("exp-1"))
    }

    @Test
    fun scoreEntriesEqualStandaloneScoreJson() {
        val score = demoScore("Solo")
        val document = documentWith(listOf(score), emptyList())
        val restored = MeconBundleCodec.readDocument(MeconBundleCodec.writeTextEntries(document))
        // The container score entry is exactly the shared score JSON — a full structural round-trip.
        // Metadata timestamps default to currentTimeMillis() and aren't stably encoded, so canonicalise them.
        fun StorageScore.canon() = copy(metadata = metadata.copy(createdAt = 0L, modifiedAt = 0L))
        assertEquals(score.canon(), restored.scores.single().canon())
    }

    @Test
    fun unknownModuleTypeSurvivesRoundTripUntouched() {
        val score = demoScore("Host")
        // A module family the container has never heard of: it must round-trip byte-for-payload intact,
        // so a future/foreign module is never dropped by a load→save through an older reader.
        val future = MeconModuleEntry(
            id = "imp-7",
            type = "improvisation",
            schemaVersion = 4,
            payload = buildJsonObject {
                put("motif", JsonPrimitive("C4 E4 G4"))
                put("swing", JsonPrimitive(0.62))
            },
        )
        val document = documentWith(listOf(score), listOf(future))
        val restored = MeconBundleCodec.readDocument(MeconBundleCodec.writeTextEntries(document))
        assertEquals(future, restored.module("imp-7"))
        assertEquals(listOf("improvisation"), restored.modules.map { it.type })
    }

    @Test
    fun readDocumentRejectsArchiveWithoutManifest() {
        assertFailsWith<IllegalArgumentException> {
            MeconBundleCodec.readDocument(mapOf("scores/x.json" to "{}"))
        }
    }

    @Test
    fun readDocumentRejectsDanglingManifestReference() {
        val score = demoScore("Dangling")
        val document = documentWith(listOf(score), emptyList())
        val entries = MeconBundleCodec.writeTextEntries(document).toMutableMap()
        entries.remove(MeconFormat.scorePath(score.id.value)) // manifest still lists it
        assertFailsWith<IllegalArgumentException> { MeconBundleCodec.readDocument(entries) }
    }

    @Test
    fun emptyWorkspaceAndNoActiveScoreDefaultsToFirst() {
        val a = demoScore("Alpha")
        val b = demoScore("Beta")
        val manifest = MeconManifest(
            scores = listOf(a, b).map { MeconScoreRef(it.id.value, it.metadata.title, MeconFormat.scorePath(it.id.value)) },
            activeScoreId = null,
        )
        val restored = MeconBundleCodec.readDocument(
            MeconBundleCodec.writeTextEntries(MeconDocument(manifest, listOf(a, b)))
        )
        assertNull(restored.manifest.activeScoreId)
        assertEquals("Alpha", restored.activeScore?.metadata?.title)
    }
}
