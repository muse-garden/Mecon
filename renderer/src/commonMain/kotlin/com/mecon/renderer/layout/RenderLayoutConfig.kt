package com.mecon.renderer.layout

import com.mecon.renderer.geometry.StaffSpace
import com.mecon.renderer.layout.stem.BeamLayoutConfig
import com.mecon.renderer.layout.stem.VoiceStemConfig
import com.mecon.renderer.smufl.BravuraFont
import com.mecon.renderer.smufl.EngravingDefaults
import kotlinx.serialization.Serializable

/**
 * Configuration for score layout and rendering.
 *
 * This configuration allows runtime customization of layout parameters.
 * Default values are sourced from [RenderConstants].
 */
@Serializable
data class RenderLayoutConfig(
    // Horizontal spacing
    /** Minimum space between notes */
    val minimumNoteSpacing: StaffSpace = RenderConstants.MINIMUM_NOTE_SPACING,

    /** Base spacing unit for rhythmic spacing */
    val baseSpacingUnit: StaffSpace = RenderConstants.BASE_SPACING_UNIT,

    /** Spacing ratio between durations (typically 1.5-2.0) */
    val spacingRatio: Float = RenderConstants.SPACING_RATIO,

    /** Space after clef */
    val spaceAfterClef: StaffSpace = RenderConstants.SPACE_AFTER_CLEF,

    /** Space after key signature */
    val spaceAfterKeySignature: StaffSpace = RenderConstants.SPACE_AFTER_KEY_SIGNATURE,

    /** Space after time signature */
    val spaceAfterTimeSignature: StaffSpace = RenderConstants.SPACE_AFTER_TIME_SIGNATURE,

    /** Space before barline */
    val spaceBeforeBarline: StaffSpace = RenderConstants.SPACE_BEFORE_BARLINE,

    /** Space after barline */
    val spaceAfterBarline: StaffSpace = RenderConstants.SPACE_AFTER_BARLINE,

    /** Minimum measure width */
    val minimumMeasureWidth: StaffSpace = RenderConstants.MINIMUM_MEASURE_WIDTH,

    // Vertical spacing
    /** Space between staves in the same part (e.g., piano grand staff) */
    val innerStaffSpacing: StaffSpace = RenderConstants.INNER_STAFF_SPACING,

    /** Space between different parts */
    val partSpacing: StaffSpace = RenderConstants.PART_SPACING,

    /** Top margin of the page/system */
    val topMargin: StaffSpace = RenderConstants.TOP_MARGIN,

    /** Left margin (indent for first system) */
    val firstSystemIndent: StaffSpace = RenderConstants.FIRST_SYSTEM_INDENT,

    /** Left margin for subsequent systems */
    val systemIndent: StaffSpace = RenderConstants.SYSTEM_INDENT,

    /** Right margin */
    val rightMargin: StaffSpace = RenderConstants.RIGHT_MARGIN,

    /** Bottom margin */
    val bottomMargin: StaffSpace = RenderConstants.BOTTOM_MARGIN,

    // Staff vertical spacing
    /** Gap between content extents of two adjacent staves within the same part. */
    val interStaffGap: StaffSpace = RenderConstants.INTER_STAFF_GAP,

    /** Gap between content extents of two adjacent staves across different parts. */
    val interPartGap: StaffSpace = RenderConstants.INTER_PART_GAP,

    /** Extra padding around each staff hit region for click selection. */
    val staffSelectionPadding: StaffSpace = RenderConstants.STAFF_SELECTION_PADDING,

    // Staff properties
    /** Number of lines per staff (usually 5) */
    val staffLineCount: Int = RenderConstants.STAFF_LINE_COUNT,

    /** Default staff width (can be overridden by page width) */
    val defaultStaffWidth: StaffSpace = RenderConstants.DEFAULT_STAFF_WIDTH,

    // Notehead and stem
    /** Default stem length */
    val stemLength: StaffSpace = RenderConstants.STEM_LENGTH,

    /** Extended stem length for beamed notes */
    val beamedStemExtension: StaffSpace = RenderConstants.BEAMED_STEM_EXTENSION,

    // Accidentals
    /** Space between accidental and notehead */
    val accidentalNoteheadSpacing: StaffSpace = RenderConstants.ACCIDENTAL_NOTEHEAD_SPACING,

    /** Space between multiple accidentals */
    val accidentalSpacing: StaffSpace = RenderConstants.ACCIDENTAL_SPACING,

    // Dots
    /** Space between notehead and first dot */
    val noteheadDotSpacing: StaffSpace = RenderConstants.NOTEHEAD_DOT_SPACING,

    /** Space between dots */
    val dotDotSpacing: StaffSpace = RenderConstants.DOT_DOT_SPACING,

    // Ties and slurs
    /** Minimum tie/slur height */
    val minimumTieHeight: StaffSpace = RenderConstants.MINIMUM_TIE_HEIGHT,

    /** Default slur curvature */
    val defaultSlurCurvature: Float = RenderConstants.DEFAULT_SLUR_CURVATURE,

    /** Horizontal inset between a tie endpoint and the adjacent notehead edge */
    val tieHorizontalInset: StaffSpace = RenderConstants.TIE_HORIZONTAL_INSET,

    /** Vertical gap between a tie endpoint and the notehead's vertical center (signed by direction) */
    val tieVerticalGap: StaffSpace = RenderConstants.TIE_VERTICAL_GAP,

    /** Horizontal inset between a slur endpoint and the adjacent notehead edge. */
    val slurHorizontalInset: StaffSpace = RenderConstants.SLUR_HORIZONTAL_INSET,

    /** Vertical clearance between a slur endpoint and the anchor notehead's outer edge. */
    val slurVerticalGap: StaffSpace = RenderConstants.SLUR_VERTICAL_GAP,

    /** Additional apex height added per nested slur level. */
    val slurNestedGap: StaffSpace = RenderConstants.DEFAULT_SLUR_NESTED_GAP,

    /** Vertical margin between a slur apex and any intervening notehead. */
    val slurCollisionMargin: StaffSpace = RenderConstants.SLUR_COLLISION_MARGIN,

    // Articulations
    /** Gap between the note and the nearest articulation mark. */
    val articulationNoteGap: StaffSpace = RenderConstants.ARTICULATION_NOTE_GAP,

    /** Vertical gap between stacked articulation marks. */
    val articulationStackSpacing: StaffSpace = RenderConstants.ARTICULATION_STACK_SPACING,

    /** Minimum clearance between a notehead-side articulation and a staff line. */
    val articulationLineClearance: StaffSpace = RenderConstants.ARTICULATION_LINE_CLEARANCE,

    // Dynamics & hairpins (staff attachments)
    /** Gap between a staff's note content extent and the dynamics band. */
    val dynamicStaffGap: StaffSpace = StaffSpace(1.2f),

    /** Vertical gap between stacked rows of staff attachments on the same side. */
    val dynamicRowSpacing: StaffSpace = StaffSpace(0.4f),

    /** Extra horizontal tracking added between composed dynamic letters. */
    val dynamicLetterTracking: StaffSpace = StaffSpace(0.04f),

    /** Full vertical spread of a hairpin wedge at its open end. */
    val hairpinSpread: StaffSpace = StaffSpace(1.15f),

    /** Line thickness for hairpin wedges and dashed continuations. */
    val hairpinThickness: StaffSpace = StaffSpace(0.16f),

    /** Gap between `cresc.`/`dim.` text and the start of its dashed line. */
    val hairpinTextGap: StaffSpace = StaffSpace(0.5f),

    // Grace notes
    /** Scale of grace-note glyphs relative to normal notes. */
    val graceNoteScale: Float = RenderConstants.GRACE_NOTE_SCALE,

    /** Horizontal spacing between consecutive grace notes in a group. */
    val graceNoteSpacing: StaffSpace = RenderConstants.GRACE_NOTE_SPACING,

    /** Gap between the last grace and the principal note. */
    val graceNotePrincipalGap: StaffSpace = RenderConstants.GRACE_NOTE_PRINCIPAL_GAP,

    // Beams
    /** Maximum beam slant (rise per staff space of run) */
    val maxBeamSlant: Float = RenderConstants.MAX_BEAM_SLANT,

    /** Prefer horizontal beams */
    val preferFlatBeams: Boolean = RenderConstants.PREFER_FLAT_BEAMS,

    // Proportional layout settings
    /** Minimum spacing between elements in proportional layout */
    val minimumProportionalSpacing: StaffSpace = StaffSpace(1f),

    // Stem direction settings
    /** Voice-based stem direction configuration */
    val voiceStemConfig: VoiceStemConfig = VoiceStemConfig.DEFAULT,

    /** Beam layout configuration */
    val beamLayoutConfig: BeamLayoutConfig = BeamLayoutConfig.DEFAULT,

    /**
     * Whether a musically empty measure (one whose voices contain only rests) is padded to at least
     * [minimumMeasureWidth] so there is room to hover over and click individual beats while editing.
     *
     * Off by default so existing snapshot tests keep their tight, content-driven spacing. The app
     * turns it on for the editable score view (new / opened scores), and future automatic part
     * extraction relies on it to keep blank bars a sensible width.
     */
    val padEmptyMeasures: Boolean = false,

    /**
     * Optional post-intrinsic continuous time projection. null preserves the normal engraving path.
     * This mode is intentionally ignored for paginated geometry.
     */
    val alignedTimeAxisRequest: AlignedTimeAxisRequest? = null,
) {
    /**
     * Engraving defaults from the font context.
     */
    context(BravuraFont)
    val engravingDefaults: EngravingDefaults
        get() = this@BravuraFont.engravingDefaults

    companion object {
        /** Default configuration using RenderConstants defaults */
        val DEFAULT = RenderLayoutConfig()

        /** Compact configuration for smaller display */
        val COMPACT = RenderLayoutConfig(
            minimumNoteSpacing = RenderConstants.Compact.MINIMUM_NOTE_SPACING,
            baseSpacingUnit = RenderConstants.Compact.BASE_SPACING_UNIT,
            innerStaffSpacing = RenderConstants.Compact.INNER_STAFF_SPACING,
            partSpacing = RenderConstants.Compact.PART_SPACING
        )

        /** Spacious configuration for easier reading */
        val SPACIOUS = RenderLayoutConfig(
            minimumNoteSpacing = RenderConstants.Spacious.MINIMUM_NOTE_SPACING,
            baseSpacingUnit = RenderConstants.Spacious.BASE_SPACING_UNIT,
            innerStaffSpacing = RenderConstants.Spacious.INNER_STAFF_SPACING,
            partSpacing = RenderConstants.Spacious.PART_SPACING
        )
    }
}
