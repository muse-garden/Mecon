package com.mecon.theory

import com.mecon.api.primitive.Duration
import com.mecon.api.primitive.Fraction
import com.mecon.api.primitive.Pitch
import com.mecon.api.primitive.ScoreId
import com.mecon.api.primitive.TimeCode
import com.mecon.api.storage.ScoreMetadata
import com.mecon.api.storage.StorageMeasure
import com.mecon.api.storage.StorageScore
import com.mecon.api.storage.events.StoragePitchEvent
import com.mecon.api.storage.events.StorageVoiceEvent
import com.mecon.api.storage.tracks.BracketStyle
import com.mecon.api.storage.tracks.Clef
import com.mecon.api.storage.tracks.StoragePitchTrack
import com.mecon.api.storage.tracks.StorageStaffGroup
import com.mecon.api.storage.tracks.StorageStaffTrack
import com.mecon.api.storage.tracks.StorageVoiceTrack

internal fun fixedVoiceStorageScore(
    sopranoPitches: List<List<Pitch>> = listOf(listOf(Pitch.fromName("C5")), listOf(Pitch.fromName("D5"))),
    altoPitches: List<List<Pitch>> = listOf(listOf(Pitch.fromName("E4"))),
    tenorPitches: List<List<Pitch>> = listOf(listOf(Pitch.fromName("G3"))),
    bassPitches: List<List<Pitch>> = listOf(listOf(Pitch.fromName("C3"))),
    sopranoDuration: Duration = Duration.QUARTER,
    altoDuration: Duration = Duration.QUARTER,
    tenorDuration: Duration = Duration.HALF,
    bassDuration: Duration = Duration.HALF,
): StorageScore {
    val soprano = fixedVoiceTrack("Soprano", 1, sopranoPitches, defaultDuration = sopranoDuration)
    val alto = fixedVoiceTrack("Alto", 2, altoPitches, defaultDuration = altoDuration)
    val tenor = fixedVoiceTrack("Tenor", 1, tenorPitches, defaultDuration = tenorDuration)
    val bass = fixedVoiceTrack("Bass", 2, bassPitches, defaultDuration = bassDuration)
    val trebleStaff = StorageStaffTrack.create(
        name = "Upper",
        clef = Clef.TREBLE,
        voiceTrackIds = listOf(soprano.voice.id, alto.voice.id),
    )
    val bassStaff = StorageStaffTrack.create(
        name = "Lower",
        clef = Clef.BASS,
        voiceTrackIds = listOf(tenor.voice.id, bass.voice.id),
    )
    val pitchTracks = listOf(soprano, alto, tenor, bass).associate { it.pitch.id to it.pitch }
    val voiceTracks = listOf(soprano, alto, tenor, bass).associate { it.voice.id to it.voice }
    val staffTracks = listOf(trebleStaff, bassStaff).associateBy { it.id }
    return StorageScore(
        id = ScoreId.generate(),
        metadata = ScoreMetadata(title = "Four Part"),
        measures = listOf(StorageMeasure(number = 1)),
        pitchTracks = pitchTracks,
        voiceTracks = voiceTracks,
        staffTracks = staffTracks,
        staffGroups = listOf(
            StorageStaffGroup.ofStaffs(
                bracket = BracketStyle.BRACE,
                barlineConnect = true,
                staffIds = listOf(trebleStaff.id, bassStaff.id),
            )
        ),
    )
}

private data class FixedVoiceFixture(
    val pitch: StoragePitchTrack,
    val voice: StorageVoiceTrack,
)

private fun fixedVoiceTrack(
    name: String,
    voiceNumber: Int,
    pitchGroups: List<List<Pitch>>,
    defaultDuration: Duration = Duration.QUARTER,
): FixedVoiceFixture {
    val pitchTrack = StoragePitchTrack.create("$name Pitches")
    val pitchEvents = pitchGroups.mapIndexed { index, pitches ->
        StoragePitchEvent.create(
            onset = TimeCode.of(1, Fraction(index, 4)),
            pitches = pitches,
        )
    }
    val voiceEvents = pitchEvents.map { pitchEvent ->
        StorageVoiceEvent.create(
            onset = pitchEvent.onset,
            pitchEventId = pitchEvent.id,
            duration = defaultDuration,
        )
    }
    return FixedVoiceFixture(
        pitch = pitchTrack.copy(events = pitchEvents),
        voice = StorageVoiceTrack.create(
            name = name,
            voiceNumber = voiceNumber,
            pitchTrackId = pitchTrack.id,
        ).copy(events = voiceEvents),
    )
}
