package com.mecon.renderer.layout

import com.mecon.api.plugin.AnnotationElement
import com.mecon.api.plugin.PluginStaffId
import com.mecon.api.computed.ComputedStaffAttachment
import com.mecon.api.primitive.KeySignature
import com.mecon.api.primitive.TrackId
import com.mecon.api.storage.tracks.Clef
import com.mecon.api.storage.tracks.MeasureRange
import com.mecon.api.storage.tracks.MeasureRanges
import com.mecon.renderer.elements.ClefElement
import com.mecon.renderer.elements.KeySignatureElement
import com.mecon.renderer.geometry.StaffSpace

/**
 * Internal data class for staff information during layout computation.
 */
internal data class StaffInfo(
    val trackId: TrackId,
    val staffIndex: Int,
    val partIndex: Int,
    val clef: Clef,
    val keySignature: KeySignature,
    /** Measure ranges over which this staff is hidden (from [com.mecon.api.runtime.tracks.RuntimeStaffTrack]). */
    val hiddenRanges: List<MeasureRange> = emptyList(),
) {
    /** True when every measure in [range] is hidden, so the staff collapses out of that line. */
    fun isFullyHiddenOver(range: IntRange): Boolean =
        hiddenRanges.isNotEmpty() && MeasureRanges.coversAll(hiddenRanges, range.first, range.last)
}

/**
 * Whether a [StaffLayoutInfo] describes a regular notation staff or a plugin-
 * contributed annotation staff. Annotation staves carry no staff lines and
 * draw only [AnnotationElement]s.
 */
enum class StaffKind { NOTATION, ANNOTATION }

/**
 * Layout information for a staff.
 *
 * For [StaffKind.NOTATION] staves [trackId] / [staffIndex] / [clef] describe
 * the notation track. For [StaffKind.ANNOTATION] staves [pluginStaffId] is set
 * and [clef] is a placeholder (treble) — annotation staves do not render lines.
 *
 * [topY] / [bottomY] mark the visible staff (e.g. the five-line range for
 * notation staves). [contentTopY] / [contentBottomY] mark the actual occupied
 * vertical range including notes, stems and ledger lines that extend beyond
 * the staff itself — this is what hit testing should use.
 */
data class StaffLayoutInfo(
    val trackId: TrackId,
    val staffIndex: Int,
    val partIndex: Int,
    val centerY: StaffSpace,
    val topY: StaffSpace,
    val bottomY: StaffSpace,
    val contentTopY: StaffSpace,
    val contentBottomY: StaffSpace,
    val clef: Clef,
    val kind: StaffKind = StaffKind.NOTATION,
    val pluginStaffId: PluginStaffId? = null
)

/**
 * One [AnnotationElement] resolved to absolute x / centerY coordinates by the
 * annotation-staff layout pass. The renderer consumes these to produce
 * `RenderElement`s without the plugin ever touching pixel coordinates.
 */
data class PlacedAnnotationElement(
    val staffId: PluginStaffId,
    val x: StaffSpace,
    /** Resolved range end; null for point annotations. */
    val endX: StaffSpace? = null,
    val centerY: StaffSpace,
    val element: AnnotationElement,
    /** 0 for continuous (single-system) layout; set per-element in paginated mode. */
    val systemIndex: Int = 0,
)

/**
 * A clef + key signature re-stated at the start of a system (line) other than the
 * first. [clef] / [keySignature] are pre-built layout elements (their relativeX
 * positions them within the header column); the renderer draws them at [baseX]
 * using the system staff's centre Y. Either may be null (e.g. C major has no
 * key-signature accidentals).
 */
data class LineStartHeader(
    val systemIndex: Int,
    val staffIndex: Int,
    val clef: ClefElement?,
    val keySignature: KeySignatureElement?,
    /** Left edge (in page X space) where this system's header column begins. */
    val baseX: StaffSpace,
)

/**
 * A small courtesy ("cautionary") clef drawn at the *right* end of a system when the **next**
 * system opens with a clef change. Standard engraving restates the new clef at the end of the
 * current line so the reader is warned before the break; the new line then shows it full-size in
 * its [LineStartHeader]. [clef] is a pre-built small-scale layout element; the renderer draws it at
 * [baseX] using this system's staff centre Y. The in-stream body clef at the next line's opening is
 * suppressed (see [UnifiedLayoutResult.suppressedClefTimes]) so the clef never appears twice.
 */
data class LineEndClef(
    val systemIndex: Int,
    val staffIndex: Int,
    val clef: ClefElement,
    /** Left edge (in page X space) where the courtesy clef is drawn. */
    val baseX: StaffSpace,
)

/**
 * A barline drawn at the right edge of a justified system, standing in for the
 * measure-opening barline of the next system (which is suppressed at that next
 * line's start). Null for the last system, whose final barline renders normally.
 */
data class ClosingBarline(
    val type: com.mecon.api.primitive.BarlineType,
    val x: StaffSpace,
)

/**
 * One system (line) of the score after line-breaking.
 *
 * Slots belonging to this system carry [UnifiedTimeSlot.systemIndex] == [systemIndex];
 * their X has been stretched into `[lineStartX(+header), lineEndX]`. [staffLayouts]
 * are this system's staves with [yOffset] already folded into their Y coordinates,
 * so anything resolved through them draws at the correct vertical position.
 */
data class SystemLayout(
    val systemIndex: Int,
    val pageIndex: Int,
    val measureRange: IntRange,
    val yOffset: StaffSpace,
    /** X where staff lines / barlines begin for this system. */
    val lineStartX: StaffSpace,
    /** X where staff lines / barlines end (right edge of the justified line). */
    val lineEndX: StaffSpace,
    /** Leftmost note-slot X after justification, cached for cross-line attachment continuation. */
    val lineFirstNoteX: StaffSpace? = null,
    /** This system's staves (notation + any annotation), Y already offset by [yOffset]. */
    val staffLayouts: List<StaffLayoutInfo>,
    val lineStartHeaders: List<LineStartHeader> = emptyList(),
    /** Barline to draw at this system's right edge (justified systems only). */
    val closingBarline: ClosingBarline? = null,
    /**
     * Courtesy clefs restated at this system's right end because the *next* system opens with a
     * clef change. Empty unless a clef change lands exactly on a line break.
     */
    val lineEndClefs: List<LineEndClef> = emptyList(),
    /**
     * Staff-header labels / brackets (instrument names + group brackets) for THIS system,
     * computed against its own Y-offset staves so they repeat correctly at the top of every
     * system (and align vertically with the staves on every page).
     */
    val headerLabels: List<StaffHeaderLayoutComputer.PlacedLabel> = emptyList(),
    val headerBrackets: List<StaffHeaderLayoutComputer.PlacedBracket> = emptyList(),
)

/**
 * How one system in a **reflow-incremental** layout relates to a system in the cached (previous) layout.
 * Produced by [SystemBreaker] when an edit moves line breaks: the untouched prefix + the converged tail
 * are [Reuse]d (measure-for-measure identical to a cached system — justification is identical, only Y /
 * page may shift), while the re-packed middle is [Regenerate]d. Null on [UnifiedLayoutResult] means "no
 * reflow lineage" (a full solve, or a non-reflow incremental result); the renderer then keeps its
 * existing per-system reuse / full-render path.
 */
sealed interface SystemOrigin {
    /** Measure-for-measure identical to cached system [cachedIndex]; the renderer may reuse its elements
     *  rigidly translated by this system's ΔY. */
    data class Reuse(val cachedIndex: Int) : SystemOrigin
    /** Re-packed by the reflow breaker — the renderer must regenerate this system from scratch. */
    data object Regenerate : SystemOrigin
}

/** One page of the paginated score, used to draw page rectangles / separation. */
data class PageLayout(
    val pageIndex: Int,
    val originY: StaffSpace,
    val width: StaffSpace,
    val height: StaffSpace,
)

/**
 * Result of unified layout computation.
 */
data class UnifiedLayoutResult(
    /** All time slots with X positions */
    val timeSlotMap: UnifiedTimeSlotMap,
    /** Voice event layouts with relative coordinates */
    val voiceEventLayouts: VoiceEventLayoutMap,
    /** Barline layouts */
    val barlineLayouts: BarlineLayoutMap,
    /** Staff layout information (notation + annotation) */
    val staffLayouts: List<StaffLayoutInfo>,
    /** Total width */
    val width: StaffSpace,
    /** Total height */
    val height: StaffSpace,
    /** Annotation elements placed against their resolved x / staff. Empty when no plugins contribute. */
    val annotationElementLayouts: Map<PluginStaffId, List<PlacedAnnotationElement>> = emptyMap(),
    /**
     * Header origin X — where the staff-header (brackets / labels) starts, just
     * inside the left page margin. The header occupies [headerOriginX, systemStartX).
     */
    val headerOriginX: StaffSpace = StaffSpace.ZERO,
    /**
     * X where staff lines and barlines start. This is to the right of the header
     * when brackets / labels are present.
     */
    val systemStartX: StaffSpace = StaffSpace.ZERO,
    /** Placed staff attachments (dynamics, hairpins). */
    val placedAttachments: List<PlacedStaffAttachment> = emptyList(),
    /** Pre-split attachment placement against paginated, justified slots; reused system-by-system. */
    val paginatedAttachmentLayout: StaffAttachmentLayoutResult? = null,
    /**
     * Systems (lines) after line-breaking. Always exactly one in continuous mode,
     * covering the whole score with [SystemLayout.yOffset] == 0. The renderer
     * iterates these to draw per-system staff lines, barlines and headers.
     */
    val systems: List<SystemLayout> = emptyList(),
    /** Pages after pagination. Single page in continuous mode. */
    val pages: List<PageLayout> = emptyList(),
    /** Note/annotation-only vertical result before attachment extents are folded into system spacing. */
    val preAttachmentSystems: List<SystemLayout> = systems,
    /** Page assignment paired with [preAttachmentSystems]. */
    val preAttachmentPages: List<PageLayout> = pages,
    /**
     * Times of measure-opening barlines that must NOT be drawn from their slot,
     * because they fall at the start of a system (>0) and are instead drawn as the
     * previous system's [SystemLayout.closingBarline]. Empty in continuous mode.
     */
    val suppressedBarlineTimes: Set<com.mecon.api.primitive.TimeCode> = emptySet(),
    /**
     * Times of in-stream body clefs that must NOT be drawn from their slot, because they fall at the
     * start of a system (>0) where the restated [LineStartHeader] already draws the clef. The same
     * clef is restated as a courtesy at the previous system's right end ([SystemLayout.lineEndClefs]).
     * Empty in continuous mode and whenever no clef change lands on a line break.
     */
    val suppressedClefTimes: Set<com.mecon.api.primitive.TimeCode> = emptySet(),
    /** Whether this result was produced in paginated mode. */
    val paginated: Boolean = false,
    /**
     * Score title block (title / subtitle / composer) placed above the first system
     * on page 0. Non-null only in paginated mode when the metadata carries text; the
     * first system is pushed down by [TitleBlockLayout.height] to make room.
     */
    val titleBlock: TitleBlockLayout? = null,
    /** Non-spacing user-edit entry points placed only after line/page layout is complete. */
    val postLayoutMarkers: List<PostLayoutMarker> = emptyList(),
    /** Shared continuous projection published only when an aligned request was applied. */
    val resolvedTimeAxis: ResolvedTimeAxis? = null,
    /** Musical-time canonicalizer from the same runtime frame as this layout. */
    val scoreTimeMap: com.mecon.api.runtime.ScoreTimeMap? = null,
    /**
     * The proportional slot map **before** line-breaking / justification. In continuous mode this is the
     * same instance as [timeSlotMap] (no breaking happens). In paginated mode [timeSlotMap] holds the
     * breaker's post-justification X, so this carries the pre-justification proportional X separately.
     *
     * The incremental layout reuses *this* map's X (re-solving only the changed measures, translating the
     * rest). See `docs/data_model/incremental-update.md` §3.2.
     */
    val preBreakTimeSlotMap: UnifiedTimeSlotMap = timeSlotMap,
    /**
     * Pre-break (intrinsic) width of each measure, keyed by measure number, as the [SystemBreaker]
     * derived it from the barline grid. Empty in continuous mode. The incremental breaker uses these as
     * drift-free widths for unchanged measures when validating that an edit leaves the cached line
     * partition intact — never re-deriving breakpoints from translated (drifting) coordinates. This keeps
     * the live greedy partition identical to a full solve even when regenerated X differs sub-pixel-wise.
     */
    val preBreakMeasureWidths: Map<Int, StaffSpace> = emptyMap(),
    /**
     * Per-(measure → staffIndex) note vertical extent (topExtent, bottomExtent), as
     * [StaffLayoutComputer.extentsByMeasureStaff] derived it. Drives line-local vertical layout: a
     * paginated system's per-staff extent is the per-staff max over the measures it contains, so each
     * line is spaced from its own content rather than the whole score. Empty in continuous mode. Kept
     * (mirroring [preBreakMeasureWidths]) as the source chunks for [measureVerticalExtentTree].
     */
    val preBreakMeasureExtents: Map<Int, Map<Int, Pair<StaffSpace, StaffSpace>>> = emptyMap(),
    /** Persistent range-max index over [preBreakMeasureExtents], patched only in the edit window. */
    val measureVerticalExtentTree: MeasureVerticalExtentTree = MeasureVerticalExtentTree.EMPTY,
    /**
     * Time-ordered voice layouts partitioned by measure. Incremental step 5 replaces only the edited
     * measure chunks, then concatenates the unchanged lists without walking every slot event again.
     */
    val voiceLayoutsByMeasure: Map<Int, List<VoiceEventLayout>> = emptyMap(),
    /**
     * Persistent measure → staff/local-X note-extent B+ tree. Incremental layout replaces only affected
     * measure chunks; continuous and paginated attachment passes share it through per-frame X transforms.
     */
    val noteExtentTree: NoteExtentTree = NoteExtentTree.EMPTY,
    /** Attachment list that produced [staffAttachmentsByMeasure], used as its cache-lineage guard. */
    val staffAttachmentsSnapshot: List<ComputedStaffAttachment> = emptyList(),
    /** Persistent-by-reference measure chunks used to select attachment candidates for an affected system. */
    val staffAttachmentsByMeasure: Map<Int, List<ComputedStaffAttachment>> = emptyMap(),
    /** Cached start/end system pairs for the relatively few spanning staff attachments. */
    val attachmentSystemSpans: List<Pair<Int, Int>> = emptyList(),
    /**
     * Per-system lineage for a **reflow-incremental** layout, aligned with [systems] by index: each entry
     * says whether that system is [SystemOrigin.Reuse] of a cached system (identical up to a vertical /
     * page shift) or was [SystemOrigin.Regenerate]d by the re-pack. Null for full solves and non-reflow
     * incremental results. Consumed by the paginated render splicer to reuse prefix / suffix elements
     * across a line-count change. See `docs/data_model/incremental-update.md` §3.2.
     */
    val systemLineage: List<SystemOrigin>? = null
) {
    /** Index from systemIndex → (staffIndex → staff layout with Y offset applied). */
    private val staffBySystem: Map<Int, Map<Int, StaffLayoutInfo>> by lazy {
        systems.associate { sys ->
            sys.systemIndex to sys.staffLayouts.associateBy { it.staffIndex }
        }
    }

    /**
     * Resolve the staff layout (Y already offset for the system) for the given
     * system + staff index.
     *
     * A **known** system that does not contain [staffIndex] means the staff collapsed out of that line
     * (fully hidden here — see the hidden-staff feature): return null so callers SKIP its glyphs rather
     * than drawing them at the flat/global Y (which would strand the hidden staff's notes at the top of
     * the score, above the title). The flat [staffLayouts] fallback applies only to an **unknown** system
     * (defensive; should not happen once [systems] is populated — e.g. the continuous single-system path).
     */
    fun staffForSystem(systemIndex: Int, staffIndex: Int): StaffLayoutInfo? {
        val bySystem = staffBySystem[systemIndex]
        return if (bySystem != null) bySystem[staffIndex]
        else staffLayouts.firstOrNull { it.staffIndex == staffIndex }
    }

    /**
     * Check if result is empty.
     */
    fun isEmpty(): Boolean = voiceEventLayouts.isEmpty() && barlineLayouts.isEmpty()

    companion object {
        val EMPTY = UnifiedLayoutResult(
            timeSlotMap = UnifiedTimeSlotMap.EMPTY,
            voiceEventLayouts = VoiceEventLayoutMap.EMPTY,
            barlineLayouts = BarlineLayoutMap.EMPTY,
            staffLayouts = emptyList(),
            width = StaffSpace.ZERO,
            height = StaffSpace.ZERO
        )
    }
}
