package com.mecon.theory.chorale

import com.mecon.api.primitive.Fraction
import com.mecon.api.primitive.Pitch
import com.mecon.api.primitive.TimeCode
import com.mecon.api.primitive.TimeSignature
import com.mecon.theory.Key
import com.mecon.theory.MeterPlan
import com.mecon.theory.NonChordToneClassifier
import com.mecon.theory.NonChordToneContext
import com.mecon.theory.Sonority
import com.mecon.theory.VoiceBoundary
import com.mecon.theory.VoiceRange
import kotlin.math.abs

/** Everything one voice needs to know about one harmonic span. */
internal data class ChoraleSpanContext(
    val slot: Int,
    val onset: TimeCode,
    val duration: Fraction,
    val meterPlan: MeterPlan,
    val key: Key,
    val range: VoiceRange,
    val boundary: VoiceBoundary?,
    val current: Pitch,
    val previous: Pitch?,
    val next: Pitch?,
    val chord: Sonority,
    val previousChord: Sonority?,
    val nextChord: Sonority?,
    /** Set when the user asked this voice to suspend into this slot. */
    val suspensionRequired: Boolean,
)

/** One voice's realization of one span, already classified. */
internal data class ChoraleSpanFill(
    val patternId: String,
    val notes: List<ChoraleNote>,
) {
    val figurationCount: Int get() = notes.count { it.nonChordTone != null }

    /**
     * What the listener hears, not which pattern produced it: two patterns that merge to the same
     * notes are the same music and must group together.
     */
    val signature: String get() = notes.joinToString(",") { note ->
        note.nonChordTone?.abbreviation ?: "-"
    }
}

/**
 * Enumerates how one voice can fill one harmonic span.
 *
 * There is deliberately no per-figure generator. One filling rule plus
 * [NonChordToneClassifier] verification is what produces passing tones, neighbours, anticipations,
 * suspensions and chordal skips — so nothing can be generated that the checker cannot name
 * (`docs/theory/chorale-harmonization.md` §3.2).
 */
internal object ChoraleSpanFilling {

    fun fillings(
        context: ChoraleSpanContext,
        patterns: List<ChoraleRhythmPattern>,
        limit: Int,
    ): List<ChoraleSpanFill> = patterns.flatMap { pattern ->
        pitchSequences(context, pattern.size, limit).mapNotNull { sequence ->
            classify(context, pattern, sequence)?.let { ChoraleSpanFill(pattern.id, it) }
        }
    }
        // Deduplicate on the notes actually produced, not on the pattern that produced them:
        // two equal halves merge into one note, which is the same music as a sustained span.
        .distinctBy { fill -> fill.notes.joinToString(",") { note -> note.identity } }
        .sortedBy { fill -> fill.notes.joinToString(",") { note -> note.identity } }
        .take(limit)

    /** Candidate pitch sequences of length [size], one entry per rhythmic division. */
    private fun pitchSequences(
        context: ChoraleSpanContext,
        size: Int,
        limit: Int,
    ): List<List<Pitch>> {
        if (size == 1) {
            // A single attack cannot resolve anything, so it can only state the skeleton pitch.
            return if (context.suspensionRequired) emptyList() else listOf(listOf(context.current))
        }
        val start = if (context.suspensionRequired) {
            context.previous ?: return emptyList()
        } else context.current
        val results = mutableListOf<List<Pitch>>()
        fun extend(prefix: List<Pitch>) {
            if (results.size >= limit * PREFILTER_FACTOR) return
            if (prefix.size == size) {
                if (accepts(context, prefix)) results += prefix
                return
            }
            options(context, prefix.last()).forEach { candidate ->
                extend(prefix + candidate)
            }
        }
        extend(listOf(start))
        return results
    }

    private fun options(context: ChoraleSpanContext, from: Pitch): List<Pitch> = buildList {
        add(from)
        add(context.current)
        context.next?.let(::add)
        chordTonesNear(context).forEach(::add)
        listOf(-1, 1).forEach { direction ->
            diatonicNeighbor(from, direction, context.key)?.let(::add)
        }
    }.distinct()
        .filter { it in context.range && abs(it.midiNumber - from.midiNumber) <= MAX_STEP_SEMITONES }
        .sortedBy { it.midiNumber }

    /** Chord tones of this span within a fifth of the skeleton pitch, so leaps stay singable. */
    private fun chordTonesNear(context: ChoraleSpanContext): List<Pitch> {
        val pitchClasses = context.chord.pitchClasses.mapTo(hashSetOf()) { it.value }
        return (context.current.midiNumber - PERFECT_FIFTH..context.current.midiNumber + PERFECT_FIFTH)
            .filter { it.mod(12) in pitchClasses }
            .map(Pitch::fromMidi)
            .filter { it in context.range }
    }

    private fun accepts(context: ChoraleSpanContext, sequence: List<Pitch>): Boolean {
        val chordPitchClasses = context.chord.pitchClasses.mapTo(hashSetOf()) { it.value }
        fun isChordTone(pitch: Pitch) = pitch.pitchClass.value in chordPitchClasses

        if (context.suspensionRequired) {
            // The held tone must be foreign to the new harmony and must give way inside the span.
            val previous = context.previous ?: return false
            val arrival = sequence.indexOfFirst { it != previous }
            if (arrival <= 0) return false
            if (sequence.take(arrival).any { it != previous }) return false
            if (sequence[arrival] != context.current) return false
            if (isChordTone(previous)) return false
        } else {
            if (sequence.first() != context.current) return false
        }
        // The skeleton's vertical is what sounds first: no chord tone may precede the structural
        // pitch, otherwise stage one's spacing and parallel checks would describe a chord that is
        // never heard.
        val firstChordTone = sequence.firstOrNull(::isChordTone) ?: return false
        if (firstChordTone != context.current) return false

        val last = sequence.last()
        val next = context.next
        if (next != null && !isChordTone(last)) {
            // A dissonance must hand over by step, never by leap into the next chord.
            if (abs(last.diatonicSteps - next.diatonicSteps) > 1 && last != next) return false
        }
        return true
    }

    /** Merges repeated pitches into single notes and names every non-chord tone. */
    private fun classify(
        context: ChoraleSpanContext,
        pattern: ChoraleRhythmPattern,
        sequence: List<Pitch>,
    ): List<ChoraleNote>? {
        val chordPitchClasses = context.chord.pitchClasses.mapTo(hashSetOf()) { it.value }
        val merged = mutableListOf<Triple<Pitch, Fraction, Fraction>>() // pitch, offset, duration
        var offset = Fraction.ZERO
        sequence.forEachIndexed { index, pitch ->
            val slice = pattern.divisions[index] * context.duration
            val last = merged.lastOrNull()
            if (last != null && last.first == pitch) {
                merged[merged.lastIndex] = last.copy(third = last.third + slice)
            } else {
                merged += Triple(pitch, offset, slice)
            }
            offset += slice
        }
        return merged.mapIndexed { index, (pitch, noteOffset, duration) ->
            val onset = advance(context.onset, noteOffset, context.meterPlan)
            val role = if (pitch.pitchClass.value in chordPitchClasses) null else {
                val classification = NonChordToneClassifier.classify(
                    NonChordToneContext(
                        previousPitch = merged.getOrNull(index - 1)?.first ?: context.previous,
                        pitch = pitch,
                        nextPitch = merged.getOrNull(index + 1)?.first ?: context.next,
                        previousChord = context.previousChord,
                        chord = context.chord,
                        nextChord = context.nextChord,
                        beatWeight = context.meterPlan.beatWeightAt(onset),
                        voiceBoundary = context.boundary,
                        isDiatonic = context.key.scale.contains(pitch.pitchClass),
                    )
                ) ?: return null
                classification.primary
            }
            ChoraleNote(
                onset = onset,
                duration = duration.simplified(),
                pitch = pitch,
                slot = context.slot,
                nonChordTone = role,
            )
        }
    }

    private const val PERFECT_FIFTH = 7
    private const val MAX_STEP_SEMITONES = 7
    private const val PREFILTER_FACTOR = 8
}

/**
 * Diatonic step neighbour in [key], preferring the smallest accidental.
 *
 * Minor keys use their natural form here, so a v1 neighbour note never invents a raised leading
 * tone; chord tones still come from the chord target, which does carry the raised degree.
 */
internal fun diatonicNeighbor(pitch: Pitch, direction: Int, key: Key): Pitch? {
    val steps = pitch.diatonicSteps + direction
    return listOf(0, -1, 1, -2, 2)
        .map { offset -> Pitch(steps, offset) }
        .firstOrNull { key.scale.contains(it.pitchClass) }
}

/** Meter-aware advance: [TimeCode.plus] alone never rolls over a bar line. */
internal fun advance(time: TimeCode, delta: Fraction, meterPlan: MeterPlan): TimeCode {
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

