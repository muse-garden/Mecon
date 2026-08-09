package com.mecon.api.computed

import com.mecon.api.primitive.*
import com.mecon.api.storage.tracks.BracketStyle
import com.mecon.api.storage.tracks.Clef
import com.mecon.api.storage.NavigationMark
import com.mecon.api.storage.NavigationMarkOffset
import kotlinx.serialization.Serializable

/**
 * Base interface for all notation events (non-note elements) in the computed score.
 *
 * These events represent barlines, clefs, key signatures, time signatures, etc.
 * that need to be laid out alongside voice events.
 */
sealed interface ComputedNotationEvent {
    /** Time code for this event */
    val time: TimeCode
}

/**
 * Computed barline event.
 *
 * Barlines are generated at measure boundaries and at the start/end of the score.
 */
@Serializable
data class ComputedBarline(
    override val time: TimeCode,
    /** Type of barline */
    val type: BarlineType,
    /** Measure number after this barline (0 for start of piece) */
    val measureNumber: Int,
    /**
     * Contiguous staff-index ranges that this barline connects (inclusive).
     * Each range produces one vertical barline segment spanning the listed staves.
     *
     * Empty means the renderer falls back to per-staff barlines.
     * Single full-system range means a single line spans all staves.
     * Multiple disjoint ranges mean the barline is broken between groups.
     */
    val connectedStaffRanges: List<StaffIndexRange> = emptyList()
) : ComputedNotationEvent

/** One contiguous first/second-ending bracket decided by the computed layer. */
@Serializable
data class ComputedVoltaEnding(
    val startMeasure: Int,
    val endMeasure: Int,
    val numbers: Set<Int>,
)

/** A score-navigation sign or jump instruction at a logical boundary. */
@Serializable
data class ComputedNavigationMark(
    override val time: TimeCode,
    val boundaryMeasure: Int,
    val mark: NavigationMark,
    val offset: NavigationMarkOffset = NavigationMarkOffset(),
) : ComputedNotationEvent

/**
 * Inclusive staff index range, used by [ComputedBarline.connectedStaffRanges] and
 * by header geometry to describe which staves an element spans.
 */
@Serializable
data class StaffIndexRange(val first: Int, val last: Int) {
    init { require(first <= last) { "StaffIndexRange first ($first) > last ($last)" } }
    operator fun contains(idx: Int): Boolean = idx in first..last
    val size: Int get() = last - first + 1
}

/**
 * Computed clef event.
 *
 * Clefs are generated at the start of each staff and when clef changes occur.
 */
@Serializable
data class ComputedClef(
    override val time: TimeCode,
    /** Staff track ID this clef belongs to */
    val staffTrackId: TrackId,
    /** Clef type */
    val clef: Clef,
    /** Whether this is the initial clef (at start of system) */
    val isInitial: Boolean
) : ComputedNotationEvent

/**
 * A natural sign that cancels a previous key signature accidental.
 *
 * @param noteName The note whose accidental is being cancelled
 * @param fromSharpKey true if the cancelled accidental was a sharp, false if flat.
 *   Determines staff-line placement (sharp and flat positions differ).
 */
@Serializable
data class CancellationNatural(
    val noteName: NoteName,
    val fromSharpKey: Boolean
)

/**
 * Computed key signature event.
 *
 * Key signatures are generated at the start of each staff and when key changes occur.
 * For mid-score key changes, [cancellationNaturals] lists the natural signs that must
 * appear before the new accidentals to cancel the previous key's sharps/flats.
 */
@Serializable
data class ComputedKeySignature(
    override val time: TimeCode,
    /** Staff track ID this key signature belongs to */
    val staffTrackId: TrackId,
    /** Key signature (number of sharps/flats) */
    val keySignature: KeySignature,
    /** Whether this is the initial key signature (at start of system) */
    val isInitial: Boolean,
    /** Naturals to render before new accidentals when key changes */
    val cancellationNaturals: List<CancellationNatural> = emptyList()
) : ComputedNotationEvent

/**
 * Computed time signature event.
 *
 * Time signatures are generated at the start of the score and when time signature changes occur.
 * They typically apply to all staves in a system.
 */
@Serializable
data class ComputedTimeSignature(
    override val time: TimeCode,
    /** Staff track ID this time signature belongs to (or first staff if system-wide) */
    val staffTrackId: TrackId,
    /** Time signature */
    val timeSignature: TimeSignature,
    /** Whether this is the initial time signature (at start of system) */
    val isInitial: Boolean
) : ComputedNotationEvent
