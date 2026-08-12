@file:OptIn(ExperimentalJsExport::class)

package com.mecon.web

import com.mecon.audio.converter.ScoreToMidiConverter
import com.mecon.audio.model.MidiNoteOffEvent
import com.mecon.audio.model.MidiNoteOnEvent
import com.mecon.core.serializer.ScoreSerializer
import com.mecon.exploration.FreePracticeDocument
import com.mecon.exploration.DEFAULT_FREE_PRACTICE_MODULE_ID
import com.mecon.exploration.FREE_PRACTICE_MODULE_TYPE
import com.mecon.exploration.FREE_PRACTICE_SCHEMA_VERSION
import com.mecon.exploration.VoicePlanScoreAssembler
import com.mecon.features.freepractice.FreePracticeBackgroundExecutor
import com.mecon.features.freepractice.FreePracticeCodec
import com.mecon.features.freepractice.FreePracticePreset
import com.mecon.features.freepractice.FreePracticeSession
import com.mecon.features.freepractice.PracticeFindingComputer
import com.mecon.features.freepractice.PracticeFindingExecutor
import com.mecon.features.freepractice.PracticeTeachingCatalogExecutor
import kotlinx.serialization.json.Json
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

@Serializable
data class WebPlaybackNote(
    val midiNumber: Int,
    val velocity: Int,
    val startSeconds: Double,
    val durationSeconds: Double,
)

@Serializable
data class WebPlaybackExcerpt(
    val notes: List<WebPlaybackNote>,
    val durationSeconds: Double,
    val startTick: Long,
    val endTick: Long,
    val secondsPerTick: Double,
)

/** String-only worker facade for the shared free-writing session. */
@JsExport
class MeconFreePractice(
    documentJson: String,
    scoreJson: String,
) {
    private var session: FreePracticeSession? = FreePracticeSession.open(
        document = Json { ignoreUnknownKeys = true }.decodeFromString<FreePracticeDocument>(documentJson),
        score = ScoreSerializer.fromJson(scoreJson),
    )

    fun initialUpdateJson(): String = FreePracticeCodec.encodeUpdate(requireSession().initialUpdate())

    fun dispatchJson(intentJson: String): String {
        val active = requireSession()
        return FreePracticeCodec.encodeUpdate(
            active.toWireUpdate(active.dispatch(FreePracticeCodec.decodeIntent(intentJson))),
        )
    }

    fun applyBackgroundResultJson(resultJson: String): String {
        val active = requireSession()
        return FreePracticeCodec.encodeUpdate(
            active.toWireUpdate(
                active.applyBackgroundResult(FreePracticeCodec.decodeBackgroundResult(resultJson)),
            ),
        )
    }

    fun applyTeachingCatalogResultJson(resultJson: String): String {
        val active = requireSession()
        return FreePracticeCodec.encodeUpdate(
            active.toWireUpdate(
                active.applyTeachingCatalogResult(FreePracticeCodec.decodeTeachingCatalogResult(resultJson)),
            ),
        )
    }

    fun applyFindingResultJson(resultJson: String): String {
        val active = requireSession()
        return FreePracticeCodec.encodeUpdate(
            active.toWireUpdate(
                active.applyFindingResult(FreePracticeCodec.decodeFindingResult(resultJson)),
            ),
        )
    }

    /**
     * Crash channel for the terminable search workers. A worker that throws, dies or fails to load
     * can never answer, so the shell reports the failure here and the session rolls back to its
     * last committed state instead of staying locked in RUNNING.
     */
    fun applyBackgroundFailureJson(failureJson: String): String {
        val active = requireSession()
        return FreePracticeCodec.encodeUpdate(
            active.toWireUpdate(
                active.applyBackgroundFailure(FreePracticeCodec.decodeBackgroundFailure(failureJson)),
            ),
        )
    }

    fun applyTeachingCatalogFailureJson(failureJson: String): String {
        val active = requireSession()
        return FreePracticeCodec.encodeUpdate(
            active.toWireUpdate(
                active.applyTeachingCatalogFailure(FreePracticeCodec.decodeBackgroundFailure(failureJson)),
            ),
        )
    }

    fun applyFindingFailureJson(failureJson: String): String {
        val active = requireSession()
        return FreePracticeCodec.encodeUpdate(
            active.toWireUpdate(
                active.applyFindingFailure(FreePracticeCodec.decodeBackgroundFailure(failureJson)),
            ),
        )
    }

    fun previewTimelineEditJson(requestJson: String): String {
        val active = requireSession()
        return FreePracticeCodec.encodeTimelinePreviewResult(
            active.previewTimelineEdit(FreePracticeCodec.decodeTimelinePreviewRequest(requestJson)),
        )
    }

    fun buildPlaybackExcerptJson(rangeJson: String): String {
        val active = requireSession()
        val range = Json.decodeFromString<com.mecon.features.freepractice.PracticeReplayRange>(rangeJson)
        val score = active.currentScore()
        val timeMap = com.mecon.api.runtime.ScoreTimeMap.from(score)
        val startTick = ScoreToMidiConverter.timeCodeToPlaybackTicks(timeMap.timeCodeAt(range.start), score)
        val endTick = ScoreToMidiConverter.timeCodeToPlaybackTicks(timeMap.timeCodeAt(range.end), score)
        val midi = ScoreToMidiConverter.convert(score)
        val secondsPerTick = 60.0 / (range.tempoBpm * midi.ticksPerQuarter.toDouble())
        val notes = buildList {
            midi.tracks.forEach { track ->
                val activeNotes = mutableMapOf<Pair<Int, Int>, ArrayDeque<MidiNoteOnEvent>>()
                track.getEventsSorted().forEach { event ->
                    when (event) {
                        is MidiNoteOnEvent -> activeNotes.getOrPut(event.midiNumber to event.channel.value) {
                            ArrayDeque()
                        }.addLast(event)
                        is MidiNoteOffEvent -> {
                            val on = activeNotes[event.midiNumber to event.channel.value]?.removeFirstOrNull()
                                ?: return@forEach
                            val clippedStart = maxOf(on.absoluteTicks, startTick)
                            val clippedEnd = minOf(event.absoluteTicks, endTick)
                            if (clippedEnd > clippedStart) add(
                                WebPlaybackNote(
                                    midiNumber = on.midiNumber,
                                    velocity = on.velocity.value,
                                    startSeconds = (clippedStart - startTick) * secondsPerTick,
                                    durationSeconds = (clippedEnd - clippedStart) * secondsPerTick,
                                )
                            )
                        }
                        else -> Unit
                    }
                }
            }
        }.sortedWith(compareBy(WebPlaybackNote::startSeconds, WebPlaybackNote::midiNumber))
        return Json.encodeToString(
            WebPlaybackExcerpt(
                notes = notes,
                durationSeconds = maxOf(0L, endTick - startTick) * secondsPerTick,
                startTick = startTick,
                endTick = endTick,
                secondsPerTick = secondsPerTick,
            )
        )
    }

    fun close() {
        session = null
    }

    private fun requireSession(): FreePracticeSession =
        checkNotNull(session) { "MeconFreePractice is closed" }
}

/** Separate facade intended for a terminable search worker. */
@JsExport
class MeconFreePracticeExecutor {
    fun executeJson(requestJson: String): String = FreePracticeCodec.encodeBackgroundResult(
        FreePracticeBackgroundExecutor.execute(
            FreePracticeCodec.decodeBackgroundRequest(requestJson),
        )
    )

    fun executeTeachingCatalogJson(requestJson: String): String =
        FreePracticeCodec.encodeTeachingCatalogResult(
            PracticeTeachingCatalogExecutor.execute(
                FreePracticeCodec.decodeTeachingCatalogRequest(requestJson),
            ),
        )

    fun executeFindingJson(requestJson: String): String = FreePracticeCodec.encodeFindingResult(
        PracticeFindingExecutor.execute(
            FreePracticeCodec.decodeFindingRequest(requestJson),
        )
    )
}

/** Deterministic bootstrap provider shared by the Web shell and JVM/JS contract tests. */
@JsExport
class MeconFreePracticePreset {
    fun documentJson(): String = Json.encodeToString(FreePracticePreset.document())

    fun moduleId(): String = DEFAULT_FREE_PRACTICE_MODULE_ID

    fun moduleType(): String = FREE_PRACTICE_MODULE_TYPE

    fun moduleSchemaVersion(): Int = FREE_PRACTICE_SCHEMA_VERSION

    fun scoreJson(): String {
        val document = FreePracticePreset.document()
        val key = PracticeFindingComputer.fallbackKey(document)
        val signature = if (key.mode == com.mecon.theory.KeySignatureMode.MAJOR) {
            com.mecon.api.primitive.KeySignature.majorByFifths(key.fifths)
        } else {
            com.mecon.api.primitive.KeySignature.minorByFifths(key.fifths)
        }
        return ScoreSerializer.toJson(
            VoicePlanScoreAssembler.emptyPracticeScore(
                document.workspace,
                signature,
                document.settings.staffVoices,
            )
        )
    }
}
