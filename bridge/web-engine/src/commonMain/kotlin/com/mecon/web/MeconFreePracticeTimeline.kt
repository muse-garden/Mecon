@file:OptIn(ExperimentalJsExport::class)

package com.mecon.web

import com.mecon.features.freepractice.FreePracticeTimelineController
import com.mecon.features.freepractice.FreePracticeToolbarSpec
import com.mecon.features.freepractice.PracticeTimelineInput
import com.mecon.features.freepractice.PracticeTimelineScene
import com.mecon.features.freepractice.PracticeTimelineSceneProjector
import com.mecon.features.freepractice.PracticeTimelineSceneRequest
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** JSON-only bridge for the common timeline projector and gesture reducer. */
@JsExport
class MeconFreePracticeTimeline {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    fun projectJson(requestJson: String): String = json.encodeToString(
        PracticeTimelineSceneProjector.project(json.decodeFromString<PracticeTimelineSceneRequest>(requestJson)),
    )

    fun toolbarDescriptorJson(): String = json.encodeToString(FreePracticeToolbarSpec.descriptor)

    fun handleJson(sceneJson: String, requestJson: String, inputJson: String): String = json.encodeToString(
        FreePracticeTimelineController.handle(
            json.decodeFromString<PracticeTimelineScene>(sceneJson),
            json.decodeFromString<PracticeTimelineSceneRequest>(requestJson),
            json.decodeFromString<PracticeTimelineInput>(inputJson),
        ),
    )
}
