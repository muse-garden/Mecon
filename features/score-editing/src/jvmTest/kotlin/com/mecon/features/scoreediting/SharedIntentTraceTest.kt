package com.mecon.features.scoreediting

import com.mecon.core.serializer.ScoreSerializer
import java.io.File
import kotlin.test.Test
import kotlin.test.fail
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Replays [SharedIntentTrace.FIXTURE] against the shared session and compares every step against the
 * checked-in expectation. `web/packages/web-renderer/test/intent-trace.test.js` replays the very same
 * file through the generated Kotlin/JS bundle, so JVM and JS cannot drift without one of them failing.
 *
 * Regenerate after an intended protocol or engine change:
 * `./gradlew.bat :features:score-editing:jvmTest -Pscoreediting.trace.write=true`
 */
class SharedIntentTraceTest {
    @Test
    fun sharedIntentTraceMatchesCheckedInExpectations() {
        val file = SharedIntentTrace.fixtureFile()
        val fixture = SharedIntentTrace.json.parseToJsonElement(file.readText()).jsonObject
        val write = System.getProperty("scoreediting.trace.write") == "true"

        val session = ScoreEditingSession.open(
            ScoreSerializer.fromJson(fixture.getValue("score").toString()),
        )
        val ids = SharedIntentTrace.IdNormalizer()
        val steps = fixture.getValue("steps").jsonArray
        var latest = SharedIntentTrace.json.parseToJsonElement(
            ScoreEditCodec.encodeUpdate(session.initialUpdate()),
        ).jsonObject
        ids.normalize(latest)

        val regenerated = mutableListOf<JsonElement>()
        val failures = mutableListOf<String>()

        steps.forEachIndexed { index, rawStep ->
            val step = rawStep.jsonObject
            val name = step["name"]?.jsonPrimitive?.content ?: "step $index"
            val intent = SharedIntentTrace.resolvePlaceholders(
                step.getValue("intent"),
                latest,
                ids,
            )
            latest = SharedIntentTrace.json.parseToJsonElement(
                ScoreEditCodec.encodeUpdate(
                    session.dispatch(ScoreEditCodec.decodeIntent(intent.toString())).toWireUpdate(),
                ),
            ).jsonObject
            val actual = ids.normalize(latest)

            regenerated += buildJsonObject {
                put("name", JsonPrimitive(name))
                put("intent", step.getValue("intent"))
                put("expect", actual)
            }
            if (!write) {
                val expected = step["expect"] ?: JsonNull
                if (!SharedIntentTrace.deepEquals(expected, actual)) {
                    failures += "$name: ${SharedIntentTrace.describeDifference(expected, actual)}"
                }
            }
        }

        if (write) {
            file.writeText(
                SharedIntentTrace.prettyJson.encodeToString(
                    JsonObject.serializer(),
                    buildJsonObject {
                        fixture.forEach { (key, value) -> if (key != "steps") put(key, value) }
                        put("steps", JsonArray(regenerated))
                    },
                ) + "\n",
            )
            fail("Rewrote ${file.path}; rerun without -Pscoreediting.trace.write to verify it.")
        }
        if (failures.isNotEmpty()) {
            fail("Shared intent trace diverged from the fixture:\n" + failures.joinToString("\n"))
        }
    }
}

/** Shared trace helpers; kept next to the test so the JS mirror has an exact reference to follow. */
object SharedIntentTrace {
    val json = Json { ignoreUnknownKeys = true }
    val prettyJson = Json {
        prettyPrint = true
        prettyPrintIndent = "  "
    }

    private val GENERATED_ID = Regex("^[0-9a-z]{9}$")

    fun fixtureFile(): File {
        var directory: File? = File(".").absoluteFile
        while (directory != null) {
            val candidate = File(directory, "features/score-editing/testdata/intent-trace.json")
            if (candidate.isFile) return candidate
            val local = File(directory, "testdata/intent-trace.json")
            if (local.isFile) return local
            directory = directory.parentFile
        }
        error("intent-trace.json fixture was not found from ${File(".").absolutePath}")
    }

    /**
     * Replaces engine-generated ids with stable ordinals assigned on first appearance in a
     * key-sorted traversal, so both platforms label the same logical id identically even though the
     * random values differ per run. The map accumulates across the whole trace.
     */
    class IdNormalizer {
        private val byActual = LinkedHashMap<String, String>()
        private val byOrdinal = LinkedHashMap<String, String>()

        fun actualFor(ordinal: String): String? = byOrdinal[ordinal]

        fun normalize(element: JsonElement): JsonElement = when (element) {
            is JsonObject -> buildJsonObject {
                element.keys.sorted().forEach { key ->
                    // Stable ids can also be JSON object keys (ScoreGeometry maps). Reuse an
                    // already-seen ordinal without changing the value-driven id assignment order.
                    put(byActual[key] ?: key, normalize(element.getValue(key)))
                }
            }
            is JsonArray -> buildJsonArray { element.forEach { add(normalize(it)) } }
            is JsonPrimitive -> {
                val content = element.takeIf { it.isString }?.content
                if (content != null && GENERATED_ID.matches(content)) {
                    JsonPrimitive(
                        byActual.getOrPut(content) {
                            "@id:${byActual.size}".also { byOrdinal[it] = content }
                        },
                    )
                } else {
                    element
                }
            }
        }
    }

    /**
     * `@sel:<index>.<field>` reads the previous update's selection, `@event:<voice>:<index>` reads a
     * voice track event id, and `@id:<n>` names a previously normalized id.
     */
    fun resolvePlaceholders(
        element: JsonElement,
        latest: JsonObject,
        ids: IdNormalizer,
    ): JsonElement = when (element) {
        is JsonObject -> buildJsonObject {
            element.forEach { (key, value) -> put(key, resolvePlaceholders(value, latest, ids)) }
        }
        is JsonArray -> buildJsonArray {
            element.forEach { add(resolvePlaceholders(it, latest, ids)) }
        }
        is JsonPrimitive -> {
            val content = element.takeIf { it.isString }?.content
            val resolved = content?.let { resolvePlaceholder(it, latest, ids) }
            if (resolved != null) JsonPrimitive(resolved) else element
        }
    }

    private fun resolvePlaceholder(value: String, latest: JsonObject, ids: IdNormalizer): String? {
        if (value.startsWith("@id:")) {
            return ids.actualFor(value) ?: error("Unknown normalized id placeholder: $value")
        }
        if (value.startsWith("@sel:")) {
            val (index, field) = value.removePrefix("@sel:").split(".", limit = 2)
            val target = latest["selection"]?.jsonArray?.getOrNull(index.toInt())?.jsonObject
                ?: error("Placeholder $value has no matching selection entry")
            return target[field]?.jsonPrimitive?.content
                ?: error("Placeholder $value is missing field $field")
        }
        if (value.startsWith("@event:")) {
            val (voice, index) = value.removePrefix("@event:").split(":", limit = 2)
            val events = latest["score"]?.jsonObject
                ?.get("voiceTracks")?.jsonObject
                ?.get(voice)?.jsonObject
                ?.get("events")?.jsonArray
                ?: error("Placeholder $value has no matching voice track")
            return events.getOrNull(index.toInt())?.jsonObject?.get("id")?.jsonPrimitive?.content
                ?: error("Placeholder $value is out of range")
        }
        return null
    }

    /** Structural equality that compares numbers by value so 1 and 1.0 do not diverge by platform. */
    fun deepEquals(left: JsonElement, right: JsonElement): Boolean = when {
        left is JsonObject && right is JsonObject ->
            left.keys == right.keys && left.all { (key, value) -> deepEquals(value, right.getValue(key)) }
        left is JsonArray && right is JsonArray ->
            left.size == right.size && left.indices.all { deepEquals(left[it], right[it]) }
        left is JsonPrimitive && right is JsonPrimitive -> {
            val leftNumber = left.takeUnless { it.isString }?.content?.toDoubleOrNull()
            val rightNumber = right.takeUnless { it.isString }?.content?.toDoubleOrNull()
            if (leftNumber != null && rightNumber != null) leftNumber == rightNumber else left == right
        }
        else -> false
    }

    fun describeDifference(expected: JsonElement, actual: JsonElement, path: String = ""): String {
        if (deepEquals(expected, actual)) return "no difference"
        if (expected is JsonObject && actual is JsonObject) {
            (expected.keys - actual.keys).firstOrNull()?.let { return "$path/$it is missing" }
            (actual.keys - expected.keys).firstOrNull()?.let { return "$path/$it is unexpected" }
            expected.keys.forEach { key ->
                if (!deepEquals(expected.getValue(key), actual.getValue(key))) {
                    return describeDifference(expected.getValue(key), actual.getValue(key), "$path/$key")
                }
            }
        }
        if (expected is JsonArray && actual is JsonArray) {
            if (expected.size != actual.size) {
                return "$path has ${actual.size} entries, expected ${expected.size}"
            }
            expected.indices.forEach { index ->
                if (!deepEquals(expected[index], actual[index])) {
                    return describeDifference(expected[index], actual[index], "$path[$index]")
                }
            }
        }
        return "$path expected $expected but was $actual"
    }
}
