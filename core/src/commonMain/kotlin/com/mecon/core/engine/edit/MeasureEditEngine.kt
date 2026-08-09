package com.mecon.core.engine.edit

import com.mecon.api.primitive.Fraction
import com.mecon.api.primitive.TimeCode
import com.mecon.api.runtime.RuntimeMeasure
import com.mecon.api.runtime.RuntimeScore
import com.mecon.api.runtime.TimeIndexedList
import com.mecon.api.storage.events.StorageDynamicMark
import com.mecon.api.storage.events.StorageBreathMark
import com.mecon.api.storage.events.StorageHairpin
import com.mecon.api.storage.events.StorageOrnamentMark
import com.mecon.api.storage.events.StorageOctaveShiftEnd
import com.mecon.api.storage.events.StorageOctaveShiftStart
import com.mecon.api.storage.tracks.StorageGlobalEvent
import com.mecon.api.storage.tracks.StorageKeySignatureChange
import com.mecon.api.storage.tracks.StoragePageBreak
import com.mecon.api.storage.tracks.StorageSystemBreak
import com.mecon.api.storage.tracks.StorageTimeSignatureChange
import com.mecon.api.storage.tracks.StorageFermata
import com.mecon.api.storage.tracks.StorageGlobalBreathMark

/** Immutable structural insert/delete operations for numbered score measures. */
object MeasureEditEngine {
    enum class BoundaryInsertion {
        /** Inserted measures stay before a forced boundary selected at the previous system end. */
        BEFORE_BREAK,
        /** Inserted measures stay after a forced boundary selected at the next system start. */
        AFTER_BREAK,
    }

    fun insertAfter(
        score: RuntimeScore,
        afterMeasure: Int,
        count: Int,
        boundaryInsertion: BoundaryInsertion = BoundaryInsertion.BEFORE_BREAK,
    ): RuntimeScore? {
        val max = score.measures.maxOfOrNull { it.value.number } ?: return null
        if (count <= 0 || afterMeasure !in 0..max) return null
        return transform(
            score,
            mapMeasure = { number -> if (number > afterMeasure) number + count else number },
            mapBreakMeasure = { number ->
                if (boundaryInsertion == BoundaryInsertion.AFTER_BREAK && number == afterMeasure + 1) number
                else if (number > afterMeasure) number + count else number
            },
        ) {
            val inherited = score.getMeasure(afterMeasure.coerceAtLeast(1))
                ?: score.getMeasure(1)
                ?: RuntimeMeasure(1, score.defaultTimeSignature, score.defaultKeySignature)
            score.measures.map { it.value.copy(number = if (it.value.number > afterMeasure) it.value.number + count else it.value.number) } +
                (1..count).map { inherited.copy(
                    number = afterMeasure + it,
                    hasExplicitTimeSignature = false,
                    hasExplicitKeySignature = false,
                    rehearsalMark = null,
                    endBarlineType = null,
                    repeatStart = false,
                    repeatEnd = false,
                    repeatCount = 2,
                ) }
        }
    }

    fun delete(score: RuntimeScore, measureNumbers: Set<Int>): RuntimeScore? {
        val removed = measureNumbers.filter { it > 0 }.toSet()
        val max = score.measures.maxOfOrNull { it.value.number } ?: return null
        if (removed.isEmpty() || removed.any { it > max } || removed.size >= max) return null
        fun target(number: Int): Int? = when {
            number in removed -> null
            else -> number - removed.count { it < number }
        }
        fun targetBreak(boundary: Int): Int? {
            val leftSurvives = (1 until boundary).any { it !in removed }
            val rightSurvives = (boundary..max).any { it !in removed }
            if (!leftSurvives || !rightSurvives) return null
            return 1 + (1 until boundary).count { it !in removed }
        }
        return transform(score, ::target, ::targetBreak) {
            score.measures.mapNotNull { entry -> target(entry.value.number)?.let { entry.value.copy(number = it) } }
        }
    }

    private fun transform(
        score: RuntimeScore,
        mapMeasure: (Int) -> Int?,
        mapBreakMeasure: (Int) -> Int? = mapMeasure,
        measures: () -> List<RuntimeMeasure>,
    ): RuntimeScore {
        fun mapTime(time: TimeCode): TimeCode? = mapMeasure(time.measure)?.let { measure ->
            TimeCode(listOf(Fraction(measure, 1)) + time.components.drop(1))
        }
        fun mapGlobal(event: StorageGlobalEvent): StorageGlobalEvent? = when (event) {
            is StorageKeySignatureChange -> mapTime(event.onset)?.let { event.copy(onset = it) }
            is StorageTimeSignatureChange -> mapTime(event.onset)?.let { event.copy(onset = it) }
            is StorageFermata -> mapTime(event.onset)?.let { event.copy(onset = it) }
            is StorageGlobalBreathMark -> mapTime(event.onset)?.let { event.copy(onset = it) }
            is StorageSystemBreak -> mapBreakMeasure(event.onset.measure)?.let { measure ->
                event.copy(onset = TimeCode.of(measure, Fraction.ZERO))
            }
            is StoragePageBreak -> mapBreakMeasure(event.onset.measure)?.let { measure ->
                event.copy(onset = TimeCode.of(measure, Fraction.ZERO))
            }
        }
        val voiceTracks = score.voiceTracks.mapValues { (_, track) ->
            track.copy(events = TimeIndexedList.of(track.events.mapNotNull { event ->
                val onset = mapTime(event.onset) ?: return@mapNotNull null
                val span = event.tupletSpan?.let { value ->
                    mapTime(value.endTimeCode)?.let { value.copy(endTimeCode = it) }
                }
                event.copy(onset = onset, tupletSpan = span)
            }))
        }
        val referencedPitchIds = voiceTracks.values.flatMap { it.events }.mapTo(HashSet()) { it.pitchEvent.id }
        val pitchTracks = score.pitchTracks.mapValues { (_, track) ->
                track.copy(events = TimeIndexedList.of(track.events.mapNotNull { event ->
                    mapTime(event.onset)?.takeIf { event.id in referencedPitchIds }?.let { event.copy(onset = it) }
                }))
            }
        val staffTracks = score.staffTracks.mapValues { (_, staff) ->
                staff.copy(
                    clefChanges = staff.clefChanges.mapNotNull { change -> mapTime(change.onset)?.let { change.copy(onset = it) } },
                    attachments = staff.attachments.mapNotNull { attachment -> mapAttachment(attachment, ::mapTime) },
                )
            }
        val mappedGlobalRaw = score.globalTrack.events.mapNotNull(::mapGlobal)
        // Deleting all measures between two boundaries can collapse them onto the same opening.
        // Keep one event there, with PAGE taking precedence because it implies a system break.
        val pageBoundaryMeasures = mappedGlobalRaw.filterIsInstance<StoragePageBreak>()
            .mapTo(LinkedHashSet()) { it.onset.measure }
        val mappedGlobalEvents = (
            mappedGlobalRaw.filterNot { it is StorageSystemBreak || it is StoragePageBreak } +
                pageBoundaryMeasures.map { StoragePageBreak(TimeCode.of(it, Fraction.ZERO)) } +
                mappedGlobalRaw.filterIsInstance<StorageSystemBreak>()
                    .map { it.onset.measure }
                    .distinct()
                    .filterNot { it in pageBoundaryMeasures }
                    .map { StorageSystemBreak(TimeCode.of(it, Fraction.ZERO)) }
            ).sortedBy { it.onset }
        val pageBreaks = mappedGlobalEvents.filterIsInstance<StoragePageBreak>().mapTo(LinkedHashSet()) { it.onset.measure }
        val systemBreaks = mappedGlobalEvents.filterIsInstance<StorageSystemBreak>().mapTo(LinkedHashSet()) { it.onset.measure }
        return score.copy(
            controllerTracks = score.controllerTracks.mapValues { (_, track) ->
                track.copy(events = track.events.mapNotNull { event -> mapTime(event.onset)?.let { event.copy(onset = it) } })
            },
            globalTrack = score.globalTrack.copy(
                tempoEvents = score.globalTrack.tempoEvents.mapNotNull { event -> mapTime(event.onset)?.let { event.copy(onset = it) } },
                events = mappedGlobalEvents,
            ),
            forcedSystemBreaks = systemBreaks + pageBreaks,
            forcedPageBreaks = pageBreaks,
        ).replaceMeasures(measures().sortedBy { it.number })
            .replaceTracks(pitchTracks, voiceTracks, staffTracks)
    }

    private fun mapAttachment(
        attachment: com.mecon.api.storage.events.StorageStaffAttachment,
        mapTime: (TimeCode) -> TimeCode?,
    ): com.mecon.api.storage.events.StorageStaffAttachment? = when (attachment) {
        is StorageDynamicMark -> mapTime(attachment.onset)?.let { attachment.copy(onset = it) }
        is StorageBreathMark -> mapTime(attachment.onset)?.let { attachment.copy(onset = it) }
        is StorageOrnamentMark -> {
            val start = mapTime(attachment.onset) ?: return null
            val oldEnd = attachment.endOnset
            val end = if (oldEnd == null) null else mapTime(oldEnd) ?: return null
            attachment.copy(onset = start, endOnset = end)
        }
        is StorageOctaveShiftStart -> mapTime(attachment.onset)?.let { attachment.copy(onset = it) }
        is StorageOctaveShiftEnd -> mapTime(attachment.onset)?.let { attachment.copy(onset = it) }
        is StorageHairpin -> {
            val start = mapTime(attachment.onset) ?: return null
            val end = mapTime(attachment.endOnset) ?: return null
            attachment.copy(onset = start, endOnset = end)
        }
    }
}
