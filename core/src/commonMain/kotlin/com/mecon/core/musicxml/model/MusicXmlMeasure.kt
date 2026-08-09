package com.mecon.core.musicxml.model

/**
 * A measure containing notes and other music elements.
 */
data class MusicXmlMeasure(
    /** Measure number (from number attribute) */
    val number: Int,
    /** Optional measure width */
    val width: Float? = null,
    /** Whether this is an implicit measure (e.g., pickup) */
    val implicit: Boolean = false,
    /** Attributes at the start of this measure */
    val attributes: MusicXmlAttributes? = null,
    /** Direction elements (dynamics, tempo, etc.) */
    val directions: List<MusicXmlDirection> = emptyList(),
    /** Notes and rests */
    val notes: List<MusicXmlNote> = emptyList(),
    /** Forward/backup elements for voice navigation */
    val timeMovements: List<MusicXmlTimeMovement> = emptyList(),
    /**
     * Notes, directions, and backup/forward movements in original document order.
     *
     * The parser fills this so importers can replay a single measure "cursor"
     * (advancing on notes/forward, rewinding on backup) and attach directions to
     * the right beat. [notes] / [directions] / [timeMovements] are convenience
     * projections of the same data.
     */
    val elements: List<MusicXmlMeasureElement> = emptyList(),
    /** Barline info */
    val barline: MusicXmlBarline? = null,
    /** Print element for layout */
    val print: MusicXmlPrint? = null
) {
    /**
     * Get all elements in document order for proper time tracking.
     * Falls back to [notes] when [elements] was not populated (e.g. exporter-built).
     */
    fun getOrderedElements(): List<MusicXmlMeasureElement> =
        elements.ifEmpty { notes }
}

/**
 * Marker interface for elements that appear in a measure.
 */
sealed interface MusicXmlMeasureElement

/**
 * Forward/backup elements for voice time positioning.
 */
sealed class MusicXmlTimeMovement : MusicXmlMeasureElement {
    abstract val duration: Int

    data class Forward(override val duration: Int) : MusicXmlTimeMovement()
    data class Backup(override val duration: Int) : MusicXmlTimeMovement()
}

/**
 * Direction element for expressive markings.
 */
data class MusicXmlDirection(
    /** Direction type(s) */
    val types: List<MusicXmlDirectionType> = emptyList(),
    /** Offset from current position */
    val offset: Int? = null,
    /** Placement (above/below) */
    val placement: MusicXmlPlacement? = null,
    /** Staff number if multi-staff */
    val staff: Int? = null,
    /** Sound element for playback */
    val sound: MusicXmlSound? = null
) : MusicXmlMeasureElement

/**
 * Direction type variants.
 */
sealed class MusicXmlDirectionType {
    data class Words(val text: String, val fontStyle: String? = null) : MusicXmlDirectionType()
    data class Dynamics(val type: String) : MusicXmlDirectionType()  // p, f, mf, etc.
    data class Metronome(
        val beatUnit: String,
        val beatUnitDots: Int = 0,
        val perMinute: Float
    ) : MusicXmlDirectionType()
    data class Wedge(val type: String, val spread: Int? = null) : MusicXmlDirectionType()  // crescendo, diminuendo, stop
    data class Pedal(val type: String) : MusicXmlDirectionType()  // start, stop, change
    data class OctaveShift(val type: String, val size: Int) : MusicXmlDirectionType()
    data class Other(val name: String, val content: String? = null) : MusicXmlDirectionType()
}

/**
 * Sound element for playback.
 */
data class MusicXmlSound(
    val tempo: Float? = null,
    val dynamics: Float? = null,
    val dacapo: Boolean = false,
    val segno: String? = null,
    val dalsegno: String? = null,
    val coda: String? = null,
    val tocoda: String? = null,
    val fine: Boolean = false
)

/**
 * Barline information.
 */
data class MusicXmlBarline(
    val location: String = "right",  // left, right, middle
    val barStyle: String? = null,    // regular, dotted, dashed, heavy, light-light, etc.
    val repeat: MusicXmlRepeat? = null,
    val ending: MusicXmlEnding? = null
)

/**
 * Repeat barline.
 */
data class MusicXmlRepeat(
    val direction: String,  // forward, backward
    val times: Int? = null
)

/**
 * Volta bracket ending.
 */
data class MusicXmlEnding(
    val number: String,  // "1", "2", "1, 2", etc.
    val type: String     // start, stop, discontinue
)

/**
 * Print element for layout control.
 */
data class MusicXmlPrint(
    val newSystem: Boolean = false,
    val newPage: Boolean = false,
    val blankPage: Int? = null,
    val pageNumber: String? = null
)

/**
 * Placement above or below staff.
 */
enum class MusicXmlPlacement {
    ABOVE, BELOW
}
