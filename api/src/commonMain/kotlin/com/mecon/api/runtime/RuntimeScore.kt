package com.mecon.api.runtime

import com.mecon.api.primitive.*
import com.mecon.api.runtime.events.*
import com.mecon.api.runtime.tracks.*
import com.mecon.api.storage.*
import com.mecon.api.storage.events.StorageVoiceEvent
import com.mecon.api.storage.events.StorageSlurEvent
import com.mecon.api.storage.events.StoragePluginEvent
import com.mecon.api.storage.events.TieInfo
import com.mecon.api.storage.tracks.*
import com.mecon.api.collection.BPlusTree
import com.mecon.api.collection.BPlusTreeAggregator

/**
 * Runtime measure - in-memory representation.
 */
data class RuntimeMeasure(
    val number: Int,
    val timeSignature: TimeSignature,
    val keySignature: KeySignature,
    val hasExplicitTimeSignature: Boolean = false,
    val hasExplicitKeySignature: Boolean = false,
    val rehearsalMark: String? = null,
    val endBarlineType: BarlineType? = null,
    val repeatStart: Boolean = false,
    val repeatEnd: Boolean = false,
    val repeatCount: Int = 2,
    val voltaNumbers: Set<Int> = emptySet(),
    val navigationMarks: Set<NavigationMark> = emptySet(),
    val navigationMarkOffsets: Map<NavigationMark, NavigationMarkOffset> = emptyMap(),
) {
    val startTime: TimeCode get() = TimeCode.ofMeasure(number)
    val duration: Fraction get() = timeSignature.measureDuration()
}

/**
 * Runtime score - the in-memory representation of a complete score.
 *
 * Unlike StorageScore which uses ID references, RuntimeScore contains
 * fully resolved object references for efficient access during editing
 * and computation.
 *
 * Staff display order is determined by depth-first traversal of [staffGroups].
 * Use [orderedStaffs] to get the canonical ordered list.
 */
data class RuntimeScore(
    val id: ScoreId,
    val metadata: ScoreMetadata,
    val defaultTimeSignature: TimeSignature,
    val defaultKeySignature: KeySignature,
    val defaultTempo: Float,
    val initialBarlineType: BarlineType = BarlineType.SINGLE,
    val measures: BPlusTree<Int, RuntimeMeasure, Int>,
    val staffTracks: Map<TrackId, RuntimeStaffTrack>,
    val instruments: List<RuntimeInstrument> = emptyList(),
    val voiceTracks: Map<TrackId, RuntimeVoiceTrack>,
    val pitchTracks: Map<TrackId, RuntimePitchTrack>,
    val pluginTracks: Map<TrackId, RuntimePluginTrack<out StoragePluginEvent>> = emptyMap(),
    /**
     * Controller tracks (dynamics / hairpin playback timelines). Pass-through
     * value data — there are no object references to resolve, so the storage
     * form is carried directly.
     */
    val controllerTracks: Map<TrackId, StorageControllerTrack> = emptyMap(),
    /**
     * Staff group hierarchy. Depth-first traversal gives the top-to-bottom staff order.
     * Every staff should appear exactly once as a [RuntimeStaffGroupMember.Staff] leaf.
     */
    val staffGroups: List<RuntimeStaffGroup> = emptyList(),
    /** Page layout configuration (paper, margins, scale, paginate toggle). */
    val pageLayout: PageLayoutConfig = PageLayoutConfig.DEFAULT,
    /**
     * Global notation track (key/time-signature changes, forced system/page breaks). Carried
     * through directly as pass-through value data so it survives serialization via [toStorage].
     * [forcedSystemBreaks]/[forcedPageBreaks] are
     * derived views of it used by layout.
     */
    val globalTrack: StorageGlobalTrack = StorageGlobalTrack.create(),
    /**
     * View / display preferences (page arrangement and final-pass measure numbers). Not used by
     * compute/layout; the measure-number flag is read only by the renderer's final overlay pass.
     * Persisted so UI state survives save/reload. Pass-through value data.
     */
    val viewPreferences: ScoreViewPreferences = ScoreViewPreferences.DEFAULT,
    /** Measure numbers at which a system (line) break is forced (break before the measure). */
    val forcedSystemBreaks: Set<Int> = emptySet(),
    /** Measure numbers at which a page break is forced (break before the measure). */
    val forcedPageBreaks: Set<Int> = emptySet(),
    /**
     * Persisted render geometry overlay (slurs / articulations), carried through
     * from storage for the renderer. `null` = auto-lay-out everything. Pass-through
     * value data (not resolved by compute). See [com.mecon.api.storage.ScoreGeometry].
     */
    val geometry: com.mecon.api.storage.ScoreGeometry? = null,
    val reductions: List<StorageReduction> = emptyList(),
    val orchestration: StorageOrchestration? = null,
    /** Generate visible time-signature notation while retaining meter semantics. */
    val showTimeSignatures: Boolean = true,
) {
    // ========== Accessors ==========

    fun getMeasure(number: Int): RuntimeMeasure? = measures.get(number)

    fun getTimeSignatureAt(measureNumber: Int): TimeSignature =
        getMeasure(measureNumber)?.timeSignature ?: defaultTimeSignature

    fun getKeySignatureAt(measureNumber: Int): KeySignature =
        getMeasure(measureNumber)?.keySignature ?: defaultKeySignature

    fun getAllPitchEvents(): List<RuntimePitchEvent> =
        pitchTracks.values.flatMap { it.events.toList() }

    fun getAllVoiceEvents(): List<RuntimeVoiceEvent> =
        voiceTracks.values.flatMap { it.events.toList() }

    fun getPitchEventsInRange(start: TimeCode, end: TimeCode): List<RuntimePitchEvent> =
        pitchTracks.values.flatMap { it.eventsInRange(start, end) }

    fun getVoiceEventsInRange(start: TimeCode, end: TimeCode): List<RuntimeVoiceEvent> =
        voiceTracks.values.flatMap { it.eventsInRange(start, end) }

    fun findPitchEvent(eventId: EventId): RuntimePitchEvent? =
        pitchTracks.values.firstNotNullOfOrNull { it.findEvent(eventId) }

    fun getStaffTrack(id: TrackId): RuntimeStaffTrack? = staffTracks[id]
    fun getVoiceTrack(id: TrackId): RuntimeVoiceTrack? = voiceTracks[id]
    fun getPitchTrack(id: TrackId): RuntimePitchTrack? = pitchTracks[id]
    fun getPluginTrack(id: TrackId): RuntimePluginTrack<*>? = pluginTracks[id]

    // ========== Mutations ==========

    fun updatePluginTrack(trackId: TrackId, updatedTrack: RuntimePluginTrack<*>): RuntimeScore =
        copy(pluginTracks = pluginTracks + (trackId to updatedTrack))

    fun replaceMeasures(newMeasures: Iterable<RuntimeMeasure>): RuntimeScore {
        val aggregator = object : BPlusTreeAggregator<RuntimeMeasure, Int> {
            override fun extract(value: RuntimeMeasure): Int = 0
            override fun combine(a1: Int, a2: Int): Int = 0
            override val empty: Int = 0
        }
        var tree = BPlusTree<Int, RuntimeMeasure, Int>(aggregator = aggregator)
        for (measure in newMeasures) tree = tree.put(measure.number, measure)
        return copy(measures = tree)
    }

    /** Replace track maps and re-link every resolved reference in the runtime object graph. */
    fun replaceTracks(
        pitchTracks: Map<TrackId, RuntimePitchTrack> = this.pitchTracks,
        voiceTracks: Map<TrackId, RuntimeVoiceTrack> = this.voiceTracks,
        staffTracks: Map<TrackId, RuntimeStaffTrack> = this.staffTracks,
    ): RuntimeScore {
        val relinkedVoices = voiceTracks.mapValues { (_, voice) ->
            val pitchTrack = pitchTracks[voice.pitchTrackId] ?: voice.pitchTrack
            voice.copy(
                pitchTrack = pitchTrack,
                events = TimeIndexedList.of(voice.events.map { event ->
                    event.copy(pitchEvent = pitchTrack.findEvent(event.pitchEvent.id) ?: event.pitchEvent)
                }),
            )
        }
        val relinkedStaffs = staffTracks.mapValues { (_, staff) ->
            staff.copy(voiceTracks = staff.voiceTracks.mapNotNull { relinkedVoices[it.id] })
        }
        fun relinkGroup(group: RuntimeStaffGroup): RuntimeStaffGroup = group.copy(
            members = group.members.map { member ->
                when (member) {
                    is RuntimeStaffGroupMember.Staff -> RuntimeStaffGroupMember.Staff(
                        relinkedStaffs[member.staff.id] ?: member.staff
                    )
                    is RuntimeStaffGroupMember.Group -> RuntimeStaffGroupMember.Group(relinkGroup(member.group))
                }
            }
        )
        return copy(
            pitchTracks = pitchTracks,
            voiceTracks = relinkedVoices,
            staffTracks = relinkedStaffs,
            instruments = instruments.map { instrument ->
                instrument.copy(staves = instrument.staves.mapNotNull { relinkedStaffs[it.id] })
            },
            staffGroups = staffGroups.map(::relinkGroup),
        )
    }

    fun addPitchEvent(trackId: TrackId, event: RuntimePitchEvent): RuntimeScore {
        val track = pitchTracks[trackId] ?: return this
        return updatePitchTrackInHierarchy(trackId, track.addEvent(event))
    }

    fun removePitchEvent(trackId: TrackId, eventId: EventId): RuntimeScore {
        val track = pitchTracks[trackId] ?: return this
        return updatePitchTrackInHierarchy(trackId, track.removeEvent(eventId))
    }

    fun addVoiceEvent(trackId: TrackId, event: RuntimeVoiceEvent): RuntimeScore {
        val track = voiceTracks[trackId] ?: return this
        return updateVoiceTrackInHierarchy(trackId, track.addEvent(event))
    }

    fun removeVoiceEvent(trackId: TrackId, eventId: EventId): RuntimeScore {
        val track = voiceTracks[trackId] ?: return this
        return updateVoiceTrackInHierarchy(trackId, track.removeEvent(eventId))
    }

    // ========== Private helpers ==========

    private fun updateVoiceTrackInHierarchy(
        trackId: TrackId,
        updatedTrack: RuntimeVoiceTrack
    ): RuntimeScore {
        val newVoiceTracks = voiceTracks + (trackId to updatedTrack)
        val newStaffTracks = staffTracks.mapValues { (_, staff) ->
            val updatedVoiceTracks = staff.voiceTracks.map { v ->
                if (v.id == trackId) updatedTrack else v
            }
            if (updatedVoiceTracks != staff.voiceTracks) staff.copy(voiceTracks = updatedVoiceTracks)
            else staff
        }
        return copy(voiceTracks = newVoiceTracks, staffTracks = newStaffTracks)
    }

    private fun updatePitchTrackInHierarchy(
        trackId: TrackId,
        updatedTrack: RuntimePitchTrack
    ): RuntimeScore {
        val newPitchTracks = pitchTracks + (trackId to updatedTrack)
        val newVoiceTracks = voiceTracks.mapValues { (_, voice) ->
            if (voice.pitchTrackId == trackId) voice.copy(pitchTrack = updatedTrack) else voice
        }
        val newStaffTracks = staffTracks.mapValues { (_, staff) ->
            val updatedVoiceTracks = staff.voiceTracks.map { v ->
                newVoiceTracks[v.id] ?: v
            }
            if (updatedVoiceTracks != staff.voiceTracks) staff.copy(voiceTracks = updatedVoiceTracks)
            else staff
        }
        return copy(
            pitchTracks = newPitchTracks,
            voiceTracks = newVoiceTracks,
            staffTracks = newStaffTracks
        )
    }

    companion object {
        fun fromStorage(storage: StorageScore): RuntimeScore {
            val globalKeySigEvents = storage.globalTrack.events
                .filterIsInstance<StorageKeySignatureChange>()
                .associate { it.onset.measure to it.keySignature }
            val globalTimeSigEvents = storage.globalTrack.events
                .filterIsInstance<StorageTimeSignatureChange>()
                .associate { it.onset.measure to it.timeSignature }
            val forcedPageBreaks = storage.globalTrack.events
                .filterIsInstance<StoragePageBreak>()
                .map { it.onset.measure }
                .toSet()
            // A page break also forces a system break at the same measure.
            val forcedSystemBreaks = storage.globalTrack.events
                .filterIsInstance<StorageSystemBreak>()
                .map { it.onset.measure }
                .toSet() + forcedPageBreaks

            var runningKeySignature = storage.defaultKeySignature
            var runningTimeSignature = storage.defaultTimeSignature
            val measures = storage.measures.sortedBy { it.number }.map { storageMeasure ->
                val effectiveKey = storageMeasure.keySignature
                    ?: globalKeySigEvents[storageMeasure.number]
                    ?: runningKeySignature
                runningKeySignature = effectiveKey
                val effectiveTimeSig = storageMeasure.timeSignature
                    ?: globalTimeSigEvents[storageMeasure.number]
                    ?: runningTimeSignature
                runningTimeSignature = effectiveTimeSig
                RuntimeMeasure(
                    number = storageMeasure.number,
                    timeSignature = effectiveTimeSig,
                    keySignature = effectiveKey,
                    hasExplicitTimeSignature = storageMeasure.timeSignature != null ||
                        globalTimeSigEvents.containsKey(storageMeasure.number),
                    hasExplicitKeySignature = storageMeasure.keySignature != null ||
                        globalKeySigEvents.containsKey(storageMeasure.number),
                    rehearsalMark = storageMeasure.rehearsalMark,
                    endBarlineType = storageMeasure.endBarlineType,
                    repeatStart = storageMeasure.repeatStart,
                    repeatEnd = storageMeasure.repeatEnd,
                    repeatCount = storageMeasure.repeatCount,
                    voltaNumbers = storageMeasure.voltaNumbers,
                    navigationMarks = storageMeasure.navigationMarks,
                    navigationMarkOffsets = storageMeasure.navigationMarkOffsets,
                )
            }

            val dummyAgg = object : BPlusTreeAggregator<RuntimeMeasure, Int> {
                override fun extract(value: RuntimeMeasure): Int = 0
                override fun combine(a1: Int, a2: Int): Int = 0
                override val empty: Int = 0
            }
            var measuresTree = BPlusTree<Int, RuntimeMeasure, Int>(aggregator = dummyAgg)
            for (m in measures) measuresTree = measuresTree.put(m.number, m)

            val pitchTracks = storage.pitchTracks.mapValues { (_, track) ->
                RuntimePitchTrack.fromStorage(track)
            }

            val pitchEventById: Map<EventId, RuntimePitchEvent> =
                pitchTracks.values.flatMap { it.events.toList() }.associateBy { it.id }

            val voiceTracks = storage.voiceTracks.mapValues { (_, storageVoice) ->
                val voicePitchTrack = pitchTracks[storageVoice.pitchTrackId]
                    ?: RuntimePitchTrack.create("Unknown")
                RuntimeVoiceTrack(
                    id = storageVoice.id,
                    name = storageVoice.name,
                    voiceNumber = storageVoice.voiceNumber,
                    pitchTrackId = storageVoice.pitchTrackId,
                    pitchTrack = voicePitchTrack,
                    events = TimeIndexedList.of(storageVoice.events.map { storageVoiceEvent ->
                        val resolvedPitchEvent = pitchEventById[storageVoiceEvent.pitchEventId]
                            ?: error("Pitch event ${storageVoiceEvent.pitchEventId} not found")
                        RuntimeVoiceEvent(
                            id = storageVoiceEvent.id,
                            onset = storageVoiceEvent.onset,
                            pitchEvent = resolvedPitchEvent,
                            duration = storageVoiceEvent.duration,
                            rendering = storageVoiceEvent.rendering,
                            ties = storageVoiceEvent.ties.map { RuntimeTieInfo(it.pitchIndex, it.isLetRing) },
                            tupletSpan = storageVoiceEvent.tupletSpan,
                            slurStarts = storageVoiceEvent.slurStarts,
                            slurEnds = storageVoiceEvent.slurEnds,
                            graceInfo = storageVoiceEvent.graceInfo,
                        )
                    }),
                    slurs = storageVoice.slurs.map {
                        RuntimeSlur(it.id, it.startEventId, it.endEventId)
                    }
                )
            }

            val staffTracks = storage.staffTracks.mapValues { (_, storageStaff) ->
                val staffVoiceTracks = storageStaff.voiceTrackIds.mapNotNull { voiceTracks[it] }
                RuntimeStaffTrack.fromStorage(storageStaff, staffVoiceTracks)
            }

            val runtimeInstruments = (storage.instruments.ifEmpty {
                val ids = storage.staffTracks.keys.toList()
                if (ids.isEmpty()) emptyList() else listOf(
                    StorageInstrument(
                        id = InstrumentId.generate(),
                        name = "Piano",
                        abbreviation = "Pno.",
                        staffIds = ids
                    )
                )
            }).map { instrument ->
                RuntimeInstrument(
                    id = instrument.id,
                    name = instrument.name,
                    abbreviation = instrument.abbreviation,
                    catalogId = instrument.catalogId,
                    staves = instrument.staffIds.mapNotNull { staffTracks[it] },
                    playback = instrument.playback
                )
            }

            // Resolve staff groups: replace TrackId references with concrete staves.
            fun resolveGroup(sg: StorageStaffGroup): RuntimeStaffGroup = RuntimeStaffGroup(
                id = sg.id,
                bracket = sg.bracket,
                label = sg.label,
                abbreviation = sg.abbreviation,
                barlineConnect = sg.barlineConnect,
                members = sg.members.mapNotNull { member ->
                    when (member) {
                        is StaffGroupMember.Staff -> staffTracks[member.staffId]
                            ?.let { RuntimeStaffGroupMember.Staff(it) }
                        is StaffGroupMember.Group -> RuntimeStaffGroupMember.Group(resolveGroup(member.group))
                    }
                }
            )
            val runtimeStaffGroups = storage.staffGroups.map { resolveGroup(it) }

            return RuntimeScore(
                id = storage.id,
                metadata = storage.metadata,
                defaultTimeSignature = storage.defaultTimeSignature,
                defaultKeySignature = storage.defaultKeySignature,
                defaultTempo = storage.defaultTempo,
                initialBarlineType = storage.initialBarlineType,
                measures = measuresTree,
                staffTracks = staffTracks,
                instruments = runtimeInstruments,
                voiceTracks = voiceTracks,
                pitchTracks = pitchTracks,
                pluginTracks = storage.pluginTracks.mapValues { (_, storagePt) ->
                    RuntimePluginTrack(
                        id = storagePt.id,
                        name = storagePt.name,
                        type = storagePt.type,
                        events = TimeIndexedList.of(storagePt.events.map { ev ->
                            object : RuntimePluginEvent<StoragePluginEvent> {
                                override val id = ev.id
                                override val onset = ev.onset
                                override val storageEvent = ev
                            }
                        })
                    )
                },
                controllerTracks = storage.controllerTracks,
                staffGroups = runtimeStaffGroups,
                pageLayout = storage.pageLayout,
                globalTrack = storage.globalTrack,
                viewPreferences = storage.viewPreferences,
                forcedSystemBreaks = forcedSystemBreaks,
                forcedPageBreaks = forcedPageBreaks,
                geometry = storage.geometry,
                reductions = storage.reductions,
                orchestration = storage.orchestration,
                showTimeSignatures = storage.showTimeSignatures,
            )
        }

        fun create(
            title: String = "Untitled",
            timeSignature: TimeSignature = TimeSignature.COMMON,
            keySignature: KeySignature = KeySignature.C_MAJOR
        ): RuntimeScore {
            val storage = StorageScore.create(StorageScore.CreationOptions(title, timeSignature, keySignature))
            return fromStorage(storage)
        }
    }
}

/** Returns the staves in display order (depth-first traversal of [RuntimeScore.staffGroups]). */
fun RuntimeScore.orderedStaffs(): List<RuntimeStaffTrack> {
    fun visit(member: RuntimeStaffGroupMember): List<RuntimeStaffTrack> = when (member) {
        is RuntimeStaffGroupMember.Staff -> listOf(member.staff)
        is RuntimeStaffGroupMember.Group -> member.group.members.flatMap { visit(it) }
    }
    return staffGroups.flatMap { g -> g.members.flatMap { visit(it) } }
}

@Suppress("UNCHECKED_CAST")
fun <T : StoragePluginEvent> RuntimeScore.pluginTrackOf(
    trackType: String
): RuntimePluginTrack<T>? =
    pluginTracks.values.firstOrNull { it.type == trackType } as? RuntimePluginTrack<T>

fun RuntimeScore.toStorage(): StorageScore {
    val storagePitchTracks = pitchTracks.mapValues { (_, track) ->
        StoragePitchTrack(id = track.id, name = track.name,
            events = track.events.map { it.toStorage() })
    }

    val storageVoiceTracks = voiceTracks.mapValues { (_, track) ->
        StorageVoiceTrack(
            id = track.id, name = track.name,
            voiceNumber = track.voiceNumber, pitchTrackId = track.pitchTrackId,
            events = track.events.map { voiceEvent ->
                StorageVoiceEvent(
                    id = voiceEvent.id, onset = voiceEvent.onset,
                    pitchEventId = voiceEvent.pitchEvent.id,
                    duration = voiceEvent.duration, rendering = voiceEvent.rendering,
                    ties = voiceEvent.ties.map { TieInfo(it.pitchIndex, it.isLetRing) },
                    tupletSpan = voiceEvent.tupletSpan,
                    slurStarts = voiceEvent.slurStarts,
                    slurEnds = voiceEvent.slurEnds,
                    graceInfo = voiceEvent.graceInfo,
                )
            },
            // Preserve first-class (promoted) slurs so they survive the runtime→storage round-trip.
            slurs = track.slurs.map { StorageSlurEvent(it.id, it.startEventId, it.endEventId) },
        )
    }

    val storageStaffTracks = staffTracks.mapValues { (_, track) ->
        StorageStaffTrack(
            id = track.id, name = track.name, clef = track.clef,
            keySignature = track.keySignature, transposition = track.transposition,
            voiceTrackIds = track.voiceTracks.map { it.id },
            staffLabel = track.staffLabel,
            staffLabelAbbreviation = track.staffLabelAbbreviation,
            attachments = track.attachments,
            clefChanges = track.clefChanges,
            hiddenRanges = track.hiddenRanges
        )
    }

    fun toStorageGroup(rg: RuntimeStaffGroup): StorageStaffGroup = StorageStaffGroup(
        id = rg.id, bracket = rg.bracket, label = rg.label,
        abbreviation = rg.abbreviation, barlineConnect = rg.barlineConnect,
        members = rg.members.map { m ->
            when (m) {
                is RuntimeStaffGroupMember.Staff -> StaffGroupMember.Staff(m.staff.id)
                is RuntimeStaffGroupMember.Group -> StaffGroupMember.Group(toStorageGroup(m.group))
            }
        }
    )

    val storageMeasures = measures.map { entry ->
        val measure = entry.value
        StorageMeasure(
            number = measure.number,
            timeSignature = measure.timeSignature.takeIf { measure.hasExplicitTimeSignature },
            keySignature = measure.keySignature.takeIf { measure.hasExplicitKeySignature },
            rehearsalMark = measure.rehearsalMark,
            endBarlineType = measure.endBarlineType,
            repeatStart = measure.repeatStart, repeatEnd = measure.repeatEnd,
              repeatCount = measure.repeatCount,
              voltaNumbers = measure.voltaNumbers,
              navigationMarks = measure.navigationMarks,
              navigationMarkOffsets = measure.navigationMarkOffsets,
        )
    }

    return StorageScore(
        id = id, metadata = metadata,
        defaultTimeSignature = defaultTimeSignature,
        defaultKeySignature = defaultKeySignature,
        defaultTempo = defaultTempo,
        initialBarlineType = initialBarlineType,
        measures = storageMeasures,
        pitchTracks = storagePitchTracks,
        voiceTracks = storageVoiceTracks,
        staffTracks = storageStaffTracks,
        instruments = instruments.map { instrument ->
            StorageInstrument(
                id = instrument.id,
                name = instrument.name,
                abbreviation = instrument.abbreviation,
                catalogId = instrument.catalogId,
                staffIds = instrument.staves.map { it.id },
                playback = instrument.playback
            )
        },
        controllerTracks = controllerTracks,
        pluginTracks = pluginTracks.mapValues { (_, track) ->
            StoragePluginTrack(
                id = track.id,
                name = track.name,
                type = track.type,
                events = track.events.map { it.storageEvent },
            )
        },
        globalTrack = globalTrack,
        staffGroups = staffGroups.map { toStorageGroup(it) },
        pageLayout = pageLayout,
        viewPreferences = viewPreferences,
        geometry = geometry,
        reductions = reductions,
        orchestration = orchestration,
        showTimeSignatures = showTimeSignatures,
    )
}
