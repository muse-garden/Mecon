package com.mecon.desktop.input

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.mecon.api.interaction.EventSection
import com.mecon.api.interaction.MeasureStaffSection
import com.mecon.api.interaction.VoiceArticulationSection
import com.mecon.api.interaction.VoiceEventSection
import com.mecon.api.interaction.VoiceFlagSection
import com.mecon.api.interaction.VoiceNoteSection
import com.mecon.api.interaction.VoiceStemSection
import com.mecon.api.interaction.VoiceTupletSection
import com.mecon.api.primitive.EventId
import com.mecon.api.primitive.Fraction
import com.mecon.api.primitive.Pitch
import com.mecon.api.primitive.TimeCode
import com.mecon.api.runtime.RuntimeScore
import com.mecon.desktop.AppSettings
import com.mecon.desktop.voiceTrackIdOf
import com.mecon.input.ComputerKeyboardLayout
import com.mecon.input.ComputerNoteKey
import com.mecon.input.InputPitchMode
import com.mecon.input.KeyboardPitchRange
import com.mecon.input.NoteEntryCaret
import com.mecon.input.NoteEntryNavigation

enum class NoteInputEntryMode {
    STEP,
    REALTIME,
}

enum class NoteInputPhase {
    INACTIVE,
    STEP_READY,
    REALTIME_ARMED,
    RECORDING,
}

class NoteInputState {
    var phase by mutableStateOf(NoteInputPhase.INACTIVE)
        private set
    var entryMode by mutableStateOf(AppSettings.noteInputEntryMode)
        private set
    var pitchMode by mutableStateOf(AppSettings.noteInputPitchMode)
        private set
    var centerOctave by mutableStateOf(AppSettings.noteInputCenterOctave)
        private set
    var registerOffset by mutableStateOf(0)
        private set
    var anchorKey by mutableStateOf(AppSettings.noteInputAnchorKey)
        private set
    var quantizeDenominator by mutableStateOf(AppSettings.noteInputQuantizeDenominator)
        private set
    var allowedTuplets by mutableStateOf(AppSettings.noteInputAllowedTuplets)
        private set
    var caret by mutableStateOf<NoteEntryCaret?>(null)
        private set

    val active: Boolean get() = phase != NoteInputPhase.INACTIVE
    val layout: ComputerKeyboardLayout get() = ComputerKeyboardLayout(anchorKey)

    fun activate(runtime: RuntimeScore, selection: Set<EventSection>, voiceNumber: Int) {
        caret = resolveInitialCaret(runtime, selection, voiceNumber) ?: return
        phase = when (entryMode) {
            NoteInputEntryMode.STEP -> NoteInputPhase.STEP_READY
            NoteInputEntryMode.REALTIME -> NoteInputPhase.REALTIME_ARMED
        }
    }

    fun deactivate() {
        phase = NoteInputPhase.INACTIVE
    }

    fun resetForDocumentSwitch() {
        phase = NoteInputPhase.INACTIVE
        caret = null
        registerOffset = 0
    }

    fun setEntryMode(value: NoteInputEntryMode, runtime: RuntimeScore?, selection: Set<EventSection>, voiceNumber: Int) {
        if (phase == NoteInputPhase.RECORDING) return
        entryMode = value
        AppSettings.noteInputEntryMode = value
        if (active && runtime != null) {
            caret = resolveInitialCaret(runtime, selection, voiceNumber) ?: caret
            phase = when (value) {
                NoteInputEntryMode.STEP -> NoteInputPhase.STEP_READY
                NoteInputEntryMode.REALTIME -> NoteInputPhase.REALTIME_ARMED
            }
        }
    }

    fun togglePitchMode() {
        pitchMode = when (pitchMode) {
            InputPitchMode.ABSOLUTE -> InputPitchMode.RELATIVE_TO_KEY
            InputPitchMode.RELATIVE_TO_KEY -> InputPitchMode.ABSOLUTE
        }
        AppSettings.noteInputPitchMode = pitchMode
    }

    fun beginRecording() {
        if (phase == NoteInputPhase.REALTIME_ARMED) phase = NoteInputPhase.RECORDING
    }

    fun shiftOctave(delta: Int) {
        registerOffset = (registerOffset + delta).coerceIn(-8, 8)
    }

    fun chooseAnchorKey(value: ComputerNoteKey) {
        ComputerKeyboardLayout(value)
        anchorKey = value
        AppSettings.noteInputAnchorKey = value
    }

    fun cycleAnchorKey() {
        val anchors = listOf(
            ComputerNoteKey.A, ComputerNoteKey.S, ComputerNoteKey.D, ComputerNoteKey.F,
            ComputerNoteKey.G, ComputerNoteKey.H, ComputerNoteKey.J, ComputerNoteKey.K,
            ComputerNoteKey.L, ComputerNoteKey.SEMICOLON, ComputerNoteKey.APOSTROPHE,
        )
        chooseAnchorKey(anchors[(anchors.indexOf(anchorKey) + 1).mod(anchors.size)])
    }

    fun cycleQuantization() {
        val values = listOf(4, 8, 16, 32, 64)
        quantizeDenominator = values[(values.indexOf(quantizeDenominator) + 1).mod(values.size)]
        AppSettings.noteInputQuantizeDenominator = quantizeDenominator
    }

    fun resolvePitch(runtime: RuntimeScore, key: ComputerNoteKey): Pitch? {
        val current = caret ?: return null
        return layout.resolve(
            key = key,
            mode = pitchMode,
            keySignature = runtime.getKeySignatureAt(current.onset.measure),
            centerOctave = centerOctave,
            registerOffset = registerOffset,
        )
    }

    fun centerPitch(runtime: RuntimeScore): Pitch? =
        resolvePitch(runtime, anchorKey)

    fun pitchRange(runtime: RuntimeScore): KeyboardPitchRange? {
        val current = caret ?: return null
        return layout.range(
            mode = pitchMode,
            keySignature = runtime.getKeySignatureAt(current.onset.measure),
            centerOctave = centerOctave,
            registerOffset = registerOffset,
        )
    }

    fun moveHorizontal(runtime: RuntimeScore, direction: Int, fallbackDuration: com.mecon.api.primitive.Duration) {
        val current = caret ?: return
        val voice = runtime.voiceTracks[current.voiceTrackId]
            ?: runtime.staffTracks[current.staffTrackId]?.voiceTracks
                ?.firstOrNull { it.voiceNumber == current.voiceNumber }
        val onsets = voice?.events?.toList()?.map { it.onset }?.distinct()?.sorted().orEmpty()
        val target = if (direction < 0) {
            onsets.lastOrNull { it < current.onset }
                ?: NoteEntryNavigation.retreat(runtime, current.onset, fallbackDuration)
        } else {
            onsets.firstOrNull { it > current.onset }
                ?: NoteEntryNavigation.advance(runtime, current.onset, fallbackDuration)
        }
        caret = current.copy(onset = clampOnset(runtime, target))
    }

    fun advanceOptimistically(runtime: RuntimeScore, duration: com.mecon.api.primitive.Duration) {
        val current = caret ?: return
        caret = current.copy(onset = clampOnset(runtime, NoteEntryNavigation.advance(runtime, current.onset, duration)))
    }

    fun updateVoiceFromInserted(runtime: RuntimeScore, eventId: EventId) {
        val current = caret ?: return
        val voiceId = runtime.voiceTrackIdOf(eventId) ?: return
        val voice = runtime.voiceTracks[voiceId] ?: return
        val staff = runtime.staffTracks.values.firstOrNull { staff ->
            staff.voiceTracks.any { it.id == voiceId }
        } ?: return
        caret = current.copy(
            staffTrackId = staff.id,
            voiceTrackId = voiceId,
            voiceNumber = voice.voiceNumber,
        )
    }

    private fun resolveInitialCaret(
        runtime: RuntimeScore,
        selection: Set<EventSection>,
        voiceNumber: Int,
    ): NoteEntryCaret? {
        val selected = selection.lastOrNull()
        if (selected is MeasureStaffSection) {
            return caretOnStaff(runtime, selected.staffTrackId, voiceNumber, TimeCode.ofMeasure(selected.measureNumber))
        }

        selected?.voiceEventId()?.let { eventId ->
            val voiceId = runtime.voiceTrackIdOf(eventId)
            val voice = voiceId?.let(runtime.voiceTracks::get)
            val staff = voiceId?.let { id ->
                runtime.staffTracks.values.firstOrNull { staff -> staff.voiceTracks.any { it.id == id } }
            }
            val onset = selected.voiceOnset()
            if (voiceId != null && voice != null && staff != null && onset != null) {
                return NoteEntryCaret(staff.id, voiceId, voice.voiceNumber, onset)
            }
        }

        val old = caret
        if (old != null && runtime.staffTracks.containsKey(old.staffTrackId)) {
            return old.copy(onset = clampOnset(runtime, old.onset))
        }

        val staff = runtime.staffTracks.values.firstOrNull() ?: return null
        return caretOnStaff(runtime, staff.id, voiceNumber, TimeCode.ofMeasure(1))
    }

    private fun caretOnStaff(
        runtime: RuntimeScore,
        staffId: com.mecon.api.primitive.TrackId,
        voiceNumber: Int,
        onset: TimeCode,
    ): NoteEntryCaret? {
        val staff = runtime.staffTracks[staffId] ?: return null
        val voice = staff.voiceTracks.firstOrNull { it.voiceNumber == voiceNumber }
            ?: staff.voiceTracks.firstOrNull()
            ?: return null
        return NoteEntryCaret(staff.id, voice.id, voiceNumber, clampOnset(runtime, onset))
    }

    private fun clampOnset(runtime: RuntimeScore, onset: TimeCode): TimeCode {
        val lastMeasure = runtime.measures.maxOfOrNull { it.value.number } ?: 1
        if (onset.measure < 1) return TimeCode.ofMeasure(1)
        if (onset.measure > lastMeasure) {
            return TimeCode.of(
                lastMeasure,
                runtime.getTimeSignatureAt(lastMeasure).measureDuration(),
            )
        }
        val length = runtime.getTimeSignatureAt(onset.measure).measureDuration()
        val beat = onset.beat ?: Fraction.ZERO
        return TimeCode.of(onset.measure, beat.coerceAtMost(length))
    }
}

private fun EventSection.voiceEventId(): EventId? = when (this) {
    is VoiceEventSection -> event.id
    is VoiceNoteSection -> event.id
    is VoiceStemSection -> event.id
    is VoiceFlagSection -> event.id
    is VoiceArticulationSection -> event.id
    is VoiceTupletSection -> startEvent.id
    else -> null
}

private fun EventSection.voiceOnset(): TimeCode? = when (this) {
    is VoiceEventSection -> event.onset
    is VoiceNoteSection -> event.onset
    is VoiceStemSection -> event.onset
    is VoiceFlagSection -> event.onset
    is VoiceArticulationSection -> event.onset
    is VoiceTupletSection -> startEvent.onset
    else -> null
}
