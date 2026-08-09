package com.mecon.core.musicxml.export

import com.mecon.core.musicxml.import.DurationConverter
import com.mecon.api.primitive.*
import com.mecon.api.storage.tracks.Clef

/**
 * Stateful context for MusicXML export.
 *
 * Tracks current state during export such as:
 * - Written key/time signatures
 * - Written clefs
 * - Position tracking
 */
class ExportContext {

    /** Divisions per quarter note for export */
    val divisions: Int = DurationConverter.EXPORT_DIVISIONS

    /** Last written key signature per staff */
    private val writtenKeys = mutableMapOf<Int, KeySignature>()

    /** Last written time signature */
    private var writtenTimeSignature: TimeSignature? = null

    /** Last written clef per staff */
    private val writtenClefs = mutableMapOf<Int, Clef>()

    /** Whether attributes have been written for current measure */
    var attributesWritten = false
        private set

    /**
     * Check if key signature needs to be written.
     */
    fun needsKeySignature(staff: Int, key: KeySignature): Boolean {
        return writtenKeys[staff] != key
    }

    /**
     * Mark key signature as written.
     */
    fun markKeySignatureWritten(staff: Int, key: KeySignature) {
        writtenKeys[staff] = key
    }

    /**
     * Check if time signature needs to be written.
     */
    fun needsTimeSignature(time: TimeSignature): Boolean {
        return writtenTimeSignature != time
    }

    /**
     * Mark time signature as written.
     */
    fun markTimeSignatureWritten(time: TimeSignature) {
        writtenTimeSignature = time
    }

    /**
     * Check if clef needs to be written.
     */
    fun needsClef(staff: Int, clef: Clef): Boolean {
        return writtenClefs[staff] != clef
    }

    /**
     * Mark clef as written.
     */
    fun markClefWritten(staff: Int, clef: Clef) {
        writtenClefs[staff] = clef
    }

    /**
     * Mark attributes as written for current measure.
     */
    fun markAttributesWritten() {
        attributesWritten = true
    }

    /**
     * Reset for new measure.
     */
    fun resetForNewMeasure() {
        attributesWritten = false
    }

    /**
     * Convert KeySignature to fifths value.
     */
    fun keyToFifths(key: KeySignature): Int {
        return key.fifths
    }

    /**
     * Convert Clef to MusicXML sign and line.
     */
    fun clefToSignAndLine(clef: Clef): Pair<String, Int> {
        return when (clef) {
            Clef.TREBLE -> "G" to 2
            Clef.BASS -> "F" to 4
            Clef.ALTO -> "C" to 3
            Clef.TENOR -> "C" to 4
            Clef.PERCUSSION -> "percussion" to 0
        }
    }

    /**
     * Convert Duration to divisions.
     */
    fun durationToDivisions(duration: Duration): Int {
        return DurationConverter.toMusicXml(duration, divisions).duration
    }

    /**
     * Convert ticks to divisions.
     * Mecon uses 4096 ticks per whole note.
     */
    fun ticksToDivisions(ticks: Int): Int {
        // 4096 ticks = whole note = divisions * 4
        // So: ticks / 4096 = divisions * 4 / divisions / 4
        // divisions per tick = divisions / 1024 (since quarter = 1024 ticks = divisions)
        return (ticks * divisions) / 1024
    }
}
