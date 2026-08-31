package com.mecon.exploration

import com.mecon.api.primitive.Duration
import com.mecon.api.primitive.EventId
import com.mecon.api.primitive.Fraction
import com.mecon.api.primitive.KeySignature
import com.mecon.api.primitive.ScoreId
import com.mecon.api.primitive.StaffGroupId
import com.mecon.api.primitive.TimeCode
import com.mecon.api.primitive.TrackId
import com.mecon.api.storage.PageLayoutConfig
import com.mecon.api.storage.RenderingProps
import com.mecon.api.storage.ScoreMetadata
import com.mecon.api.storage.StemDirection
import com.mecon.api.storage.StorageMeasure
import com.mecon.api.storage.StorageScore
import com.mecon.api.storage.events.StoragePitchEvent
import com.mecon.api.storage.events.StorageVoiceEvent
import com.mecon.api.storage.events.TieInfo
import com.mecon.api.storage.tracks.BracketStyle
import com.mecon.api.storage.tracks.Clef
import com.mecon.api.storage.tracks.StaffGroupMember
import com.mecon.api.storage.tracks.StoragePitchTrack
import com.mecon.api.storage.tracks.StorageStaffGroup
import com.mecon.api.storage.tracks.StorageStaffTrack
import com.mecon.api.storage.tracks.StorageVoiceTrack
import com.mecon.api.storage.tracks.StoragePluginTrack
import com.mecon.plugins.chord.StorageChordEvent
import com.mecon.theory.FixedVoiceRole
import com.mecon.theory.MeterPlan
import com.mecon.theory.NonChordToneType
import com.mecon.theory.chorale.ChoraleEventIds
import com.mecon.theory.chorale.ChoraleLine
import com.mecon.theory.chorale.ChoraleNote
import com.mecon.theory.chorale.ChoraleRealization
import com.mecon.theory.constraint.ConstraintProgram

/**
 * Turns a chorale realization into a playable, renderable SATB score.
 *
 * Unlike [ExplorationScoreAssembler], this cannot go through `FourPartVoicingFrame`: the whole
 * point of the chorale engine is that voices carry independent rhythms, so notes are written at
 * their own onsets and durations. See `docs/theory/chorale-harmonization.md` §7.
 */
object ChoraleScoreAssembler {

    fun assemble(
        realization: ChoraleRealization,
        program: ConstraintProgram,
        keySignature: KeySignature,
        title: String,
    ): StorageScore {
        val meterPlan = program.meterPlan
        val lastEnd = realization.lines.flatMap { it.notes }
            .maxOfOrNull { advance(it.onset, it.duration, meterPlan) }
            ?: TimeCode.of(1, Fraction.ZERO)
        // A note that ends exactly on a downbeat does not open that measure.
        val measureCount = maxOf(
            1,
            if ((lastEnd.beat ?: Fraction.ZERO).isZero) lastEnd.measure - 1 else lastEnd.measure,
        )

        val tracks = ROLES.associateWith { role ->
            val line = realization.lines.firstOrNull { it.role == role }
                ?: ChoraleLine(role, emptyList())
            buildVoice(line, meterPlan)
        }

        return StorageScore(
            id = ScoreId("exploration-chorale"),
            metadata = ScoreMetadata(title = title),
            defaultTimeSignature = meterPlan.timeSignatureAt(1),
            defaultKeySignature = keySignature,
            measures = (1..measureCount).map(::StorageMeasure),
            pitchTracks = ROLES.associate { role ->
                role.pitchTrackId() to StoragePitchTrack(
                    id = role.pitchTrackId(),
                    name = "${role.displayName()} Pitch",
                    events = tracks.getValue(role).pitches,
                )
            },
            voiceTracks = ROLES.associate { role ->
                role.voiceTrackId() to StorageVoiceTrack(
                    id = role.voiceTrackId(),
                    name = role.displayName(),
                    voiceNumber = if (role.isUpperOfItsStaff()) 1 else 2,
                    pitchTrackId = role.pitchTrackId(),
                    events = tracks.getValue(role).voices,
                )
            },
            staffTracks = mapOf(
                UPPER_STAFF to StorageStaffTrack(
                    id = UPPER_STAFF,
                    name = "Soprano / Alto",
                    clef = Clef.TREBLE,
                    keySignature = keySignature,
                    voiceTrackIds = listOf(
                        FixedVoiceRole.SOPRANO.voiceTrackId(),
                        FixedVoiceRole.ALTO.voiceTrackId(),
                    ),
                ),
                LOWER_STAFF to StorageStaffTrack(
                    id = LOWER_STAFF,
                    name = "Tenor / Bass",
                    clef = Clef.BASS,
                    keySignature = keySignature,
                    voiceTrackIds = listOf(
                        FixedVoiceRole.TENOR.voiceTrackId(),
                        FixedVoiceRole.BASS.voiceTrackId(),
                    ),
                ),
            ),
            staffGroups = listOf(
                StorageStaffGroup(
                    id = StaffGroupId("exploration-chorale-group"),
                    bracket = BracketStyle.BRACE,
                    barlineConnect = true,
                    members = listOf(
                        StaffGroupMember.Staff(UPPER_STAFF),
                        StaffGroupMember.Staff(LOWER_STAFF),
                    ),
                )
            ),
            pluginTracks = mapOf(
                CHORD_TRACK to StoragePluginTrack(
                    id = CHORD_TRACK,
                    name = "Chorale Harmony",
                    type = StorageChordEvent.TRACK_TYPE,
                    events = realization.skeleton.mapIndexed { slot, voicing ->
                        StorageChordEvent.create(
                            onset = program.slots[slot].time.onset,
                            root = voicing.target.sonority.root.value,
                            quality = voicing.target.quality,
                            bass = voicing.target.bassPitchClass
                                .takeIf { it != voicing.target.sonority.root }?.value,
                        ).copy(id = EventId("chorale-chord-$slot"))
                    },
                )
            ),
            pageLayout = PageLayoutConfig.DEFAULT.copy(paginated = false),
        )
    }

    private data class VoiceEvents(
        val pitches: List<StoragePitchEvent>,
        val voices: List<StorageVoiceEvent>,
    )

    private fun buildVoice(line: ChoraleLine, meterPlan: MeterPlan): VoiceEvents {
        val pitches = mutableListOf<StoragePitchEvent>()
        val voices = mutableListOf<StorageVoiceEvent>()
        line.notes.forEachIndexed { index, note ->
            val pieces = writable(note.onset, note.duration, meterPlan)
            pieces.forEachIndexed { pieceIndex, (onset, duration) ->
                // The first piece keeps the shared id so findings anchor to the note the engine
                // reasoned about; continuation pieces are tied tails.
                val id = if (pieceIndex == 0) {
                    ChoraleEventIds.note(line.role, note.onset)
                } else {
                    EventId(ChoraleEventIds.note(line.role, note.onset).value + "-tie$pieceIndex")
                }
                val pitchId = if (pieceIndex == 0) {
                    ChoraleEventIds.pitch(line.role, note.onset)
                } else {
                    EventId(ChoraleEventIds.pitch(line.role, note.onset).value + "-tie$pieceIndex")
                }
                pitches += StoragePitchEvent(pitchId, onset, listOf(note.pitch))
                voices += StorageVoiceEvent(
                    id = id,
                    onset = onset,
                    pitchEventId = pitchId,
                    duration = duration,
                    rendering = RenderingProps(
                        stemDirection = if (line.role.isUpperOfItsStaff()) {
                            StemDirection.UP
                        } else StemDirection.DOWN,
                    ),
                    // Tie into the next piece of the same note, and into a suspension, which is
                    // held over from its preparation rather than re-attacked.
                    ties = if (
                        pieceIndex < pieces.lastIndex ||
                        line.notes.getOrNull(index + 1)?.isTiedFrom(note) == true
                    ) listOf(TieInfo(0)) else emptyList(),
                )
            }
        }
        return VoiceEvents(pitches, voices)
    }

    private fun ChoraleNote.isTiedFrom(previous: ChoraleNote): Boolean =
        pitch == previous.pitch &&
            (nonChordTone == NonChordToneType.SUSPENSION || nonChordTone == NonChordToneType.RETARDATION)

    /**
     * Splits one note into notated pieces: at bar lines, and into durations a notehead can express.
     *
     * Custom rhythm patterns can ask for spans no single symbol covers, and a note may straddle a
     * bar line once harmonic spans stop aligning with measures; both come out as tied notes rather
     * than as a rounded, wrong duration.
     */
    private fun writable(
        onset: TimeCode,
        duration: Fraction,
        meterPlan: MeterPlan,
    ): List<Pair<TimeCode, Duration>> {
        val pieces = mutableListOf<Pair<TimeCode, Duration>>()
        var cursor = onset
        var remaining = duration
        var guard = 0
        while (remaining.isPositive) {
            guard++
            require(guard <= MAX_TIED_PIECES) { "Chorale note at $onset could not be notated" }
            val measureLength = meterPlan.timeSignatureAt(cursor.measure).measureDuration()
            val untilBarline = measureLength - (cursor.beat ?: Fraction.ZERO)
            val available = if (untilBarline < remaining) untilBarline else remaining
            val piece = Duration.fromFraction(available) ?: Duration.atMost(available)
            pieces += cursor to piece
            val consumed = piece.toFraction()
            remaining = (remaining - consumed).simplified()
            cursor = advance(cursor, consumed, meterPlan)
        }
        return pieces
    }

    private fun advance(time: TimeCode, delta: Fraction, meterPlan: MeterPlan): TimeCode {
        if (delta.isZero) return time
        var measure = time.measure
        var beat = (time.beat ?: Fraction.ZERO) + delta
        var measureLength = meterPlan.timeSignatureAt(measure).measureDuration()
        while (beat >= measureLength) {
            beat -= measureLength
            measure++
            measureLength = meterPlan.timeSignatureAt(measure).measureDuration()
        }
        return TimeCode.of(measure, beat.simplified())
    }

    private fun FixedVoiceRole.isUpperOfItsStaff(): Boolean =
        this == FixedVoiceRole.SOPRANO || this == FixedVoiceRole.TENOR

    private fun FixedVoiceRole.displayName(): String =
        name.lowercase().replaceFirstChar { it.uppercase() }

    private fun FixedVoiceRole.pitchTrackId() = TrackId("chorale-${name.lowercase()}-pitch")

    private fun FixedVoiceRole.voiceTrackId() = TrackId("chorale-${name.lowercase()}-voice")

    private val ROLES = listOf(
        FixedVoiceRole.SOPRANO,
        FixedVoiceRole.ALTO,
        FixedVoiceRole.TENOR,
        FixedVoiceRole.BASS,
    )
    private val UPPER_STAFF = TrackId("chorale-upper-staff")
    private val LOWER_STAFF = TrackId("chorale-lower-staff")
    private val CHORD_TRACK = TrackId("chorale-chord-track")
    private const val MAX_TIED_PIECES = 16
}
