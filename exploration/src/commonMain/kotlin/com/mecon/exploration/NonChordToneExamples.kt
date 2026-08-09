package com.mecon.exploration

import com.mecon.api.primitive.Duration
import com.mecon.api.primitive.EventId
import com.mecon.api.primitive.Fraction
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
import com.mecon.api.storage.events.StorageVoiceEvent
import com.mecon.api.storage.events.TieInfo
import com.mecon.api.storage.tracks.BracketStyle
import com.mecon.api.storage.tracks.Clef
import com.mecon.api.storage.tracks.StaffGroupMember
import com.mecon.api.storage.tracks.StoragePitchTrack
import com.mecon.api.storage.tracks.StoragePluginTrack
import com.mecon.api.storage.tracks.StorageStaffGroup
import com.mecon.api.storage.tracks.StorageStaffTrack
import com.mecon.api.storage.tracks.StorageVoiceTrack
import com.mecon.plugins.chord.StorageChordEvent
import com.mecon.theory.FixedVoiceRole
import com.mecon.theory.NonChordToneType
import com.mecon.theory.RuleId
import com.mecon.theory.constraint.ChordVoicing
import com.mecon.theory.constraint.ChordTone
import com.mecon.theory.textbook.NonChordToneRules
import com.mecon.theory.textbook.SuspensionInterval
import com.mecon.theory.textbook.TextbookFigurationProblem
import com.mecon.theory.textbook.TextbookFigurationSolver
import com.mecon.theory.textbook.TextbookTriadPosition
import com.mecon.theory.textbook.TextbookTriadWritingSlot
import com.mecon.theory.textbook.textbookTriadInKey

/** 探索页仅把 theory 的“和声骨架 -> 装饰声部”结果转成 StorageScore。 */
internal object NonChordToneExamples {
    fun output(request: RuleExampleRequest, ruleId: RuleId): CellOutput {
        val type = requireNotNull(NonChordToneRules.typeFor(ruleId))
        val example = example(ruleId, type, request.key)
        return CellOutput(
            fingerprint = ExplorationRequestRunner.fingerprint(request),
            candidates = listOf(
                OutputCandidate(
                    score = example.score,
                    totalScore = 0.0,
                    findings = listOf(
                        StoredFinding(
                            ruleId = ruleId.value,
                            severity = "HINT",
                            kind = "INDICATION",
                            messageKey = ruleId.value,
                            anchors = example.anchors,
                            relatedAnchors = example.related,
                        )
                    ),
                    breakdownEntries = emptyList(),
                )
            ),
        )
    }

    private data class NoteSpec(
        val time: TimeCode,
        val pitch: Pitch,
        val duration: Duration,
        val tieToNext: Boolean = false,
    )

    private data class HarmonySpec(
        val degree: Int,
        val position: TextbookTriadPosition = TextbookTriadPosition.ROOT_POSITION,
    )

    private data class Recipe(
        val harmony: List<HarmonySpec>,
        val figuredVoice: FixedVoiceRole = FixedVoiceRole.SOPRANO,
        val deltas: List<Int> = emptyList(),
        val voiceTones: List<ChordTone?> = emptyList(),
        val suspensionIntervals: List<SuspensionInterval>? = null,
        val noteFactory: (List<ChordVoicing>, (Int, Int, Int) -> TimeCode) -> List<NoteSpec>,
        val anchors: List<Int>,
        val related: List<Int>,
    )

    private data class Example(
        val score: StorageScore,
        val anchors: List<EventId>,
        val related: List<EventId>,
    )

    private fun example(ruleId: RuleId, type: NonChordToneType, key: KeySpec): Example {
        fun t(measure: Int, numerator: Int, denominator: Int) = TimeCode.of(measure, Fraction(numerator, denominator))
        val recipe = recipe(ruleId, type, key)
        val theoryKey = key.toKey()
        val slots = recipe.harmony.map { spec ->
            val triad = textbookTriadInKey(theoryKey, spec.degree)
            when (spec.position) {
                TextbookTriadPosition.ROOT_POSITION -> TextbookTriadWritingSlot.rootPosition(triad)
                TextbookTriadPosition.FIRST_INVERSION -> TextbookTriadWritingSlot.firstInversion(triad)
                TextbookTriadPosition.SECOND_INVERSION -> TextbookTriadWritingSlot.secondInversion(triad)
            }
        }
        val solution = requireNotNull(
            TextbookFigurationSolver.solve(
                TextbookFigurationProblem(
                    key = theoryKey,
                    slots = slots,
                    figuredVoice = recipe.figuredVoice,
                    requiredDiatonicDeltas = recipe.deltas,
                    requiredVoiceTones = recipe.voiceTones,
                    suspensionIntervals = recipe.suspensionIntervals,
                )
            )
        ) { "No legal textbook harmony skeleton for $ruleId in $theoryKey" }
        val notes = recipe.noteFactory(solution.harmony, ::t)
        val voicePrefix = recipe.figuredVoice.idPrefix()
        return Example(
            score = assemble(type, key, solution.harmony, recipe.figuredVoice, notes),
            anchors = recipe.anchors.map { EventId("nct-$voicePrefix-voice-$it") },
            related = recipe.related.map { EventId("nct-$voicePrefix-voice-$it") },
        )
    }

    private fun recipe(ruleId: RuleId, type: NonChordToneType, key: KeySpec): Recipe {
        val shift = if (key.mode == KeyModeSpec.MAJOR) (key.fifths * 7).mod(12) else ((key.fifths * 7) + 9).mod(12)
        fun p(base: Pitch) = Pitch.fromMidi(base.midiNumber + shift, preferSharps = key.fifths >= 0)
        val whole = Duration.WHOLE
        val quarter = Duration.QUARTER
        val eighth = Duration.EIGHTH
        fun sopranoSuspension(
            harmony: List<HarmonySpec>,
            intervals: List<SuspensionInterval>,
            voiceTones: List<ChordTone>,
        ) = Recipe(harmony, deltas = List(harmony.size - 1) { -1 }, suspensionIntervals = intervals,
            voiceTones = voiceTones,
            noteFactory = { v, t -> buildList {
                add(NoteSpec(t(1, 0, 1), v[0].soprano, whole, true))
                for (slot in 1 until v.size) {
                    add(NoteSpec(t(slot + 1, 0, 1), v[slot - 1].soprano, quarter))
                    add(NoteSpec(t(slot + 1, 1, 4), v[slot].soprano, Duration.DOTTED_HALF, slot < v.lastIndex))
                }
            } }, anchors = (1 until harmony.size).map { it * 2 - 1 }, related = (0 until harmony.size * 2 - 1).filter { it % 2 == 0 })

        return when (ruleId) {
            NonChordToneRules.SUSPENSION_4_3 -> sopranoSuspension(
                listOf(HarmonySpec(4), HarmonySpec(1)), listOf(SuspensionInterval(4, 3)),
                listOf(ChordTone.ROOT, ChordTone.THIRD))
            NonChordToneRules.SUSPENSION_7_6 -> sopranoSuspension(
                listOf(HarmonySpec(1), HarmonySpec(2, TextbookTriadPosition.FIRST_INVERSION)),
                listOf(SuspensionInterval(7, 6)), listOf(ChordTone.THIRD, ChordTone.ROOT))
            NonChordToneRules.SUSPENSION_9_8 -> sopranoSuspension(
                listOf(HarmonySpec(5), HarmonySpec(1)), listOf(SuspensionInterval(9, 8)),
                listOf(ChordTone.FIFTH, ChordTone.ROOT))
            NonChordToneRules.SUSPENSION_CHAIN -> sopranoSuspension(
                listOf(HarmonySpec(4), HarmonySpec(1), HarmonySpec(2, TextbookTriadPosition.FIRST_INVERSION)),
                listOf(SuspensionInterval(4, 3), SuspensionInterval(7, 6)),
                listOf(ChordTone.ROOT, ChordTone.THIRD, ChordTone.ROOT))
            NonChordToneRules.RETARDATION -> Recipe(
                harmony = listOf(HarmonySpec(1), HarmonySpec(5, TextbookTriadPosition.FIRST_INVERSION)),
                figuredVoice = FixedVoiceRole.BASS,
                deltas = listOf(-1),
                suspensionIntervals = listOf(SuspensionInterval(2, 3)),
                noteFactory = { v, t -> listOf(
                    NoteSpec(t(1, 0, 1), v[0].bass, whole, true),
                    NoteSpec(t(2, 0, 1), v[0].bass, quarter),
                    NoteSpec(t(2, 1, 4), v[1].bass, Duration.DOTTED_HALF),
                ) }, anchors = listOf(1), related = listOf(0, 2),
            )
            else -> simpleRecipe(type, ::p, whole, quarter, eighth)
        }
    }

    private fun simpleRecipe(
        type: NonChordToneType,
        p: (Pitch) -> Pitch,
        whole: Duration,
        quarter: Duration,
        eighth: Duration,
    ): Recipe = when (type) {
        NonChordToneType.PASSING -> Recipe(listOf(HarmonySpec(1)), noteFactory = { _, t -> listOf(
            NoteSpec(t(1, 0, 1), p(Pitch.C4), eighth), NoteSpec(t(1, 1, 8), p(Pitch.D4), eighth), NoteSpec(t(1, 1, 4), p(Pitch.E4), quarter),
        ) }, anchors = listOf(1), related = listOf(0, 2))
        NonChordToneType.NEIGHBOR -> Recipe(listOf(HarmonySpec(1)), noteFactory = { _, t -> listOf(
            NoteSpec(t(1, 0, 1), p(Pitch.E4), eighth), NoteSpec(t(1, 1, 8), p(Pitch.F4), eighth), NoteSpec(t(1, 1, 4), p(Pitch.E4), quarter),
        ) }, anchors = listOf(1), related = listOf(0, 2))
        NonChordToneType.APPOGGIATURA -> Recipe(listOf(HarmonySpec(1)), noteFactory = { _, t -> listOf(
            NoteSpec(t(1, 0, 1), p(Pitch.C4), quarter), NoteSpec(t(1, 1, 4), p(Pitch.F4), quarter), NoteSpec(t(1, 1, 2), p(Pitch.E4), quarter),
        ) }, anchors = listOf(1), related = listOf(0, 2))
        NonChordToneType.ESCAPE -> Recipe(listOf(HarmonySpec(1)), noteFactory = { _, t -> listOf(
            NoteSpec(t(1, 0, 1), p(Pitch.C4), eighth), NoteSpec(t(1, 1, 8), p(Pitch.D4), eighth), NoteSpec(t(1, 1, 4), p(Pitch.G4), quarter),
        ) }, anchors = listOf(1), related = listOf(0, 2))
        NonChordToneType.NEIGHBOR_GROUP -> Recipe(listOf(HarmonySpec(1)), noteFactory = { _, t -> listOf(
            NoteSpec(t(1, 0, 1), p(Pitch.E4), eighth), NoteSpec(t(1, 1, 8), p(Pitch.F4), eighth),
            NoteSpec(t(1, 1, 4), p(Pitch.E4), eighth), NoteSpec(t(1, 3, 8), p(Pitch.D4), eighth), NoteSpec(t(1, 1, 2), p(Pitch.E4), quarter),
        ) }, anchors = listOf(1, 3), related = listOf(0, 2, 4))
        NonChordToneType.ANTICIPATION -> Recipe(listOf(HarmonySpec(1), HarmonySpec(5)), noteFactory = { v, t -> listOf(
            NoteSpec(t(1, 0, 1), v[0].soprano, Duration.DOTTED_HALF),
            NoteSpec(t(1, 3, 4), v[1].soprano, quarter), NoteSpec(t(2, 0, 1), v[1].soprano, whole),
        ) }, anchors = listOf(1), related = listOf(0, 2))
        NonChordToneType.PEDAL -> Recipe(
            harmony = listOf(HarmonySpec(1), HarmonySpec(5), HarmonySpec(1)), figuredVoice = FixedVoiceRole.BASS,
            noteFactory = { v, t -> listOf(
                NoteSpec(t(1, 0, 1), v[0].bass, whole), NoteSpec(t(2, 0, 1), v[0].bass, whole), NoteSpec(t(3, 0, 1), v[0].bass, whole),
            ) }, anchors = listOf(1), related = listOf(0, 2),
        )
        NonChordToneType.SUSTAINED -> Recipe(
            harmony = listOf(HarmonySpec(1), HarmonySpec(4), HarmonySpec(1)),
            figuredVoice = FixedVoiceRole.SOPRANO,
            noteFactory = { v, t -> listOf(
                NoteSpec(t(1, 0, 1), v[0].soprano, whole),
                NoteSpec(t(2, 0, 1), v[0].soprano, whole),
                NoteSpec(t(3, 0, 1), v[0].soprano, whole),
            ) },
            anchors = listOf(1),
            related = listOf(0, 2),
        )
        NonChordToneType.SUSPENSION, NonChordToneType.RETARDATION -> error("Interval suspension requires a named recipe")
    }

    private fun assemble(
        type: NonChordToneType,
        key: KeySpec,
        harmony: List<ChordVoicing>,
        figuredVoice: FixedVoiceRole,
        figuredNotes: List<NoteSpec>,
    ): StorageScore {
        val roleData = FOUR_PART_ROLES.associateWith { role ->
            val specs = if (role == figuredVoice) figuredNotes else harmony.mapIndexed { index, voicing ->
                NoteSpec(TimeCode.of(index + 1, Fraction.ZERO), voicing.pitch(role), Duration.WHOLE)
            }
            val prefix = role.idPrefix()
            val pitches = specs.mapIndexed { index, note -> StoragePitchEvent(EventId("nct-$prefix-pitch-$index"), note.time, listOf(note.pitch)) }
            val voices = specs.mapIndexed { index, note -> StorageVoiceEvent(
                id = EventId("nct-$prefix-voice-$index"), onset = note.time, pitchEventId = pitches[index].id,
                duration = note.duration,
                rendering = RenderingProps(stemDirection = if (role == FixedVoiceRole.SOPRANO || role == FixedVoiceRole.TENOR) StemDirection.UP else StemDirection.DOWN),
                ties = if (note.tieToNext) listOf(TieInfo(0)) else emptyList(),
            ) }
            Triple(specs, pitches, voices)
        }
        val chordEvents = harmony.mapIndexed { index, voicing -> StorageChordEvent.create(
            onset = TimeCode.of(index + 1, Fraction.ZERO), root = voicing.target.sonority.root.value,
            quality = voicing.target.quality,
            bass = voicing.target.bassPitchClass.takeIf { it != voicing.target.sonority.root }?.value,
        ).copy(id = EventId("nct-chord-$index")) }
        val keySignature = key.toApiKeySignature()
        return StorageScore(
            id = ScoreId("exploration-nct-${type.name.lowercase()}"), metadata = ScoreMetadata(title = "${type.abbreviation} - ${type.name.lowercase()}"),
            defaultTimeSignature = TimeSignature.COMMON, defaultKeySignature = keySignature,
            measures = (1..harmony.size).map(::StorageMeasure),
            pitchTracks = FOUR_PART_ROLES.associate { role -> role.pitchTrackId() to StoragePitchTrack(role.pitchTrackId(), "${role.name} Pitch", roleData.getValue(role).second) },
            voiceTracks = FOUR_PART_ROLES.associate { role -> role.voiceTrackId() to StorageVoiceTrack(
                role.voiceTrackId(), if (role == figuredVoice) "${role.displayName()} figuration" else "${role.displayName()} harmony",
                if (role == FixedVoiceRole.SOPRANO || role == FixedVoiceRole.TENOR) 1 else 2, role.pitchTrackId(), roleData.getValue(role).third,
            ) },
            staffTracks = mapOf(
                UPPER_STAFF to StorageStaffTrack(
                    id = UPPER_STAFF, name = "Soprano / Alto", clef = Clef.TREBLE,
                    keySignature = keySignature,
                    voiceTrackIds = listOf(FixedVoiceRole.SOPRANO.voiceTrackId(), FixedVoiceRole.ALTO.voiceTrackId()),
                ),
                LOWER_STAFF to StorageStaffTrack(
                    id = LOWER_STAFF, name = "Tenor / Bass", clef = Clef.BASS,
                    keySignature = keySignature,
                    voiceTrackIds = listOf(FixedVoiceRole.TENOR.voiceTrackId(), FixedVoiceRole.BASS.voiceTrackId()),
                ),
            ),
            staffGroups = listOf(
                StorageStaffGroup(
                    id = StaffGroupId("exploration-nct-group"), bracket = BracketStyle.BRACE,
                    barlineConnect = true,
                    members = listOf(StaffGroupMember.Staff(UPPER_STAFF), StaffGroupMember.Staff(LOWER_STAFF)),
                )
            ),
            pluginTracks = mapOf(CHORD_TRACK to StoragePluginTrack(CHORD_TRACK, "Annotated Chords", StorageChordEvent.TRACK_TYPE, chordEvents)),
            pageLayout = PageLayoutConfig.DEFAULT.copy(paginated = false),
        )
    }

    private fun ChordVoicing.pitch(role: FixedVoiceRole): Pitch = when (role) {
        FixedVoiceRole.SOPRANO -> soprano; FixedVoiceRole.ALTO -> alto; FixedVoiceRole.TENOR -> tenor; FixedVoiceRole.BASS -> bass
        else -> error("Only standard SATB roles can be rendered")
    }
    private fun FixedVoiceRole.idPrefix() = name.lowercase()
    private fun FixedVoiceRole.displayName() = name.lowercase().replaceFirstChar { it.uppercase() }
    private fun FixedVoiceRole.pitchTrackId() = TrackId("nct-${idPrefix()}-pitch-track")
    private fun FixedVoiceRole.voiceTrackId() = TrackId("nct-${idPrefix()}-voice")

    private val FOUR_PART_ROLES = listOf(FixedVoiceRole.SOPRANO, FixedVoiceRole.ALTO, FixedVoiceRole.TENOR, FixedVoiceRole.BASS)
    private val UPPER_STAFF = TrackId("nct-upper-staff")
    private val LOWER_STAFF = TrackId("nct-lower-staff")
    private val CHORD_TRACK = TrackId("nct-chord-track")
}
