package com.mecon.core.musicxml.export

import com.mecon.api.primitive.*
import com.mecon.api.runtime.RuntimeScore
import com.mecon.api.runtime.resolvedTempoKeyframes
import com.mecon.api.storage.*
import com.mecon.api.storage.events.*
import com.mecon.api.storage.tracks.*
import com.mecon.api.computed.BeamInfo
import com.mecon.core.engine.StaffPositionComputer
import com.mecon.core.musicxml.import.DurationConverter
import com.mecon.core.musicxml.import.PitchConverter
import com.mecon.core.musicxml.model.*

/**
 * Exports StorageScore to the MusicXML intermediate model.
 *
 * Conversions wired here (StorageScore → intermediate model):
 * - Pitches, durations (incl. tuplet ratio), rests, chords, ties, beams, articulations, ornaments.
 * - Clefs: initial ([StorageStaffTrack.clef]) + mid-score ([clefChanges]).
 * - Key / time signature changes from [StorageGlobalTrack.events].
 * - Tempo from [StorageGlobalTrack.tempoEvents] (metronome + sound, first part only).
 * - Dynamics / hairpins / 8va-8vb from [StorageStaffTrack.attachments].
 * - Slurs ([slurStarts]/[slurEnds]) with stack-based numbering; tuplet brackets ([TupletSpan]).
 * - Grace notes ([TimeCode.grace] + [GraceNoteInfo]); transposing instruments ([transposition]).
 * - Repeat barlines.
 * - Forced system/page breaks.
 * - Page size, margins, and scale defaults.
 *
 * Multi-voice timing is realised by the writer, which inserts `<backup>` between voice groups.
 */
class MusicXmlExporter {

    private val context = ExportContext()
    private var geometry: ScoreGeometry? = null

    fun export(score: StorageScore): MusicXmlScore {
        geometry = score.geometry
        val automaticBeamInfoByEventId = MusicXmlBeamExport.computeAutomaticBeamInfo(score)
        val identification = exportIdentification(score.metadata)
        val defaults = exportDefaults(score.pageLayout)
        val partList = mutableListOf<MusicXmlPartInfo>()
        val parts = mutableListOf<MusicXmlPart>()

        // Score-wide key / time signature changes keyed by measure number.
        val keyChangeByMeasure = score.globalTrack.events
            .filterIsInstance<StorageKeySignatureChange>()
            .associate { it.onset.measure to it.keySignature }
        val timeChangeByMeasure = score.globalTrack.events
            .filterIsInstance<StorageTimeSignatureChange>()
            .associate { it.onset.measure to it.timeSignature }
        val systemBreakMeasures = score.globalTrack.events
            .filterIsInstance<StorageSystemBreak>()
            .map { it.onset.measure }
            .toSet()
        val pageBreakMeasures = score.globalTrack.events
            .filterIsInstance<StoragePageBreak>()
            .map { it.onset.measure }
            .toSet()

        data class ExportPartSpec(
            val name: String,
            val abbreviation: String?,
            val staffIds: List<TrackId>,
            val midiProgram: Int?
        )
        val exportParts = if (score.instruments.isNotEmpty()) {
            score.instruments.map {
                ExportPartSpec(it.name, it.abbreviation, it.staffIds, it.playback.midiProgram)
            }
        } else {
            score.staffGroups.mapIndexed { index, group ->
                ExportPartSpec(group.label ?: "Part ${index + 1}", group.abbreviation, group.allStaffIds(), null)
            }
        }

        exportParts.forEachIndexed { partIndex, spec ->
            if (spec.staffIds.isEmpty()) return@forEachIndexed
            val partId = "P${partList.size + 1}"
            val instrumentId = "$partId-I1"
            partList += MusicXmlPartInfo(
                id = partId,
                partName = spec.name,
                partAbbreviation = spec.abbreviation,
                scoreInstrument = MusicXmlScoreInstrument(
                    id = instrumentId,
                    instrumentName = spec.name,
                    instrumentAbbreviation = spec.abbreviation
                ),
                midiInstrument = spec.midiProgram?.let {
                    MusicXmlMidiInstrument(id = instrumentId, midiChannel = partIndex + 1, midiProgram = it + 1)
                }
            )
            parts += exportPart(
                score = score,
                staffTrackIds = spec.staffIds,
                partId = partId,
                keyChangeByMeasure = keyChangeByMeasure,
                timeChangeByMeasure = timeChangeByMeasure,
                automaticBeamInfoByEventId = automaticBeamInfoByEventId,
                includeTempo = partIndex == 0,
                systemBreakMeasures = systemBreakMeasures,
                pageBreakMeasures = pageBreakMeasures
            )
        }

        return MusicXmlScore(
            workTitle = score.metadata.title,
            movementTitle = score.metadata.subtitle,
            identification = identification,
            defaults = defaults,
            credits = exportCredits(score.metadata, defaults.pageLayout),
            partList = partList,
            parts = parts
        )
    }

    private fun exportIdentification(metadata: ScoreMetadata): MusicXmlIdentification {
        val creators = mutableListOf<MusicXmlCreator>()
        metadata.composer?.let { creators.add(MusicXmlCreator("composer", it)) }
        metadata.arranger?.let { creators.add(MusicXmlCreator("arranger", it)) }
        metadata.lyricist?.let { creators.add(MusicXmlCreator("lyricist", it)) }
        return MusicXmlIdentification(
            creators = creators,
            rights = metadata.copyright,
            encoding = MusicXmlEncoding(
                software = "Mecon",
                supports = listOf("beam", "tie", "articulations")
            )
        )
    }

    private fun exportDefaults(pageLayout: PageLayoutConfig): MusicXmlDefaults {
        val tenthsPerStaffSpace = 10f
        val tenthsPerMm = tenthsPerStaffSpace / pageLayout.staffSpaceMm

        fun mmToTenths(mm: Float): Float = mm * tenthsPerMm

        return MusicXmlDefaults(
            scaling = MusicXmlScaling(
                millimeters = pageLayout.staffSpaceMm,
                tenths = tenthsPerStaffSpace
            ),
            pageLayout = MusicXmlPageLayout(
                pageHeight = mmToTenths(pageLayout.paperHeightMm),
                pageWidth = mmToTenths(pageLayout.paperWidthMm),
                margins = MusicXmlPageMargins(
                    type = "both",
                    leftMargin = mmToTenths(pageLayout.marginLeftMm),
                    rightMargin = mmToTenths(pageLayout.marginRightMm),
                    topMargin = mmToTenths(pageLayout.marginTopMm),
                    bottomMargin = mmToTenths(pageLayout.marginBottomMm)
                )
            )
        )
    }

    private fun exportCredits(metadata: ScoreMetadata, pageLayout: MusicXmlPageLayout?): List<MusicXmlCredit> {
        val pageWidth = pageLayout?.pageWidth ?: 1600f
        val pageHeight = pageLayout?.pageHeight ?: 2200f
        val margins = pageLayout?.margins
        val contentLeft = margins?.leftMargin ?: pageWidth * 0.08f
        val contentRight = pageWidth - (margins?.rightMargin ?: pageWidth * 0.08f)
        val centerX = (contentLeft + contentRight) / 2f
        val topY = pageHeight - (margins?.topMargin ?: pageHeight * 0.05f)
        val bottomY = margins?.bottomMargin ?: pageHeight * 0.05f

        fun credit(
            type: String,
            text: String,
            defaultX: Float,
            defaultY: Float,
            justify: String,
            halign: String,
            valign: String,
            fontSize: Float
        ): MusicXmlCredit = MusicXmlCredit(
            page = 1,
            creditTypes = listOf(type),
            words = listOf(
                MusicXmlCreditWords(
                    text = text,
                    defaultX = defaultX,
                    defaultY = defaultY,
                    justify = justify,
                    halign = halign,
                    valign = valign,
                    fontSize = fontSize
                )
            )
        )

        return buildList {
            metadata.title.takeIf { it.isNotBlank() }?.let {
                add(
                    credit(
                        type = "title",
                        text = it,
                        defaultX = centerX,
                        defaultY = topY,
                        justify = "center",
                        halign = "center",
                        valign = "top",
                        fontSize = 24f
                    )
                )
            }
            metadata.subtitle?.takeIf { it.isNotBlank() }?.let {
                add(
                    credit(
                        type = "subtitle",
                        text = it,
                        defaultX = centerX,
                        defaultY = topY - 48f,
                        justify = "center",
                        halign = "center",
                        valign = "top",
                        fontSize = 14f
                    )
                )
            }
            metadata.lyricist?.takeIf { it.isNotBlank() }?.let {
                add(
                    credit(
                        type = "lyricist",
                        text = it,
                        defaultX = contentLeft,
                        defaultY = topY - 108f,
                        justify = "left",
                        halign = "left",
                        valign = "top",
                        fontSize = 12f
                    )
                )
            }
            metadata.composer?.takeIf { it.isNotBlank() }?.let {
                add(
                    credit(
                        type = "composer",
                        text = it,
                        defaultX = contentRight,
                        defaultY = topY - 108f,
                        justify = "right",
                        halign = "right",
                        valign = "top",
                        fontSize = 12f
                    )
                )
            }
            metadata.arranger?.takeIf { it.isNotBlank() }?.let {
                add(
                    credit(
                        type = "arranger",
                        text = it,
                        defaultX = contentRight,
                        defaultY = topY - 144f,
                        justify = "right",
                        halign = "right",
                        valign = "top",
                        fontSize = 10f
                    )
                )
            }
            metadata.copyright?.takeIf { it.isNotBlank() }?.let {
                add(
                    credit(
                        type = "rights",
                        text = it,
                        defaultX = centerX,
                        defaultY = bottomY,
                        justify = "center",
                        halign = "center",
                        valign = "bottom",
                        fontSize = 9f
                    )
                )
            }
        }
    }

    /** Per-voice export metadata. */
    private data class VoiceMeta(val mxlVoice: Int, val staffNumber: Int, val staffTrackId: TrackId)

    private data class ClefChangeExport(val onset: TimeCode, val staffNumber: Int, val clef: Clef)

    private data class TimedMeasureElement(val onset: TimeCode, val element: MusicXmlMeasureElement)

    /** Tuplet bracket marker resolved per voice event. */
    private data class TupletMarker(val isStart: Boolean, val isStop: Boolean, val span: TupletSpan?)
    private data class SlurMarker(val number: Int, val slurId: EventId? = null)

    private fun exportPart(
        score: StorageScore,
        staffTrackIds: List<TrackId>,
        partId: String,
        keyChangeByMeasure: Map<Int, KeySignature>,
        timeChangeByMeasure: Map<Int, TimeSignature>,
        automaticBeamInfoByEventId: Map<EventId, BeamInfo>,
        includeTempo: Boolean,
        systemBreakMeasures: Set<Int>,
        pageBreakMeasures: Set<Int>
    ): MusicXmlPart {
        val numStaves = staffTrackIds.size

        // Assign globally-unique MusicXML voice numbers within the part, and remember
        // each voice's staff + initial clef.
        val voiceMetaById = mutableMapOf<TrackId, VoiceMeta>()
        val initialClefByStaff = mutableMapOf<Int, Clef>()
        val clefChangesByMeasure = mutableMapOf<Int, MutableList<ClefChangeExport>>()
        // Per-staff visibility transitions → <staff-details print-object="no|yes"> at the measure a staff
        // enters / leaves a hidden range (see [StorageStaffTrack.hiddenRanges]).
        val staffDetailsByMeasure = mutableMapOf<Int, MutableList<MusicXmlStaffDetails>>()
        val maxMeasure = score.measures.maxOfOrNull { it.number } ?: 0
        var nextVoiceNumber = 1
        staffTrackIds.forEachIndexed { staffIndex, staffTrackId ->
            val staffNumber = staffIndex + 1
            val staffTrack = score.staffTracks[staffTrackId] ?: return@forEachIndexed
            initialClefByStaff[staffNumber] = staffTrack.clef
            for (change in staffTrack.clefChanges) {
                clefChangesByMeasure.getOrPut(change.onset.measure) { mutableListOf() }
                    .add(ClefChangeExport(change.onset, staffNumber, change.clef))
            }
            if (staffTrack.hiddenRanges.isNotEmpty()) {
                var prevHidden = false
                for (m in 1..maxMeasure) {
                    val hidden = MeasureRanges.contains(staffTrack.hiddenRanges, m)
                    if (hidden != prevHidden) {
                        staffDetailsByMeasure.getOrPut(m) { mutableListOf() }.add(
                            MusicXmlStaffDetails(
                                staff = if (numStaves > 1) staffNumber else null,
                                printObject = !hidden,
                            )
                        )
                    }
                    prevHidden = hidden
                }
            }
            for (voiceTrackId in staffTrack.voiceTrackIds) {
                voiceMetaById[voiceTrackId] = VoiceMeta(nextVoiceNumber++, staffNumber, staffTrackId)
            }
        }

        // Slur and tuplet markers, resolved once per voice track (stateful across measures).
        val slurByEvent = mutableMapOf<EventId, Pair<List<SlurMarker>, List<SlurMarker>>>()
        val tupletByEvent = mutableMapOf<EventId, TupletMarker>()
        for (staffTrackId in staffTrackIds) {
            val staffTrack = score.staffTracks[staffTrackId] ?: continue
            for (voiceTrackId in staffTrack.voiceTrackIds) {
                val voiceTrack = score.voiceTracks[voiceTrackId] ?: continue
                resolveSlurs(voiceTrack, slurByEvent)
                resolveTuplets(voiceTrack, tupletByEvent)
            }
        }

        // Flatten all voice events of the part, tagged with their voice metadata.
        data class TaggedEvent(val event: StorageVoiceEvent, val meta: VoiceMeta, val pitchEvent: StoragePitchEvent?)
        val tagged = mutableListOf<TaggedEvent>()
        for (staffTrackId in staffTrackIds) {
            val staffTrack = score.staffTracks[staffTrackId] ?: continue
            for (voiceTrackId in staffTrack.voiceTrackIds) {
                val voiceTrack = score.voiceTracks[voiceTrackId] ?: continue
                val meta = voiceMetaById[voiceTrackId] ?: continue
                for (voiceEvent in voiceTrack.events) {
                    val pitchEvent = score.findPitchEvent(voiceEvent.pitchEventId)
                    tagged.add(TaggedEvent(voiceEvent, meta, pitchEvent))
                }
            }
        }
        val eventsByMeasure = tagged.groupBy { it.event.onset.measure }

        // Directions (dynamics / hairpins / octave shifts / tempo) keyed by measure.
        val directionsByMeasure = buildDirectionsByMeasure(score, staffTrackIds, voiceMetaById, includeTempo)

        val measures = mutableListOf<MusicXmlMeasure>()
        for (storageMeasure in score.measures) {
            val measureNumber = storageMeasure.number
            val isFirst = measureNumber == 1
            val measureEvents = eventsByMeasure[measureNumber] ?: emptyList()

            val attributes = exportAttributes(
                score = score,
                measureNumber = measureNumber,
                isFirst = isFirst,
                keyChange = keyChangeByMeasure[measureNumber],
                timeChange = timeChangeByMeasure[measureNumber],
                initialClefByStaff = initialClefByStaff,
                clefChangesAtMeasureStart = clefChangesByMeasure[measureNumber]
                    .orEmpty()
                    .filter { it.onset.beat == null || it.onset.beat == Fraction.ZERO }
                    .associate { it.staffNumber to it.clef },
                numStaves = numStaves,
                transposition = score.staffTracks[staffTrackIds.first()]?.transposition,
                staffDetails = staffDetailsByMeasure[measureNumber].orEmpty()
            )

            // Notes grouped by voice (ascending), preserving onset + chord order within a voice.
            val notes = mutableListOf<MusicXmlNote>()
            val byVoice = measureEvents.groupBy { it.meta.mxlVoice }
            for (mxlVoice in byVoice.keys.sorted()) {
                val voiceEvents = byVoice.getValue(mxlVoice).sortedBy { it.event.onset }
                for (te in voiceEvents) {
                    notes.addAll(
                        exportEvent(
                            te.event,
                            te.meta,
                            te.pitchEvent,
                            slurByEvent,
                            tupletByEvent,
                            automaticBeamInfoByEventId,
                            effectiveClefAt(score, te.meta.staffTrackId, te.event.onset)
                        )
                    )
                }
            }
            val inlineClefElements = clefChangesByMeasure[measureNumber]
                .orEmpty()
                .filter { (it.onset.beat ?: Fraction.ZERO) != Fraction.ZERO }
                .sortedBy { it.onset }
                .map { TimedMeasureElement(it.onset, exportClefAttributes(it.clef, it.staffNumber, numStaves)) }
            val orderedElements = if (inlineClefElements.isNotEmpty()) {
                buildElementsWithInlineAttributes(inlineClefElements)
            } else {
                emptyList()
            }

            val barline = exportBarline(
                storageMeasure,
                initialType = score.initialBarlineType.takeIf {
                    measureNumber == 1 && it != BarlineType.SINGLE
                },
            )

            measures.add(
                MusicXmlMeasure(
                    number = measureNumber,
                    print = exportPrint(measureNumber, systemBreakMeasures, pageBreakMeasures),
                    attributes = attributes,
                    directions = directionsByMeasure[measureNumber].orEmpty(),
                    notes = notes,
                    elements = orderedElements,
                    barline = barline
                )
            )
        }

        return MusicXmlPart(id = partId, measures = measures)
    }

    // ---- Attributes ----

    private fun exportAttributes(
        score: StorageScore,
        measureNumber: Int,
        isFirst: Boolean,
        keyChange: KeySignature?,
        timeChange: TimeSignature?,
        initialClefByStaff: Map<Int, Clef>,
        clefChangesAtMeasureStart: Map<Int, Clef>,
        numStaves: Int,
        transposition: TranspositionConfig?,
        staffDetails: List<MusicXmlStaffDetails> = emptyList()
    ): MusicXmlAttributes? {
        val keys = mutableListOf<MusicXmlKey>()
        val times = mutableListOf<MusicXmlTime>()
        val clefs = mutableListOf<MusicXmlClef>()

        when {
            isFirst -> keys.add(exportKey(score.defaultKeySignature))
            keyChange != null -> keys.add(exportKey(keyChange))
        }
        when {
            isFirst -> times.add(exportTime(score.defaultTimeSignature))
            timeChange != null -> times.add(exportTime(timeChange))
        }

        if (isFirst) {
            for (staffNum in 1..numStaves) {
                initialClefByStaff[staffNum]?.let {
                    clefs.add(exportClef(it, if (numStaves > 1) staffNum else null))
                }
            }
        } else {
            for ((staffNum, clef) in clefChangesAtMeasureStart) {
                clefs.add(exportClef(clef, if (numStaves > 1) staffNum else null))
            }
        }

        val transpose = if (isFirst && transposition != null && transposition.interval.semitones != 0) {
            MusicXmlTranspose(
                chromatic = transposition.interval.semitones,
                octaveChange = transposition.octaveShift
            )
        } else null

        if (keys.isEmpty() && times.isEmpty() && clefs.isEmpty() && transpose == null &&
            staffDetails.isEmpty() && !isFirst) return null

        return MusicXmlAttributes(
            divisions = if (isFirst) context.divisions else null,
            keys = keys,
            times = times,
            staves = if (isFirst && numStaves > 1) numStaves else 1,
            clefs = clefs,
            staffDetails = staffDetails,
            transpose = transpose
        )
    }

    private fun exportClef(clef: Clef, staffNumber: Int?): MusicXmlClef {
        val (sign, line) = context.clefToSignAndLine(clef)
        return MusicXmlClef(sign = sign, line = line.takeIf { it > 0 }, staff = staffNumber)
    }

    private fun exportClefAttributes(clef: Clef, staffNumber: Int, numStaves: Int): MusicXmlAttributes =
        MusicXmlAttributes(clefs = listOf(exportClef(clef, if (numStaves > 1) staffNumber else null)))

    private fun exportKey(key: KeySignature): MusicXmlKey {
        val mode = when (key.mode) {
            Mode.MAJOR -> "major"
            Mode.MINOR -> "minor"
            Mode.DORIAN -> "dorian"
            Mode.PHRYGIAN -> "phrygian"
            Mode.LYDIAN -> "lydian"
            Mode.MIXOLYDIAN -> "mixolydian"
            Mode.AEOLIAN -> "aeolian"
            Mode.LOCRIAN -> "locrian"
        }
        return MusicXmlKey(fifths = key.fifths, mode = mode)
    }

    private fun exportTime(time: TimeSignature): MusicXmlTime {
        val symbol = when (time.symbol) {
            TimeSignatureSymbol.COMMON -> "common"
            TimeSignatureSymbol.CUT -> "cut"
            null -> null
        }
        return MusicXmlTime(
            beats = listOf(time.numerator.toString()),
            beatType = listOf(time.denominator),
            symbol = symbol
        )
    }

    private fun exportBarline(
        storageMeasure: StorageMeasure,
        initialType: BarlineType? = null,
    ): MusicXmlBarline? = when {
        storageMeasure.repeatStart -> MusicXmlBarline(
            location = "left",
            barStyle = "heavy-light",
            repeat = MusicXmlRepeat("forward")
        )
        storageMeasure.repeatEnd -> MusicXmlBarline(
            location = "right",
            barStyle = "light-heavy",
            repeat = MusicXmlRepeat("backward", storageMeasure.repeatCount.takeIf { it > 1 })
        )
        storageMeasure.endBarlineType != null -> MusicXmlBarline(
            location = "right",
            barStyle = requireNotNull(storageMeasure.endBarlineType).toMusicXmlBarStyle(),
        )
        initialType != null -> MusicXmlBarline(
            location = "left",
            barStyle = initialType.toMusicXmlBarStyle(),
        )
        else -> null
    }

    private fun BarlineType.toMusicXmlBarStyle(): String = when (this) {
        BarlineType.SINGLE -> "regular"
        BarlineType.DOUBLE -> "light-light"
        BarlineType.FINAL -> "light-heavy"
        BarlineType.REVERSE_FINAL -> "heavy-light"
        BarlineType.DASHED -> "dashed"
        BarlineType.DOTTED -> "dotted"
        BarlineType.SHORT -> "short"
        BarlineType.TICK -> "tick"
        BarlineType.REPEAT_LEFT -> "heavy-light"
        BarlineType.REPEAT_RIGHT, BarlineType.REPEAT_BOTH -> "light-heavy"
    }

    private fun exportPrint(
        measureNumber: Int,
        systemBreakMeasures: Set<Int>,
        pageBreakMeasures: Set<Int>
    ): MusicXmlPrint? {
        if (measureNumber <= 1) return null
        val newPage = measureNumber in pageBreakMeasures
        val newSystem = measureNumber in systemBreakMeasures || newPage
        return if (newSystem || newPage) MusicXmlPrint(newSystem = newSystem, newPage = newPage) else null
    }

    private fun buildElementsWithInlineAttributes(attributes: List<TimedMeasureElement>): List<MusicXmlMeasureElement> {
        val elements = mutableListOf<MusicXmlMeasureElement>()
        var cursor = 0
        for (timed in attributes.sortedBy { it.onset }) {
            val target = onsetBeatToDivisions(timed.onset)
            val forward = target - cursor
            if (forward > 0) {
                elements.add(MusicXmlTimeMovement.Forward(forward))
                cursor = target
            } else if (forward < 0) {
                elements.add(MusicXmlTimeMovement.Backup(-forward))
                cursor = target
            }
            elements.add(timed.element)
        }
        if (cursor > 0) {
            elements.add(MusicXmlTimeMovement.Backup(cursor))
        }
        return elements
    }

    private fun onsetBeatToDivisions(onset: TimeCode): Int {
        val beat = onset.beat ?: Fraction.ZERO
        val numerator = beat.numerator * context.divisions * 4
        return (numerator + beat.denominator / 2) / beat.denominator
    }

    // ---- Directions ----

    private fun buildDirectionsByMeasure(
        score: StorageScore,
        staffTrackIds: List<TrackId>,
        voiceMetaById: Map<TrackId, VoiceMeta>,
        includeTempo: Boolean
    ): Map<Int, List<MusicXmlDirection>> {
        val byMeasure = mutableMapOf<Int, MutableList<MusicXmlDirection>>()
        fun add(measure: Int, direction: MusicXmlDirection) {
            byMeasure.getOrPut(measure) { mutableListOf() }.add(direction)
        }

        if (includeTempo) {
            val effectiveById = RuntimeScore.fromStorage(score).resolvedTempoKeyframes()
                .associate { it.source.id to it.effectiveBpm }
            for (tempo in score.globalTrack.tempoEvents) {
                val effectiveBpm = effectiveById[tempo.id] ?: tempo.bpm
                val types = mutableListOf<MusicXmlDirectionType>()
                tempo.text?.let { types.add(MusicXmlDirectionType.Words(it)) }
                if (tempo.displayStyle in setOf(
                        TempoDisplayStyle.AUTO,
                        TempoDisplayStyle.METRONOME,
                        TempoDisplayStyle.TEXT_AND_METRONOME,
                        TempoDisplayStyle.METRIC_MODULATION,
                    )) types.add(
                    MusicXmlDirectionType.Metronome(
                        beatUnit = DurationConverter.baseToBeatUnit(tempo.beatUnit),
                        perMinute = tempo.displayedBpm(effectiveBpm),
                    )
                )
                if (types.isEmpty()) types.add(MusicXmlDirectionType.Other("mecon-tempo-keyframe"))
                add(
                    tempo.onset.measure,
                    MusicXmlDirection(
                        types = types,
                        placement = MusicXmlPlacement.ABOVE,
                        sound = MusicXmlSound(tempo = effectiveBpm)
                    )
                )
            }
        }

        staffTrackIds.forEachIndexed { staffIndex, staffTrackId ->
            val staffTrack = score.staffTracks[staffTrackId] ?: return@forEachIndexed
            val staffNumber = staffIndex + 1
            val staffAttr = if (staffTrackIds.size > 1) staffNumber else null
            for (attachment in staffTrack.attachments) {
                when (attachment) {
                    is StorageDynamicMark -> add(
                        attachment.onset.measure,
                        MusicXmlDirection(
                            types = listOf(MusicXmlDirectionType.Dynamics(attachment.level.letters)),
                            placement = toPlacement(attachment.placement),
                            staff = staffAttr
                        )
                    )
                    is StorageHairpin -> {
                        val wedgeType = when (attachment.direction) {
                            HairpinType.CRESCENDO -> "crescendo"
                            HairpinType.DIMINUENDO -> "diminuendo"
                        }
                        add(
                            attachment.onset.measure,
                            MusicXmlDirection(
                                types = listOf(MusicXmlDirectionType.Wedge(wedgeType)),
                                placement = toPlacement(attachment.placement),
                                staff = staffAttr
                            )
                        )
                        add(
                            attachment.endOnset.measure,
                            MusicXmlDirection(
                                types = listOf(MusicXmlDirectionType.Wedge("stop")),
                                placement = toPlacement(attachment.placement),
                                staff = staffAttr
                            )
                        )
                    }
                    is StorageOctaveShiftStart -> {
                        // 8va alta ↔ type="down"; 8vb bassa ↔ type="up" (Finale/MuseScore convention).
                        val type = when (attachment.shiftType) {
                            OctaveShiftType.OTTAVA -> "down"
                            OctaveShiftType.OTTAVA_BASSA -> "up"
                        }
                        add(
                            attachment.onset.measure,
                            MusicXmlDirection(
                                types = listOf(MusicXmlDirectionType.OctaveShift(type, size = 8)),
                                placement = toPlacement(attachment.placement),
                                staff = staffAttr
                            )
                        )
                    }
                    is StorageOctaveShiftEnd -> add(
                        attachment.onset.measure,
                        MusicXmlDirection(
                            types = listOf(MusicXmlDirectionType.OctaveShift("stop", size = 8)),
                            placement = toPlacement(attachment.placement),
                            staff = staffAttr
                        )
                    )
                    else -> { /* other attachments not mapped */ }
                }
            }
        }

        return byMeasure
    }

    private fun toPlacement(placement: StaffAttachmentPlacement): MusicXmlPlacement = when (placement) {
        StaffAttachmentPlacement.ABOVE -> MusicXmlPlacement.ABOVE
        StaffAttachmentPlacement.BELOW -> MusicXmlPlacement.BELOW
    }

    // ---- Slur / tuplet resolution ----

    /** Allocate MusicXML slur numbers across a voice track, honoring LIFO nesting. */
    private fun resolveSlurs(
        voiceTrack: StorageVoiceTrack,
        out: MutableMap<EventId, Pair<List<SlurMarker>, List<SlurMarker>>>
    ) {
        if (voiceTrack.slurs.isNotEmpty()) {
            val numberById = voiceTrack.slurs.sortedBy { slur ->
                voiceTrack.events.firstOrNull { it.id == slur.startEventId }?.onset
            }.mapIndexed { index, slur -> slur.id to (index % 6 + 1) }.toMap()
            for (slur in voiceTrack.slurs) {
                val marker = SlurMarker(numberById.getValue(slur.id), slur.id)
                val startPair = out[slur.startEventId] ?: (emptyList<SlurMarker>() to emptyList())
                out[slur.startEventId] = (startPair.first + marker) to startPair.second
                val endPair = out[slur.endEventId] ?: (emptyList<SlurMarker>() to emptyList())
                out[slur.endEventId] = endPair.first to (endPair.second + marker)
            }
            return
        }
        val openStack = ArrayDeque<Int>()
        val freeNumbers = sortedSetLikeList()
        var nextNew = 1
        for (event in voiceTrack.events.sortedBy { it.onset }) {
            if (event.slurStarts == 0 && event.slurEnds == 0) continue
            val stops = mutableListOf<SlurMarker>()
            repeat(event.slurEnds) {
                val n = openStack.removeLastOrNull() ?: return@repeat
                stops.add(SlurMarker(n))
                freeNumbers.add(n)
            }
            val starts = mutableListOf<SlurMarker>()
            repeat(event.slurStarts) {
                val n = if (freeNumbers.isNotEmpty()) freeNumbers.removeAt(0) else nextNew++
                openStack.addLast(n)
                starts.add(SlurMarker(n))
            }
            out[event.id] = starts to stops
        }
    }

    /** Resolve each [TupletSpan] to a start marker (on its event) and a stop marker (last event in span). */
    private fun resolveTuplets(
        voiceTrack: StorageVoiceTrack,
        out: MutableMap<EventId, TupletMarker>
    ) {
        val ordered = voiceTrack.events.sortedBy { it.onset }
        for ((index, event) in ordered.withIndex()) {
            val span = event.tupletSpan ?: continue
            // Stop is the last event whose onset is strictly before the span's exclusive end.
            var lastIdx = index
            for (j in index until ordered.size) {
                if (ordered[j].onset < span.endTimeCode) lastIdx = j else break
            }
            val stopId = ordered[lastIdx].id
            if (stopId == event.id) {
                out[event.id] = TupletMarker(isStart = true, isStop = true, span = span)
            } else {
                out[event.id] = TupletMarker(isStart = true, isStop = false, span = span)
                out[stopId] = TupletMarker(isStart = false, isStop = true, span = null)
            }
        }
    }

    // ---- Note export ----

    private fun exportEvent(
        voiceEvent: StorageVoiceEvent,
        meta: VoiceMeta,
        pitchEvent: StoragePitchEvent?,
        slurByEvent: Map<EventId, Pair<List<SlurMarker>, List<SlurMarker>>>,
        tupletByEvent: Map<EventId, TupletMarker>,
        automaticBeamInfoByEventId: Map<EventId, BeamInfo>,
        clef: Clef
    ): List<MusicXmlNote> {
        val pitches = pitchEvent?.pitches ?: emptyList()
        return when {
            pitches.isEmpty() -> listOf(exportRest(voiceEvent, meta, clef))
            pitches.size == 1 -> listOf(
                exportNote(
                    voiceEvent,
                    meta,
                    pitchEvent,
                    pitches.first(),
                    isChord = false,
                    slurByEvent = slurByEvent,
                    tupletByEvent = tupletByEvent,
                    automaticBeamInfoByEventId = automaticBeamInfoByEventId
                )
            )
            else -> pitches.mapIndexed { index, pitch ->
                exportNote(
                    voiceEvent,
                    meta,
                    pitchEvent,
                    pitch,
                    isChord = index > 0,
                    slurByEvent = slurByEvent,
                    tupletByEvent = tupletByEvent,
                    automaticBeamInfoByEventId = automaticBeamInfoByEventId
                )
            }
        }
    }

    private fun exportNote(
        voiceEvent: StorageVoiceEvent,
        meta: VoiceMeta,
        pitchEvent: StoragePitchEvent?,
        pitch: Pitch,
        isChord: Boolean,
        slurByEvent: Map<EventId, Pair<List<SlurMarker>, List<SlurMarker>>>,
        tupletByEvent: Map<EventId, TupletMarker>,
        automaticBeamInfoByEventId: Map<EventId, BeamInfo>
    ): MusicXmlNote {
        val xmlPitch = PitchConverter.toMusicXml(pitch)
        val durationResult = DurationConverter.toMusicXml(voiceEvent.duration, context.divisions)

        val pitchIndex = pitchEvent?.pitches?.indexOf(pitch) ?: -1
        val hasTie = voiceEvent.ties.any { it.pitchIndex == pitchIndex && !it.isLetRing }
        val isLetRing = voiceEvent.ties.any { it.pitchIndex == pitchIndex && it.isLetRing }
        // `<tie>` is the sound element (start/stop only); let-ring is expressed solely
        // by `<tied type="let-ring"/>` in notations (MusicXML 4.0).
        val ties = mutableListOf<MusicXmlTie>()
        if (hasTie) ties.add(MusicXmlTie("start"))

        // Notations (ties, articulations, ornaments, slurs, tuplets) — only on the chord's first note.
        val notations = exportNotations(
            voiceEvent, pitchEvent, pitchIndex, hasTie, isLetRing,
            includeNonTie = !isChord, slurByEvent, tupletByEvent
        )

        val stem = voiceEvent.rendering?.stemDirection?.let {
            when (it) {
                StemDirection.UP -> MusicXmlStem("up")
                StemDirection.DOWN -> MusicXmlStem("down")
                StemDirection.AUTO -> null
            }
        }

        val grace = if (voiceEvent.onset.grace != null) {
            MusicXmlGrace(
                stealTimePrevious = if (voiceEvent.graceInfo?.stealFrom == GraceTimeSource.PREVIOUS) 50 else null
            )
        } else null

        return MusicXmlNote(
            pitch = xmlPitch,
            duration = durationResult.duration,
            isChord = isChord,
            tie = ties,
            voice = meta.mxlVoice,
            type = durationResult.type,
            dots = durationResult.dots,
            timeModification = durationResult.timeModification,
            stem = stem,
            staff = meta.staffNumber,
            beams = MusicXmlBeamExport.exportBeams(voiceEvent, automaticBeamInfoByEventId[voiceEvent.id]),
            grace = grace,
            notations = notations.takeIf {
                it.tied.isNotEmpty() || it.articulations.isNotEmpty() ||
                    it.ornaments.isNotEmpty() || it.fermatas.isNotEmpty() ||
                    it.slurs.isNotEmpty() || it.tuplets.isNotEmpty()
            },
            printObject = voiceEvent.rendering?.hidden != true
        )
    }

    private fun exportRest(voiceEvent: StorageVoiceEvent, meta: VoiceMeta, clef: Clef): MusicXmlNote {
        val durationResult = DurationConverter.toMusicXml(voiceEvent.duration, context.divisions)
        // A non-default display position is written as <display-step>/<display-octave>: the position the
        // rest would occupy if it were a note on that step/octave under the current clef.
        val rest = voiceEvent.rendering?.restStaffPosition?.let { staffPosition ->
            val ds = StaffPositionComputer.diatonicStepsAt(staffPosition, clef)
            val pitch = Pitch(diatonicSteps = ds, chromaticOffset = 0)
            MusicXmlRest(displayStep = pitch.noteName.name, displayOctave = pitch.octave)
        } ?: MusicXmlRest()
        return MusicXmlNote(
            rest = rest,
            duration = durationResult.duration,
            voice = meta.mxlVoice,
            type = durationResult.type,
            dots = durationResult.dots,
            timeModification = durationResult.timeModification,
            staff = meta.staffNumber,
            printObject = voiceEvent.rendering?.hidden != true
        )
    }

    /** The clef in effect on [staffTrackId] at [onset] (last clef change at/before it, else initial). */
    private fun effectiveClefAt(score: StorageScore, staffTrackId: TrackId, onset: TimeCode): Clef {
        val staff = score.staffTracks[staffTrackId] ?: return Clef.TREBLE
        return staff.clefChanges
            .filter { it.onset <= onset }
            .maxByOrNull { it.onset }
            ?.clef
            ?: staff.clef
    }

    private fun exportNotations(
        voiceEvent: StorageVoiceEvent,
        pitchEvent: StoragePitchEvent?,
        pitchIndex: Int,
        hasTie: Boolean,
        isLetRing: Boolean,
        includeNonTie: Boolean,
        slurByEvent: Map<EventId, Pair<List<SlurMarker>, List<SlurMarker>>>,
        tupletByEvent: Map<EventId, TupletMarker>
    ): MusicXmlNotations {
        val tied = mutableListOf<MusicXmlTied>()
        val articulations = mutableListOf<MusicXmlArticulation>()
        val ornaments = mutableListOf<MusicXmlOrnament>()
        val fermatas = mutableListOf<MusicXmlFermata>()
        val slurs = mutableListOf<MusicXmlSlur>()
        val tuplets = mutableListOf<MusicXmlTuplet>()

        val tieGeometry = geometry?.ties?.get(voiceEvent.id)
            ?.firstOrNull { it.sourcePitchIndex == pitchIndex }
        if (hasTie) tied.add(tiedFromGeometry("start", tieGeometry))
        if (isLetRing) tied.add(tiedFromGeometry("let-ring", tieGeometry))

        if (includeNonTie) {
            pitchEvent?.articulations?.forEach { art ->
                when (art) {
                    Articulation.STACCATO -> articulations.add(MusicXmlArticulation.Staccato)
                    Articulation.SPICCATO -> articulations.add(MusicXmlArticulation.Staccatissimo)
                    Articulation.STACCATISSIMO -> articulations.add(MusicXmlArticulation.Staccatissimo)
                    Articulation.TENUTO -> articulations.add(MusicXmlArticulation.Tenuto)
                    Articulation.ACCENT -> articulations.add(MusicXmlArticulation.Accent)
                    Articulation.MARCATO -> articulations.add(MusicXmlArticulation.StrongAccent)
                    Articulation.FERMATA -> fermatas.add(MusicXmlFermata())
                }
            }
            voiceEvent.rendering?.ornaments?.forEach { orn ->
                when (orn) {
                    Ornament.TRILL -> ornaments.add(MusicXmlOrnament.TrillMark())
                    Ornament.MORDENT -> ornaments.add(MusicXmlOrnament.Mordent())
                    Ornament.INVERTED_MORDENT -> ornaments.add(MusicXmlOrnament.InvertedMordent())
                    Ornament.TURN -> ornaments.add(MusicXmlOrnament.Turn())
                    Ornament.INVERTED_TURN -> ornaments.add(MusicXmlOrnament.InvertedTurn())
                }
            }
            slurByEvent[voiceEvent.id]?.let { (starts, stops) ->
                stops.forEach { marker ->
                    slurs.add(slurFromGeometry(
                        "stop", marker, marker.slurId?.let { geometry?.slurs?.get(it) },
                    ))
                }
                starts.forEach { marker ->
                    slurs.add(slurFromGeometry(
                        "start", marker, marker.slurId?.let { geometry?.slurs?.get(it) },
                    ))
                }
            }
            tupletByEvent[voiceEvent.id]?.let { marker ->
                if (marker.isStart) {
                    val style = marker.span?.displayStyle ?: TupletDisplayStyle.BRACKET_AND_NUMBER
                    tuplets.add(
                        MusicXmlTuplet(
                            type = "start",
                            bracket = style == TupletDisplayStyle.BRACKET_AND_NUMBER,
                            showNumber = if (style == TupletDisplayStyle.NONE) "none" else "actual"
                        )
                    )
                }
                if (marker.isStop) tuplets.add(MusicXmlTuplet(type = "stop"))
            }
        }

        return MusicXmlNotations(
            tied = tied,
            articulations = articulations,
            ornaments = ornaments,
            fermatas = fermatas,
            slurs = slurs,
            tuplets = tuplets
        )
    }

    private fun tiedFromGeometry(type: String, geometry: TieGeometry?): MusicXmlTied {
        if (geometry == null) return MusicXmlTied(type)
        val placement = if (geometry.above) MusicXmlPlacement.ABOVE else MusicXmlPlacement.BELOW
        return MusicXmlTied(
            type = type,
            orientation = if (geometry.above) "over" else "under",
            placement = placement,
            curve = MusicXmlCurvePosition(
                relativeX = geometry.startDx * TENTHS_PER_STAFF_SPACE,
                relativeY = -geometry.startDy * TENTHS_PER_STAFF_SPACE,
                bezierY = (if (geometry.above) 1f else -1f) *
                    geometry.minApex / BEZIER_APEX_FACTOR * TENTHS_PER_STAFF_SPACE,
            ),
        )
    }

    private fun slurFromGeometry(
        type: String,
        marker: SlurMarker,
        geometry: SlurGeometry?,
    ): MusicXmlSlur {
        if (geometry == null) return MusicXmlSlur(type = type, number = marker.number)
        val start = type == "start"
        return MusicXmlSlur(
            type = type,
            number = marker.number,
            placement = if (geometry.above) MusicXmlPlacement.ABOVE else MusicXmlPlacement.BELOW,
            curve = MusicXmlCurvePosition(
                relativeX = (if (start) geometry.startDx else geometry.endDx) * TENTHS_PER_STAFF_SPACE,
                relativeY = -(if (start) geometry.startDy else geometry.endDy) * TENTHS_PER_STAFF_SPACE,
                bezierY = (if (geometry.above) 1f else -1f) *
                    geometry.minApex / BEZIER_APEX_FACTOR * TENTHS_PER_STAFF_SPACE,
            ),
        )
    }

    /** Tiny ascending-ordered int list used as a number pool (avoids JVM-only TreeSet in commonMain). */
    private fun sortedSetLikeList(): MutableList<Int> = mutableListOf()

    private companion object {
        const val TENTHS_PER_STAFF_SPACE = 10f
        const val BEZIER_APEX_FACTOR = 0.75f
    }
}
