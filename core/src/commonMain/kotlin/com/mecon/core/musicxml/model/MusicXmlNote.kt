package com.mecon.core.musicxml.model

/**
 * A note element representing a pitch, rest, or chord member.
 */
data class MusicXmlNote(
    /** Pitch info (null for rest) */
    val pitch: MusicXmlPitch? = null,
    /** Unpitched info for percussion */
    val unpitched: MusicXmlUnpitched? = null,
    /** Rest marker */
    val rest: MusicXmlRest? = null,
    /** Duration in divisions */
    val duration: Int? = null,
    /** Whether this is part of a chord (has <chord/> element) */
    val isChord: Boolean = false,
    /** Tie info */
    val tie: List<MusicXmlTie> = emptyList(),
    /** Voice number */
    val voice: Int = 1,
    /** Note type (whole, half, quarter, etc.) */
    val type: String? = null,
    /** Number of dots */
    val dots: Int = 0,
    /** Accidental display */
    val accidental: MusicXmlAccidental? = null,
    /** Time modification for tuplets */
    val timeModification: MusicXmlTimeModification? = null,
    /** Stem direction */
    val stem: MusicXmlStem? = null,
    /** Notehead type */
    val notehead: MusicXmlNotehead? = null,
    /** Staff number (1-based) */
    val staff: Int = 1,
    /** Beam info */
    val beams: List<MusicXmlBeam> = emptyList(),
    /** Notations (articulations, ornaments, slurs, etc.) */
    val notations: MusicXmlNotations? = null,
    /** Lyric elements */
    val lyrics: List<MusicXmlLyric> = emptyList(),
    /** Grace note marker */
    val grace: MusicXmlGrace? = null,
    /** Cue note marker */
    val cue: Boolean = false,
    /** Print-object attribute (false = hidden) */
    val printObject: Boolean = true
) : MusicXmlMeasureElement {

    /** Check if this is a rest */
    val isRest: Boolean get() = rest != null

    /** Check if this is a pitched note (not rest, not unpitched) */
    val isPitched: Boolean get() = pitch != null

    /** Check if this is a grace note */
    val isGrace: Boolean get() = grace != null
}

/**
 * Pitch representation.
 */
data class MusicXmlPitch(
    /** Step: C, D, E, F, G, A, B */
    val step: String,
    /** Chromatic alteration: -2 to +2 */
    val alter: Int = 0,
    /** Octave: 0-9 (C4 = middle C) */
    val octave: Int
)

/**
 * Unpitched note (percussion).
 */
data class MusicXmlUnpitched(
    val displayStep: String,
    val displayOctave: Int
)

/**
 * Rest marker.
 */
data class MusicXmlRest(
    /** Display step for positioning */
    val displayStep: String? = null,
    /** Display octave for positioning */
    val displayOctave: Int? = null,
    /** Whether this is a measure rest */
    val measure: Boolean = false
)

/**
 * Tie type.
 */
data class MusicXmlTie(
    val type: String  // start, stop, continue, let-ring
)

/**
 * Tied notation (visual representation of tie).
 */
data class MusicXmlTied(
    val type: String,  // start, stop, continue, let-ring
    val number: Int = 1,
    val orientation: String? = null,  // over, under
    val placement: MusicXmlPlacement? = null,
    val curve: MusicXmlCurvePosition = MusicXmlCurvePosition(),
)

/** Standard MusicXML position / Bézier attributes shared by `<tied>` and `<slur>`. */
data class MusicXmlCurvePosition(
    val defaultX: Float? = null,
    val defaultY: Float? = null,
    val relativeX: Float? = null,
    val relativeY: Float? = null,
    val bezierX: Float? = null,
    val bezierY: Float? = null,
)

/**
 * Accidental display.
 */
data class MusicXmlAccidental(
    val value: String,  // sharp, flat, natural, double-sharp, double-flat, etc.
    val cautionary: Boolean = false,
    val editorial: Boolean = false,
    val parentheses: Boolean = false,
    val bracket: Boolean = false
)

/**
 * Time modification for tuplets.
 */
data class MusicXmlTimeModification(
    /** Actual number of notes */
    val actualNotes: Int,
    /** Normal number of notes */
    val normalNotes: Int,
    /** Normal note type */
    val normalType: String? = null,
    /** Normal dots */
    val normalDot: Int = 0
)

/**
 * Stem direction.
 */
data class MusicXmlStem(
    val value: String  // up, down, double, none
)

/**
 * Notehead type.
 */
data class MusicXmlNotehead(
    val value: String,  // normal, diamond, x, slash, triangle, etc.
    val filled: Boolean? = null,
    val parentheses: Boolean = false
)

/**
 * Beam element.
 */
data class MusicXmlBeam(
    val number: Int,  // Beam level (1 = primary beam)
    val value: String  // begin, continue, end, forward hook, backward hook
)

/**
 * Grace note marker.
 */
data class MusicXmlGrace(
    /** Whether this is a slashed grace note (acciaccatura) */
    val slash: Boolean = false,
    /** Steal time from previous note (percent) */
    val stealTimePrevious: Int? = null,
    /** Steal time from following note (percent) */
    val stealTimeFollowing: Int? = null,
    /** Make time (percentage of the regular note duration) */
    val makeTime: Int? = null
)

/**
 * Notations container.
 */
data class MusicXmlNotations(
    val tied: List<MusicXmlTied> = emptyList(),
    val slurs: List<MusicXmlSlur> = emptyList(),
    val tuplets: List<MusicXmlTuplet> = emptyList(),
    val articulations: List<MusicXmlArticulation> = emptyList(),
    val ornaments: List<MusicXmlOrnament> = emptyList(),
    val technical: List<MusicXmlTechnical> = emptyList(),
    val dynamics: List<String> = emptyList(),
    val fermatas: List<MusicXmlFermata> = emptyList(),
    val arpeggiate: MusicXmlArpeggiate? = null,
    val nonArpeggiate: MusicXmlNonArpeggiate? = null,
    val glissando: List<MusicXmlGlissando> = emptyList(),
    val slide: List<MusicXmlSlide> = emptyList(),
    val other: List<MusicXmlOtherNotation> = emptyList()
)

/**
 * Slur notation.
 */
data class MusicXmlSlur(
    val type: String,  // start, stop, continue
    val number: Int = 1,
    val placement: MusicXmlPlacement? = null,
    val curve: MusicXmlCurvePosition = MusicXmlCurvePosition(),
)

/**
 * Tuplet notation.
 */
data class MusicXmlTuplet(
    val type: String,  // start, stop
    val number: Int = 1,
    val bracket: Boolean? = null,
    val showNumber: String? = null,  // actual, both, none
    val showType: String? = null     // actual, both, none
)

/**
 * Articulation marks.
 */
sealed class MusicXmlArticulation {
    data object Accent : MusicXmlArticulation()
    data object StrongAccent : MusicXmlArticulation()  // marcato
    data object Staccato : MusicXmlArticulation()
    data object Staccatissimo : MusicXmlArticulation()
    data object Tenuto : MusicXmlArticulation()
    data object DetachedLegato : MusicXmlArticulation()  // portato
    data object Spiccato : MusicXmlArticulation()
    data class Breath(val placement: MusicXmlPlacement? = null) : MusicXmlArticulation()
    data class Caesura(val placement: MusicXmlPlacement? = null) : MusicXmlArticulation()
    data class Other(val name: String) : MusicXmlArticulation()
}

/**
 * Ornament marks.
 */
sealed class MusicXmlOrnament {
    data class Trill(val placement: MusicXmlPlacement? = null) : MusicXmlOrnament()
    data class Turn(val placement: MusicXmlPlacement? = null, val delayed: Boolean = false) : MusicXmlOrnament()
    data class InvertedTurn(val placement: MusicXmlPlacement? = null, val delayed: Boolean = false) : MusicXmlOrnament()
    data class Mordent(val placement: MusicXmlPlacement? = null, val long: Boolean = false) : MusicXmlOrnament()
    data class InvertedMordent(val placement: MusicXmlPlacement? = null, val long: Boolean = false) : MusicXmlOrnament()
    data class Tremolo(val type: String, val marks: Int) : MusicXmlOrnament()  // single, start, stop
    data class TrillMark(val placement: MusicXmlPlacement? = null) : MusicXmlOrnament()
    data class WavyLine(val type: String, val number: Int = 1) : MusicXmlOrnament()
    data class Other(val name: String) : MusicXmlOrnament()
}

/**
 * Technical markings.
 */
sealed class MusicXmlTechnical {
    data class Fingering(val finger: String) : MusicXmlTechnical()
    data class String(val stringNumber: Int) : MusicXmlTechnical()
    data class Fret(val fretNumber: Int) : MusicXmlTechnical()
    data object UpBow : MusicXmlTechnical()
    data object DownBow : MusicXmlTechnical()
    data object Harmonic : MusicXmlTechnical()
    data object OpenString : MusicXmlTechnical()
    data object Stopped : MusicXmlTechnical()
    data object SnapPizzicato : MusicXmlTechnical()
    data class Other(val name: String) : MusicXmlTechnical()
}

/**
 * Fermata marking.
 */
data class MusicXmlFermata(
    val type: String = "upright",  // upright, inverted
    val shape: String = "normal"   // normal, angled, square
)

/**
 * Arpeggiate marking.
 */
data class MusicXmlArpeggiate(
    val direction: String? = null,  // up, down
    val number: Int = 1
)

/**
 * Non-arpeggiate marking (bracket).
 */
data class MusicXmlNonArpeggiate(
    val type: String,  // top, bottom
    val number: Int = 1
)

/**
 * Glissando marking.
 */
data class MusicXmlGlissando(
    val type: String,  // start, stop
    val number: Int = 1,
    val lineType: String? = null  // solid, dashed, dotted, wavy
)

/**
 * Slide marking (portamento).
 */
data class MusicXmlSlide(
    val type: String,  // start, stop
    val number: Int = 1,
    val lineType: String? = null
)

/**
 * Other notation (extension point).
 */
data class MusicXmlOtherNotation(
    val type: String,
    val content: String? = null
)

/**
 * Lyric element.
 */
data class MusicXmlLyric(
    val number: Int = 1,
    val syllabic: String? = null,  // single, begin, middle, end
    val text: String,
    val elision: String? = null,
    val extend: Boolean = false
)
