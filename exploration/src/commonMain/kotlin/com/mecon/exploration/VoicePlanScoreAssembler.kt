package com.mecon.exploration

import com.mecon.api.primitive.KeySignature
import com.mecon.api.primitive.ScoreId
import com.mecon.api.primitive.StaffGroupId
import com.mecon.api.primitive.TimeSignature
import com.mecon.api.runtime.RuntimeMeasure
import com.mecon.api.runtime.RuntimeScore
import com.mecon.api.runtime.toStorage
import com.mecon.api.storage.PageLayoutConfig
import com.mecon.api.storage.ScoreMetadata
import com.mecon.api.storage.StorageMeasure
import com.mecon.api.storage.StorageScore
import com.mecon.api.storage.events.StorageBreathMark
import com.mecon.api.storage.events.StorageDynamicMark
import com.mecon.api.storage.events.StorageHairpin
import com.mecon.api.storage.events.StorageOctaveShiftEnd
import com.mecon.api.storage.events.StorageOctaveShiftStart
import com.mecon.api.storage.events.StorageOrnamentMark
import com.mecon.api.storage.events.StorageStaffAttachment
import com.mecon.api.storage.tracks.BracketStyle
import com.mecon.api.storage.tracks.StaffGroupMember
import com.mecon.api.storage.tracks.StoragePitchTrack
import com.mecon.api.storage.tracks.StorageStaffGroup
import com.mecon.api.storage.tracks.StorageStaffTrack
import com.mecon.api.storage.tracks.StorageVoiceTrack
import com.mecon.theory.freepractice.HarmonyWorkspaceState
import com.mecon.theory.freepractice.VoiceNotationPlan
import com.mecon.theory.writing.GrandStaffVoiceLayout

object VoicePlanScoreAssembler {
    val FREE_PRACTICE_METER = TimeSignature(2, 4)
    private val FREE_PRACTICE_GROUP_ID = StaffGroupId("free-practice-staff-group")

    fun emptyPracticeScore(
        workspace: HarmonyWorkspaceState,
        keySignature: KeySignature,
        staffVoices: GrandStaffVoiceLayout = GrandStaffVoiceLayout.defaultFor(
            workspace.voices.size
        ),
        title: String = "Free Practice",
    ): StorageScore {
        val notation = VoiceNotationPlan.from(workspace.voicePlan, staffVoices)
        val bindingsByVoice = notation.bindings.associateBy { it.voiceId }
        val measureCount = requiredMeasureCount(workspace)
        val pitchTracks = workspace.voices.associate { voice ->
            val binding = bindingsByVoice.getValue(voice.id)
            binding.pitchTrackId to StoragePitchTrack(
                id = binding.pitchTrackId,
                name = "${voice.label ?: voice.id.value} Pitch",
            )
        }
        val voiceTracks = workspace.voices.associate { voice ->
            val binding = bindingsByVoice.getValue(voice.id)
            voice.id to StorageVoiceTrack(
                id = voice.id,
                name = voice.label ?: voice.id.value,
                voiceNumber = binding.voiceNumber,
                pitchTrackId = binding.pitchTrackId,
            )
        }
        val staffTracks = notation.bindings.groupBy { it.staffId }.mapValues { (staffId, bindings) ->
            StorageStaffTrack(
                id = staffId,
                name = if (staffId == VoiceNotationPlan.UPPER_STAFF_ID) "Upper" else "Lower",
                clef = bindings.first().clef,
                keySignature = keySignature,
                voiceTrackIds = bindings.map { it.voiceId },
            )
        }
        val staffIds = notation.bindings.map { it.staffId }.distinct()
        return StorageScore(
            id = ScoreId("schoenberg-free-practice"),
            metadata = ScoreMetadata(title = title),
            defaultTimeSignature = FREE_PRACTICE_METER,
            defaultKeySignature = keySignature,
            measures = (1..measureCount).map(::StorageMeasure),
            pitchTracks = pitchTracks,
            voiceTracks = voiceTracks,
            staffTracks = staffTracks,
            staffGroups = listOf(
                StorageStaffGroup(
                    id = FREE_PRACTICE_GROUP_ID,
                    bracket = BracketStyle.BRACE,
                    barlineConnect = true,
                    members = staffIds.map(StaffGroupMember::Staff),
                )
            ),
            pageLayout = PageLayoutConfig.DEFAULT.copy(paginated = false),
        )
    }

    /**
     * Upgrades the legacy one-voice-per-staff representation to the v3 two-staff layout.
     *
     * Voice/pitch tracks and every event stay intact. Only the staff membership and each voice
     * track's number within its target staff change.
     */
    fun migrateToGrandStaff(
        score: StorageScore,
        workspace: HarmonyWorkspaceState,
        staffVoices: GrandStaffVoiceLayout,
    ): StorageScore {
        val notation = VoiceNotationPlan.from(workspace.voicePlan, staffVoices)
        val bindingsByVoice = notation.bindings.associateBy { it.voiceId }
        val targetStaffIds = setOf(
            VoiceNotationPlan.UPPER_STAFF_ID,
            VoiceNotationPlan.LOWER_STAFF_ID,
        )
        val alreadyMigrated =
            score.staffTracks.keys == targetStaffIds &&
                notation.bindings.all { binding ->
                    score.staffTracks[binding.staffId]?.voiceTrackIds?.contains(binding.voiceId) == true &&
                        score.voiceTracks[binding.voiceId]?.voiceNumber == binding.voiceNumber
                }
        if (alreadyMigrated) return score

        val sourceStaffByVoice = buildMap {
            score.staffTracks.values.forEach { staff ->
                staff.voiceTrackIds.forEach { voiceId -> put(voiceId, staff) }
            }
        }
        val voiceTracks = score.voiceTracks.mapValues { (voiceId, track) ->
            bindingsByVoice[voiceId]?.let { track.copy(voiceNumber = it.voiceNumber) } ?: track
        }
        val staffTracks = notation.bindings.groupBy { it.staffId }.mapValues { (staffId, bindings) ->
            val targetVoiceIds = bindings.mapTo(hashSetOf()) { it.voiceId }
            val sourceStaffs = bindings.mapNotNull { sourceStaffByVoice[it.voiceId] }
                .distinctBy(StorageStaffTrack::id)
            val representative = sourceStaffs.firstOrNull()
            StorageStaffTrack(
                id = staffId,
                name = if (staffId == VoiceNotationPlan.UPPER_STAFF_ID) "Upper" else "Lower",
                clef = bindings.first().clef,
                keySignature = representative?.keySignature ?: score.defaultKeySignature,
                transposition = representative?.transposition,
                voiceTrackIds = bindings.map { it.voiceId },
                staffLabel = representative?.staffLabel,
                staffLabelAbbreviation = representative?.staffLabelAbbreviation,
                attachments = sourceStaffs.flatMap { sourceStaff ->
                    val sourceVoiceByNumber = sourceStaff.voiceTrackIds.mapNotNull { voiceId ->
                        score.voiceTracks[voiceId]?.voiceNumber?.let { it to voiceId }
                    }.toMap()
                    sourceStaff.attachments.mapNotNull { attachment ->
                        val sourceVoiceId = attachment.voiceNumber
                            ?.let(sourceVoiceByNumber::get)
                            ?: sourceStaff.voiceTrackIds.firstOrNull()
                            ?: return@mapNotNull null
                        if (sourceVoiceId !in targetVoiceIds) {
                            null
                        } else if (attachment.voiceNumber == null) {
                            attachment
                        } else {
                            attachment.withVoiceNumber(
                                bindingsByVoice.getValue(sourceVoiceId).voiceNumber,
                            )
                        }
                    }
                }.distinctBy(StorageStaffAttachment::id),
                clefChanges = representative?.clefChanges.orEmpty(),
                hiddenRanges = sourceStaffs.map { it.hiddenRanges }
                    .reduceOrNull { common, ranges -> common.intersect(ranges.toSet()).toList() }
                    .orEmpty(),
            )
        }
        val staffIds = notation.bindings.map { it.staffId }.distinct()
        return score.copy(
            voiceTracks = voiceTracks,
            staffTracks = staffTracks,
            staffGroups = listOf(
                StorageStaffGroup(
                    id = FREE_PRACTICE_GROUP_ID,
                    bracket = BracketStyle.BRACE,
                    barlineConnect = true,
                    members = staffIds.map(StaffGroupMember::Staff),
                )
            ),
        )
    }

    /**
     * Matches an existing practice score to the workspace timeline without rebuilding its tracks.
     * Measure metadata and notes inside the timeline are preserved.
     */
    fun ensureTimelineMeasures(
        score: RuntimeScore,
        workspace: HarmonyWorkspaceState,
    ): RuntimeScore {
        val requiredCount = requiredMeasureCount(workspace)
        val existing = score.measures.map { it.value }.sortedBy(RuntimeMeasure::number)
        val currentCount = existing.lastOrNull()?.number ?: 0
        if (currentCount == requiredCount) return score
        if (currentCount > requiredCount) return trimTrailingMeasures(score, requiredCount)

        val appended = (currentCount + 1..requiredCount).map { number ->
            RuntimeMeasure(
                number = number,
                timeSignature = score.getTimeSignatureAt(number),
                keySignature = score.getKeySignatureAt(number),
            )
        }
        return score.replaceMeasures(existing + appended)
    }

    /**
     * Drops the measures the timeline no longer reaches, together with the material inside them.
     *
     * Free-practice notation is a projection of the chord timeline, so everything after the last
     * slot is orphaned by construction. Keeping those measures let every shortening edit — dragging
     * a chord back to the left, deleting chords — pile empty measures up at the end of the score.
     * The rebuild goes through storage because dropping a voice event also has to drop the pitch
     * event it owns and any slur that ended on it.
     */
    private fun trimTrailingMeasures(score: RuntimeScore, requiredCount: Int): RuntimeScore {
        val storage = score.toStorage()
        val voiceTracks = storage.voiceTracks.mapValues { (_, track) ->
            val kept = track.events.filter { it.onset.measure <= requiredCount }
            val keptIds = kept.mapTo(hashSetOf()) { it.id }
            track.copy(
                events = kept,
                slurs = track.slurs.filter { it.startEventId in keptIds && it.endEventId in keptIds },
            )
        }
        val referenced = voiceTracks.values.flatMapTo(hashSetOf()) { track ->
            track.events.map { it.pitchEventId }
        }
        return RuntimeScore.fromStorage(
            storage.copy(
                measures = storage.measures.filter { it.number <= requiredCount },
                voiceTracks = voiceTracks,
                pitchTracks = storage.pitchTracks.mapValues { (_, track) ->
                    track.copy(
                        events = track.events.filter {
                            it.id in referenced || it.onset.measure <= requiredCount
                        },
                    )
                },
                staffTracks = storage.staffTracks.mapValues { (_, track) ->
                    track.copy(
                        clefChanges = track.clefChanges.filter { it.onset.measure <= requiredCount },
                        attachments = track.attachments.filter { it.onset.measure <= requiredCount },
                    )
                },
            )
        )
    }

    internal fun requiredMeasureCount(workspace: HarmonyWorkspaceState): Int {
        val totalDuration = workspace.slots.maxOf { it.onset + it.duration }
        val measures = totalDuration / FREE_PRACTICE_METER.measureDuration()
        return ((measures.numerator + measures.denominator - 1) / measures.denominator)
            .coerceAtLeast(1)
    }
}

private fun StorageStaffAttachment.withVoiceNumber(voiceNumber: Int?): StorageStaffAttachment =
    when (this) {
        is StorageOrnamentMark -> copy(voiceNumber = voiceNumber)
        is StorageBreathMark -> copy(voiceNumber = voiceNumber)
        is StorageDynamicMark -> copy(voiceNumber = voiceNumber)
        is StorageOctaveShiftStart -> copy(voiceNumber = voiceNumber)
        is StorageOctaveShiftEnd -> copy(voiceNumber = voiceNumber)
        is StorageHairpin -> copy(voiceNumber = voiceNumber)
    }
