package com.mecon.exploration

import com.mecon.api.primitive.Duration
import com.mecon.api.primitive.EventId
import com.mecon.api.primitive.Fraction
import com.mecon.api.primitive.KeySignature
import com.mecon.api.primitive.Pitch
import com.mecon.api.primitive.ScoreId
import com.mecon.api.primitive.StaffGroupId
import com.mecon.api.primitive.TimeCode
import com.mecon.api.primitive.TimeSignature
import com.mecon.api.primitive.TrackId
import com.mecon.api.storage.PageLayoutConfig
import com.mecon.api.storage.RenderingProps
import com.mecon.api.storage.ScoreMetadata
import com.mecon.api.storage.StemDirection
import com.mecon.api.storage.StorageMeasure
import com.mecon.api.storage.StorageScore
import com.mecon.api.storage.events.StoragePitchEvent
import com.mecon.api.storage.events.TieInfo
import com.mecon.api.storage.events.StorageVoiceEvent
import com.mecon.api.storage.tracks.BracketStyle
import com.mecon.api.storage.tracks.Clef
import com.mecon.api.storage.tracks.StaffGroupMember
import com.mecon.api.storage.tracks.StoragePitchTrack
import com.mecon.api.storage.tracks.StorageStaffGroup
import com.mecon.api.storage.tracks.StorageStaffTrack
import com.mecon.api.storage.tracks.StorageVoiceTrack
import com.mecon.theory.constraint.ChordVoicing
import com.mecon.theory.constraint.ChordTone
import com.mecon.theory.constraint.ConstraintProgram
import com.mecon.theory.constraint.HarmonicVoiceParticipation
import com.mecon.theory.textbook.TextbookSeventhVoicing
import com.mecon.theory.textbook.RootPositionTriadVoicing
import com.mecon.theory.textbook.TextbookTriadVoicing
import com.mecon.theory.writing.VoicingEventPlanner
import com.mecon.theory.writing.VoicingPlanFrame

object ExplorationScoreAssembler {
    fun assemble(
        title: String,
        keySignature: KeySignature,
        voicings: List<RootPositionTriadVoicing>,
    ): StorageScore = assembleFourPartScore(
        scoreId = "exploration-root-position-triad",
        title = title,
        keySignature = keySignature,
        frames = voicings.map { it.toFrame() },
    )

    fun assembleTextbookTriads(
        title: String,
        keySignature: KeySignature,
        voicings: List<TextbookTriadVoicing>,
    ): StorageScore = assembleFourPartScore(
        scoreId = "exploration-textbook-triad",
        title = title,
        keySignature = keySignature,
        frames = voicings.map { it.toFrame() },
    )

    fun assembleSeventhChords(
        title: String,
        keySignature: KeySignature,
        voicings: List<TextbookSeventhVoicing>,
    ): StorageScore = assembleFourPartScore(
        scoreId = "exploration-dominant-seventh",
        title = title,
        keySignature = keySignature,
        frames = voicings.map { it.toFrame() },
    )

    fun assembleTextbookChords(
        title: String,
        keySignature: KeySignature,
        voicings: List<ChordVoicing>,
        measureBreakBeforeSlots: Set<Int> = emptySet(),
        showTimeSignatures: Boolean = true,
        program: ConstraintProgram? = null,
    ): StorageScore = assembleFourPartScore(
        scoreId = "exploration-textbook-chord",
        title = title,
        keySignature = keySignature,
        frames = voicings.map { it.toFrame() },
        measureBreakBeforeSlots = measureBreakBeforeSlots,
        showTimeSignatures = showTimeSignatures,
        program = program,
    )

    private fun assembleFourPartScore(
        scoreId: String,
        title: String,
        keySignature: KeySignature,
        frames: List<FourPartVoicingFrame>,
        measureBreakBeforeSlots: Set<Int> = emptySet(),
        showTimeSignatures: Boolean = true,
        program: ConstraintProgram? = null,
    ): StorageScore {
        val measureSlotCounts = measureSlotCounts(frames.size, measureBreakBeforeSlots)
        val measureCount = program?.slots?.maxOf { it.time.onset.measure }
            ?: measureSlotCounts?.size
            ?: maxOf(1, (frames.size + 3) / 4)
        val slotOnsets = measureSlotCounts?.let(::slotOnsetsForMeasureSizes)
        val effectiveTimeSignature = program?.meterPlan?.timeSignatureAt(1)
            ?: measureSlotCounts
            ?.firstOrNull()
            ?.let { TimeSignature(it, 4) }
            ?: TimeSignature.COMMON
        val sustainedCells = program?.texturePlan?.participations.orEmpty()
            .filter { it.participation is HarmonicVoiceParticipation.Sustained }
            .flatMap { span ->
                val end = span.window.end ?: frames.lastIndex
                (span.window.start..end).map { it to span.voiceId }
            }
            .toSet()
        val voiceIds = listOf(SOPRANO_VOICE, ALTO_VOICE, TENOR_VOICE, BASS_VOICE)
        val eventPlan = VoicingEventPlanner.plan(
            frames = frames.map { frame ->
                VoicingPlanFrame(
                    slotKey = frame.slotIndex,
                    onset = program?.slots?.get(frame.slotIndex)?.time?.onset
                        ?: slotOnsets?.getValue(frame.slotIndex)
                        ?: onsetForSlot(frame.slotIndex),
                    duration = program?.durationAt(frame.slotIndex) ?: Duration.QUARTER,
                    pitchesByVoiceId = mapOf(
                        SOPRANO_VOICE to frame.soprano,
                        ALTO_VOICE to frame.alto,
                        TENOR_VOICE to frame.tenor,
                        BASS_VOICE to frame.bass,
                    ),
                )
            },
            voiceIds = voiceIds,
            omittedVoiceIdsByFrameIndex = frames.mapIndexedNotNull { frameIndex, frame ->
                sustainedCells
                    .filter { (slotIndex, _) -> slotIndex == frame.slotIndex }
                    .mapTo(linkedSetOf()) { (_, voiceId) -> voiceId }
                    .takeIf { it.isNotEmpty() }
                    ?.let { frameIndex to it }
            }.toMap(),
        )
        val chordTracks = eventPlan.fold(emptyTracks()) { tracks, cell ->
            val voice = voiceCell(cell.voiceId, cell.pitch)
            tracks.addVoiceEvent(
                slotIndex = cell.slotKey,
                voiceTrackId = cell.voiceId,
                pitchTrackId = voice.pitchTrackId,
                pitch = cell.pitch,
                stemDirection = voice.stemDirection,
                onset = cell.onset,
                duration = cell.duration,
            )
        }
        val filled = if (program == null) chordTracks else
            materializeSustainedVoices(chordTracks, frames, program)
        return StorageScore(
            id = ScoreId(scoreId),
            metadata = ScoreMetadata(title = title),
            defaultTimeSignature = effectiveTimeSignature,
            defaultKeySignature = keySignature,
            measures = if (program != null) {
                (1..measureCount).map { measure ->
                    StorageMeasure(
                        number = measure,
                        keySignature = program.keySignatureChangesByMeasure[measure],
                    )
                }
            } else measureSlotCounts?.mapIndexed { index, slotCount ->
                val signature = TimeSignature(slotCount, 4)
                StorageMeasure(
                    number = index + 1,
                    timeSignature = signature.takeIf { signature != effectiveTimeSignature },
                )
            } ?: (1..measureCount).map { StorageMeasure(number = it) },
            pitchTracks = filled.pitchTracks,
            voiceTracks = filled.voiceTracks,
            staffTracks = staffTracks(keySignature),
            staffGroups = listOf(satbStaffGroup()),
            pageLayout = PageLayoutConfig.DEFAULT.copy(paginated = false),
            showTimeSignatures = showTimeSignatures,
        )
    }

    private fun measureSlotCounts(
        slotCount: Int,
        measureBreakBeforeSlots: Set<Int>,
    ): List<Int>? = measureBreakBeforeSlots
        .sorted()
        .takeIf { it.isNotEmpty() }
        ?.also { breaks ->
            require(breaks.all { it in 1 until slotCount }) {
                "Measure breaks must fall between chord slots"
            }
        }
        ?.let { breaks ->
            (listOf(0) + breaks + slotCount).zipWithNext { from, to -> to - from }
        }

    private fun emptyTracks(): Tracks = Tracks(
        pitchTracks = mapOf(
            SOPRANO_PITCH_TRACK to pitchTrack(SOPRANO_PITCH_TRACK, "Soprano"),
            ALTO_PITCH_TRACK to pitchTrack(ALTO_PITCH_TRACK, "Alto"),
            TENOR_PITCH_TRACK to pitchTrack(TENOR_PITCH_TRACK, "Tenor"),
            BASS_PITCH_TRACK to pitchTrack(BASS_PITCH_TRACK, "Bass"),
        ),
        voiceTracks = mapOf(
            SOPRANO_VOICE to voiceTrack(SOPRANO_VOICE, "Soprano", 1, SOPRANO_PITCH_TRACK),
            ALTO_VOICE to voiceTrack(ALTO_VOICE, "Alto", 2, ALTO_PITCH_TRACK),
            TENOR_VOICE to voiceTrack(TENOR_VOICE, "Tenor", 1, TENOR_PITCH_TRACK),
            BASS_VOICE to voiceTrack(BASS_VOICE, "Bass", 2, BASS_PITCH_TRACK),
        ),
    )

    private fun staffTracks(keySignature: KeySignature): Map<TrackId, StorageStaffTrack> =
        mapOf(
            UPPER_STAFF to StorageStaffTrack(
                id = UPPER_STAFF,
                name = "Soprano / Alto",
                clef = Clef.TREBLE,
                keySignature = keySignature,
                voiceTrackIds = listOf(SOPRANO_VOICE, ALTO_VOICE),
                staffLabel = "S/A",
                staffLabelAbbreviation = "S/A",
            ),
            LOWER_STAFF to StorageStaffTrack(
                id = LOWER_STAFF,
                name = "Tenor / Bass",
                clef = Clef.BASS,
                keySignature = keySignature,
                voiceTrackIds = listOf(TENOR_VOICE, BASS_VOICE),
                staffLabel = "T/B",
                staffLabelAbbreviation = "T/B",
            ),
        )

    private fun satbStaffGroup(): StorageStaffGroup = StorageStaffGroup(
        id = StaffGroupId("exploration-satb-group"),
        bracket = BracketStyle.BRACE,
        barlineConnect = true,
        members = listOf(
            StaffGroupMember.Staff(UPPER_STAFF),
            StaffGroupMember.Staff(LOWER_STAFF),
        ),
    )

    private fun Tracks.addVoiceEvent(
        slotIndex: Int,
        voiceTrackId: TrackId,
        pitchTrackId: TrackId,
        pitch: Pitch,
        stemDirection: StemDirection,
        onset: TimeCode,
        duration: Duration = Duration.QUARTER,
        suffix: String = "",
        ties: List<TieInfo> = emptyList(),
    ): Tracks {
        val stableSuffix = suffix.takeIf { it.isNotEmpty() }?.let { "-$it" }.orEmpty()
        val pitchEvent = StoragePitchEvent(
            id = EventId("solver-pitch-$slotIndex-${voiceTrackId.value}$stableSuffix"),
            onset = onset,
            pitches = listOf(pitch),
        )
        val voiceEvent = StorageVoiceEvent(
            id = EventId("solver-voice-$slotIndex-${voiceTrackId.value}$stableSuffix"),
            onset = onset,
            pitchEventId = pitchEvent.id,
            duration = duration,
            rendering = RenderingProps(stemDirection = stemDirection),
            ties = ties,
        )
        return copy(
            pitchTracks = pitchTracks + (pitchTrackId to pitchTracks.getValue(pitchTrackId).copy(
                events = pitchTracks.getValue(pitchTrackId).events + pitchEvent
            )),
            voiceTracks = voiceTracks + (voiceTrackId to voiceTracks.getValue(voiceTrackId).copy(
                events = voiceTracks.getValue(voiceTrackId).events + voiceEvent
            )),
        )
    }

    private fun slotOnsetsForMeasureSizes(measureSlotCounts: List<Int>): Map<Int, TimeCode> {
        var slot = 0
        return buildMap {
            measureSlotCounts.forEachIndexed { measureIndex, count ->
                repeat(count) { beat ->
                    val beatPosition = if (beat == 0) Fraction.ZERO else Fraction(beat, 4)
                    put(slot++, TimeCode.of(measureIndex + 1, beatPosition))
                }
            }
        }
    }

    private data class Tracks(
        val pitchTracks: Map<TrackId, StoragePitchTrack>,
        val voiceTracks: Map<TrackId, StorageVoiceTrack>,
    )

    private data class VoiceCell(
        val voiceId: TrackId,
        val pitchTrackId: TrackId,
        val pitch: Pitch,
        val stemDirection: StemDirection,
    )

    private data class FourPartVoicingFrame(
        val slotIndex: Int,
        val soprano: Pitch,
        val alto: Pitch,
        val tenor: Pitch,
        val bass: Pitch,
        val target: com.mecon.theory.constraint.ChordTarget? = null,
    )

    private fun RootPositionTriadVoicing.toFrame() =
        FourPartVoicingFrame(slotIndex, soprano, alto, tenor, bass)

    private fun TextbookTriadVoicing.toFrame() =
        FourPartVoicingFrame(slotIndex, soprano, alto, tenor, bass)

    private fun TextbookSeventhVoicing.toFrame() =
        FourPartVoicingFrame(slotIndex, soprano, alto, tenor, bass)

    private fun ChordVoicing.toFrame() =
        FourPartVoicingFrame(slotIndex, soprano, alto, tenor, bass, target)

    private fun materializeSustainedVoices(
        initial: Tracks,
        frames: List<FourPartVoicingFrame>,
        program: ConstraintProgram,
    ): Tracks {
        var tracks = initial
        program.texturePlan.participations
            .filter { it.participation is HarmonicVoiceParticipation.Sustained }
            .forEach { span ->
                val participation = span.participation as HarmonicVoiceParticipation.Sustained
                val endSlot = span.window.end ?: frames.lastIndex
                val release = program.texturePlan.sustainedToneReleases
                    .singleOrNull { it.voiceId == span.voiceId && it.slot == endSlot }
                    ?: return@forEach
                val start = program.slots[span.window.start].time.onset
                val boundary = TimeCode.of(release.releaseOnset.measure, Fraction.ZERO)
                val voice = voiceCell(span.voiceId, participation.pitch)
                if (start.measure < release.releaseOnset.measure) {
                    tracks = tracks.addVoiceEvent(
                        slotIndex = span.window.start,
                        voiceTrackId = voice.voiceId,
                        pitchTrackId = voice.pitchTrackId,
                        pitch = participation.pitch,
                        stemDirection = voice.stemDirection,
                        onset = start,
                        duration = Duration.HALF,
                        suffix = "sustain-start",
                        ties = listOf(TieInfo(0)),
                    )
                    tracks = tracks.addVoiceEvent(
                        slotIndex = endSlot,
                        voiceTrackId = voice.voiceId,
                        pitchTrackId = voice.pitchTrackId,
                        pitch = participation.pitch,
                        stemDirection = voice.stemDirection,
                        onset = boundary,
                        duration = Duration.QUARTER,
                        suffix = "sustain-end",
                    )
                } else {
                    tracks = tracks.addVoiceEvent(
                        slotIndex = span.window.start,
                        voiceTrackId = voice.voiceId,
                        pitchTrackId = voice.pitchTrackId,
                        pitch = participation.pitch,
                        stemDirection = voice.stemDirection,
                        onset = start,
                        duration = Duration.QUARTER,
                        suffix = "sustain",
                    )
                }
                val finalFrame = frames[endSlot]
                tracks = tracks.addVoiceEvent(
                    slotIndex = endSlot,
                    voiceTrackId = voice.voiceId,
                    pitchTrackId = voice.pitchTrackId,
                    pitch = successorPitch(participation.pitch, finalFrame),
                    stemDirection = voice.stemDirection,
                    onset = release.releaseOnset,
                    duration = Duration.QUARTER,
                    suffix = "sustain-successor",
                )
            }
        return tracks
    }

    private fun voiceCell(voiceId: TrackId, pitch: Pitch): VoiceCell =
        when (voiceId) {
            SOPRANO_VOICE -> VoiceCell(voiceId, SOPRANO_PITCH_TRACK, pitch, StemDirection.UP)
            ALTO_VOICE -> VoiceCell(voiceId, ALTO_PITCH_TRACK, pitch, StemDirection.DOWN)
            TENOR_VOICE -> VoiceCell(voiceId, TENOR_PITCH_TRACK, pitch, StemDirection.UP)
            BASS_VOICE -> VoiceCell(voiceId, BASS_PITCH_TRACK, pitch, StemDirection.DOWN)
            else -> error("Textbook score assembler does not contain voice $voiceId")
        }

    private fun successorPitch(sustained: Pitch, frame: FourPartVoicingFrame): Pitch {
        val target = frame.target ?: return frame.alto
        val preferredClass = target.pitchClassFor(ChordTone.THIRD)
            ?: target.sonority.pitchClasses.first { it != sustained.pitchClass }
        val spelling = target.spellingFor(preferredClass)
        return ((sustained.octave - 1)..(sustained.octave + 1))
            .map { octave -> spelling?.pitchAt(octave) ?: Pitch.fromMidi(12 * (octave + 1) + preferredClass.value) }
            .minBy { kotlin.math.abs(it.midiNumber - sustained.midiNumber) }
    }
}
private fun pitchTrack(id: TrackId, name: String): StoragePitchTrack =
    StoragePitchTrack(id = id, name = "$name Pitch")

private fun voiceTrack(id: TrackId, name: String, voiceNumber: Int, pitchTrackId: TrackId): StorageVoiceTrack =
    StorageVoiceTrack(id = id, name = name, voiceNumber = voiceNumber, pitchTrackId = pitchTrackId)

private fun onsetForSlot(slotIndex: Int): TimeCode =
    TimeCode.of((slotIndex / 4) + 1, Fraction(slotIndex % 4, 4))

private val UPPER_STAFF = TrackId("solver-upper-staff")
private val LOWER_STAFF = TrackId("solver-lower-staff")
private val SOPRANO_VOICE = TrackId("solver-soprano")
private val ALTO_VOICE = TrackId("solver-alto")
private val TENOR_VOICE = TrackId("solver-tenor")
private val BASS_VOICE = TrackId("solver-bass")
private val SOPRANO_PITCH_TRACK = TrackId("solver-soprano-pitch")
private val ALTO_PITCH_TRACK = TrackId("solver-alto-pitch")
private val TENOR_PITCH_TRACK = TrackId("solver-tenor-pitch")
private val BASS_PITCH_TRACK = TrackId("solver-bass-pitch")
