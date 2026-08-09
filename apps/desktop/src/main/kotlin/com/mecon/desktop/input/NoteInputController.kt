package com.mecon.desktop.input

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import com.mecon.api.interaction.EventSection
import com.mecon.api.interaction.VoiceEventSection
import com.mecon.api.primitive.Duration
import com.mecon.api.primitive.DurationBase
import com.mecon.api.primitive.Fraction
import com.mecon.api.primitive.Pitch
import com.mecon.api.primitive.TimeCode
import com.mecon.api.primitive.Tuplet
import com.mecon.api.runtime.RuntimeScore
import com.mecon.api.storage.BeamingInfo
import com.mecon.core.engine.edit.NoteEditEngine
import com.mecon.desktop.service.ScoreSession
import com.mecon.desktop.ui.components.NoteToolState
import com.mecon.desktop.ui.components.NoteEntryKind
import com.mecon.input.ChordCollector
import com.mecon.input.CollectedChord
import com.mecon.input.ComputerNoteKey
import com.mecon.input.PerformanceInputEvent
import com.mecon.input.InputKeyId
import com.mecon.input.MidiPitchResolver
import com.mecon.input.MidiPitchSemantics
import com.mecon.input.MidiPitchSettings
import com.mecon.input.PerformanceClock
import com.mecon.input.PitchSpellingContext
import com.mecon.input.PerformanceQuantizer
import com.mecon.input.QuantizationSettings
import com.mecon.input.QuantizedGridKind
import com.mecon.input.QuantizedPerformanceTake
import com.mecon.input.RealtimeTakeRecorder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class NoteInputController(
    private val scope: CoroutineScope,
    private val state: NoteInputState,
    private val session: ScoreSession,
    private val noteTool: NoteToolState,
    private val onInserted: (EventSection, RuntimeScore) -> Unit,
    private val onLiveNoteOn: (midi: Int, velocity: Int) -> Unit = { _, _ -> },
    private val onLiveNoteOff: (midi: Int) -> Unit = {},
    private val onLiveAllNotesOff: () -> Unit = {},
    private val onMetronomeTick: (accent: Boolean) -> Unit = {},
) {
    private val chordCollector = ChordCollector(AppSettingsBridge.chordWindowNanos())
    private var flushJob: Job? = null
    private val liveMidiByInputKey = HashMap<InputKeyId, Int>()
    private var recorder = RealtimeTakeRecorder()
    private var performanceClock: PerformanceClock? = null
    private var recordingScore: RuntimeScore? = null
    private var metronomeJob: Job? = null

    fun handle(event: KeyEvent): Boolean {
        if (!state.active) return false
        if (event.isCtrlPressed || event.isAltPressed || event.isMetaPressed) return false

        val noteKey = event.key.toComputerNoteKey()
        if (event.type == KeyEventType.KeyUp) {
            if (noteKey != null) {
                val atNanos = System.nanoTime()
                if (state.entryMode == NoteInputEntryMode.REALTIME) {
                    recorder.noteOff(InputKeyId(KEYBOARD_SOURCE, noteKey.ordinal), atNanos)
                } else {
                    chordCollector.noteOff(
                        PerformanceInputEvent.NoteOff(KEYBOARD_SOURCE, atNanos, noteKey.ordinal)
                    )
                }
                return true
            }
            return false
        }
        if (event.type != KeyEventType.KeyDown) return false

        val runtime = session.runtimeScore ?: return false
        if (state.entryMode == NoteInputEntryMode.REALTIME && event.key == Key.Spacebar) {
            stopAndExit(commit = true)
            return true
        }
        when (event.key) {
            Key.DirectionUp -> {
                state.shiftOctave(1)
                return true
            }
            Key.DirectionDown -> {
                state.shiftOctave(-1)
                return true
            }
            Key.DirectionLeft -> {
                if (state.entryMode == NoteInputEntryMode.REALTIME) return true
                flushPending(runtime)
                state.moveHorizontal(runtime, -1, noteTool.duration)
                return true
            }
            Key.DirectionRight -> {
                if (state.entryMode == NoteInputEntryMode.REALTIME) return true
                flushPending(runtime)
                state.moveHorizontal(runtime, 1, noteTool.duration)
                return true
            }
            Key.Zero -> {
                if (state.entryMode == NoteInputEntryMode.REALTIME) return true
                flushPending(runtime)
                submit(runtime, emptyList(), isRest = true)
                return true
            }
            else -> Unit
        }

        if (noteKey != null) {
            if (state.entryMode == NoteInputEntryMode.REALTIME) {
                val pitch = state.resolvePitch(recordingScore ?: runtime, noteKey) ?: return true
                realtimeNoteOn(
                    InputKeyId(KEYBOARD_SOURCE, noteKey.ordinal),
                    pitch,
                    System.nanoTime(),
                    velocity = 96,
                )
                return true
            }
            val expired = chordCollector.noteOn(
                PerformanceInputEvent.NoteOn(
                    sourceId = KEYBOARD_SOURCE,
                    atNanos = System.nanoTime(),
                    key = noteKey.ordinal,
                    spellingHint = state.layout.degreeFor(noteKey)?.spellingHint,
                )
            )
            expired?.let { submitCollected(runtime, it) }
            scheduleFlush()
            return true
        }
        return false
    }

    fun flushPending(runtime: RuntimeScore? = session.runtimeScore) {
        flushJob?.cancel()
        flushJob = null
        val chord = chordCollector.flush() ?: return
        runtime?.let { submitCollected(it, chord) }
    }

    fun handlePerformanceEvent(event: PerformanceInputEvent) {
        if (!state.active) return
        val runtime = session.runtimeScore ?: return
        when (event) {
            is PerformanceInputEvent.NoteOn -> {
                if (event.sourceId == KEYBOARD_SOURCE) return
                if (state.entryMode == NoteInputEntryMode.REALTIME) {
                    resolveMidiPitch(recordingScore ?: runtime, event.key, emptyList())?.let { pitch ->
                        realtimeNoteOn(
                            InputKeyId(event.sourceId, event.key),
                            pitch,
                            event.atNanos,
                            event.velocity,
                        )
                        sendMidiThru(runtime, event, pitch)
                    }
                    return
                }
                val expired = chordCollector.noteOn(event)
                expired?.let { submitCollected(runtime, it) }
                scheduleFlush()
                resolveMidiPitch(runtime, event.key, emptyList())?.let { sendMidiThru(runtime, event, it) }
            }
            is PerformanceInputEvent.NoteOff -> {
                if (state.entryMode == NoteInputEntryMode.REALTIME) {
                    recorder.noteOff(InputKeyId(event.sourceId, event.key), event.atNanos)
                } else {
                    chordCollector.noteOff(event)
                }
                liveMidiByInputKey.remove(InputKeyId(event.sourceId, event.key))?.let(onLiveNoteOff)
            }
            is PerformanceInputEvent.ControlChange -> {
                if (event.controller == 120 || event.controller == 123) {
                    chordCollector.releaseSource(event.sourceId)
                    recorder.releaseSource(event.sourceId, event.atNanos)
                    val ids = liveMidiByInputKey.keys.filter { it.sourceId == event.sourceId }
                    ids.forEach { liveMidiByInputKey.remove(it) }
                    onLiveAllNotesOff()
                }
                // CC64 intentionally affects thru only in the future LiveNoteSink pedal path; it
                // never changes notated durations.
            }
            is PerformanceInputEvent.SourceDisconnected -> {
                chordCollector.releaseSource(event.sourceId)
                recorder.releaseSource(event.sourceId, event.atNanos)
                liveMidiByInputKey.keys
                    .filter { it.sourceId == event.sourceId }
                    .forEach { liveMidiByInputKey.remove(it) }
                onLiveAllNotesOff()
                if (state.entryMode == NoteInputEntryMode.REALTIME) {
                    stopAndExit(commit = state.phase == NoteInputPhase.RECORDING)
                }
            }
        }
    }

    fun cancel() {
        flushJob?.cancel()
        flushJob = null
        chordCollector.reset()
        recorder.reset()
        performanceClock = null
        recordingScore = null
        metronomeJob?.cancel()
        metronomeJob = null
        liveMidiByInputKey.clear()
        onLiveAllNotesOff()
        state.deactivate()
    }

    /** Called by Compose whenever the state machine enters a new phase. */
    fun onPhaseChanged() {
        when (state.phase) {
            NoteInputPhase.REALTIME_ARMED -> armRealtime()
            NoteInputPhase.INACTIVE -> {
                metronomeJob?.cancel()
                metronomeJob = null
                recorder.reset()
                performanceClock = null
                recordingScore = null
                liveMidiByInputKey.clear()
                onLiveAllNotesOff()
            }
            NoteInputPhase.STEP_READY -> {
                metronomeJob?.cancel()
                metronomeJob = null
                recorder.reset()
                performanceClock = null
                recordingScore = null
            }
            NoteInputPhase.RECORDING -> Unit
        }
    }

    fun stopAndExit(commit: Boolean) {
        flushJob?.cancel()
        flushJob = null
        metronomeJob?.cancel()
        metronomeJob = null
        val now = System.nanoTime()
        val take = if (commit) recorder.finish(now) else null
        val clock = performanceClock
        val score = recordingScore
        val caret = state.caret
        if (take != null && clock != null && score != null && caret != null) {
            val settings = quantizationSettings(score, caret.onset.measure)
            PerformanceQuantizer.quantize(take, clock, settings)?.let { quantized ->
                buildCaptureInsertion(score, caret, quantized, settings)?.let(session::applyCaptureInput)
            }
        }
        recorder.reset()
        performanceClock = null
        recordingScore = null
        liveMidiByInputKey.clear()
        onLiveAllNotesOff()
        state.deactivate()
    }

    private fun armRealtime() {
        val snapshot = session.runtimeScore ?: return
        val caret = state.caret ?: return
        val now = System.nanoTime()
        recorder = RealtimeTakeRecorder()
        recordingScore = snapshot
        performanceClock = PerformanceClock(
            score = snapshot,
            anchorTime = caret.onset,
            anchorNanos = now,
            inputLatencyNanos = com.mecon.desktop.AppSettings.noteInputLatencyMs * 1_000_000L,
        )
        metronomeJob?.cancel()
        metronomeJob = scope.launch { runMetronome(snapshot, performanceClock!!) }
    }

    private fun realtimeNoteOn(key: InputKeyId, pitch: Pitch, atNanos: Long, velocity: Int) {
        if (performanceClock == null || recordingScore == null) armRealtime()
        if (recorder.noteOn(key, pitch, atNanos, velocity)) {
            state.beginRecording()
        }
    }

    private fun sendMidiThru(
        runtime: RuntimeScore,
        event: PerformanceInputEvent.NoteOn,
        pitch: Pitch,
    ) {
        if (!com.mecon.desktop.AppSettings.midiInputThru) return
        val sounding = pitch.midiNumber + currentStaffTransposition(runtime)
        if (sounding in 0..127) {
            liveMidiByInputKey[InputKeyId(event.sourceId, event.key)] = sounding
            onLiveNoteOn(sounding, event.velocity)
        }
    }

    private suspend fun runMetronome(score: RuntimeScore, clock: PerformanceClock) {
        var target = metronomeBoundaryAtOrAfter(score, clock.anchorPosition)
        while (currentCoroutineContext().isActive && state.entryMode == NoteInputEntryMode.REALTIME) {
            val targetNanos = clock.nanosAt(PerformanceClock.timeCodeAt(score, target.first))
            while (currentCoroutineContext().isActive) {
                val remainingMs = (targetNanos - System.nanoTime()) / 1_000_000L
                if (remainingMs <= 0L) break
                delay(remainingMs.coerceAtMost(50L))
            }
            if (!currentCoroutineContext().isActive) break
            onMetronomeTick(target.second)
            val next = nextMetronomeBoundary(score, target.first)
            if (next.first <= target.first) {
                stopAndExit(commit = state.phase == NoteInputPhase.RECORDING)
                break
            }
            target = next
        }
    }

    private fun metronomeBoundaryAtOrAfter(
        score: RuntimeScore,
        position: Fraction,
    ): Pair<Fraction, Boolean> {
        val time = PerformanceClock.timeCodeAt(score, position)
        val measureStart = PerformanceClock.absolute(score, TimeCode.of(time.measure, Fraction.ZERO))
        val signature = score.getTimeSignatureAt(time.measure)
        val local = time.beat ?: Fraction.ZERO
        var boundary = Fraction.ZERO
        for ((index, group) in signature.beatGrouping().withIndex()) {
            if (boundary >= local) return (measureStart + boundary) to (index == 0)
            boundary += Fraction(group, signature.denominator)
        }
        return (measureStart + signature.measureDuration()) to true
    }

    private fun nextMetronomeBoundary(
        score: RuntimeScore,
        position: Fraction,
    ): Pair<Fraction, Boolean> {
        val time = PerformanceClock.timeCodeAt(score, position)
        val measureStart = PerformanceClock.absolute(score, TimeCode.of(time.measure, Fraction.ZERO))
        val signature = score.getTimeSignatureAt(time.measure)
        val local = position - measureStart
        var boundary = Fraction.ZERO
        for ((index, group) in signature.beatGrouping().withIndex()) {
            boundary += Fraction(group, signature.denominator)
            if (boundary > local) {
                val atBarline = boundary == signature.measureDuration()
                return (measureStart + boundary) to (if (atBarline) true else index + 1 == 0)
            }
        }
        return (measureStart + signature.measureDuration()) to true
    }

    private fun quantizationSettings(score: RuntimeScore, measure: Int): QuantizationSettings {
        val signature = score.getTimeSignatureAt(measure)
        val firstGroup = signature.beatGrouping().firstOrNull() ?: 1
        return QuantizationSettings(
            straightUnit = Fraction(1, state.quantizeDenominator),
            allowedTuplets = state.allowedTuplets,
            beatUnit = Fraction(firstGroup, signature.denominator),
            compoundMeter = signature.isCompound,
        )
    }

    private fun buildCaptureInsertion(
        score: RuntimeScore,
        caret: com.mecon.input.NoteEntryCaret,
        take: QuantizedPerformanceTake,
        settings: QuantizationSettings,
    ): NoteEditEngine.CaptureInsertion? {
        val cells = ArrayList<NoteEditEngine.ChordInsertion>()
        val createdTupletGroups = LinkedHashSet<Pair<Fraction, Int>>()
        var captureStart = take.start
        var captureEnd = take.end

        fun addCell(
            start: Fraction,
            duration: Duration,
            pitches: List<Pitch>,
            tieOutMidi: Set<Int>,
            tupletCount: Int? = null,
        ) {
            cells += NoteEditEngine.ChordInsertion(
                voiceTrackId = caret.voiceTrackId,
                staffTrackId = caret.staffTrackId,
                voiceNumber = caret.voiceNumber,
                start = PerformanceClock.timeCodeAt(score, start),
                duration = duration,
                pitches = pitches,
                isRest = pitches.isEmpty(),
                tieOutMidi = tieOutMidi,
                tupletCount = tupletCount,
            )
        }

        for (segment in take.segments) {
            val tupletCount = segment.grid.tupletCount
            val tupletDuration = tupletCount?.let {
                durationForTupletCell(settings.beatUnit / it, it)
            }
            if (tupletCount != null && tupletDuration != null) {
                val unit = tupletDuration.toFraction()
                var cursor = segment.start
                while (cursor < segment.end) {
                    val groupIndex = (cursor / settings.beatUnit).let { it.numerator / it.denominator }
                    val groupStart = settings.beatUnit * groupIndex
                    val groupKey = groupStart to tupletCount
                    if (createdTupletGroups.add(groupKey)) {
                        val groupDuration = durationForFraction(settings.beatUnit) ?: return null
                        addCell(groupStart, groupDuration, emptyList(), emptySet(), tupletCount)
                        captureStart = minOf(captureStart, groupStart)
                        captureEnd = maxOf(captureEnd, groupStart + settings.beatUnit)
                    }
                    val next = minOf(cursor + unit, segment.end)
                    val continuesInsideSegment = next < segment.end
                    addCell(
                        cursor,
                        tupletDuration,
                        segment.pitches,
                        if (continuesInsideSegment) {
                            segment.pitches.mapTo(linkedSetOf()) { it.midiNumber }
                        } else {
                            segment.tieOutMidi
                        },
                    )
                    cursor = next
                }
            } else {
                var cursor = segment.start
                val durations = decomposeStraight(segment.length)
                durations.forEachIndexed { index, duration ->
                    val continuesInsideSegment = index < durations.lastIndex
                    addCell(
                        cursor,
                        duration,
                        segment.pitches,
                        if (continuesInsideSegment) {
                            segment.pitches.mapTo(linkedSetOf()) { it.midiNumber }
                        } else {
                            segment.tieOutMidi
                        },
                    )
                    cursor += duration.toFraction()
                }
            }
        }
        if (cells.isEmpty()) return null
        return NoteEditEngine.CaptureInsertion(
            voiceTrackId = caret.voiceTrackId,
            staffTrackId = caret.staffTrackId,
            voiceNumber = caret.voiceNumber,
            start = PerformanceClock.timeCodeAt(score, captureStart),
            end = PerformanceClock.timeCodeAt(score, captureEnd),
            cells = cells,
            replace = !com.mecon.desktop.AppSettings.noteInputOverdub,
        )
    }

    private fun durationForTupletCell(length: Fraction, count: Int): Duration? {
        val tuplet = when (count) {
            2 -> Tuplet.DUPLET
            3 -> Tuplet.TRIPLET
            6 -> Tuplet.SEXTUPLET
            else -> return null
        }
        val baseLength = length / tuplet.ratio
        val base = DurationBase.entries.firstOrNull { it.toFraction() == baseLength } ?: return null
        return Duration(base, tuplet = tuplet)
    }

    private fun durationForFraction(length: Fraction): Duration? {
        for (base in DurationBase.entries) {
            for (dots in 0..3) {
                val candidate = Duration(base, dots)
                if (candidate.toFraction() == length) return candidate
            }
        }
        return null
    }

    private fun decomposeStraight(length: Fraction): List<Duration> {
        val out = ArrayList<Duration>()
        var remaining = length
        while (remaining.isPositive) {
            val next = DurationBase.entries
                .asSequence()
                .flatMap { base -> (3 downTo 0).asSequence().map { Duration(base, it) } }
                .filter { it.toFraction() <= remaining }
                .maxByOrNull { it.toFraction() }
                ?: break
            out += next
            remaining -= next.toFraction()
        }
        return out
    }

    private fun scheduleFlush() {
        flushJob?.cancel()
        flushJob = scope.launch {
            delay(AppSettingsBridge.chordWindowMillis())
            flushPending()
        }
    }

    private fun submitCollected(runtime: RuntimeScore, chord: CollectedChord) {
        val pitches = ArrayList<com.mecon.api.primitive.Pitch>()
        chord.notes.forEach { note ->
            val pitch = if (note.sourceId == KEYBOARD_SOURCE) {
                ComputerNoteKey.entries.getOrNull(note.key)?.let { state.resolvePitch(runtime, it) }
            } else {
                resolveMidiPitch(runtime, note.key, pitches)
            }
            if (pitch != null) {
                pitches += pitch
            }
        }
        if (pitches.isNotEmpty()) submit(runtime, pitches, isRest = false)
    }

    private fun resolveMidiPitch(
        runtime: RuntimeScore,
        inputMidi: Int,
        pitchesAtOnset: List<com.mecon.api.primitive.Pitch>,
    ): com.mecon.api.primitive.Pitch? {
        val caret = state.caret ?: return null
        val voice = runtime.voiceTracks[caret.voiceTrackId]
        val previousEvents = voice?.events?.toList().orEmpty()
            .filter { it.onset.measure == caret.onset.measure && it.onset < caret.onset }
            .sortedBy { it.onset }
        val key = runtime.getKeySignatureAt(caret.onset.measure)
        return MidiPitchResolver.resolveWrittenPitch(
            inputMidi = inputMidi,
            keySignature = key,
            staffTranspositionSemitones = currentStaffTransposition(runtime),
            settings = MidiPitchSettings(
                mode = state.pitchMode,
                semantics = if (com.mecon.desktop.AppSettings.midiInputSendsSoundingPitch) {
                    MidiPitchSemantics.SOUNDING
                } else {
                    MidiPitchSemantics.WRITTEN
                },
                deviceCenterMidi = com.mecon.desktop.AppSettings.midiInputCenterNote,
                centerOctave = state.centerOctave,
                registerOffset = state.registerOffset,
            ),
            context = PitchSpellingContext(
                keySignature = key,
                previousPitch = previousEvents.lastOrNull()?.pitches?.lastOrNull(),
                pitchesAtOnset = pitchesAtOnset,
                previousPitchesInMeasure = previousEvents.flatMap { it.pitches },
            ),
        )
    }

    private fun currentStaffTransposition(runtime: RuntimeScore): Int {
        val staffId = state.caret?.staffTrackId ?: return 0
        return runtime.staffTracks[staffId]?.transposition?.interval?.semitones ?: 0
    }

    private fun submit(runtime: RuntimeScore, pitches: List<com.mecon.api.primitive.Pitch>, isRest: Boolean) {
        val caret = state.caret ?: return
        val voice = runtime.voiceTracks[caret.voiceTrackId]
            ?: runtime.staffTracks[caret.staffTrackId]?.voiceTracks
                ?.firstOrNull { it.voiceNumber == noteTool.activeVoiceNumber }
        val existing = voice?.eventsAt(caret.onset)?.firstOrNull { !it.isRest }
        val requestedDuration = noteTool.duration
        val insertionDuration = existing?.duration ?: requestedDuration
        val advanceDuration = noteTool.tupletCount?.let { count ->
            NoteEditEngine.tupletSpecFor(requestedDuration.toFraction(), count)?.let { spec ->
                com.mecon.api.primitive.Duration(spec.beatUnit, tuplet = spec.tuplet)
            }
        } ?: insertionDuration

        session.applyChordInput(
            NoteEditEngine.ChordInsertion(
                voiceTrackId = caret.voiceTrackId,
                staffTrackId = caret.staffTrackId,
                voiceNumber = noteTool.activeVoiceNumber,
                start = caret.onset,
                duration = insertionDuration,
                pitches = pitches,
                isRest = isRest,
                trailingTie = noteTool.tieMode,
                tupletCount = noteTool.tupletCount,
                beaming = noteTool.insertionBeaming,
                articulations = noteTool.articulations.toList(),
                grace = if (noteTool.noteEntryKind == NoteEntryKind.GRACE) {
                    NoteEditEngine.GraceInsertion(
                        totalDuration = noteTool.graceTotalDuration,
                        stealFrom = noteTool.graceTimeSource,
                        noteType = noteTool.graceNoteType,
                    )
                } else null,
            ),
        ) { inserted, committed ->
            (inserted as? VoiceEventSection)?.let {
                state.updateVoiceFromInserted(committed, it.event.id)
            }
            onInserted(inserted, committed)
        }
        state.advanceOptimistically(runtime, advanceDuration)

        if (noteTool.tupletCount != null) {
            noteTool.tupletCount = null
        }
        val beam = noteTool.insertionBeaming
        if (beam == BeamingInfo.start() || beam == BeamingInfo.end()) {
            noteTool.insertionBeaming = null
        }
    }

    private object AppSettingsBridge {
        fun chordWindowMillis(): Long = com.mecon.desktop.AppSettings.noteInputChordWindowMs
        fun chordWindowNanos(): Long = chordWindowMillis() * 1_000_000L
    }

    companion object {
        private const val KEYBOARD_SOURCE = "computer-keyboard"
    }
}

internal fun Key.toComputerNoteKey(): ComputerNoteKey? = when (this) {
    Key.Q -> ComputerNoteKey.Q
    Key.W -> ComputerNoteKey.W
    Key.E -> ComputerNoteKey.E
    Key.R -> ComputerNoteKey.R
    Key.T -> ComputerNoteKey.T
    Key.Y -> ComputerNoteKey.Y
    Key.U -> ComputerNoteKey.U
    Key.I -> ComputerNoteKey.I
    Key.O -> ComputerNoteKey.O
    Key.P -> ComputerNoteKey.P
    Key.LeftBracket -> ComputerNoteKey.LEFT_BRACKET
    Key.A -> ComputerNoteKey.A
    Key.S -> ComputerNoteKey.S
    Key.D -> ComputerNoteKey.D
    Key.F -> ComputerNoteKey.F
    Key.G -> ComputerNoteKey.G
    Key.H -> ComputerNoteKey.H
    Key.J -> ComputerNoteKey.J
    Key.K -> ComputerNoteKey.K
    Key.L -> ComputerNoteKey.L
    Key.Semicolon -> ComputerNoteKey.SEMICOLON
    Key.Apostrophe -> ComputerNoteKey.APOSTROPHE
    Key.Z -> ComputerNoteKey.Z
    Key.X -> ComputerNoteKey.X
    Key.C -> ComputerNoteKey.C
    Key.V -> ComputerNoteKey.V
    Key.B -> ComputerNoteKey.B
    Key.N -> ComputerNoteKey.N
    Key.M -> ComputerNoteKey.M
    Key.Comma -> ComputerNoteKey.COMMA
    Key.Period -> ComputerNoteKey.PERIOD
    Key.Slash -> ComputerNoteKey.SLASH
    else -> null
}
