package com.mecon.core.musicxml.import

import com.mecon.core.musicxml.model.*
import com.mecon.core.engine.StaffPositionComputer
import com.mecon.api.primitive.*
import com.mecon.api.storage.*
import com.mecon.api.storage.events.*
import com.mecon.api.storage.tracks.*
import kotlin.math.abs

/**
 * Imports MusicXML intermediate model to StorageScore.
 *
 * Conversions wired here (intermediate model → StorageScore):
 * - Pitches, durations (incl. tuplet ratio), rests, chords, ties, beams.
 * - Grace notes → [TimeCode] grace component + [GraceNoteInfo].
 * - Slurs → [StorageVoiceEvent.slurStarts] / [slurEnds].
 * - Tuplet brackets → [TupletSpan].
 * - Mid-score clef changes → [StorageStaffTrack.clefChanges].
 * - Key / time signature changes → [StorageGlobalTrack.events].
 * - Tempo (metronome / sound) → [StorageGlobalTrack.tempoEvents].
 * - Dynamics / hairpins / 8va-8vb → [StorageStaffTrack.attachments].
 * - Transposing instruments → [StorageStaffTrack.transposition].
 * - Repeat barlines → [StorageMeasure].
 * - Forced system/page breaks → [StorageGlobalTrack.events].
 * - Page size, margins, and scale defaults → [StorageScore.pageLayout].
 */
class MusicXmlImporter {

    private val context = ImportContext()

    fun import(xmlScore: MusicXmlScore): StorageScore {
        val scoreId = ScoreId.generate()
        val metadata = extractMetadata(xmlScore)

        val pitchTracks = mutableMapOf<TrackId, StoragePitchTrack>()
        val voiceTracks = mutableMapOf<TrackId, StorageVoiceTrack>()
        val staffTracks = mutableMapOf<TrackId, StorageStaffTrack>()
        val instruments = mutableListOf<StorageInstrument>()
        val staffGroups = mutableListOf<StorageStaffGroup>()
        val importedTieGeometry = mutableMapOf<EventId, List<TieGeometry>>()
        val importedSlurGeometry = mutableMapOf<EventId, SlurGeometry>()

        // Score-wide notation changes accumulated across all parts (keyed by measure).
        val keyChanges = mutableMapOf<Int, KeySignature>()
        val timeChanges = mutableMapOf<Int, TimeSignature>()
        val tempoEvents = mutableListOf<StorageTempoEvent>()

        // Per-measure barline data (repeats); keyed by measure number.
        val measureRepeats = mutableMapOf<Int, MusicXmlBarline>()
        // Score-wide forced layout breaks; first part wins when repeated by part.
        val layoutBreaks = mutableMapOf<Int, MusicXmlPrint>()

        var maxMeasureNumber = 0
        var scoreDefaultKey = KeySignature.C_MAJOR
        var scoreDefaultTime: TimeSignature = TimeSignature.COMMON
        var defaultsCaptured = false

        for ((partIndex, xmlPart) in xmlScore.parts.withIndex()) {
            context.resetForNewPart()

            val partInfo = xmlScore.partList.find { it.id == xmlPart.id }
            val partName = partInfo?.partName ?: "Part ${partIndex + 1}"

            val partVoices = mutableMapOf<Pair<Int, Int>, TrackId>()  // (staff, voice) -> voiceTrackId
            val partStaves = mutableMapOf<Int, TrackId>()

            // Per-staff clef tracking: first seen clef = initial, later changes recorded.
            val initialClefByStaff = mutableMapOf<Int, Clef>()
            val clefChangesByStaff = mutableMapOf<Int, MutableList<StorageClefChange>>()
            val transposeByStaff = mutableMapOf<Int, TranspositionConfig>()
            // Per-staff visibility: <staff-details print-object="no|yes"> toggles fold into hidden ranges.
            val hiddenRangesByStaff = mutableMapOf<Int, MutableList<MeasureRange>>()
            val hideStartByStaff = mutableMapOf<Int, Int>()
            val partMaxMeasure = xmlPart.measures.maxOfOrNull { it.number } ?: 0

            // Directions captured with their (measure, beat) onset and target staff.
            val directionsByStaff = mutableMapOf<Int, MutableList<DirectionWithOnset>>()

            val partNotes = mutableListOf<NoteImportData>()

            var runningKey = context.defaultKeySignature
            var runningTime = context.defaultTimeSignature

            for (xmlMeasure in xmlPart.measures) {
                maxMeasureNumber = maxOf(maxMeasureNumber, xmlMeasure.number)
                context.resetVoicePositions()

                xmlMeasure.attributes?.let { attrs ->
                    context.updateFromAttributes(attrs)

                    // Transpose (per part; staff-scoped if number given).
                    attrs.transpose?.let { t ->
                        transposeByStaff[t.staff ?: 1] = TranspositionConfig(
                            interval = Interval(t.chromatic),
                            octaveShift = t.octaveChange
                        )
                    }

                    // Staff visibility toggles: print-object="no" opens a hidden run, "yes" closes it.
                    for (sd in attrs.staffDetails) {
                        val staffNum = sd.staff ?: 1
                        if (!sd.printObject) {
                            hideStartByStaff.getOrPut(staffNum) { xmlMeasure.number }
                        } else {
                            hideStartByStaff.remove(staffNum)?.let { start ->
                                if (xmlMeasure.number - 1 >= start) {
                                    hiddenRangesByStaff.getOrPut(staffNum) { mutableListOf() }
                                        .add(MeasureRange(start, xmlMeasure.number - 1))
                                }
                            }
                        }
                    }
                }

                // Capture initial defaults from the very first measure with attributes.
                if (!defaultsCaptured && xmlMeasure.number == 1) {
                    scoreDefaultKey = context.getKeySignature(1)
                    scoreDefaultTime = context.getTimeSignature(1)
                    runningKey = scoreDefaultKey
                    runningTime = scoreDefaultTime
                    defaultsCaptured = true
                }

                // Key / time signature changes (score-wide) at measure boundaries.
                val effKey = context.getKeySignature(1)
                if (effKey != runningKey) {
                    keyChanges[xmlMeasure.number] = effKey
                    runningKey = effKey
                }
                val effTime = context.getTimeSignature(1)
                if (effTime != runningTime) {
                    timeChanges[xmlMeasure.number] = effTime
                    runningTime = effTime
                }

                // Barline repeats (first part wins; identical across parts in practice).
                xmlMeasure.barline?.let {
                    if (xmlMeasure.number !in measureRepeats) measureRepeats[xmlMeasure.number] = it
                }
                // MusicXML print breaks happen before the measure containing <print>.
                // A first-measure break is only an initial layout marker, not a forced break.
                xmlMeasure.print?.let {
                    if (xmlMeasure.number > 1 && (it.newSystem || it.newPage) && xmlMeasure.number !in layoutBreaks) {
                        layoutBreaks[xmlMeasure.number] = it
                    }
                }

                // Walk note stream with a measure cursor honoring backup/forward.
                val measureResult = processMeasure(xmlMeasure, partVoices, partStaves)
                partNotes.addAll(measureResult.notes)
                for (attr in measureResult.attributes) {
                    recordClefs(
                        attrs = attr.attributes,
                        onset = attr.onset,
                        initialClefByStaff = initialClefByStaff,
                        clefChangesByStaff = clefChangesByStaff
                    )
                }
                for (d in measureResult.directions) {
                    val staff = d.staff
                    directionsByStaff.getOrPut(staff) { mutableListOf() }.add(d)
                }
            }

            // ---- Build tracks for this part ----
            // Keep every MusicXML (staff, voice) stream on its own pitch track. Pitch-track
            // event order is also the playback timeline, so merging simultaneous piano staves
            // into one track would make one hand's event end at the other's identical onset.
            val pitchTrackByStaffVoice = mutableMapOf<Pair<Int, Int>, TrackId>()

            // Voice tracks for discovered (staff, voice) pairs.
            val voiceTrackIds = mutableListOf<TrackId>()
            for (staffVoice in partVoices.keys.sortedBy { it.first * 1000 + it.second }) {
                val (staff, voice) = staffVoice
                val pitchTrack = StoragePitchTrack.create(
                    name = "$partName Staff $staff Voice $voice Notes"
                )
                pitchTracks[pitchTrack.id] = pitchTrack
                pitchTrackByStaffVoice[staffVoice] = pitchTrack.id
                val voiceTrack = StorageVoiceTrack.create(
                    name = "Voice $voice (Staff $staff)",
                    voiceNumber = voice,
                    pitchTrackId = pitchTrack.id
                )
                voiceTracks[voiceTrack.id] = voiceTrack
                partVoices[staffVoice] = voiceTrack.id
                voiceTrackIds.add(voiceTrack.id)
            }
            if (voiceTrackIds.isEmpty()) {
                val pitchTrack = StoragePitchTrack.create(name = "$partName Staff 1 Voice 1 Notes")
                pitchTracks[pitchTrack.id] = pitchTrack
                pitchTrackByStaffVoice[1 to 1] = pitchTrack.id
                val voiceTrack = StorageVoiceTrack.create("Voice 1", 1, pitchTrack.id)
                voiceTracks[voiceTrack.id] = voiceTrack
                partVoices[1 to 1] = voiceTrack.id
                voiceTrackIds.add(voiceTrack.id)
            }

            // Reconstruct tuplet spans per (staff, voice).
            val tupletSpans = reconstructTupletSpans(partNotes)

            // Populate pitch + voice events.
            val updatedPitchTracks = pitchTracks.toMutableMap()
            val updatedVoiceTracks = voiceTracks.toMutableMap()
            data class OpenImportedSlur(val eventId: EventId, val notation: MusicXmlSlur)
            val openSlurs = mutableMapOf<TrackId, MutableMap<Int, ArrayDeque<OpenImportedSlur>>>()
            val importedSlurs = mutableMapOf<TrackId, MutableList<StorageSlurEvent>>()
            for (noteData in partNotes) {
                val staffVoice = noteData.staff to noteData.voice
                val voiceTrackId = partVoices[staffVoice]
                    ?: partVoices.values.firstOrNull() ?: continue
                val pitchTrackId = updatedVoiceTracks[voiceTrackId]?.pitchTrackId
                    ?: pitchTrackByStaffVoice[staffVoice] ?: continue
                val pitchEvent = StoragePitchEvent.create(
                    onset = noteData.onset,
                    pitches = noteData.pitches,
                    articulations = noteData.articulations
                )
                updatedPitchTracks[pitchTrackId] = updatedPitchTracks.getValue(pitchTrackId).addEvent(pitchEvent)

                // A rest with <display-step>/<display-octave> becomes an explicit display staff position,
                // resolved against the clef in effect on this staff at the rest's onset.
                val rendering = noteData.restDisplay?.let { (step, octave) ->
                    val clef = clefChangesByStaff[noteData.staff].orEmpty()
                        .filter { it.onset <= noteData.onset }.maxByOrNull { it.onset }?.clef
                        ?: initialClefByStaff[noteData.staff] ?: Clef.TREBLE
                    val noteIndex = "CDEFGAB".indexOf(step.uppercase())
                    if (noteIndex < 0) noteData.rendering
                    else {
                        val ds = (octave - 4) * 7 + noteIndex
                        val staffPosition = StaffPositionComputer.staffPositionOf(ds, clef)
                        (noteData.rendering ?: RenderingProps()).copy(restStaffPosition = staffPosition)
                    }
                } ?: noteData.rendering

                val voiceEvent = StorageVoiceEvent.create(
                    onset = noteData.onset,
                    pitchEventId = pitchEvent.id,
                    duration = noteData.duration,
                    rendering = rendering,
                    ties = noteData.ties,
                    tupletSpan = tupletSpans[Triple(noteData.staff, noteData.voice, noteData.onset)],
                    slurStarts = noteData.slurStarts,
                    slurEnds = noteData.slurEnds,
                    graceInfo = noteData.graceInfo
                )
                val currentVoiceTrack = updatedVoiceTracks[voiceTrackId]!!
                updatedVoiceTracks[voiceTrackId] = currentVoiceTrack.addEvent(voiceEvent)

                val ties = noteData.tieNotations.mapNotNull { (pitchIndex, notation) ->
                    tieGeometryFromMusicXml(pitchIndex, notation)
                }
                if (ties.isNotEmpty()) importedTieGeometry[voiceEvent.id] = ties

                val byNumber = openSlurs.getOrPut(voiceTrackId) { mutableMapOf() }
                noteData.slurNotations.filter { it.type == "stop" }.forEach { stop ->
                    val open = byNumber[stop.number]?.removeLastOrNull() ?: return@forEach
                    val id = EventId.generate()
                    importedSlurs.getOrPut(voiceTrackId) { mutableListOf() }
                        .add(StorageSlurEvent(id, open.eventId, voiceEvent.id))
                    slurGeometryFromMusicXml(open.notation, stop)?.let {
                        importedSlurGeometry[id] = it
                    }
                }
                noteData.slurNotations.filter { it.type == "start" }.forEach { start ->
                    byNumber.getOrPut(start.number) { ArrayDeque() }
                        .addLast(OpenImportedSlur(voiceEvent.id, start))
                }
            }
            importedSlurs.forEach { (voiceTrackId, slurs) ->
                updatedVoiceTracks[voiceTrackId]?.let { track ->
                    updatedVoiceTracks[voiceTrackId] = track.copy(slurs = slurs)
                }
            }
            pitchTracks.putAll(updatedPitchTracks)
            voiceTracks.putAll(updatedVoiceTracks)

            // Close any staff still hidden at the part's end (no explicit re-show).
            for ((staffNum, start) in hideStartByStaff) {
                if (partMaxMeasure >= start) {
                    hiddenRangesByStaff.getOrPut(staffNum) { mutableListOf() }
                        .add(MeasureRange(start, partMaxMeasure))
                }
            }

            // Staff tracks (initial clef + clef changes + transpose + attachments).
            val staffTrackIds = mutableListOf<TrackId>()
            val numStaves = maxOf(1, partStaves.keys.maxOrNull() ?: context.staves)
            for (staffNum in 1..numStaves) {
                val staffVoiceIds = partVoices.entries
                    .filter { it.key.first == staffNum }
                    .map { it.value }
                    .ifEmpty { voiceTrackIds }

                val attachments = directionsByStaff[staffNum]
                    ?.let { directionsToAttachments(it) }
                    ?: emptyList()
                // Tempo / words (staff-independent) accumulate score-wide.
                directionsByStaff[staffNum]?.let { collectTempo(it, tempoEvents) }

                val staffTrack = StorageStaffTrack(
                    id = TrackId.generate(),
                    name = if (numStaves > 1) "$partName Staff $staffNum" else partName,
                    clef = initialClefByStaff[staffNum] ?: Clef.TREBLE,
                    keySignature = scoreDefaultKey,
                    transposition = transposeByStaff[staffNum],
                    voiceTrackIds = staffVoiceIds,
                    attachments = attachments,
                    clefChanges = clefChangesByStaff[staffNum]?.sortedBy { it.onset } ?: emptyList(),
                    hiddenRanges = MeasureRanges.normalize(
                        hiddenRangesByStaff[staffNum].orEmpty(), 1, partMaxMeasure
                    )
                )
                staffTracks[staffTrack.id] = staffTrack
                partStaves[staffNum] = staffTrack.id
                staffTrackIds.add(staffTrack.id)
            }
            // Directions whose staff wasn't materialised (e.g. staff 0) → tempo only.
            directionsByStaff.filterKeys { it !in 1..numStaves }
                .values.forEach { collectTempo(it, tempoEvents) }

            instruments += StorageInstrument(
                id = InstrumentId.generate(),
                name = partInfo?.scoreInstrument?.instrumentName ?: partName,
                abbreviation = partInfo?.partAbbreviation,
                staffIds = staffTrackIds,
                playback = InstrumentPlayback(
                    midiProgram = ((partInfo?.midiInstrument?.midiProgram ?: 1) - 1).coerceIn(0, 127)
                )
            )

            val bracket = if (staffTrackIds.size >= 2) BracketStyle.BRACE else BracketStyle.NONE
            staffGroups.add(
                StorageStaffGroup.ofStaffs(
                    bracket = bracket,
                    label = partInfo?.scoreInstrument?.instrumentName ?: partInfo?.partName,
                    barlineConnect = staffTrackIds.size >= 2,
                    staffIds = staffTrackIds
                )
            )
        }

        // Measures with repeat barline info.
        val allMeasures = (1..maxMeasureNumber).map { n ->
            val bl = measureRepeats[n]
            val isForward = bl?.repeat?.direction == "forward"
            val isBackward = bl?.repeat?.direction == "backward"
            StorageMeasure(
                number = n,
                endBarlineType = bl
                    ?.takeUnless { it.location == "left" || isForward || isBackward }
                    ?.barStyle
                    ?.toBarlineType(),
                repeatStart = isForward,
                repeatEnd = isBackward,
                repeatCount = bl?.repeat?.takeIf { isBackward }?.times?.coerceAtLeast(2) ?: 2
            )
        }

        // Global track: key/time changes + tempo.
        val globalEvents = buildList {
            keyChanges.forEach { (m, k) -> add(StorageKeySignatureChange(TimeCode.of(m, Fraction.ZERO), k)) }
            timeChanges.forEach { (m, t) -> add(StorageTimeSignatureChange(TimeCode.of(m, Fraction.ZERO), t)) }
            layoutBreaks.forEach { (m, print) ->
                val onset = TimeCode.of(m, Fraction.ZERO)
                if (print.newPage) add(StoragePageBreak(onset))
                else if (print.newSystem) add(StorageSystemBreak(onset))
            }
        }.sortedBy { it.onset }

        val globalTrack = StorageGlobalTrack(
            id = TrackId.generate(),
            // Multi-part files often repeat the same tempo direction in every part.
            tempoEvents = tempoEvents.distinctBy { it.onset to it.bpm }.sortedBy { it.onset },
            events = globalEvents
        )

        return StorageScore(
            id = scoreId,
            metadata = metadata,
            defaultTimeSignature = scoreDefaultTime,
            defaultKeySignature = scoreDefaultKey,
            initialBarlineType = measureRepeats[1]
                ?.takeIf { it.location == "left" && it.repeat == null }
                ?.barStyle
                ?.toBarlineType()
                ?: BarlineType.SINGLE,
            measures = allMeasures,
            pitchTracks = pitchTracks,
            voiceTracks = voiceTracks,
            staffTracks = staffTracks,
            instruments = instruments,
            globalTrack = globalTrack,
            staffGroups = staffGroups,
            pageLayout = importPageLayout(xmlScore.defaults, layoutBreaks.isNotEmpty()),
            geometry = ScoreGeometry(
                ties = importedTieGeometry,
                slurs = importedSlurGeometry,
            ).takeUnless { it.isEmpty },
        )
    }

    private fun String.toBarlineType(): BarlineType? = when (lowercase()) {
        "regular" -> BarlineType.SINGLE
        "light-light" -> BarlineType.DOUBLE
        "light-heavy" -> BarlineType.FINAL
        "heavy-light" -> BarlineType.REVERSE_FINAL
        "dashed" -> BarlineType.DASHED
        "dotted" -> BarlineType.DOTTED
        "short" -> BarlineType.SHORT
        "tick" -> BarlineType.TICK
        else -> null
    }

    private fun importPageLayout(defaults: MusicXmlDefaults?, hasForcedBreaks: Boolean): PageLayoutConfig {
        val hasPageLayout = defaults?.pageLayout != null
        val base = PageLayoutConfig.DEFAULT.copy(paginated = hasForcedBreaks || hasPageLayout)
        if (defaults == null) return base

        val tenthsToMm = defaults.scaling?.let { scaling ->
            if (scaling.tenths > 0f) scaling.millimeters / scaling.tenths else null
        } ?: (base.staffSpaceMm / 10f)

        val layout = defaults.pageLayout ?: return base.copy(staffSpaceMm = tenthsToMm * 10f)
        val margins = layout.margins

        fun Float?.toMmOr(default: Float): Float = this?.let { it * tenthsToMm } ?: default

        return base.copy(
            paperWidthMm = layout.pageWidth.toMmOr(base.paperWidthMm),
            paperHeightMm = layout.pageHeight.toMmOr(base.paperHeightMm),
            marginTopMm = margins?.topMargin.toMmOr(base.marginTopMm),
            marginBottomMm = margins?.bottomMargin.toMmOr(base.marginBottomMm),
            marginLeftMm = margins?.leftMargin.toMmOr(base.marginLeftMm),
            marginRightMm = margins?.rightMargin.toMmOr(base.marginRightMm),
            staffSpaceMm = tenthsToMm * 10f,
            presetName = null
        )
    }

    private fun extractMetadata(xmlScore: MusicXmlScore): ScoreMetadata {
        return ScoreMetadata(
            title = xmlScore.getTitle(),
            subtitle = xmlScore.getSubtitle(),
            composer = xmlScore.getCreator("composer"),
            arranger = xmlScore.getCreator("arranger"),
            lyricist = xmlScore.getCreator("lyricist"),
            copyright = xmlScore.identification?.rights ?: xmlScore.getCreator("rights")
        )
    }

    // ---- Note stream walking (cursor honoring backup/forward, chords, grace) ----

    private class MeasureResult(
        val notes: List<NoteImportData>,
        val directions: List<DirectionWithOnset>,
        val attributes: List<AttributesWithOnset>
    )

    private fun processMeasure(
        xmlMeasure: MusicXmlMeasure,
        partVoices: MutableMap<Pair<Int, Int>, TrackId>,
        partStaves: MutableMap<Int, TrackId>
    ): MeasureResult {
        val result = mutableListOf<NoteImportData>()
        val directions = mutableListOf<DirectionWithOnset>()
        val attributes = mutableListOf<AttributesWithOnset>()
        val measureNumber = xmlMeasure.number

        var cursor = 0                 // divisions from measure start
        var pendingChordStart = 0      // cursor position where the current chord began
        val pendingChord = mutableListOf<MusicXmlNote>()
        val pendingGrace = mutableListOf<MusicXmlNote>()

        fun flushPendingChord() {
            if (pendingChord.isEmpty()) return
            val first = pendingChord.first()
            val voice = first.voice
            val staff = first.staff
            partVoices.getOrPut(staff to voice) { TrackId.generate() }
            partStaves.getOrPut(staff) { TrackId.generate() }

            val beat = positionToFraction(pendingChordStart)
            emitGraceGroup(pendingGrace, measureNumber, beat, staff, voice, partVoices, partStaves, result)
            pendingGrace.clear()

            val onset = TimeCode.of(measureNumber, beat)
            val pitches = pendingChord.mapNotNull { it.pitch?.let { p -> PitchConverter.fromMusicXml(p) } }
            result.add(
                NoteImportData(
                    onset = onset,
                    pitches = pitches,
                    duration = DurationConverter.fromMusicXml(first, context.divisions),
                    voice = voice,
                    staff = staff,
                    rendering = extractRenderingProps(first),
                    articulations = extractArticulations(first),
                    ties = extractTies(pendingChord),
                    tieNotations = extractTieNotations(pendingChord),
                    slurNotations = first.notations?.slurs.orEmpty(),
                    slurStarts = countSlurs(first, "start"),
                    slurEnds = countSlurs(first, "stop"),
                    tupletStartActual = tupletStartActual(first),
                    tupletStartStyle = tupletStartStyle(first),
                    tupletStop = first.notations?.tuplets?.any { it.type == "stop" } == true,
                    restDisplay = first.rest?.let { r ->
                        val step = r.displayStep; val oct = r.displayOctave
                        if (step != null && oct != null) step to oct else null
                    }
                )
            )
            pendingChord.clear()
        }

        for (element in xmlMeasure.getOrderedElements()) {
            when (element) {
                is MusicXmlNote -> {
                    when {
                        element.isGrace -> pendingGrace.add(element)
                        element.isChord -> pendingChord.add(element)
                        else -> {
                            flushPendingChord()
                            pendingChord.add(element)
                            pendingChordStart = cursor
                            cursor += element.duration ?: 0
                        }
                    }
                }
                is MusicXmlTimeMovement.Backup -> {
                    flushPendingChord()
                    cursor = maxOf(0, cursor - element.duration)
                }
                is MusicXmlTimeMovement.Forward -> {
                    flushPendingChord()
                    cursor += element.duration
                }
                is MusicXmlDirection -> {
                    directions.add(
                        DirectionWithOnset(
                            onset = TimeCode.of(measureNumber, positionToFraction(cursor)),
                            staff = element.staff ?: 1,
                            direction = element
                        )
                    )
                }
                is MusicXmlAttributes -> {
                    flushPendingChord()
                    attributes.add(AttributesWithOnset(TimeCode.of(measureNumber, positionToFraction(cursor)), element))
                }
            }
        }
        flushPendingChord()
        // Trailing grace notes with no principal: anchor at the cursor position.
        if (pendingGrace.isNotEmpty()) {
            val first = pendingGrace.first()
            emitGraceGroup(
                pendingGrace, measureNumber, positionToFraction(cursor),
                first.staff, first.voice, partVoices, partStaves, result
            )
            pendingGrace.clear()
        }
        return MeasureResult(result, directions, attributes)
    }

    private fun recordClefs(
        attrs: MusicXmlAttributes,
        onset: TimeCode,
        initialClefByStaff: MutableMap<Int, Clef>,
        clefChangesByStaff: MutableMap<Int, MutableList<StorageClefChange>>
    ) {
        for (clef in attrs.clefs) {
            val staff = clef.staff ?: 1
            val meconClef = ImportContext.clefFromSignAndLine(clef.sign, clef.line)
            val isFirstMeasureStart = onset.measure == 1 && (onset.beat ?: Fraction.ZERO).isZero
            if (isFirstMeasureStart && !initialClefByStaff.containsKey(staff)) {
                initialClefByStaff[staff] = meconClef
                continue
            }

            val current = clefChangesByStaff[staff]?.lastOrNull()?.clef
                ?: initialClefByStaff[staff]
                ?: Clef.TREBLE
            if (current != meconClef) {
                clefChangesByStaff.getOrPut(staff) { mutableListOf() }
                    .add(StorageClefChange(onset, meconClef))
            }
        }
    }

    /**
     * Emit a grace-note group sharing the principal's [beat]. Grace components evenly
     * divide the half-open window [-1, 0): k-th of N → -(N-k+1)/N. Metadata
     * ([GraceNoteInfo]) is carried by the first event only.
     */
    private fun emitGraceGroup(
        graces: List<MusicXmlNote>,
        measureNumber: Int,
        beat: Fraction,
        staff: Int,
        voice: Int,
        partVoices: MutableMap<Pair<Int, Int>, TrackId>,
        partStaves: MutableMap<Int, TrackId>,
        out: MutableList<NoteImportData>
    ) {
        if (graces.isEmpty()) return
        partVoices.getOrPut(staff to voice) { TrackId.generate() }
        partStaves.getOrPut(staff) { TrackId.generate() }
        val n = graces.size
        graces.forEachIndexed { index, g ->
            val graceFraction = Fraction(-(n - index), n)
            val onset = TimeCode.of(measureNumber, beat, graceFraction)
            val graceInfo = if (index == 0) {
                GraceNoteInfo(
                    totalDuration = DurationConverter.fromMusicXml(g, context.divisions),
                    stealFrom = if (g.grace?.stealTimePrevious != null) GraceTimeSource.PREVIOUS
                    else GraceTimeSource.PRINCIPAL
                )
            } else null
            out.add(
                NoteImportData(
                    onset = onset,
                    pitches = g.pitch?.let { listOf(PitchConverter.fromMusicXml(it)) } ?: emptyList(),
                    duration = DurationConverter.fromMusicXml(g, context.divisions),
                    voice = g.voice,
                    staff = g.staff,
                    rendering = extractRenderingProps(g),
                    articulations = extractArticulations(g),
                    ties = extractTies(listOf(g)),
                    tieNotations = extractTieNotations(listOf(g)),
                    slurNotations = g.notations?.slurs.orEmpty(),
                    slurStarts = countSlurs(g, "start"),
                    slurEnds = countSlurs(g, "stop"),
                    graceInfo = graceInfo
                )
            )
        }
    }

    private fun positionToFraction(positionDivisions: Int): Fraction =
        if (context.divisions > 0) Fraction(positionDivisions, context.divisions * 4).simplified()
        else Fraction.ZERO

    // ---- Tuplet span reconstruction ----

    private fun tupletStartActual(note: MusicXmlNote): Int? {
        val hasStart = note.notations?.tuplets?.any { it.type == "start" } == true
        return if (hasStart) note.timeModification?.actualNotes else null
    }

    private fun tupletStartStyle(note: MusicXmlNote): TupletDisplayStyle? {
        val start = note.notations?.tuplets?.find { it.type == "start" } ?: return null
        return when {
            start.bracket == false && start.showNumber == "none" -> TupletDisplayStyle.NONE
            start.bracket == false -> TupletDisplayStyle.NUMBER_ONLY
            else -> TupletDisplayStyle.BRACKET_AND_NUMBER
        }
    }

    /** Build a map (staff, voice, startOnset) → [TupletSpan] from tuplet start/stop markers. */
    private fun reconstructTupletSpans(notes: List<NoteImportData>): Map<Triple<Int, Int, TimeCode>, TupletSpan> {
        val spans = mutableMapOf<Triple<Int, Int, TimeCode>, TupletSpan>()
        val byVoice = notes.groupBy { it.staff to it.voice }
        for ((_, voiceNotes) in byVoice) {
            val ordered = voiceNotes.sortedBy { it.onset }
            var startIndex: Int? = null
            for (i in ordered.indices) {
                val nd = ordered[i]
                if (nd.tupletStartActual != null && startIndex == null) startIndex = i
                if (nd.tupletStop && startIndex != null) {
                    val start = ordered[startIndex]
                    val nextOnset = ordered.getOrNull(i + 1)?.onset
                        ?: (nd.onset + nd.duration.toFraction())
                    spans[Triple(start.staff, start.voice, start.onset)] = TupletSpan(
                        endTimeCode = nextOnset,
                        count = start.tupletStartActual ?: 3,
                        beatUnit = start.duration.base,
                        displayStyle = start.tupletStartStyle ?: TupletDisplayStyle.BRACKET_AND_NUMBER
                    )
                    startIndex = null
                }
            }
        }
        return spans
    }

    // ---- Directions → storage ----

    private fun directionsToAttachments(directions: List<DirectionWithOnset>): List<StorageStaffAttachment> {
        val attachments = mutableListOf<StorageStaffAttachment>()
        var openWedge: Pair<TimeCode, HairpinType>? = null
        var openOctave: Triple<TimeCode, OctaveShiftType, StaffAttachmentPlacement>? = null

        for (d in directions.sortedBy { it.onset }) {
            val placement = when (d.direction.placement) {
                MusicXmlPlacement.ABOVE -> StaffAttachmentPlacement.ABOVE
                MusicXmlPlacement.BELOW -> StaffAttachmentPlacement.BELOW
                null -> null
            }
            for (type in d.direction.types) {
                when (type) {
                    is MusicXmlDirectionType.Dynamics -> dynamicLevelOf(type.type)?.let { level ->
                        attachments.add(
                            StorageDynamicMark.create(
                                onset = d.onset,
                                level = level,
                                placement = placement ?: StaffAttachmentPlacement.BELOW
                            )
                        )
                    }
                    is MusicXmlDirectionType.Wedge -> when (type.type.lowercase()) {
                        "crescendo" -> openWedge = d.onset to HairpinType.CRESCENDO
                        "diminuendo" -> openWedge = d.onset to HairpinType.DIMINUENDO
                        "stop" -> openWedge?.let { (startOnset, dir) ->
                            attachments.add(
                                StorageHairpin.create(
                                    onset = startOnset,
                                    endOnset = d.onset,
                                    direction = dir,
                                    placement = placement ?: StaffAttachmentPlacement.BELOW
                                )
                            )
                            openWedge = null
                        }
                    }
                    is MusicXmlDirectionType.OctaveShift -> when (type.type.lowercase()) {
                        // MusicXML convention (as written by Finale/MuseScore):
                        //   8va alta  ↔ type="down"  → OTTAVA
                        //   8vb bassa ↔ type="up"     → OTTAVA_BASSA
                        "down" -> openOctave = Triple(
                            d.onset, OctaveShiftType.OTTAVA, placement ?: StaffAttachmentPlacement.ABOVE
                        )
                        "up" -> openOctave = Triple(
                            d.onset, OctaveShiftType.OTTAVA_BASSA, placement ?: StaffAttachmentPlacement.BELOW
                        )
                        "stop" -> openOctave?.let { (startOnset, shiftType, place) ->
                            val end = StorageOctaveShiftEnd.create(onset = d.onset, placement = place)
                            attachments.add(
                                StorageOctaveShiftStart.create(
                                    onset = startOnset,
                                    shiftType = shiftType,
                                    endEventId = end.id,
                                    placement = place
                                )
                            )
                            attachments.add(end)
                            openOctave = null
                        }
                    }
                    else -> { /* words / metronome handled elsewhere */ }
                }
            }
        }
        return attachments
    }

    private fun collectTempo(directions: List<DirectionWithOnset>, out: MutableList<StorageTempoEvent>) {
        for (d in directions) {
            val metronome = d.direction.types.filterIsInstance<MusicXmlDirectionType.Metronome>().firstOrNull()
            val words = d.direction.types.filterIsInstance<MusicXmlDirectionType.Words>().firstOrNull()?.text
            when {
                metronome != null -> {
                    val beatUnit = DurationConverter.beatUnitToBase(metronome.beatUnit)
                    out.add(StorageTempoEvent(
                        id = EventId.generate(),
                        onset = d.onset,
                        bpm = metronome.perMinute * beatUnit.quarterNoteFactor(),
                        beatUnit = beatUnit,
                        text = words,
                        markType = TempoMarkType.METRONOME,
                        displayStyle = if (words.isNullOrBlank()) TempoDisplayStyle.METRONOME
                            else TempoDisplayStyle.TEXT_AND_METRONOME,
                    ))
                }
                d.direction.sound?.tempo != null -> out.add(
                    StorageTempoEvent(
                        id = EventId.generate(),
                        onset = d.onset,
                        bpm = d.direction.sound!!.tempo!!,
                        beatUnit = DurationBase.QUARTER,
                        text = words,
                        markType = if (words.isNullOrBlank()) TempoMarkType.KEYFRAME else TempoMarkType.CUSTOM,
                        displayStyle = if (words.isNullOrBlank()) TempoDisplayStyle.HIDDEN else TempoDisplayStyle.TEXT,
                    )
                )
                !words.isNullOrBlank() -> {
                    val normalized = words.lowercase().trim()
                    val type = when {
                        "più mosso" in normalized || "piu mosso" in normalized -> TempoMarkType.PIU_MOSSO
                        "meno mosso" in normalized -> TempoMarkType.MENO_MOSSO
                        normalized == "a tempo" -> TempoMarkType.A_TEMPO
                        normalized.startsWith("tempo i") -> TempoMarkType.TEMPO_I
                        normalized.startsWith("accel") -> TempoMarkType.ACCELERANDO
                        normalized.startsWith("rit") || normalized.startsWith("rall") -> TempoMarkType.RITARDANDO
                        else -> null
                    }
                    if (type != null) {
                        val previous = out.filter { it.onset < d.onset }.maxByOrNull { it.onset }
                        val opening = out.minByOrNull { it.onset }
                        val source = if (type == TempoMarkType.TEMPO_I) opening else previous
                        val ratio = when (type) {
                            TempoMarkType.PIU_MOSSO -> 1.15f
                            TempoMarkType.MENO_MOSSO -> 0.85f
                            else -> 1f
                        }
                        out.add(StorageTempoEvent(
                            id = EventId.generate(),
                            onset = d.onset,
                            bpm = (source?.bpm ?: 120f) * ratio,
                            text = words,
                            markType = type,
                            displayStyle = if (type in setOf(TempoMarkType.ACCELERANDO, TempoMarkType.RITARDANDO)) {
                                TempoDisplayStyle.GRADUAL_TEXT
                            } else TempoDisplayStyle.TEXT,
                            referenceEventId = source?.id,
                            referenceRatio = ratio,
                            transitionToNext = if (type in setOf(TempoMarkType.ACCELERANDO, TempoMarkType.RITARDANDO)) {
                                TempoTransition.LINEAR
                            } else TempoTransition.STEP,
                        ))
                    }
                }
            }
        }
    }

    private fun dynamicLevelOf(letters: String): DynamicLevel? =
        DynamicLevel.entries.find { it.letters.equals(letters, ignoreCase = true) }

    // ---- Per-note property extraction ----

    private fun extractRenderingProps(note: MusicXmlNote): RenderingProps? {
        val stemDirection = when (note.stem?.value?.lowercase()) {
            "up" -> StemDirection.UP
            "down" -> StemDirection.DOWN
            else -> null
        }
        val accidentalDisplay = note.accidental?.let {
            when {
                it.cautionary -> AccidentalDisplay.CAUTIONARY
                it.parentheses -> AccidentalDisplay.PARENTHESES
                it.editorial -> AccidentalDisplay.FORCE
                else -> AccidentalDisplay.FORCE
            }
        }
        val ornaments = note.notations?.ornaments?.mapNotNull { orn ->
            when (orn) {
                is MusicXmlOrnament.Trill, is MusicXmlOrnament.TrillMark -> Ornament.TRILL
                is MusicXmlOrnament.Mordent -> Ornament.MORDENT
                is MusicXmlOrnament.InvertedMordent -> Ornament.INVERTED_MORDENT
                is MusicXmlOrnament.Turn -> Ornament.TURN
                is MusicXmlOrnament.InvertedTurn -> Ornament.INVERTED_TURN
                else -> null
            }
        }?.takeIf { it.isNotEmpty() }
        val beaming = extractBeamingInfo(note)

        return if (stemDirection != null || accidentalDisplay != null ||
            ornaments != null || beaming != null || !note.printObject
        ) {
            RenderingProps(
                stemDirection = stemDirection,
                accidentalDisplay = accidentalDisplay,
                ornaments = ornaments,
                beaming = beaming,
                hidden = !note.printObject
            )
        } else null
    }

    private fun extractArticulations(note: MusicXmlNote): List<Articulation> {
        val articulations = note.notations?.articulations?.mapNotNull { art ->
            when (art) {
                is MusicXmlArticulation.Staccato -> Articulation.STACCATO
                is MusicXmlArticulation.Spiccato -> Articulation.SPICCATO
                is MusicXmlArticulation.Staccatissimo -> Articulation.STACCATISSIMO
                is MusicXmlArticulation.Tenuto -> Articulation.TENUTO
                is MusicXmlArticulation.Accent -> Articulation.ACCENT
                is MusicXmlArticulation.StrongAccent -> Articulation.MARCATO
                else -> null
            }
        } ?: emptyList()
        val hasFermata = note.notations?.fermatas?.isNotEmpty() == true
        return if (hasFermata) articulations + Articulation.FERMATA else articulations
    }

    private fun extractBeamingInfo(note: MusicXmlNote): BeamingInfo? {
        val beams = note.beams
        if (beams.isEmpty()) {
            // In MusicXML, a beamable note without <beam> explicitly means "draw flags,
            // do not join this note into a beam group".
            return if (note.rest == null && isBeamableNoteType(note.type)) BeamingInfo.NONE else null
        }
        val primaryBeam = beams.find { it.number == 1 } ?: return null
        return when (primaryBeam.value.lowercase()) {
            "begin" -> BeamingInfo.start()
            "continue" -> BeamingInfo.middle()
            "end" -> BeamingInfo.end()
            else -> null
        }
    }

    private fun isBeamableNoteType(type: String?): Boolean = when (type?.lowercase()) {
        "eighth", "16th", "32nd", "64th", "128th", "256th", "512th", "1024th" -> true
        else -> false
    }

    private fun extractTies(notes: List<MusicXmlNote>): List<TieInfo> {
        val ties = mutableListOf<TieInfo>()
        notes.forEachIndexed { index, note ->
            val hasTieStart = note.tie.any { it.type == "start" } ||
                note.notations?.tied?.any { it.type == "start" } == true
            val isLetRing = note.tie.any { it.type == "let-ring" } ||
                note.notations?.tied?.any { it.type == "let-ring" } == true
            if (hasTieStart || isLetRing) {
                ties.add(TieInfo(pitchIndex = index, isLetRing = isLetRing))
            }
        }
        return ties
    }

    private fun extractTieNotations(notes: List<MusicXmlNote>): List<Pair<Int, MusicXmlTied>> =
        notes.mapIndexedNotNull { index, note ->
            note.notations?.tied?.firstOrNull {
                it.type == "start" || it.type == "let-ring"
            }?.let { index to it }
        }

    private fun tieGeometryFromMusicXml(pitchIndex: Int, tied: MusicXmlTied): TieGeometry? {
        val curve = tied.curve
        if (tied.placement == null && tied.orientation == null &&
            curve.relativeX == null && curve.relativeY == null &&
            curve.bezierX == null && curve.bezierY == null
        ) return null
        val above = curveAbove(tied.placement, tied.orientation, tied.curve.bezierY)
        val apex = importedApex(tied.curve.bezierY, default = 0.5f)
        return TieGeometry(
            sourcePitchIndex = pitchIndex,
            startDx = (tied.curve.relativeX ?: 0f) / TENTHS_PER_STAFF_SPACE,
            startDy = -(tied.curve.relativeY ?: 0f) / TENTHS_PER_STAFF_SPACE,
            endDx = 0f,
            endDy = 0f,
            above = above,
            minApex = apex,
            maxApex = maxOf(1.4f, apex),
            directionLocked = tied.placement != null || tied.orientation != null ||
                tied.curve.bezierY != null,
            manuallyAdjusted = tied.curve.bezierY != null,
            autoEndpoints = true,
        )
    }

    private fun slurGeometryFromMusicXml(start: MusicXmlSlur, stop: MusicXmlSlur): SlurGeometry? {
        val placement = start.placement ?: stop.placement
        val bezierY = start.curve.bezierY ?: stop.curve.bezierY
        if (placement == null && bezierY == null &&
            start.curve.relativeX == null && start.curve.relativeY == null &&
            stop.curve.relativeX == null && stop.curve.relativeY == null
        ) return null
        val above = curveAbove(placement, null, bezierY)
        val apex = importedApex(bezierY, default = 0.6f)
        return SlurGeometry(
            startPitchIndex = 0,
            endPitchIndex = 0,
            startDx = (start.curve.relativeX ?: 0f) / TENTHS_PER_STAFF_SPACE,
            startDy = -(start.curve.relativeY ?: 0f) / TENTHS_PER_STAFF_SPACE,
            endDx = (stop.curve.relativeX ?: 0f) / TENTHS_PER_STAFF_SPACE,
            endDy = -(stop.curve.relativeY ?: 0f) / TENTHS_PER_STAFF_SPACE,
            above = above,
            minApex = apex,
            maxApex = maxOf(2f, apex),
            slopeDamping = 1f,
            middleStraightening = 0f,
            directionLocked = placement != null || bezierY != null,
            manuallyAdjusted = bezierY != null,
            autoEndpoints = true,
        )
    }

    private fun curveAbove(
        placement: MusicXmlPlacement?,
        orientation: String?,
        bezierY: Float?,
    ): Boolean = when {
        orientation == "over" || placement == MusicXmlPlacement.ABOVE -> true
        orientation == "under" || placement == MusicXmlPlacement.BELOW -> false
        else -> (bezierY ?: 1f) >= 0f
    }

    private fun importedApex(bezierY: Float?, default: Float): Float =
        bezierY?.let { abs(it) / TENTHS_PER_STAFF_SPACE * BEZIER_APEX_FACTOR }
            ?.coerceIn(0.25f, 8f) ?: default

    private fun countSlurs(note: MusicXmlNote, type: String): Int =
        note.notations?.slurs?.count { it.type == type } ?: 0
}

/** A parsed direction tagged with the measure-relative onset where it occurs. */
private data class DirectionWithOnset(
    val onset: TimeCode,
    val staff: Int,
    val direction: MusicXmlDirection
)

/** A parsed attributes element tagged with the measure-relative onset where it occurs. */
private data class AttributesWithOnset(
    val onset: TimeCode,
    val attributes: MusicXmlAttributes
)

/** Intermediate data for a note/chord/grace during import. */
private data class NoteImportData(
    val onset: TimeCode,
    val pitches: List<Pitch>,
    val duration: Duration,
    val voice: Int,
    val staff: Int,
    val rendering: RenderingProps?,
    val articulations: List<Articulation>,
    val ties: List<TieInfo>,
    val tieNotations: List<Pair<Int, MusicXmlTied>> = emptyList(),
    val slurNotations: List<MusicXmlSlur> = emptyList(),
    val slurStarts: Int = 0,
    val slurEnds: Int = 0,
    val graceInfo: GraceNoteInfo? = null,
    val tupletStartActual: Int? = null,
    val tupletStartStyle: TupletDisplayStyle? = null,
    val tupletStop: Boolean = false,
    /** Rest `<display-step>`/`<display-octave>` (letter, octave), resolved to a staff position at assembly. */
    val restDisplay: Pair<String, Int>? = null
)

private const val TENTHS_PER_STAFF_SPACE = 10f
private const val BEZIER_APEX_FACTOR = 0.75f
