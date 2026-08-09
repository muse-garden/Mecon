package com.mecon.core.engine.edit

import com.mecon.api.primitive.Fraction
import com.mecon.api.primitive.TimeCode
import com.mecon.api.primitive.TimeRange
import com.mecon.api.runtime.RuntimeScore
import com.mecon.core.engine.edit.EditGeometry.absolute
import com.mecon.core.engine.edit.StaffTrackOps.replaceVoice
import com.mecon.core.engine.edit.VoiceSpanEditing.clearInterval
import com.mecon.core.engine.edit.VoiceSpanEditing.fillGaps

/** Runtime materialization of a fully quantized take. No capture or quantization policy lives here. */
internal object CaptureMaterializer {
    fun insert(
        runtime: RuntimeScore,
        capture: NoteEditEngine.CaptureInsertion,
    ): NoteEditEngine.Result? {
        if (capture.end <= capture.start || capture.cells.isEmpty()) return null
        val initialVoice = runtime.voiceTracks[capture.voiceTrackId]
            ?: runtime.staffTracks[capture.staffTrackId]?.voiceTracks
                ?.firstOrNull { it.voiceNumber == capture.voiceNumber }
            ?: return null

        var current = runtime
        if (capture.replace) {
            val kept = clearInterval(current, initialVoice, capture.start, capture.end)
            val filled = fillGaps(
                current,
                kept,
                capture.start.measure,
                capture.end.measure,
            )
            current = replaceVoice(current, initialVoice, filled)
        }

        var insertedId: com.mecon.api.primitive.EventId? = null
        for (cell in capture.cells.sortedBy { absolute(current, it.start) }) {
            val result = NoteInsertion.insertChord(
                current,
                cell,
                NoteEditEngine.InsertionPolicy.CHORDAL,
            ) ?: continue
            current = result.score
            insertedId = insertedId ?: result.insertedEventId
        }
        if (current === runtime || current == runtime) return null
        return NoteEditEngine.Result(
            score = current,
            editInterval = TimeRange(
                TimeCode.of(capture.start.measure, Fraction.ZERO),
                TimeCode.of(capture.end.measure + 1, Fraction.ZERO),
            ),
            insertedEventId = insertedId,
        )
    }
}
