package com.mecon.features.freepractice

import java.io.File
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.float
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.fail

class RawTimelineTraceTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test
    fun jvmReplaysSharedRawInputTrace() {
        val path = System.getProperty("freepractice.timeline.trace.path")
            ?: error("freepractice.timeline.trace.path is not configured")
        val root = json.parseToJsonElement(File(path).readText()).jsonObject
        val request = json.decodeFromString<PracticeTimelineSceneRequest>(root.getValue("request").toString())
        root.getValue("cases").jsonArray.forEach { caseElement ->
            val case = caseElement.jsonObject
            val actual = replay(request, case)
            assertContains(actual, case.getValue("expected"), case.getValue("name").jsonPrimitive.content)
        }
    }

    private fun replay(request: PracticeTimelineSceneRequest, case: JsonObject): JsonObject {
        val scene = PracticeTimelineSceneProjector.project(request)
        val targetId = case.getValue("targetId").jsonPrimitive.content
        val target = assertNotNull(scene.hitObjects.firstOrNull { it.id == targetId }, targetId)
        val pointerId = 9L
        val x = target.bounds.x + target.bounds.width / 2f
        val y = target.bounds.y + target.bounds.height / 2f
        val operation = case.getValue("operation").jsonPrimitive.content
        val results = mutableListOf<PracticeTimelineInteractionResult>()
        var hover: PracticeTimelineHoverTarget? = null
        when (operation) {
            "hover" -> hover = FreePracticeTimelineController.hoverTarget(scene, x, y)
            "activate" -> results += FreePracticeTimelineController.handle(
                scene, request, PracticeTimelineInput(
                    PracticeTimelineInputType.ACTIVATE,
                    scene.generation,
                    actionTargetId = targetId,
                ),
            )
            "down" -> results += FreePracticeTimelineController.handle(
                scene, request, PracticeTimelineInput(
                    PracticeTimelineInputType.DOWN,
                    scene.generation,
                    pointerId = pointerId,
                    x = x,
                    y = y,
                ),
            )
            "key" -> results += FreePracticeTimelineController.handle(
                scene, request, PracticeTimelineInput(
                    PracticeTimelineInputType.KEY,
                    scene.generation,
                    actionTargetId = targetId,
                    key = case.getValue("key").jsonPrimitive.content,
                ),
            )
            "drag", "cancel" -> {
                val down = FreePracticeTimelineController.handle(
                    scene, request, PracticeTimelineInput(
                        PracticeTimelineInputType.DOWN,
                        scene.generation,
                        pointerId = pointerId,
                        x = x,
                        y = y,
                        ctrl = case["ctrl"]?.jsonPrimitive?.booleanOrNull == true,
                    ),
                )
                results += down
                val moved = FreePracticeTimelineController.handle(
                    scene, request.copy(gesture = assertNotNull(down.gesture)), PracticeTimelineInput(
                        PracticeTimelineInputType.MOVE,
                        scene.generation,
                        pointerId = pointerId,
                        x = x + (case["deltaX"]?.jsonPrimitive?.float ?: 0f),
                        y = y,
                    ),
                )
                results += moved
                results += FreePracticeTimelineController.handle(
                    scene,
                    request.copy(gesture = moved.gesture),
                    PracticeTimelineInput(
                        if (operation == "cancel") PracticeTimelineInputType.CANCEL else PracticeTimelineInputType.UP,
                        scene.generation,
                        pointerId = pointerId,
                    ),
                )
            }
            else -> fail("Unknown raw timeline trace operation: $operation")
        }
        fun <T> lastValue(select: (PracticeTimelineInteractionResult) -> T?): T? = results.mapNotNull(select).lastOrNull()
        return buildJsonObject {
            put("selectSlotId", lastValue { it.selectSlotId }?.let(::JsonPrimitive) ?: JsonNull)
            put("selectTonalLayoutId", lastValue { it.selectTonalLayoutId }?.let(::JsonPrimitive) ?: JsonNull)
            put("selectIdiomId", lastValue { it.selectIdiomId }?.let(::JsonPrimitive) ?: JsonNull)
            put("removeSlotId", lastValue { it.removeSlotId }?.let(::JsonPrimitive) ?: JsonNull)
            put("appendAt", lastValue { it.appendAt }?.let { json.parseToJsonElement(json.encodeToString(it)) } ?: JsonNull)
            put("appendDuration", lastValue { it.appendDuration }?.let {
                json.parseToJsonElement(json.encodeToString(it))
            } ?: JsonNull)
            put("previewEdit", lastValue { it.previewEdit }?.let { json.parseToJsonElement(json.encodeToString(it)) } ?: JsonNull)
            put("commitEdit", lastValue { it.commitEdit }?.let { json.parseToJsonElement(json.encodeToString(it)) } ?: JsonNull)
            put("effectTypes", buildJsonArray {
                results.flatMap { it.effects }.map { it.type }.distinct().forEach { add(JsonPrimitive(it)) }
            })
            put("hoverHitId", hover?.hitId?.let(::JsonPrimitive) ?: JsonNull)
            put("hoverCursor", hover?.cursor?.let(::JsonPrimitive) ?: JsonNull)
            put("hoverOverlayIds", buildJsonArray {
                hover?.overlay?.forEach { add(JsonPrimitive(it.id)) }
            })
        }
    }

    private fun assertContains(actual: JsonElement, expected: JsonElement, path: String) {
        when (expected) {
            is JsonObject -> expected.forEach { (key, value) ->
                val actualValue = (actual as? JsonObject)?.get(key)
                    ?: fail("$path is missing $key in $actual")
                assertContains(actualValue, value, "$path.$key")
            }
            is JsonArray -> assertEquals(expected, actual, path)
            else -> assertEquals(expected, actual, path)
        }
    }
}
