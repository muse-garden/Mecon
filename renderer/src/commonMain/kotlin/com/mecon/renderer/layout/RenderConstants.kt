package com.mecon.renderer.layout

import com.mecon.renderer.geometry.StaffSpace

/**
 * Constants for score rendering layout.
 *
 * This file centralizes all spacing, sizing, and layout constants used in the renderer.
 * Adjust these values to fine-tune the visual appearance of the score.
 */
object RenderConstants {
    // ========== Horizontal Spacing ==========

    /** Minimum space between consecutive notes */
    val MINIMUM_NOTE_SPACING = StaffSpace(0.5f)

    /** Base spacing unit for rhythmic spacing calculations */
    val BASE_SPACING_UNIT = StaffSpace(1.6f)

    /** Spacing ratio between different durations (typically 1.5-2.0) */
    const val SPACING_RATIO = 1.6f

    /** Space after a clef symbol */
    val SPACE_AFTER_CLEF = StaffSpace(1.0f)

    /** Space after a key signature */
    val SPACE_AFTER_KEY_SIGNATURE = StaffSpace(1.0f)

    /** Space after a time signature */
    val SPACE_AFTER_TIME_SIGNATURE = StaffSpace(1.5f)

    /** Space before a barline (minimum) */
    val SPACE_BEFORE_BARLINE = StaffSpace(1.5f)

    /** Space after a barline before the next element */
    val SPACE_AFTER_BARLINE = StaffSpace(1.5f)

    /** Minimum width for a measure */
    val MINIMUM_MEASURE_WIDTH = StaffSpace(8.0f)

    // ========== Vertical Spacing ==========

    /** Space between staves in the same part (e.g., piano grand staff) */
    val INNER_STAFF_SPACING = StaffSpace(7.0f)

    /** Space between different parts */
    val PART_SPACING = StaffSpace(10.0f)

    /** Top margin of the page/system */
    val TOP_MARGIN = StaffSpace(4.0f)

    /** Left indent for the first system (to accommodate clefs and signatures) */
    val FIRST_SYSTEM_INDENT = StaffSpace(8.0f)

    /** Left indent for subsequent systems */
    val SYSTEM_INDENT = StaffSpace(2.0f)

    /** Right margin */
    val RIGHT_MARGIN = StaffSpace(2.0f)

    /** Bottom margin */
    val BOTTOM_MARGIN = StaffSpace(4.0f)

    /**
     * Gap between the actual content extents of two adjacent staves within the
     * same part (e.g., piano grand staff). The previous staff's lowest occupied
     * Y and the next staff's highest occupied Y are separated by this amount.
     */
    val INTER_STAFF_GAP = StaffSpace(2.5f)

    /**
     * Gap between the actual content extents of two adjacent staves that belong
     * to different parts. Typically wider than [INTER_STAFF_GAP] for clarity.
     */
    val INTER_PART_GAP = StaffSpace(4.0f)

    /**
     * Extra padding added to staff hit regions for selection. Allows clicks just
     * outside the actual content (e.g., on a stem's tip or a high ledger line
     * margin) to still register against the correct staff.
     */
    val STAFF_SELECTION_PADDING = StaffSpace(0.5f)

    // ========== Brackets / Braces ==========

    /** Gap between the SQUARE bracket bar and the staff left edge. */
    val SQUARE_BRACKET_STAFF_GAP = StaffSpace(0.5f)

    /** Gap between the BRACE right edge and the staff left edge. */
    val BRACE_STAFF_GAP = StaffSpace(0.3f)

    // ========== Staff Properties ==========

    /** Number of lines per staff (usually 5) */
    const val STAFF_LINE_COUNT = 5

    /** Default staff width (can be overridden by page width) */
    val DEFAULT_STAFF_WIDTH = StaffSpace(100.0f)

    // ========== Notehead and Stem ==========

    /** Default stem length */
    val STEM_LENGTH = StaffSpace.STEM_LENGTH

    /** Extended stem length for beamed notes */
    val BEAMED_STEM_EXTENSION = StaffSpace(0.5f)

    /** Clear ink gap between simultaneous voice columns after notehead collision resolution. */
    val MULTI_VOICE_COLLISION_GAP = StaffSpace(0.15f)

    // ========== Accidentals ==========

    /** Space between accidental and notehead */
    val ACCIDENTAL_NOTEHEAD_SPACING = StaffSpace(0.15f)

    /** Space between multiple accidentals in a chord */
    val ACCIDENTAL_SPACING = StaffSpace(0.2f)

    // ========== Dots ==========

    /** Space between notehead and first augmentation dot */
    val NOTEHEAD_DOT_SPACING = StaffSpace(0.35f)

    /** Space between consecutive augmentation dots */
    val DOT_DOT_SPACING = StaffSpace(0.25f)

    // ========== Ties and Slurs ==========

    /** Minimum tie/slur height */
    val MINIMUM_TIE_HEIGHT = StaffSpace(0.5f)

    /** Default slur curvature factor */
    const val DEFAULT_SLUR_CURVATURE = 0.4f

    /** Horizontal inset between a tie endpoint and the adjacent notehead edge */
    val TIE_HORIZONTAL_INSET = StaffSpace(0.15f)

    /** Vertical distance from notehead center to the tie endpoint (signed by tie direction) */
    val TIE_VERTICAL_GAP = StaffSpace(0.45f)

    /** Horizontal inset between a slur endpoint and the adjacent notehead edge. */
    val SLUR_HORIZONTAL_INSET = StaffSpace(0.25f)

    /**
     * Vertical clearance between a slur endpoint and the anchor notehead's
     * outer edge (top/bottom). Slurs sit further from the notehead than ties
     * because they routinely pass over stems and beams.
     */
    val SLUR_VERTICAL_GAP = StaffSpace(0.7f)

    /**
     * Additional apex height added per nesting level so an outer slur clearly
     * sits above an inner one. With [DEFAULT_SLUR_NESTED_GAP] = 0.6 ss per
     * level, three nested levels add 1.8 ss to the outermost slur's bow.
     */
    val DEFAULT_SLUR_NESTED_GAP = StaffSpace(0.6f)

    /**
     * Vertical margin between the slur's apex and the highest (or lowest)
     * intervening notehead on the bow side, used by long-slur collision
     * avoidance.
     */
    val SLUR_COLLISION_MARGIN = StaffSpace(0.5f)

    // ========== Articulations ==========

    /** Gap between the note (notehead outer edge or stem tip) and the nearest articulation mark. */
    val ARTICULATION_NOTE_GAP = StaffSpace(0.3f)

    /** Vertical gap between stacked articulation marks on the same note. */
    val ARTICULATION_STACK_SPACING = StaffSpace(0.3f)

    /**
     * Minimum clearance between an articulation glyph and a staff line. Marks hug
     * the notehead; if one would touch a line it is nudged into the adjacent space
     * by this margin (notehead side only). Keeps tenuto bars off the lines.
     */
    val ARTICULATION_LINE_CLEARANCE = StaffSpace(0.2f)

    // ========== Grace Notes ==========

    /**
     * Visual scale of grace-note glyphs (noteheads, stems, flags, accidentals)
     * relative to a normal note. ~60% matches conventional engraving.
     */
    const val GRACE_NOTE_SCALE = 0.6f

    /** Horizontal spacing between consecutive grace notes inside a group. */
    val GRACE_NOTE_SPACING = StaffSpace(0.4f)

    /** Gap between the last grace note and the principal note it decorates. */
    val GRACE_NOTE_PRINCIPAL_GAP = StaffSpace(0.3f)

    // ========== Beams ==========

    /** Maximum beam slant (rise per staff space of run) */
    const val MAX_BEAM_SLANT = 0.5f

    /** Prefer horizontal beams when possible */
    const val PREFER_FLAT_BEAMS = false

    // ========== Element Widths ==========

    /** Width of an initial clef (at start of system) */
    val INITIAL_CLEF_WIDTH = StaffSpace(4.0f)

    /** Width of a mid-system clef change */
    val CLEF_CHANGE_WIDTH = StaffSpace(3.0f)

    /** Visual scale for clef changes that occur inside a measure. */
    const val INLINE_CLEF_CHANGE_SCALE = 0.8f

    /** Width per accidental in a key signature */
    val KEY_SIGNATURE_ACCIDENTAL_WIDTH = StaffSpace(1.0f)

    /** Base width for a key signature (plus accidental width per sharp/flat) */
    val KEY_SIGNATURE_BASE_WIDTH = StaffSpace(0.5f)

    /** Width of a time signature */
    val TIME_SIGNATURE_WIDTH = StaffSpace(2.5f)

    /** Width of a single barline */
    val SINGLE_BARLINE_WIDTH = StaffSpace(0.5f)

    /** Width of a double barline */
    val DOUBLE_BARLINE_WIDTH = StaffSpace(1.0f)

    /** Width of a final barline */
    val FINAL_BARLINE_WIDTH = StaffSpace(1.5f)

    /** Width of a repeat barline */
    val REPEAT_BARLINE_WIDTH = StaffSpace(2.0f)

    /** Minimum width for a notehead element */
    val MINIMUM_NOTEHEAD_WIDTH = StaffSpace(2.5f)

    // ========== Presets ==========

    /**
     * Compact spacing preset for smaller displays.
     */
    object Compact {
        val MINIMUM_NOTE_SPACING = StaffSpace(0.4f)
        val BASE_SPACING_UNIT = StaffSpace(0.8f)
        val INNER_STAFF_SPACING = StaffSpace(6.0f)
        val PART_SPACING = StaffSpace(8.0f)
    }

    /**
     * Spacious preset for easier reading and larger displays.
     */
    object Spacious {
        val MINIMUM_NOTE_SPACING = StaffSpace(2.0f)
        val BASE_SPACING_UNIT = StaffSpace(1.2f)
        val INNER_STAFF_SPACING = StaffSpace(8.0f)
        val PART_SPACING = StaffSpace(12.0f)
    }
}
