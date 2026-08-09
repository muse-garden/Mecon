package com.mecon.theory

import com.mecon.api.primitive.IntervalQuality
import com.mecon.api.primitive.Pitch
import kotlin.math.abs

data class MelodyPeak<T>(
    val pitch: Pitch,
    val items: List<T>,
) {
    val isUnique: Boolean get() = items.size == 1
    val first: T get() = items.first()
}

enum class MelodyDirection {
    ASCENDING,
    DESCENDING,
    REPEATED,
}

data class MelodyMotion<T>(
    val index: Int,
    val from: T,
    val to: T,
    val fromPitch: Pitch,
    val toPitch: Pitch,
    val interval: SpelledInterval,
) {
    val direction: MelodyDirection
        get() = when {
            toPitch.midiNumber > fromPitch.midiNumber -> MelodyDirection.ASCENDING
            toPitch.midiNumber < fromPitch.midiNumber -> MelodyDirection.DESCENDING
            else -> MelodyDirection.REPEATED
        }

    val absoluteSemitones: Int get() = abs(interval.semitones)
    val isStep: Boolean get() = direction != MelodyDirection.REPEATED &&
        interval.simpleNumber == 2 &&
        absoluteSemitones in 1..2
    val isLeap: Boolean get() = direction != MelodyDirection.REPEATED && !isStep
    val isSmallerLeap: Boolean get() = isLeap &&
        interval.simpleNumber in 3..4 &&
        absoluteSemitones in 2..5
    val isAugmented: Boolean get() = interval.quality == IntervalQuality.AUGMENTED ||
        interval.quality == IntervalQuality.DOUBLY_AUGMENTED
    val isDiminished: Boolean get() = interval.quality == IntervalQuality.DIMINISHED ||
        interval.quality == IntervalQuality.DOUBLY_DIMINISHED
    val isSeventh: Boolean get() = interval.simpleNumber == 7
    val isGreaterThanOctave: Boolean get() = absoluteSemitones > 12

    fun isOppositeDirectionTo(other: MelodyMotion<T>): Boolean =
        direction == MelodyDirection.ASCENDING && other.direction == MelodyDirection.DESCENDING ||
            direction == MelodyDirection.DESCENDING && other.direction == MelodyDirection.ASCENDING
}

object MelodyAnalysis {
    fun <T> highestItems(
        items: List<T>,
        pitchOf: (T) -> Pitch,
    ): List<T> {
        val highestMidi = items.maxOfOrNull { pitchOf(it).midiNumber } ?: return emptyList()
        return items.filter { pitchOf(it).midiNumber == highestMidi }
    }

    fun <T> peak(
        items: List<T>,
        pitchOf: (T) -> Pitch,
    ): MelodyPeak<T>? {
        val highest = highestItems(items, pitchOf)
        return highest.firstOrNull()?.let { MelodyPeak(pitchOf(it), highest) }
    }

    fun <T> motions(
        items: List<T>,
        pitchOf: (T) -> Pitch,
    ): List<MelodyMotion<T>> =
        items.zipWithNext().mapIndexed { index, (from, to) ->
            val fromPitch = pitchOf(from)
            val toPitch = pitchOf(to)
            MelodyMotion(
                index = index,
                from = from,
                to = to,
                fromPitch = fromPitch,
                toPitch = toPitch,
                interval = SpelledInterval.between(fromPitch, toPitch),
            )
        }

    fun <T> stepwiseRatio(
        items: List<T>,
        pitchOf: (T) -> Pitch,
    ): Double {
        val motions = motions(items, pitchOf).filter { it.direction != MelodyDirection.REPEATED }
        if (motions.isEmpty()) return 1.0
        return motions.count { it.isStep }.toDouble() / motions.size.toDouble()
    }

    fun <T> directionChanges(
        items: List<T>,
        pitchOf: (T) -> Pitch,
    ): Int {
        val directions = motions(items, pitchOf)
            .map { it.direction }
            .filter { it != MelodyDirection.REPEATED }
        return directions.zipWithNext().count { (previous, next) -> previous != next }
    }

    fun scaleDegree(pitch: Pitch, key: Key): Int {
        val index = key.scale.pitchClasses.indexOf(pitch.pitchClass)
        return if (index >= 0) index + 1 else -1
    }

    fun <T> scaleDegrees(
        items: List<T>,
        key: Key,
        pitchOf: (T) -> Pitch,
    ): List<Int> =
        items.map { scaleDegree(pitchOf(it), key) }

    fun <T> hasDescendingScaleFragment(
        items: List<T>,
        key: Key,
        startIndex: Int,
        degrees: List<Int> = listOf(1, 7, 6, 5),
        pitchOf: (T) -> Pitch,
    ): Boolean {
        if (startIndex < 0 || startIndex + degrees.size > items.size) return false
        val fragment = items.subList(startIndex, startIndex + degrees.size)
        return scaleDegrees(fragment, key, pitchOf) == degrees &&
            motions(fragment, pitchOf).all { it.direction == MelodyDirection.DESCENDING && it.isStep }
    }

    fun <T> outlinesTriad(
        items: List<T>,
        pitchOf: (T) -> Pitch,
    ): Boolean {
        val pitchClasses = items.map { pitchOf(it).pitchClass.value }.toSet()
        if (pitchClasses.size != 3) return false
        val triadQualities = setOf(
            ChordQuality.MAJOR,
            ChordQuality.MINOR,
            ChordQuality.DIMINISHED,
            ChordQuality.AUGMENTED,
        )
        return ChordRecognizer.recognizePitchClasses(pitchClasses).any { candidate ->
            candidate.complete &&
                candidate.chord.quality in triadQualities &&
                candidate.chord.pitchClasses.map { it.value }.toSet() == pitchClasses
        }
    }
}
