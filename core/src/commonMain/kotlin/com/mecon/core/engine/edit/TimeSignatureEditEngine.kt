package com.mecon.core.engine.edit

import com.mecon.api.primitive.EventId
import com.mecon.api.primitive.Fraction
import com.mecon.api.primitive.TimeCode
import com.mecon.api.primitive.TimeSignature
import com.mecon.api.runtime.RuntimeMeasure
import com.mecon.api.runtime.RuntimeScore
import com.mecon.api.runtime.TimeIndexedList
import com.mecon.api.runtime.events.RuntimeVoiceEvent
import com.mecon.api.storage.events.StorageDynamicMark
import com.mecon.api.storage.events.StorageBreathMark
import com.mecon.api.storage.events.StorageHairpin
import com.mecon.api.storage.events.StorageOrnamentMark
import com.mecon.api.storage.events.StorageOctaveShiftEnd
import com.mecon.api.storage.events.StorageOctaveShiftStart
import com.mecon.api.storage.events.StorageStaffAttachment
import com.mecon.api.storage.tracks.StorageGlobalEvent
import com.mecon.api.storage.tracks.StorageKeySignatureChange
import com.mecon.api.storage.tracks.StoragePageBreak
import com.mecon.api.storage.tracks.StorageSystemBreak
import com.mecon.api.storage.tracks.StorageTimeSignatureChange
import com.mecon.api.storage.tracks.StorageFermata
import com.mecon.api.storage.tracks.StorageGlobalBreathMark

/**
 * Pure edit: set the time signature at a measure and re-bar the affected span.
 *
 * The new meter applies from [measureNumber] up to (but not including) the next measure that
 * already declared an explicit time signature. Notes keep their durations and are greedily
 * re-packed into new-size measures (a note that no longer fits the remaining space moves whole
 * to the next measure — no tie-splitting; that is the future "time-scaling" feature). Empty
 * measures stay empty. Re-barring may need more measures than before, so the score auto-expands
 * and everything after the span shifts down by the measure delta.
 *
 * A change that does not alter the measure's *duration* (e.g. only [TimeSignature.beatGroups], or
 * 2/4 → 4/8) skips re-barring: it just records the meter so beaming picks up the new grouping.
 */
object TimeSignatureEditEngine {

    data class Result(
        val score: RuntimeScore,
        val editedMeasure: Int,
    )

    fun setTimeSignature(score: RuntimeScore, measureNumber: Int, ts: TimeSignature): Result? {
        if (measureNumber < 1) return null
        val oldEffective = score.getTimeSignatureAt(measureNumber)
        if (oldEffective == ts) return null

        // A meter with the same measure duration (e.g. only a beatGroups change) needs no re-bar:
        // record it so automatic beaming regroups, but leave onsets untouched.
        if (oldEffective.measureDuration() == ts.measureDuration()) {
            return Result(writeMeasureTimeSignature(score, measureNumber, ts), measureNumber)
        }

        val newCap = ts.measureDuration()
        val lastMeasure = maxOf(
            score.measures.maxOfOrNull { it.value.number } ?: 0,
            allEventMeasures(score).maxOrNull() ?: 0,
            measureNumber,
        )
        // Span = [measureNumber, end): measures that previously inherited the old meter.
        val end = ((measureNumber + 1)..lastMeasure).firstOrNull { hasExplicitTimeSignature(score, it) }
            ?: (lastMeasure + 1)
        val oldSpanLen = end - measureNumber

        // ---- Re-bar the span, per original measure, per voice ----
        val voiceRemap = HashMap<EventId, TimeCode>()
        val measureBase = HashMap<Int, Int>()  // original measure -> new base measure
        var base = measureNumber
        for (m in measureNumber until end) {
            measureBase[m] = base
            var subCountForMeasure = 1
            for (vt in score.voiceTracks.values) {
                val evs = vt.events.filter { it.onset.measure == m }.sortedBy { it.onset }
                if (evs.isEmpty()) continue
                var pos = Fraction.ZERO
                var sub = 0
                for (ev in evs) {
                    val isGrace = ev.onset.grace != null
                    val d = ev.duration.toFraction()
                    if (!isGrace && pos.isPositive && (pos + d) > newCap) {
                        sub++
                        pos = Fraction.ZERO
                    }
                    voiceRemap[ev.id] = if (isGrace) {
                        TimeCode.of(base + sub, pos, ev.onset.grace!!)
                    } else {
                        TimeCode.of(base + sub, pos)
                    }
                    if (!isGrace) pos += d
                }
                subCountForMeasure = maxOf(subCountForMeasure, sub + 1)
            }
            base += subCountForMeasure
        }
        val newSpanLen = base - measureNumber
        val delta = newSpanLen - oldSpanLen

        // Remap any measure number: unchanged before the span, base-mapped inside it, +delta after.
        fun remapMeasure(oldMeasure: Int): Int = when {
            oldMeasure < measureNumber -> oldMeasure
            oldMeasure < end -> measureBase[oldMeasure] ?: oldMeasure
            else -> oldMeasure + delta
        }
        // Structural markers (clefs, key/time changes, attachments, tempo …) keep their beat and
        // only move to the remapped measure. Voice/pitch events in the span use the exact re-bar map.
        fun remapMarker(onset: TimeCode): TimeCode = onset.withMeasureNumber(remapMeasure(onset.measure))

        // Pitch-event onsets follow their (earliest) referencing voice event; unreferenced ones or
        // ones outside the span fall back to the marker remap.
        val pitchOnset = HashMap<EventId, TimeCode>()
        for (vt in score.voiceTracks.values) for (ev in vt.events) {
            val no = voiceRemap[ev.id] ?: continue
            val cur = pitchOnset[ev.pitchEvent.id]
            if (cur == null || no < cur) pitchOnset[ev.pitchEvent.id] = no
        }

        // ---- Apply to every onset-bearing structure ----
        var next = score

        val remappedPitchTracks = next.pitchTracks.mapValues { (_, pt) ->
                pt.copy(events = TimeIndexedList.of(pt.events.map { pe ->
                    pe.copy(onset = pitchOnset[pe.id] ?: remapMarker(pe.onset))
                }))
            }
        val remappedVoiceTracks = next.voiceTracks.mapValues { (_, vt) ->
                vt.copy(events = TimeIndexedList.of(vt.events.map { ev ->
                    remapVoiceEvent(ev, voiceRemap, ::remapMeasure)
                }))
            }
        val remappedStaffTracks = next.staffTracks.mapValues { (_, st) ->
                st.copy(
                    clefChanges = st.clefChanges.map { it.copy(onset = remapMarker(it.onset)) },
                    attachments = st.attachments.map { remapAttachment(it, ::remapMarker) },
                )
            }
        next = next.copy(
            controllerTracks = next.controllerTracks.mapValues { (_, ct) ->
                ct.copy(events = ct.events.map { it.copy(onset = remapMarker(it.onset)) })
            },
            globalTrack = next.globalTrack.copy(
                tempoEvents = next.globalTrack.tempoEvents.map { it.copy(onset = remapMarker(it.onset)) },
                events = next.globalTrack.events
                    // Drop any global time-signature change at the target measure — the meter is now
                    // recorded on the measure itself (below).
                    .filterNot { it is StorageTimeSignatureChange && it.onset.measure == measureNumber }
                    .map { remapGlobal(it, ::remapMarker) },
            ),
        ).replaceTracks(remappedPitchTracks, remappedVoiceTracks, remappedStaffTracks)

        // ---- Rebuild the measure list: remap numbers, expand to the new length, write the meter ----
        val newLast = lastMeasure + delta
        val byNumber = HashMap<Int, RuntimeMeasure>()
        for (entry in next.measures) {
            val measure = entry.value
            val n = remapMeasure(measure.number)
            byNumber[n] = measure.copy(number = n)
        }
        var activeTimeSignature = next.defaultTimeSignature
        var activeKeySignature = next.defaultKeySignature
        var useNewMeter = false
        val newMeasures = (1..newLast).map { n ->
            val existing = byNumber[n]
            if (n == measureNumber) useNewMeter = true
            if (n > measureNumber && existing?.hasExplicitTimeSignature == true) useNewMeter = false
            val timeSignature = if (useNewMeter) ts else existing?.timeSignature ?: activeTimeSignature
            val keySignature = existing?.keySignature ?: activeKeySignature
            activeTimeSignature = timeSignature
            activeKeySignature = keySignature
            (existing ?: RuntimeMeasure(n, timeSignature, keySignature)).copy(
                number = n,
                timeSignature = timeSignature,
                keySignature = keySignature,
                hasExplicitTimeSignature = if (n == measureNumber) true else existing?.hasExplicitTimeSignature ?: false,
            )
        }
        next = next.replaceMeasures(newMeasures)

        return Result(next, measureNumber)
    }

    // --- helpers --------------------------------------------------------------

    /** Record the meter on the measure without re-barring (used when the duration is unchanged). */
    private fun writeMeasureTimeSignature(score: RuntimeScore, measureNumber: Int, ts: TimeSignature): RuntimeScore {
        var active = false
        val measures = score.measures.map { entry ->
            val measure = entry.value
            when {
                measure.number == measureNumber -> {
                    active = true
                    measure.copy(timeSignature = ts, hasExplicitTimeSignature = true)
                }
                measure.number > measureNumber && measure.hasExplicitTimeSignature -> {
                    active = false
                    measure
                }
                active -> measure.copy(timeSignature = ts)
                else -> measure
            }
        }
        val cleanedGlobal = score.globalTrack.copy(
            events = score.globalTrack.events.filterNot { it is StorageTimeSignatureChange && it.onset.measure == measureNumber }
        )
        return score.copy(globalTrack = cleanedGlobal).replaceMeasures(measures)
    }

    private fun remapVoiceEvent(
        ev: RuntimeVoiceEvent,
        voiceRemap: Map<EventId, TimeCode>,
        remapMeasure: (Int) -> Int,
    ): RuntimeVoiceEvent {
        val newOnset = voiceRemap[ev.id] ?: ev.onset.withMeasureNumber(remapMeasure(ev.onset.measure))
        // Shift a tuplet's exclusive end by the same measure delta its owner moved (best effort;
        // tuplets are assumed not to be split across the new barlines in this first pass).
        val newTuplet = ev.tupletSpan?.let { span ->
            val d = newOnset.measure - ev.onset.measure
            span.copy(endTimeCode = span.endTimeCode.withMeasureNumber(span.endTimeCode.measure + d))
        }
        return ev.copy(onset = newOnset, tupletSpan = newTuplet)
    }

    private fun remapAttachment(a: StorageStaffAttachment, remap: (TimeCode) -> TimeCode): StorageStaffAttachment =
        when (a) {
            is StorageDynamicMark -> a.copy(onset = remap(a.onset))
            is StorageBreathMark -> a.copy(onset = remap(a.onset))
            is StorageOctaveShiftStart -> a.copy(onset = remap(a.onset))
            is StorageOctaveShiftEnd -> a.copy(onset = remap(a.onset))
            is StorageHairpin -> a.copy(onset = remap(a.onset), endOnset = remap(a.endOnset))
            is StorageOrnamentMark -> a.copy(
                onset = remap(a.onset),
                endOnset = a.endOnset?.let(remap),
            )
        }

    private fun remapGlobal(e: StorageGlobalEvent, remap: (TimeCode) -> TimeCode): StorageGlobalEvent =
        when (e) {
            is StorageKeySignatureChange -> e.copy(onset = remap(e.onset))
            is StorageTimeSignatureChange -> e.copy(onset = remap(e.onset))
            is StorageFermata -> e.copy(onset = remap(e.onset))
            is StorageGlobalBreathMark -> e.copy(onset = remap(e.onset))
            is StorageSystemBreak -> e.copy(onset = remap(e.onset))
            is StoragePageBreak -> e.copy(onset = remap(e.onset))
        }

    private fun hasExplicitTimeSignature(score: RuntimeScore, measureNumber: Int): Boolean =
        score.getMeasure(measureNumber)?.hasExplicitTimeSignature == true

    private fun allEventMeasures(score: RuntimeScore): Sequence<Int> = sequence {
        score.voiceTracks.values.forEach { vt -> vt.events.forEach { yield(it.onset.measure) } }
        score.pitchTracks.values.forEach { pt -> pt.events.forEach { yield(it.onset.measure) } }
    }
}

/** Replace only the measure number of a [TimeCode], preserving beat / grace components. */
internal fun TimeCode.withMeasureNumber(newMeasure: Int): TimeCode {
    if (measure == newMeasure) return this
    val comps = components.toMutableList()
    comps[0] = Fraction(newMeasure, 1)
    return TimeCode(comps)
}
