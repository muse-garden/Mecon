package com.mecon.api.storage

import com.mecon.api.primitive.*
import com.mecon.api.storage.events.*
import com.mecon.api.storage.tracks.*
import com.mecon.api.storage.tracks.BracketStyle
import com.mecon.api.storage.tracks.StorageStaffGroup
import kotlinx.serialization.Serializable

/**
 * Score metadata.
 */
@Serializable
data class ScoreMetadata(
    val title: String = "Untitled",
    val subtitle: String? = null,
    val composer: String? = null,
    val arranger: String? = null,
    val lyricist: String? = null,
    val copyright: String? = null,
    val createdAt: Long = currentTimeMillis(),
    val modifiedAt: Long = currentTimeMillis()
)

// Platform-independent time function
internal expect fun currentTimeMillis(): Long

/**
 * Predefined staff arrangements offered when creating a new score. Kept
 * deliberately small for the first editing pass.
 *
 * - [TREBLE] / [BASS] — a single staff with the matching clef.
 * - [PIANO_GRAND] — two staves (treble + bass) braced together with a shared
 *   barline, i.e. the classic piano grand staff.
 */
enum class StaffLayoutPreset { TREBLE, BASS, PIANO_GRAND, ORGAN }

/** Score-navigation signs and jump instructions attached to a measure boundary. */
@Serializable
enum class NavigationMark {
    SEGNO,
    CODA,
    TO_CODA,
    FINE,
    DA_CAPO,
    DAL_SEGNO,
    DA_CAPO_AL_FINE,
    DAL_SEGNO_AL_FINE,
    DA_CAPO_AL_CODA,
    DAL_SEGNO_AL_CODA,
}

/** User displacement from a navigation mark's automatic boundary anchor, in staff spaces. */
@Serializable
data class NavigationMarkOffset(
    val dx: Float = 0f,
    val dy: Float = 0f,
)

data class StaffTemplate(val name: String, val clef: Clef)
data class InstrumentTemplate(
    val name: String,
    val abbreviation: String? = null,
    val staves: List<StaffTemplate>,
    val midiProgram: Int = 0,
    /** Stable id from the desktop instrument catalog, when the template came from one. */
    val catalogId: String? = null,
    /** Whether score-system headers should show this template's display name. */
    val labelInHeader: Boolean = false,
    /** How this instrument is represented in the optional orchestration layer. */
    val playerKind: PlayerKind = PlayerKind.SINGLE,
    /** Number of individual players for a SINGLE instrument. */
    val playerCount: Int = 1,
    /** Per-staff player numbers, e.g. [[1, 3], [2, 4]] for four horns on two staves. */
    val playerAssignments: List<List<Int>> = emptyList(),
)

/**
 * Initial player-to-staff distribution used by score creation and orchestration editors.
 * Horn desks conventionally interleave (1,3 / 2,4); other instruments fill staves in
 * contiguous player-number order (1,2 / 3,4).
 */
fun defaultPlayerAssignments(
    staffCount: Int,
    playerCount: Int,
    interleaved: Boolean = false,
): List<List<Int>> {
    val staves = staffCount.coerceAtLeast(1)
    val players = playerCount.coerceAtLeast(1)
    if (players < staves) {
        return List(staves) { staffIndex -> listOf((staffIndex % players) + 1) }
    }
    return List(staves) { staffIndex ->
        (1..players).filter { playerNumber ->
            if (interleaved) {
                (playerNumber - 1) % staves == staffIndex
            } else {
                ((playerNumber - 1) * staves) / players == staffIndex
            }
        }
    }
}

data class StaffGroupTemplate(
    val startStaffIndex: Int,
    val endStaffIndex: Int,
    val bracket: BracketStyle,
    val label: String? = null,
    val barlineConnect: Boolean = false
)

/**
 * Measure definition.
 */
@Serializable
data class StorageMeasure(
    val number: Int,
    val timeSignature: TimeSignature? = null,  // null = inherit from previous
    val keySignature: KeySignature? = null,    // null = inherit from previous
    val rehearsalMark: String? = null,
    /**
     * Explicit style for the barline at this measure's right boundary.
     * null lets the compute layer choose SINGLE or FINAL. Repeat barlines remain
     * represented by [repeatStart]/[repeatEnd] because their two sides belong
     * to adjacent measures.
     */
    val endBarlineType: BarlineType? = null,
    val repeatStart: Boolean = false,
    val repeatEnd: Boolean = false,
    val repeatCount: Int = 2,
    /** Volta passes (normally 1 or 2) for this measure; empty means no ending bracket. */
    val voltaNumbers: Set<Int> = emptySet(),
    /** Navigation signs/instructions engraved at this measure's right boundary. */
    val navigationMarks: Set<NavigationMark> = emptySet(),
    /** Manual displacements for the marks in [navigationMarks], in staff spaces. */
    val navigationMarkOffsets: Map<NavigationMark, NavigationMarkOffset> = emptyMap(),
)

/**
 * Storage score - the complete score data structure for persistence.
 *
 * This is the top-level structure that gets serialized to YAML/JSON.
 * Uses ID references between tracks for a normalized structure.
 *
 * Note: Uses regular List/Map for serialization compatibility.
 * Immutability is maintained through copy-on-write pattern.
 */
@Serializable
data class StorageScore(
    val id: ScoreId,
    val metadata: ScoreMetadata = ScoreMetadata(),
    val defaultTimeSignature: TimeSignature = TimeSignature.COMMON,
    val defaultKeySignature: KeySignature = KeySignature.C_MAJOR,
    val defaultTempo: Float = 120f,
    /** Explicit opening barline style. A measure-1 repeat start takes precedence. */
    val initialBarlineType: BarlineType = BarlineType.SINGLE,

    // Measures
    val measures: List<StorageMeasure> = emptyList(),

    // Tracks (by ID for normalized storage)
    val pitchTracks: Map<TrackId, StoragePitchTrack> = emptyMap(),
    val voiceTracks: Map<TrackId, StorageVoiceTrack> = emptyMap(),
    val staffTracks: Map<TrackId, StorageStaffTrack> = emptyMap(),
    /** Playable instruments and their one-or-many staff mappings. */
    val instruments: List<StorageInstrument> = emptyList(),
    /** Controller tracks linking dynamics / hairpins to a playback timeline. */
    val controllerTracks: Map<TrackId, StorageControllerTrack> = emptyMap(),
    val globalTrack: StorageGlobalTrack = StorageGlobalTrack.create(),
    val pluginTracks: Map<TrackId, StoragePluginTrack> = emptyMap(),

    val reductions: List<StorageReduction> = emptyList(),
    val orchestration: StorageOrchestration? = null,

    /**
     * Hierarchical staff groups describing display order, brackets, group labels,
     * and barline connectivity. Every staff must appear in exactly one leaf
     * [StaffGroupMember.Staff] somewhere in this tree. The depth-first traversal
     * order determines the top-to-bottom staff order.
     *
     * See [StorageStaffGroup] for the tree shape.
     */
    val staffGroups: List<StorageStaffGroup> = emptyList(),

    /**
     * Page layout configuration (paper size, margins, scale, paginate toggle).
     * Default keeps old files deserializing and renders as a single continuous
     * system. See [PageLayoutConfig].
     */
    val pageLayout: PageLayoutConfig = PageLayoutConfig.DEFAULT,

    /**
     * View / display preferences (e.g. page arrangement). Not read by the renderer; persisted
     * so UI state survives save/reload. Default keeps old files deserializing.
     * See [ScoreViewPreferences].
     */
    val viewPreferences: ScoreViewPreferences = ScoreViewPreferences.DEFAULT,

    /**
     * Persisted render geometry for slurs / articulations (anchor-relative).
     * `null` = no stored geometry → the renderer auto-lays-out everything, i.e.
     * old files behave exactly as before. See [ScoreGeometry].
     */
    val geometry: ScoreGeometry? = null,
    /**
     * Whether time-signature notation is generated. The effective signatures remain active for
     * measure duration, event positioning and automatic beaming when this is false.
     */
    val showTimeSignatures: Boolean = true,
) {
    // ========== Accessors ==========

    fun getPitchTrack(id: TrackId): StoragePitchTrack? = pitchTracks[id]
    fun getVoiceTrack(id: TrackId): StorageVoiceTrack? = voiceTracks[id]
    fun getStaffTrack(id: TrackId): StorageStaffTrack? = staffTracks[id]
    fun getControllerTrack(id: TrackId): StorageControllerTrack? = controllerTracks[id]
    fun getPluginTrack(id: TrackId): StoragePluginTrack? = pluginTracks[id]

    fun getReduction(id: ReductionId): StorageReduction? = reductions.firstOrNull { it.id == id }

    fun getMeasure(number: Int): StorageMeasure? = measures.find { it.number == number }

    /**
     * Get the effective time signature at a measure.
     */
    fun getTimeSignatureAt(measureNumber: Int): TimeSignature {
        for (i in measureNumber downTo 1) {
            measures.find { it.number == i }?.timeSignature?.let { return it }
        }
        return defaultTimeSignature
    }

    /**
     * Get the effective key signature at a measure.
     */
    fun getKeySignatureAt(measureNumber: Int): KeySignature {
        for (i in measureNumber downTo 1) {
            measures.find { it.number == i }?.keySignature?.let { return it }
        }
        return defaultKeySignature
    }

    /**
     * Get all pitch events across all pitch tracks.
     */
    fun getAllPitchEvents(): List<StoragePitchEvent> =
        pitchTracks.values.flatMap { it.events }

    /**
     * Get all voice events across all voice tracks.
     */
    fun getAllVoiceEvents(): List<StorageVoiceEvent> =
        voiceTracks.values.flatMap { it.events }

    /**
     * Find a pitch event by ID.
     */
    fun findPitchEvent(eventId: EventId): StoragePitchEvent? =
        pitchTracks.values.flatMap { it.events }.find { it.id == eventId }

    /**
     * Find a voice event by ID.
     */
    fun findVoiceEvent(eventId: EventId): StorageVoiceEvent? =
        voiceTracks.values.flatMap { it.events }.find { it.id == eventId }

    /**
     * Get voice track for a staff.
     */
    fun getVoiceTracksForStaff(staffTrackId: TrackId): List<StorageVoiceTrack> {
        val staff = staffTracks[staffTrackId] ?: return emptyList()
        return staff.voiceTrackIds.mapNotNull { voiceTracks[it] }
    }

    /**
     * Get pitch track for a voice.
     */
    fun getPitchTrackForVoice(voiceTrackId: TrackId): StoragePitchTrack? {
        val voice = voiceTracks[voiceTrackId] ?: return null
        return pitchTracks[voice.pitchTrackId]
    }

    // ========== Mutations (return new score) ==========

    fun addPitchTrack(track: StoragePitchTrack): StorageScore =
        copy(pitchTracks = pitchTracks + (track.id to track))

    fun addVoiceTrack(track: StorageVoiceTrack): StorageScore =
        copy(voiceTracks = voiceTracks + (track.id to track))

    fun addStaffTrack(track: StorageStaffTrack): StorageScore =
        copy(staffTracks = staffTracks + (track.id to track))

    fun addControllerTrack(track: StorageControllerTrack): StorageScore =
        copy(controllerTracks = controllerTracks + (track.id to track))

    fun addPluginTrack(track: StoragePluginTrack): StorageScore =
        copy(pluginTracks = pluginTracks + (track.id to track))

    fun updatePluginTrack(id: TrackId, update: (StoragePluginTrack) -> StoragePluginTrack): StorageScore {
        val track = pluginTracks[id] ?: return this
        return copy(pluginTracks = pluginTracks + (id to update(track)))
    }

    fun addMeasure(measure: StorageMeasure): StorageScore =
        copy(measures = measures + measure)

    fun updatePitchTrack(id: TrackId, update: (StoragePitchTrack) -> StoragePitchTrack): StorageScore {
        val track = pitchTracks[id] ?: return this
        return copy(pitchTracks = pitchTracks + (id to update(track)))
    }

    fun updateVoiceTrack(id: TrackId, update: (StorageVoiceTrack) -> StorageVoiceTrack): StorageScore {
        val track = voiceTracks[id] ?: return this
        return copy(voiceTracks = voiceTracks + (id to update(track)))
    }

    fun updateStaffTrack(id: TrackId, update: (StorageStaffTrack) -> StorageStaffTrack): StorageScore {
        val track = staffTracks[id] ?: return this
        return copy(staffTracks = staffTracks + (id to update(track)))
    }

    fun addPitchEvent(pitchTrackId: TrackId, event: StoragePitchEvent): StorageScore =
        updatePitchTrack(pitchTrackId) { it.addEvent(event) }

    fun removePitchEvent(pitchTrackId: TrackId, eventId: EventId): StorageScore =
        updatePitchTrack(pitchTrackId) { it.removeEvent(eventId) }

    fun addVoiceEvent(voiceTrackId: TrackId, event: StorageVoiceEvent): StorageScore =
        updateVoiceTrack(voiceTrackId) { it.addEvent(event) }

    fun removeVoiceEvent(voiceTrackId: TrackId, eventId: EventId): StorageScore =
        updateVoiceTrack(voiceTrackId) { it.removeEvent(eventId) }

    fun updateMetadata(update: (ScoreMetadata) -> ScoreMetadata): StorageScore =
        copy(metadata = update(metadata))

    data class CreationOptions(
        val title: String = "Untitled",
        val timeSignature: TimeSignature = TimeSignature.COMMON,
        val keySignature: KeySignature = KeySignature.C_MAJOR,
        val tempo: Float = 120f,
        val subtitle: String? = null,
        val composer: String? = null,
        val arranger: String? = null,
        val lyricist: String? = null,
        val copyright: String? = null,
        val layout: StaffLayoutPreset = StaffLayoutPreset.TREBLE,
        val measureCount: Int = 4,
        val pageLayout: PageLayoutConfig = PageLayoutConfig.DEFAULT,
        val instrumentTemplates: List<InstrumentTemplate>? = null,
        val groupTemplates: List<StaffGroupTemplate>? = null,
        /** New-score workflow can opt into editable player/staff assignments. */
        val orchestrationEnabled: Boolean = false,
    )

    companion object {

        /**
         * Create an empty score skeleton with the chosen staff [layout], meter,
         * key, tempo and page configuration. Each staff gets its own
         * pitch/voice track pair; [PIANO_GRAND][StaffLayoutPreset.PIANO_GRAND]
         * braces the two staves with a shared barline.
         *
         * Trailing parameters are optional so existing positional callers
         * (`create(title, timeSignature, keySignature)`) keep compiling.
         */
        fun create(options: CreationOptions = CreationOptions()): StorageScore = with(options) {
            val specs = instrumentTemplates ?: when (layout) {
                StaffLayoutPreset.TREBLE -> listOf(
                    InstrumentTemplate("Piano", "Pno.", listOf(StaffTemplate("Piano", Clef.TREBLE)))
                )
                StaffLayoutPreset.BASS -> listOf(
                    InstrumentTemplate("Piano", "Pno.", listOf(StaffTemplate("Piano", Clef.BASS)))
                )
                StaffLayoutPreset.PIANO_GRAND -> listOf(
                    InstrumentTemplate("Piano", "Pno.", listOf(
                        StaffTemplate("Piano RH", Clef.TREBLE),
                        StaffTemplate("Piano LH", Clef.BASS)
                    ))
                )
                StaffLayoutPreset.ORGAN -> listOf(
                    InstrumentTemplate("Organ", "Org.", listOf(
                        StaffTemplate("Great", Clef.TREBLE),
                        StaffTemplate("Swell", Clef.TREBLE),
                        StaffTemplate("Pedal", Clef.BASS)
                    ), midiProgram = 19)
                )
            }
            require(specs.isNotEmpty()) { "A score must contain at least one instrument" }
            require(specs.all { it.staves.isNotEmpty() }) { "Every instrument must contain at least one staff" }

            val pitchTracks = LinkedHashMap<TrackId, StoragePitchTrack>()
            val voiceTracks = LinkedHashMap<TrackId, StorageVoiceTrack>()
            val staffTracks = LinkedHashMap<TrackId, StorageStaffTrack>()
            val staffIds = ArrayList<TrackId>()
            val storageInstruments = ArrayList<StorageInstrument>()

            for (instrument in specs) {
                val ownedStaffIds = ArrayList<TrackId>()
                for (staffSpec in instrument.staves) {
                    val pitchTrack = StoragePitchTrack.create("${staffSpec.name} Notes")
                    val voiceTrack = StorageVoiceTrack.create(
                        name = "Voice 1",
                        voiceNumber = 1,
                        pitchTrackId = pitchTrack.id
                    )
                    val staffTrack = StorageStaffTrack.create(
                        name = staffSpec.name,
                        clef = staffSpec.clef,
                        keySignature = keySignature,
                        voiceTrackIds = listOf(voiceTrack.id)
                    )
                    pitchTracks[pitchTrack.id] = pitchTrack
                    voiceTracks[voiceTrack.id] = voiceTrack
                    staffTracks[staffTrack.id] = staffTrack
                    staffIds += staffTrack.id
                    ownedStaffIds += staffTrack.id
                }
                storageInstruments += StorageInstrument(
                    id = InstrumentId.generate(),
                    name = instrument.name,
                    abbreviation = instrument.abbreviation,
                    catalogId = instrument.catalogId,
                    staffIds = ownedStaffIds,
                    playback = InstrumentPlayback(midiProgram = instrument.midiProgram)
                )
            }

            val resolvedGroups = groupTemplates.orEmpty().toMutableList()
            run {
                var start = 0
                specs.forEach { instrument ->
                    val end = start + instrument.staves.size - 1
                    val exactIndex = resolvedGroups.indexOfFirst {
                        it.startStaffIndex == start && it.endStaffIndex == end
                    }
                    if (exactIndex >= 0 && instrument.labelInHeader) {
                        val existing = resolvedGroups[exactIndex]
                        resolvedGroups[exactIndex] = existing.copy(label = existing.label ?: instrument.name)
                    } else if (exactIndex < 0 && (instrument.staves.size > 1 || instrument.labelInHeader)) {
                        resolvedGroups += StaffGroupTemplate(
                            startStaffIndex = start,
                            endStaffIndex = end,
                            bracket = if (instrument.staves.size > 1) BracketStyle.BRACE else BracketStyle.NONE,
                            label = instrument.name.takeIf { instrument.labelInHeader },
                            barlineConnect = instrument.staves.size > 1
                        )
                    }
                    start = end + 1
                }
            }
            val groups = buildStaffGroupForest(
                templates = resolvedGroups,
                staffIds = staffIds
            )

            val orchestration = if (!orchestrationEnabled) null else {
                val players = mutableListOf<StoragePlayer>()
                val assignments = mutableListOf<StorageStaffAssignment>()
                specs.zip(storageInstruments).forEach { (template, instrument) ->
                    val count = if (template.playerKind == PlayerKind.SECTION) 1 else template.playerCount.coerceAtLeast(1)
                    val instrumentPlayers = (1..count).map { number ->
                        StoragePlayer(
                            id = PlayerId.generate(),
                            name = if (count == 1) template.name else "${template.name} $number",
                            abbreviation = template.abbreviation,
                            kind = template.playerKind,
                            instruments = listOf(StoragePlayerInstrument(
                                id = instrument.id,
                                name = template.name,
                                abbreviation = template.abbreviation,
                                playback = instrument.playback,
                            )),
                        )
                    }
                    players += instrumentPlayers
                    val groupsForStaff = template.playerAssignments.takeIf {
                        it.size == instrument.staffIds.size &&
                            it.all { group -> group.any { number -> number in 1..count } }
                    }
                        ?: if (template.playerKind == PlayerKind.SECTION) {
                            List(instrument.staffIds.size) { listOf(1) }
                        } else {
                            defaultPlayerAssignments(
                                staffCount = instrument.staffIds.size,
                                playerCount = count,
                                interleaved = template.catalogId == "horn",
                            )
                        }
                    instrument.staffIds.forEachIndexed { staffIndex, staffId ->
                        groupsForStaff[staffIndex].distinct().filter { it in 1..count }.forEach { playerNumber ->
                            assignments += StorageStaffAssignment(
                                playerId = instrumentPlayers[playerNumber - 1].id,
                                onset = TimeCode.ofMeasure(1),
                                staffId = staffId,
                            )
                        }
                    }
                }
                StorageOrchestration(players = players, staffAssignments = assignments)
            }

            return StorageScore(
                id = ScoreId.generate(),
                metadata = ScoreMetadata(
                    title = title, subtitle = subtitle, composer = composer,
                    arranger = arranger, lyricist = lyricist, copyright = copyright
                ),
                defaultTimeSignature = timeSignature,
                defaultKeySignature = keySignature,
                defaultTempo = tempo,
                measures = (1..measureCount).map { StorageMeasure(number = it) },
                pitchTracks = pitchTracks,
                voiceTracks = voiceTracks,
                staffTracks = staffTracks,
                instruments = storageInstruments,
                globalTrack = StorageGlobalTrack.create(tempo),
                staffGroups = groups,
                pageLayout = pageLayout,
                orchestration = orchestration,
            )
        }

        fun createDemo(): StorageScore {
            var score = create(CreationOptions(title = "Demo Score"))

            // Get the default tracks
            val pitchTrackId = score.pitchTracks.keys.first()
            val voiceTrackId = score.voiceTracks.keys.first()

            // Add some notes (C major scale)
            val pitches = listOf(
                Pitch.C4, Pitch.D4, Pitch.E4, Pitch.F4,
                Pitch.G4, Pitch.A4, Pitch.B4, Pitch(7, 0) // C5
            )

            pitches.forEachIndexed { index, pitch ->
                val measureNumber = (index / 4) + 1
                val beatInMeasure = Fraction(index % 4, 4)
                val onset = TimeCode.of(measureNumber, beatInMeasure)

                // Create pitch event (pure pitch data)
                val pitchEvent = StoragePitchEvent.single(onset, pitch)
                score = score.addPitchEvent(pitchTrackId, pitchEvent)

                // Create voice event (rendered note with duration)
                val voiceEvent = StorageVoiceEvent.create(
                    onset = onset,
                    pitchEventId = pitchEvent.id,
                    duration = Duration.QUARTER
                )
                score = score.addVoiceEvent(voiceTrackId, voiceEvent)
            }

            return score
        }

        /**
         * Turn contiguous bracket ranges into the persisted staff-group tree.
         * Ranges may be disjoint or nested. Partial overlap would produce crossing
         * brackets and is rejected here so every caller gets the same invariant.
         */
        private fun buildStaffGroupForest(
            templates: List<StaffGroupTemplate>,
            staffIds: List<TrackId>
        ): List<StorageStaffGroup> {
            if (staffIds.isEmpty()) return emptyList()
            templates.forEach { group ->
                require(group.startStaffIndex in staffIds.indices)
                require(group.endStaffIndex in staffIds.indices)
                require(group.startStaffIndex <= group.endStaffIndex)
            }
            require(templates.distinctBy { it.startStaffIndex to it.endStaffIndex }.size == templates.size) {
                "Staff group ranges must be unique"
            }
            for (leftIndex in templates.indices) {
                for (rightIndex in leftIndex + 1 until templates.size) {
                    require(!templates[leftIndex].crosses(templates[rightIndex])) {
                        "Staff group ranges may be nested but must not cross"
                    }
                }
            }

            fun StaffGroupTemplate.strictlyContains(other: StaffGroupTemplate): Boolean =
                startStaffIndex <= other.startStaffIndex && endStaffIndex >= other.endStaffIndex &&
                    (startStaffIndex != other.startStaffIndex || endStaffIndex != other.endStaffIndex)

            val parentByChild = templates.associateWith { child ->
                templates.filter { it.strictlyContains(child) }
                    .minByOrNull { it.endStaffIndex - it.startStaffIndex }
            }
            val childrenByParent = templates.groupBy { parentByChild[it] }

            fun build(template: StaffGroupTemplate): StorageStaffGroup {
                val children = childrenByParent[template].orEmpty().sortedBy { it.startStaffIndex }
                val members = buildList {
                    var staffIndex = template.startStaffIndex
                    for (child in children) {
                        while (staffIndex < child.startStaffIndex) {
                            add(StaffGroupMember.Staff(staffIds[staffIndex++]))
                        }
                        add(StaffGroupMember.Group(build(child)))
                        staffIndex = child.endStaffIndex + 1
                    }
                    while (staffIndex <= template.endStaffIndex) {
                        add(StaffGroupMember.Staff(staffIds[staffIndex++]))
                    }
                }
                return StorageStaffGroup(
                    id = StaffGroupId.generate(),
                    bracket = template.bracket,
                    label = template.label,
                    barlineConnect = template.barlineConnect,
                    members = members
                )
            }

            val roots = childrenByParent[null].orEmpty().sortedBy { it.startStaffIndex }
            return buildList {
                var staffIndex = 0
                for (root in roots) {
                    while (staffIndex < root.startStaffIndex) {
                        add(StorageStaffGroup.ofStaffs(BracketStyle.NONE, staffIds = listOf(staffIds[staffIndex++])))
                    }
                    add(build(root))
                    staffIndex = root.endStaffIndex + 1
                }
                while (staffIndex < staffIds.size) {
                    add(StorageStaffGroup.ofStaffs(BracketStyle.NONE, staffIds = listOf(staffIds[staffIndex++])))
                }
            }
        }

        private fun StaffGroupTemplate.crosses(other: StaffGroupTemplate): Boolean =
            (startStaffIndex < other.startStaffIndex && other.startStaffIndex <= endStaffIndex && endStaffIndex < other.endStaffIndex) ||
                (other.startStaffIndex < startStaffIndex && startStaffIndex <= other.endStaffIndex && other.endStaffIndex < endStaffIndex)
    }
}
