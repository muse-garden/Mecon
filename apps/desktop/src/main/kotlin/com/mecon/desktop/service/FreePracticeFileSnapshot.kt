package com.mecon.desktop.service

import com.mecon.api.storage.StorageScore
import com.mecon.core.container.MeconDocument
import com.mecon.core.container.MeconModuleEntry
import com.mecon.exploration.DEFAULT_FREE_PRACTICE_MODULE_ID
import com.mecon.exploration.FREE_PRACTICE_MODULE_TYPE
import com.mecon.exploration.FREE_PRACTICE_SCHEMA_VERSION
import com.mecon.exploration.FreePracticeDocument
import com.mecon.exploration.FreePracticeDocumentCodec
import com.mecon.exploration.FreePracticeWritingSettings
import com.mecon.exploration.VoicePlanScoreAssembler
import kotlin.math.roundToInt

/** Complete save unit for the free-practice module and its referenced notation score. */
data class FreePracticeFileSnapshot(
    val document: FreePracticeDocument,
    val score: StorageScore,
    val moduleId: String = DEFAULT_FREE_PRACTICE_MODULE_ID,
) {
    fun moduleEntry(): MeconModuleEntry =
        MeconModuleEntry(
            id = moduleId,
            type = FREE_PRACTICE_MODULE_TYPE,
            schemaVersion = FREE_PRACTICE_SCHEMA_VERSION,
            scoreId = score.id.value,
            payload = FreePracticeDocumentCodec.encode(document),
        )
}

/** Resolve only the manifest-selected free-practice module; inactive modules stay as sidecars. */
fun MeconDocument.activeFreePracticeSnapshot(): FreePracticeFileSnapshot? {
    val activeModuleId = manifest.workspace.activeModuleId ?: return null
    val module = module(activeModuleId)?.takeIf { it.type == FREE_PRACTICE_MODULE_TYPE } ?: return null
    val scoreId = module.scoreId ?: return null
    val score = scores.firstOrNull { it.id.value == scoreId } ?: return null
    val decoded = FreePracticeDocumentCodec.decode(module.payload, module.schemaVersion)
    val document = if (module.schemaVersion < FREE_PRACTICE_SCHEMA_VERSION) {
        decoded.copy(
            settings = decoded.settings.copy(
                writing = FreePracticeWritingSettings.migrated(score.defaultTempo.roundToInt()),
            ),
        )
    } else {
        decoded
    }
    return FreePracticeFileSnapshot(
        document = document,
        score = VoicePlanScoreAssembler.migrateToGrandStaff(
            score = score,
            workspace = document.workspace,
            staffVoices = document.settings.staffVoices,
        ),
        moduleId = module.id,
    )
}
