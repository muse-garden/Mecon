package com.mecon.api.interaction

import com.mecon.api.primitive.EventId
import com.mecon.api.primitive.TimeCode
import com.mecon.api.primitive.TrackId
import kotlin.jvm.JvmInline

/**
 * Stable numeric identity for an [EventSection].
 *
 * Storage/runtime identifiers remain strings. They are folded into this render-interaction key once
 * when an EventSection is constructed; SectionIndex and style lookups never build or compare the
 * human-readable section string.
 */
@JvmInline
value class EventSectionId(val value: Long) {
    companion object {
        fun voiceNote(eventId: EventId, pitchIndex: Int): EventSectionId =
            build(SectionKind.VOICE_NOTE, eventId.value, pitchIndex)

        fun voiceStem(eventId: EventId): EventSectionId = build(SectionKind.VOICE_STEM, eventId.value)
        fun voiceFlag(eventId: EventId): EventSectionId = build(SectionKind.VOICE_FLAG, eventId.value)
        fun voiceBeam(groupId: String): EventSectionId = build(SectionKind.VOICE_BEAM, groupId)
        fun voiceEvent(eventId: EventId): EventSectionId = build(SectionKind.VOICE_EVENT, eventId.value)

        fun voiceArticulation(eventId: EventId, index: Int): EventSectionId =
            build(SectionKind.VOICE_ARTICULATION, eventId.value, index)

        fun voiceTuplet(eventId: EventId): EventSectionId = build(SectionKind.VOICE_TUPLET, eventId.value)
        fun voiceTie(eventId: EventId, pitchIndex: Int): EventSectionId =
            build(SectionKind.VOICE_TIE, eventId.value, pitchIndex)

        fun voiceSlur(
            startEventId: EventId,
            endEventId: EventId,
            nestingLevel: Int,
            slurId: EventId? = null,
        ): EventSectionId = if (slurId == null) {
            build(SectionKind.VOICE_SLUR, startEventId.value, endEventId.value, nestingLevel)
        } else {
            build(SectionKind.VOICE_SLUR, slurId.value)
        }

        fun staffAttachment(attachmentId: EventId): EventSectionId =
            build(SectionKind.STAFF_ATTACHMENT, attachmentId.value)

        fun barline(measureNumber: Int, time: TimeCode): EventSectionId =
            buildWithTime(SectionKind.BARLINE, null, time, measureNumber)

        fun volta(startMeasure: Int, endMeasure: Int, numbers: Set<Int>): EventSectionId {
            var hash = start(SectionKind.VOLTA).mix(startMeasure).mix(endMeasure)
            for (number in numbers.sorted()) hash = hash.mix(number)
            return EventSectionId(hash.finish())
        }

        fun navigationMark(boundaryMeasure: Int, mark: com.mecon.api.storage.NavigationMark): EventSectionId =
            build(SectionKind.NAVIGATION_MARK, mark.name, boundaryMeasure)

        fun measureStaff(staffTrackId: TrackId, measureNumber: Int): EventSectionId =
            build(SectionKind.MEASURE_STAFF, staffTrackId.value, measureNumber)

        fun clef(staffTrackId: TrackId, time: TimeCode): EventSectionId =
            buildWithTime(SectionKind.CLEF, staffTrackId.value, time)

        fun keySignature(staffTrackId: TrackId, time: TimeCode): EventSectionId =
            buildWithTime(SectionKind.KEY_SIGNATURE, staffTrackId.value, time)

        fun timeSignature(staffTrackId: TrackId, time: TimeCode): EventSectionId =
            buildWithTime(SectionKind.TIME_SIGNATURE, staffTrackId.value, time)

        fun layoutBreak(beforeMeasure: Int, kind: LayoutBreakKind): EventSectionId =
            build(SectionKind.LAYOUT_BREAK, kind.name, beforeMeasure)

        fun hiddenStaff(systemIndex: Int, staffTrackIds: List<TrackId>, from: Int, to: Int): EventSectionId {
            var hash = start(SectionKind.HIDDEN_STAFF).mix(systemIndex).mix(from).mix(to)
            for (trackId in staffTrackIds) hash = hash.mix(trackId.value)
            return EventSectionId(hash.finish())
        }

        private fun build(kind: Int, text: String, number: Int? = null): EventSectionId {
            var hash = start(kind).mix(text)
            if (number != null) hash = hash.mix(number)
            return EventSectionId(hash.finish())
        }

        private fun build(kind: Int, first: String, second: String, number: Int): EventSectionId =
            EventSectionId(start(kind).mix(first).mix(second).mix(number).finish())

        private fun buildWithTime(
            kind: Int,
            text: String?,
            time: TimeCode,
            number: Int? = null,
        ): EventSectionId {
            var hash = start(kind)
            if (text != null) hash = hash.mix(text)
            if (number != null) hash = hash.mix(number)
            hash = hash.mix(time.components.size)
            for (component in time.components) {
                hash = hash.mix(component.numerator).mix(component.denominator)
            }
            return EventSectionId(hash.finish())
        }

        private fun start(kind: Int): Long = HASH_SEED.mix(kind)
    }
}

private object SectionKind {
    const val VOICE_NOTE = 1
    const val VOICE_STEM = 2
    const val VOICE_FLAG = 3
    const val VOICE_BEAM = 4
    const val VOICE_EVENT = 5
    const val VOICE_ARTICULATION = 6
    const val VOICE_TUPLET = 7
    const val VOICE_SLUR = 8
    const val STAFF_ATTACHMENT = 9
    const val BARLINE = 10
    const val MEASURE_STAFF = 11
    const val CLEF = 12
    const val KEY_SIGNATURE = 13
    const val TIME_SIGNATURE = 14
    const val LAYOUT_BREAK = 15
    const val HIDDEN_STAFF = 16
    const val VOLTA = 17
    const val NAVIGATION_MARK = 18
    const val VOICE_TIE = 19
}

private const val HASH_SEED = -7046029254386353131L
private const val HASH_MULTIPLIER = -4658895280553007687L

private fun Long.mix(value: Int): Long = (this xor value.toLong()) * HASH_MULTIPLIER

private fun Long.mix(value: String): Long {
    var hash = mix(value.length)
    for (character in value) hash = hash.mix(character.code)
    return hash
}

private fun Long.finish(): Long {
    var hash = this
    hash = (hash xor (hash ushr 30)) * -4658895280553007687L
    hash = (hash xor (hash ushr 27)) * -7723592293110705685L
    return hash xor (hash ushr 31)
}
