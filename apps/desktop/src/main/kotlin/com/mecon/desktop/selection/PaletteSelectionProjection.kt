package com.mecon.desktop

import com.mecon.api.computed.ComputedScore
import com.mecon.api.interaction.*
import com.mecon.api.primitive.DurationBase
import com.mecon.api.runtime.RuntimeScore
import com.mecon.core.engine.edit.BarlineEditEngine
import com.mecon.desktop.ui.components.BarlineSelectionInfo
import com.mecon.desktop.ui.components.ClefSelectionInfo
import com.mecon.desktop.ui.components.KeySignatureSelectionInfo
import com.mecon.desktop.ui.components.PaletteSelectionInfo
import com.mecon.desktop.ui.components.TimeSignatureSelectionInfo

internal fun paletteInfoFor(
    selection: Set<EventSection>,
    runtime: RuntimeScore?,
    computed: ComputedScore?,
): PaletteSelectionInfo {
    val selections = runtime?.let { pitchSelections(selection, it, computed) }.orEmpty()
    if (selections.isEmpty()) return PaletteSelectionInfo.EMPTY
    fun <T> common(values: List<T>): T? = values.distinct().singleOrNull()
    val events = selections.map { it.event }
    val notePitches = selections.filter { !it.event.isRest }.flatMap { it.selectedPitchData() }
    return PaletteSelectionInfo(
        editable = true,
        durationBase = common(events.map { it.duration.base }),
        dots = common(events.map { it.duration.dots }),
        accidental = if (notePitches.isEmpty()) {
            null
        } else {
            common(notePitches.map { it.effectiveAccidental })
        },
        tieOut = if (notePitches.isEmpty()) {
            null
        } else {
            common(notePitches.map { it.tieTarget != null })
        },
        voiceNumber = common(events.mapNotNull { event ->
            event.originVoiceTrackId?.let { runtime?.voiceTracks?.get(it)?.voiceNumber }
                ?: runtime?.voiceNumberOf(event.id)
        }),
        allRests = events.all { it.isRest },
        tupletCount = common(events.map { it.duration.tuplet?.actual }),
        effectiveBeamLeft =
            common(events.map { event -> event.beamInfo?.let { it.beamsLeft > 0 } ?: false }),
        effectiveBeamRight =
            common(events.map { event -> event.beamInfo?.let { it.beamsRight > 0 } ?: false }),
        canGroupBeam = run {
            val score = runtime ?: return@run false
            val noteEvents = events.filter { !it.isRest }
            if (noteEvents.size < 2) return@run false
            if (noteEvents.any { it.duration.base.ticks > DurationBase.EIGHTH.ticks }) {
                return@run false
            }
            val firstVoiceId =
                score.voiceTrackIdOf(noteEvents.first().id) ?: return@run false
            if (noteEvents.any { score.voiceTrackIdOf(it.id) != firstVoiceId }) {
                return@run false
            }
            val voice = score.getVoiceTrack(firstVoiceId) ?: return@run false
            val voiceEvents = voice.events.toList()
            val selectedIds = noteEvents.map { it.id }.toSet()
            val positions = voiceEvents.indices.filter { voiceEvents[it].id in selectedIds }
            if (positions.size != noteEvents.size) return@run false
            positions.max() - positions.min() == noteEvents.size - 1
        },
        canAddSlur =
            runtime?.let { buildSlurTargets(selection, it, computed).isNotEmpty() } == true,
        articulations = events.map { it.articulations.toSet() }
            .reduceOrNull { common, next -> common intersect next }
            .orEmpty(),
    )
}

internal fun clefInfoFor(selection: Set<EventSection>): ClefSelectionInfo {
    val clef = selection.singleOrNull() as? ClefSection ?: return ClefSelectionInfo.EMPTY
    return ClefSelectionInfo(editable = true, clef = clef.clef.clef)
}

internal fun timeInfoFor(selection: Set<EventSection>): TimeSignatureSelectionInfo {
    val signature =
        selection.singleOrNull() as? TimeSignatureSection ?: return TimeSignatureSelectionInfo.EMPTY
    return TimeSignatureSelectionInfo(
        editable = true,
        timeSignature = signature.timeSignature.timeSignature,
        measure = signature.timeSignature.time.measure,
    )
}

internal fun keyInfoFor(selection: Set<EventSection>): KeySignatureSelectionInfo {
    val signature =
        selection.singleOrNull() as? KeySignatureSection ?: return KeySignatureSelectionInfo.EMPTY
    return KeySignatureSelectionInfo(
        editable = true,
        keySignature = signature.keySignature.keySignature,
        measure = signature.keySignature.time.measure,
    )
}

internal fun barlineInfoFor(
    selection: Set<EventSection>,
    runtime: RuntimeScore?,
): BarlineSelectionInfo {
    val section =
        selection.singleOrNull() as? BarlineSection ?: return BarlineSelectionInfo.EMPTY
    val boundary = section.barline.measureNumber
    return BarlineSelectionInfo(
        editable = true,
        type = section.barline.type,
        repeatCount = runtime?.let { BarlineEditEngine.repeatCountAt(it, boundary) } ?: 2,
    )
}
