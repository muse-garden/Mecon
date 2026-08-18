package com.mecon.features.freepractice

import com.mecon.api.primitive.Fraction
import com.mecon.theory.ModulationCircleOfFifths
import com.mecon.theory.ModulationKey
import com.mecon.theory.ModulationPitchLabels
import com.mecon.theory.freepractice.WorkspaceKeyMode
import com.mecon.theory.freepractice.WorkspaceSlotId
import com.mecon.theory.freepractice.WorkspaceTonalLayoutId
import com.mecon.theory.harmony.HarmonyKeyAccent
import com.mecon.theory.harmony.LanePacker
import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt

/** Surface-local renderer and interaction contract shared by Compose and the browser shell. */
@Serializable
data class PracticeTimelineAxisAnchor(val time: Fraction, val x: Float)

@Serializable
enum class PracticeTimelineToneLabelMode { RELATIVE, ABSOLUTE }

@Serializable
enum class PracticeTimelineDisplayMode { COMPACT, FULL }

@Serializable
data class PracticeTimelinePalette(
    val surface: String = "#1A2537",
    val surfaceLight: String = "#26344A",
    val surfaceDark: String = "#080D18",
    val primaryDark: String = "#2563B8",
    val primaryLight: String = "#60A5FA",
    val selectedSurface: String = "#4075E9",
    val selectedBorder: String = "#8FC5FF",
    val textPrimary: String = "#E1E7EE",
    val textMuted: String = "#9EADBF",
    val border: String = "#26344A",
    val borderLight: String = "#3B4A61",
    val emerald: String = "#3F927F",
    val emeraldLight: String = "#70B5A3",
    val orange: String = "#B68155",
    val orangeLight: String = "#D2A477",
    val white: String = "#FFFFFF",
)

@Serializable
data class PracticeTimelineSceneRequest(
    val revision: Long,
    val axisRevision: Long,
    val viewportWidth: Float,
    val scrollLeft: Float = 0f,
    val contentOriginX: Float = 0f,
    val axisAnchors: List<PracticeTimelineAxisAnchor> = emptyList(),
    /** Actual engraved closing-barline positions; these replace padded terminal slot anchors. */
    val measureBoundaries: List<PracticeTimelineAxisAnchor> = emptyList(),
    val axisContentEndX: Float = 0f,
    val axisSurfaceWidth: Float = 0f,
    val pixelsPerWhole: Float = 576f,
    val timeline: PracticeTimelineView,
    val selectedSlotId: String? = null,
    val selectedTonalLayoutId: String? = null,
    val selectedIdiomId: String? = null,
    val gridUnit: Fraction = Fraction.SIXTEENTH,
    val defaultChordDuration: Fraction = Fraction.QUARTER,
    val toneLabelMode: PracticeTimelineToneLabelMode = PracticeTimelineToneLabelMode.RELATIVE,
    val displayMode: PracticeTimelineDisplayMode = PracticeTimelineDisplayMode.FULL,
    val palette: PracticeTimelinePalette = PracticeTimelinePalette(),
    val showRemoveAction: Boolean = true,
    val gesture: PracticeTimelineGestureState? = null,
)

@Serializable
data class PracticeTimelineBounds(val x: Float, val y: Float, val width: Float, val height: Float) {
    fun contains(px: Float, py: Float): Boolean =
        px >= x && px <= x + width && py >= y && py <= y + height
}

@Serializable
enum class PracticeTimelineDrawKind { RECT, ROUND_RECT, CIRCLE, LINE, TEXT, BRACKET }

@Serializable
data class PracticeTimelineDrawObject(
    val id: String,
    val kind: PracticeTimelineDrawKind,
    val bounds: PracticeTimelineBounds,
    val z: Int,
    val fill: String? = null,
    val stroke: String? = null,
    val strokeWidth: Float = 0f,
    val radius: Float = 0f,
    val text: String? = null,
    val fontFamily: String? = null,
    val fontSize: Float = 0f,
    val fontWeight: Int = 400,
    val textAlign: String = "start",
    val dashPattern: List<Float> = emptyList(),
)

@Serializable
enum class PracticeTimelineHitKind {
    SLOT, SLOT_START, SLOT_END, SHARED_BOUNDARY, TONAL_LAYOUT, TONAL_START, TONAL_END,
    IDIOM, APPEND, REMOVE_SLOT
}

@Serializable
data class PracticeTimelineHitObject(
    val id: String,
    val kind: PracticeTimelineHitKind,
    val targetId: String,
    val bounds: PracticeTimelineBounds,
    val cursor: String,
    val actions: List<String>,
    val secondaryTargetId: String? = null,
    val insertOnset: Fraction? = null,
    val insertDuration: Fraction? = null,
)

/**
 * Pointer feedback for one interactive element. The projector owns which elements answer to hover,
 * which cursor they claim and what their highlight looks like; platforms only test containment in
 * list order (already sorted by hit priority) and replay [overlay] above the base draw objects.
 */
@Serializable
data class PracticeTimelineHoverTarget(
    val hitId: String,
    val kind: PracticeTimelineHitKind,
    val targetId: String,
    val bounds: PracticeTimelineBounds,
    val cursor: String,
    val overlay: List<PracticeTimelineDrawObject> = emptyList(),
)

@Serializable
data class PracticeTimelineAccessibilityObject(
    val id: String,
    val role: String,
    val label: String,
    val bounds: PracticeTimelineBounds,
    val selected: Boolean = false,
    val disabled: Boolean = false,
    val actions: List<String> = emptyList(),
)

@Serializable
data class PracticeTimelineContentAnchors(
    val scoreOriginX: Float,
    val timeZeroX: Float,
    val contentEndX: Float,
    val appendX: Float,
)

@Serializable
data class PracticeTimelineScene(
    val generation: Long,
    val revision: Long,
    val axisRevision: Long,
    val viewportWidth: Float,
    val contentOriginX: Float,
    val contentWidth: Float,
    val contentHeight: Float,
    val scrollExtent: Float,
    val drawObjects: List<PracticeTimelineDrawObject>,
    val hitObjects: List<PracticeTimelineHitObject>,
    val hoverTargets: List<PracticeTimelineHoverTarget>,
    val accessibility: List<PracticeTimelineAccessibilityObject>,
    val contentAnchors: PracticeTimelineContentAnchors,
    val gestureState: PracticeTimelineGestureState? = null,
)

@Serializable
enum class PracticeTimelineInputType { DOWN, MOVE, UP, CANCEL, KEY, WHEEL, ACTIVATE }

@Serializable
data class PracticeTimelineInput(
    val type: PracticeTimelineInputType,
    val sceneGeneration: Long,
    val pointerId: Long? = null,
    val x: Float = 0f,
    val y: Float = 0f,
    val button: Int = 0,
    val key: String? = null,
    val ctrl: Boolean = false,
    val meta: Boolean = false,
    val shift: Boolean = false,
    val deltaX: Float = 0f,
    val deltaY: Float = 0f,
    val actionTargetId: String? = null,
)

@Serializable
enum class PracticeTimelineGestureMode {
    TRANSLATE, RESIZE_START, RESIZE_END, SHARED_BOUNDARY, TONAL_START, TONAL_END
}

@Serializable
data class PracticeTimelineGestureState(
    val pointerId: Long,
    val sceneGeneration: Long,
    val mode: PracticeTimelineGestureMode,
    val slotId: String,
    val secondarySlotId: String? = null,
    val startX: Float,
    val originalOnset: Fraction,
    val originalDuration: Fraction,
    val includeFollowing: Boolean = false,
    val openEnded: Boolean = false,
    val moved: Boolean = false,
    val edit: PracticeTimelineEdit? = null,
)

@Serializable
data class PracticeTimelinePlatformEffect(
    val type: String,
    val pointerId: Long? = null,
    val cursor: String? = null,
    val targetId: String? = null,
    val deltaX: Float = 0f,
    val deltaY: Float = 0f,
)

@Serializable
data class PracticeTimelineInteractionResult(
    val accepted: Boolean,
    val reasonKey: String? = null,
    val gesture: PracticeTimelineGestureState? = null,
    val previewEdit: PracticeTimelineEdit? = null,
    val commitEdit: PracticeTimelineEdit? = null,
    val selectSlotId: String? = null,
    val selectTonalLayoutId: String? = null,
    val selectIdiomId: String? = null,
    val removeSlotId: String? = null,
    val appendAt: Fraction? = null,
    val appendDuration: Fraction? = null,
    val effects: List<PracticeTimelinePlatformEffect> = emptyList(),
    /**
     * True when the input simply does not belong to the timeline (no hit target, no active gesture,
     * foreign pointer, unhandled key). Platforms must drop these silently instead of surfacing them
     * as errors — e.g. the pointer release that follows an append click carries no gesture.
     */
    val ignored: Boolean = false,
)

/** Deterministic geometry projector. Platform adapters replay these objects without rescaling x. */
object PracticeTimelineSceneProjector {
    private const val TONAL_TOP = 20f
    private const val TONAL_ROW_HEIGHT = 22f
    private const val TONAL_BAR_HEIGHT = 18f
    private const val MIN_CHORD_HEIGHT = 54f
    private const val IDIOM_ROW_HEIGHT = 24f
    private const val IDIOM_BRACKET_HEIGHT = 16f
    private const val IDIOM_LABEL_FONT_SIZE = 11f
    private const val COMPACT_IDIOM_LABEL_HEIGHT = 15f
    private const val TERMINAL_INSERT_MIN_WIDTH = 56f
    private const val HANDLE_WIDTH = 9f

    /** Below this share of the requested spacing an anchor segment carries no usable position. */
    private const val COLLAPSED_SEGMENT_RATIO = 0.25f

    /** Hover overlays paint above every base object, including the selected-slot chrome. */
    private const val HOVER_Z = 200

    fun project(request: PracticeTimelineSceneRequest): PracticeTimelineScene {
        val generation = generation(request)
        val timeScale = TimeScale(request)
        val workspaceEnd = request.timeline.slots.maxOfOrNull { it.onset + it.duration }
            ?: Fraction.ZERO
        val appendOnset = workspaceEnd
        val displayEnd = maxOf(
            Fraction.HALF,
            request.timeline.end,
        )
        val tonalLanes = tonalLanes(request.timeline, displayEnd)
        val tonalRows = if (request.displayMode == PracticeTimelineDisplayMode.FULL) {
            tonalLanes.laneCount.coerceAtLeast(1)
        } else {
            0
        }
        val tonalRowsHeight = tonalRows * TONAL_ROW_HEIGHT
        val idiomRanges = idiomRanges(request.timeline)
        val compactIdiomLabels = if (request.displayMode == PracticeTimelineDisplayMode.COMPACT) {
            compactIdiomLabels(request.timeline)
        } else {
            emptyMap()
        }
        val compactLabelRows = compactIdiomLabels.values.maxOfOrNull(List<String>::size) ?: 0
        val compactLabelInset = compactLabelRows * COMPACT_IDIOM_LABEL_HEIGHT
        val maximumReadings = request.timeline.slots.maxOfOrNull { it.readings.size.coerceAtLeast(1) } ?: 1
        val chordHeight = maxOf(MIN_CHORD_HEIGHT, 16f * maximumReadings + 12f) + compactLabelInset
        val chordY = TONAL_TOP + tonalRowsHeight
        val idiomLaneCount = if (request.displayMode == PracticeTimelineDisplayMode.FULL) {
            (idiomRanges.maxOfOrNull { it.lane } ?: 0) + 1
        } else {
            0
        }
        val height = 54f + chordHeight + tonalRowsHeight +
            IDIOM_ROW_HEIGHT * (idiomLaneCount - 1).coerceAtLeast(0)
        val intrinsicEnd = request.contentOriginX + maxOf(timeScale.x(displayEnd), request.axisContentEndX)
        // The visible add control is always a separate cell after the closing barline. Its action
        // still inserts at the end of the material, so an existing blank tail is consumed first.
        val appendNaturalX = request.contentOriginX + timeScale.x(request.timeline.end)
        val appendNaturalWidth = (
            timeScale.x(request.timeline.end + request.defaultChordDuration) -
                timeScale.x(request.timeline.end)
            ).coerceAtLeast(TERMINAL_INSERT_MIN_WIDTH)
        val appendBounds = PracticeTimelineBounds(appendNaturalX, chordY, appendNaturalWidth, chordHeight)
        val appendContentEnd = appendBounds.x + appendBounds.width
        // Chords must stay reachable even while a drag preview holds them past the settled score:
        // the notation surface has not been re-laid out yet, so its width cannot cap them. The
        // append affordance is laid out after the final chord as ordinary scrollable content.
        val chordEnd = maxOf(
            request.timeline.end,
            request.timeline.slots.maxOfOrNull { it.onset + it.duration } ?: Fraction.ZERO,
        )
        val reachableEnd = request.contentOriginX + maxOf(timeScale.x(chordEnd), request.axisContentEndX)
        val contentWidth = maxOf(
            if (request.axisSurfaceWidth > 0f) {
                maxOf(request.viewportWidth, request.axisSurfaceWidth)
            } else {
                maxOf(request.viewportWidth, intrinsicEnd)
            },
            reachableEnd,
            appendContentEnd,
        )
        val draw = mutableListOf<PracticeTimelineDrawObject>()
        val hit = mutableListOf<PracticeTimelineHitObject>()
        val hover = mutableMapOf<String, List<PracticeTimelineDrawObject>>()
        val a11y = mutableListOf<PracticeTimelineAccessibilityObject>()
        val palette = request.palette
        val baselineY = chordY + chordHeight + 4f

        draw += PracticeTimelineDrawObject(
            "timeline:baseline",
            PracticeTimelineDrawKind.LINE,
            PracticeTimelineBounds(0f, baselineY, contentWidth, 0f),
            1,
            stroke = palette.borderLight,
            strokeWidth = 1f,
        )
        val measureBoundaryTimes = request.measureBoundaries.mapTo(hashSetOf()) { it.time }
        val fallbackMeasureGrid = request.measureBoundaries.isEmpty()
        val gridCount = ceil(displayEnd.toDouble() / request.gridUnit.toDouble()).toInt()
        repeat(gridCount + 1) { step ->
            val time = request.gridUnit * step
            val x = request.contentOriginX + timeScale.x(time)
            val atMeasure = time == Fraction.ZERO || time in measureBoundaryTimes ||
                (fallbackMeasureGrid && (time / Fraction.HALF).denominator == 1)
            val atBeat = (time / Fraction.QUARTER).denominator == 1
            val tickHeight = if (atMeasure) 16f else if (atBeat) 11f else 6f
            if (atMeasure) draw += PracticeTimelineDrawObject(
                "grid:measure:$step",
                PracticeTimelineDrawKind.LINE,
                PracticeTimelineBounds(x, 16f, 0f, baselineY - 16f),
                1,
                stroke = alpha(palette.border, 0.65f),
                strokeWidth = 1f,
            )
            draw += PracticeTimelineDrawObject(
                "grid:tick:$step",
                PracticeTimelineDrawKind.LINE,
                PracticeTimelineBounds(x, baselineY, 0f, tickHeight),
                2,
                stroke = if (atMeasure) palette.primaryLight else palette.textMuted,
                strokeWidth = if (atMeasure) 1.5f else 1f,
            )
            val radius = if (atBeat) 3f else 2f
            draw += PracticeTimelineDrawObject(
                "grid:dot:$step",
                PracticeTimelineDrawKind.CIRCLE,
                PracticeTimelineBounds(x - radius, 8f - radius, radius * 2f, radius * 2f),
                2,
                fill = alpha(palette.primaryLight, 0.72f),
            )
        }
        val measureStarts = if (request.measureBoundaries.isEmpty()) {
            val measureCount = ceil(displayEnd.toDouble() / Fraction.HALF.toDouble()).toInt()
            List(measureCount) { Fraction.HALF * it }
        } else {
            listOf(Fraction.ZERO) + request.measureBoundaries.map { it.time }
                .filter { it > Fraction.ZERO && it < displayEnd }
                .distinct()
                .sorted()
        }
        measureStarts.forEachIndexed { measureIndex, measureStart ->
            val x = request.contentOriginX + timeScale.x(measureStart)
            draw += PracticeTimelineDrawObject(
                "measure:${measureIndex + 1}",
                PracticeTimelineDrawKind.TEXT,
                PracticeTimelineBounds(x + 4f, chordY - 18f, 32f, 14f),
                8,
                fill = palette.textMuted,
                text = "${measureIndex + 1}",
                fontFamily = "system-ui",
                fontSize = 10f,
            )
        }

        val initialKey = request.timeline.tonalLayouts
            .firstOrNull { it.isBaseline }
            ?.toKey()
            ?: request.timeline.tonalLayouts.firstOrNull()?.toKey()
        if (request.displayMode == PracticeTimelineDisplayMode.FULL) request.timeline.tonalLayouts.forEachIndexed { index, layout ->
            val startX = request.contentOriginX + timeScale.x(layout.start)
            val endX = request.contentOriginX + timeScale.x(layout.end ?: displayEnd)
            val bounds = PracticeTimelineBounds(
                startX,
                TONAL_TOP + tonalLanes.manual[index] * TONAL_ROW_HEIGHT,
                (endX - startX).coerceAtLeast(18f),
                TONAL_BAR_HEIGHT,
            )
            val id = "tonal:${layout.id.value}"
            val key = layout.toKey()
            val accent = accent(layout.fifths, layout.mode, palette)
            val label = buildString {
                append(key.displayName)
                if (layout.mode == WorkspaceKeyMode.MINOR) append('m')
                initialKey?.let { source ->
                    append(" · ")
                    append(ModulationPitchLabels.relativeTonicLabel(source, key))
                    append(" · 圈")
                    append(ModulationCircleOfFifths.signedDistanceLabel(source, key))
                }
            }
            draw += PracticeTimelineDrawObject(
                id,
                PracticeTimelineDrawKind.ROUND_RECT,
                bounds,
                10,
                fill = alpha(accent, 0.12f),
                radius = 6f,
            )
            draw += PracticeTimelineDrawObject(
                "$id:line",
                PracticeTimelineDrawKind.LINE,
                PracticeTimelineBounds(bounds.x + 4f, bounds.y + bounds.height / 2f, (bounds.width - 8f).coerceAtLeast(0f), 0f),
                11,
                stroke = accent,
                strokeWidth = 2f,
            )
            addCenteredLabel(draw, "$id:label", label, bounds, accent, palette, 12)
            hit += PracticeTimelineHitObject(
                id,
                PracticeTimelineHitKind.TONAL_LAYOUT,
                layout.id.value,
                bounds,
                "pointer",
                listOf("select"),
            )
            hover[id] = listOf(
                PracticeTimelineDrawObject("$id:hover", PracticeTimelineDrawKind.ROUND_RECT, bounds,
                    HOVER_Z, stroke = accent, strokeWidth = 1.5f, radius = 6f),
            )
            a11y += PracticeTimelineAccessibilityObject(
                id,
                "button",
                "调性 $label",
                bounds,
                selected = layout.id.value == request.selectedTonalLayoutId,
                actions = listOf("select"),
            )
            if (!layout.isBaseline) {
                val startBounds = PracticeTimelineBounds(startX - HANDLE_WIDTH / 2f, bounds.y, HANDLE_WIDTH, bounds.height)
                draw += PracticeTimelineDrawObject("$id:start:paint", PracticeTimelineDrawKind.ROUND_RECT,
                    startBounds, 14, fill = alpha(accent, 0.36f), radius = 5f)
                hit += PracticeTimelineHitObject("$id:start", PracticeTimelineHitKind.TONAL_START,
                    layout.id.value, startBounds, "ew-resize", listOf("resize"))
                hover["$id:start"] = listOf(handleHighlight("$id:start:hover", startBounds, accent, 5f))
                a11y += PracticeTimelineAccessibilityObject("$id:start", "button", "调整调性线起点",
                    startBounds, actions = listOf("resize"))
            }
            val endBounds = PracticeTimelineBounds(endX - HANDLE_WIDTH / 2f, bounds.y, HANDLE_WIDTH, bounds.height)
            draw += PracticeTimelineDrawObject("$id:end:paint", PracticeTimelineDrawKind.ROUND_RECT,
                endBounds, 14, fill = alpha(accent, 0.50f), radius = 5f)
            hit += PracticeTimelineHitObject("$id:end", PracticeTimelineHitKind.TONAL_END,
                layout.id.value, endBounds, "ew-resize", listOf("resize"))
            hover["$id:end"] = listOf(handleHighlight("$id:end:hover", endBounds, accent, 5f))
            a11y += PracticeTimelineAccessibilityObject("$id:end", "button", "调整调性线终点",
                endBounds, actions = listOf("resize"))
        }

        if (request.displayMode == PracticeTimelineDisplayMode.FULL) request.timeline.derivedTonalSpans.forEachIndexed { derivedIndex, span ->
            val startX = request.contentOriginX + timeScale.x(span.start)
            val endX = request.contentOriginX + timeScale.x(span.end)
            val bounds = PracticeTimelineBounds(
                startX,
                TONAL_TOP + tonalLanes.derived[derivedIndex] * TONAL_ROW_HEIGHT,
                (endX - startX).coerceAtLeast(18f),
                TONAL_BAR_HEIGHT,
            )
            val id = "derived-tonal:$derivedIndex"
            val accent = accent(span.fifths, span.mode, palette)
            draw += PracticeTimelineDrawObject(id, PracticeTimelineDrawKind.ROUND_RECT, bounds, 10,
                fill = alpha(accent, 0.06f), radius = 6f)
            draw += PracticeTimelineDrawObject("$id:line", PracticeTimelineDrawKind.LINE,
                PracticeTimelineBounds(bounds.x + 4f, bounds.y + bounds.height / 2f, (bounds.width - 8f).coerceAtLeast(0f), 0f),
                11, stroke = alpha(accent, 0.90f), strokeWidth = 2f, dashPattern = listOf(5f, 4f))
            addCenteredLabel(draw, "$id:label", "${span.keyLabel} · 自动", bounds, accent, palette, 12)
        }

        val gestureSelectedSlot = request.gesture
            ?.takeUnless { it.mode == PracticeTimelineGestureMode.TONAL_START || it.mode == PracticeTimelineGestureMode.TONAL_END }
            ?.slotId
        val effectiveSelectedSlotId = gestureSelectedSlot ?: request.selectedSlotId
        request.timeline.slots.forEachIndexed { index, slot ->
            val startX = request.contentOriginX + timeScale.x(slot.onset)
            val endX = request.contentOriginX + timeScale.x(slot.onset + slot.duration)
            val bounds = PracticeTimelineBounds(startX, chordY, (endX - startX).coerceAtLeast(2f), chordHeight)
            val id = "slot:${slot.id.value}"
            val selected = slot.id.value == effectiveSelectedSlotId
            val locked = !slot.capabilities.canTranslate
            val fill = when {
                locked -> alpha(palette.orange, if (selected) 0.34f else 0.14f)
                slot.isPivotChord -> alpha(palette.emerald, if (selected) 0.38f else 0.18f)
                slot.symbol == null -> alpha(palette.surfaceLight, if (selected) 0.90f else 0.60f)
                selected -> palette.selectedSurface
                else -> alpha(palette.primaryDark, 0.32f)
            }
            val stroke = when {
                selected -> palette.selectedBorder
                locked -> palette.orange
                slot.isPivotChord -> palette.emeraldLight
                else -> palette.border
            }
            draw += PracticeTimelineDrawObject(
                id,
                PracticeTimelineDrawKind.ROUND_RECT,
                insetHorizontally(bounds, 3f),
                20,
                fill = fill,
                stroke = stroke,
                strokeWidth = if (selected) 2f else 1f,
                radius = 6f,
            )
            val slotLabels = compactIdiomLabels[slot.id.value].orEmpty()
            val slotContentBounds = if (compactLabelInset == 0f) {
                bounds
            } else {
                bounds.copy(
                    y = bounds.y + compactLabelInset,
                    height = bounds.height - compactLabelInset,
                )
            }
            slotLabels.forEachIndexed { labelIndex, title ->
                draw += PracticeTimelineDrawObject(
                    "$id:idiom-label:$labelIndex",
                    PracticeTimelineDrawKind.TEXT,
                    PracticeTimelineBounds(
                        bounds.x + 5f,
                        bounds.y + labelIndex * COMPACT_IDIOM_LABEL_HEIGHT + 2f,
                        (bounds.width - 10f).coerceAtLeast(1f),
                        COMPACT_IDIOM_LABEL_HEIGHT - 2f,
                    ),
                    22,
                    fill = palette.orangeLight,
                    text = title,
                    fontFamily = "system-ui",
                    fontSize = 9f,
                    fontWeight = 600,
                    textAlign = "center",
                )
            }
            addSlotText(draw, id, slot, slotContentBounds, request.toneLabelMode, palette)
            hit += PracticeTimelineHitObject(id, PracticeTimelineHitKind.SLOT, slot.id.value, bounds,
                if (slot.capabilities.canTranslate) "grab" else "pointer",
                listOf("select") + if (slot.capabilities.canTranslate) listOf("translate") else emptyList())
            hover[id] = listOf(
                PracticeTimelineDrawObject("$id:hover", PracticeTimelineDrawKind.ROUND_RECT,
                    insetHorizontally(bounds, 3f), HOVER_Z, stroke = alpha(palette.selectedBorder, 0.80f),
                    strokeWidth = 1.5f, radius = 6f),
            )
            if (slot.capabilities.canResizeStart) {
                val startBounds = PracticeTimelineBounds(startX, chordY, HANDLE_WIDTH, chordHeight)
                draw += PracticeTimelineDrawObject("$id:start:paint", PracticeTimelineDrawKind.RECT,
                    startBounds, 23, fill = alpha(palette.border, 0.50f))
                hit += PracticeTimelineHitObject("$id:start", PracticeTimelineHitKind.SLOT_START,
                    slot.id.value, startBounds, "ew-resize", listOf("resize"))
                hover["$id:start"] = listOf(
                    PracticeTimelineDrawObject("$id:start:hover", PracticeTimelineDrawKind.RECT,
                        startBounds, HOVER_Z, fill = palette.orangeLight),
                )
                a11y += PracticeTimelineAccessibilityObject("$id:start", "button",
                    "调整 ${slot.symbol ?: "和弦 ${index + 1}"} 起点", startBounds, actions = listOf("resize"))
            }
            if (slot.capabilities.canResizeEnd) {
                val endBounds = PracticeTimelineBounds(endX - HANDLE_WIDTH, chordY, HANDLE_WIDTH, chordHeight)
                draw += PracticeTimelineDrawObject("$id:end:paint", PracticeTimelineDrawKind.RECT,
                    endBounds, 23, fill = alpha(palette.border, 0.50f))
                hit += PracticeTimelineHitObject("$id:end", PracticeTimelineHitKind.SLOT_END,
                    slot.id.value, endBounds, "ew-resize", listOf("resize"))
                hover["$id:end"] = listOf(
                    PracticeTimelineDrawObject("$id:end:hover", PracticeTimelineDrawKind.RECT,
                        endBounds, HOVER_Z, fill = palette.orangeLight),
                )
                a11y += PracticeTimelineAccessibilityObject("$id:end", "button",
                    "调整 ${slot.symbol ?: "和弦 ${index + 1}"} 终点", endBounds, actions = listOf("resize"))
            }
            val accessibilityLabel = buildString {
                append(slot.symbol ?: "和弦 ${index + 1}")
                if (slotLabels.isNotEmpty()) append("，惯用进行 ${slotLabels.joinToString("、")}")
            }
            a11y += PracticeTimelineAccessibilityObject(id, "button", accessibilityLabel, bounds,
                selected = selected,
                actions = listOf("select") + if (slot.capabilities.canTranslate) listOf("move") else emptyList())
        }

        request.timeline.emptySlots.forEach { emptySlot ->
            val startX = request.contentOriginX + timeScale.x(emptySlot.onset)
            val endX = request.contentOriginX + timeScale.x(emptySlot.onset + emptySlot.duration)
            val bounds = PracticeTimelineBounds(
                startX,
                chordY,
                (endX - startX).coerceAtLeast(2f),
                chordHeight,
            )
            val painted = insetHorizontally(bounds, 3f)
            val id = "empty-slot:${emptySlot.id}"
            draw += PracticeTimelineDrawObject(
                id,
                PracticeTimelineDrawKind.ROUND_RECT,
                painted,
                20,
                fill = alpha(palette.surfaceDark, 0.55f),
                stroke = alpha(palette.borderLight, 0.55f),
                strokeWidth = 1f,
                radius = 6f,
            )
        }

        request.timeline.slots.zipWithNext().forEach { (left, right) ->
            if (left.onset + left.duration == right.onset && left.capabilities.canResizeEnd && right.capabilities.canResizeStart) {
                val boundaryX = request.contentOriginX + timeScale.x(right.onset)
                val bounds = PracticeTimelineBounds(boundaryX - HANDLE_WIDTH / 2f, chordY, HANDLE_WIDTH, chordHeight)
                hit += PracticeTimelineHitObject("boundary:${left.id.value}", PracticeTimelineHitKind.SHARED_BOUNDARY,
                    left.id.value, bounds, "ew-resize", listOf("resize"), right.id.value)
                val highlighted = request.gesture?.mode == PracticeTimelineGestureMode.SHARED_BOUNDARY &&
                    request.gesture.slotId == left.id.value
                val outer = if (highlighted) palette.white else palette.selectedBorder
                val center = if (highlighted) palette.orangeLight else palette.primaryLight
                draw += PracticeTimelineDrawObject("boundary:${left.id.value}:left", PracticeTimelineDrawKind.LINE,
                    PracticeTimelineBounds(boundaryX - 2f, chordY, 0f, chordHeight), 25, stroke = outer, strokeWidth = 1f)
                draw += PracticeTimelineDrawObject("boundary:${left.id.value}:center", PracticeTimelineDrawKind.LINE,
                    PracticeTimelineBounds(boundaryX, chordY, 0f, chordHeight), 25, stroke = center, strokeWidth = 2f)
                draw += PracticeTimelineDrawObject("boundary:${left.id.value}:right", PracticeTimelineDrawKind.LINE,
                    PracticeTimelineBounds(boundaryX + 2f, chordY, 0f, chordHeight), 25, stroke = outer, strokeWidth = 1f)
                // Hover reuses the drag-active look: white outer rails around an orange centre line.
                hover["boundary:${left.id.value}"] = listOf(
                    boundaryRail("boundary:${left.id.value}:hover:left", boundaryX - 2f, chordY, chordHeight, palette.white, 1f),
                    boundaryRail("boundary:${left.id.value}:hover:center", boundaryX, chordY, chordHeight, palette.orangeLight, 2f),
                    boundaryRail("boundary:${left.id.value}:hover:right", boundaryX + 2f, chordY, chordHeight, palette.white, 1f),
                )
            }
        }

        if (request.displayMode == PracticeTimelineDisplayMode.FULL) idiomRanges.forEach { range ->
            val startX = request.contentOriginX + timeScale.x(range.start)
            val endX = request.contentOriginX + timeScale.x(range.end)
            val y = chordY + chordHeight + 4f + range.lane * IDIOM_ROW_HEIGHT
            val bounds = PracticeTimelineBounds(startX, y, (endX - startX).coerceAtLeast(2f), 22f)
            val bracketBounds = bounds.copy(height = IDIOM_BRACKET_HEIGHT)
            val id = "idiom:${range.id}"
            val idiomColor = if (range.id == request.selectedIdiomId) palette.orangeLight else palette.orange
            draw += PracticeTimelineDrawObject(id, PracticeTimelineDrawKind.BRACKET, bracketBounds, 30,
                stroke = idiomColor, strokeWidth = if (range.id == request.selectedIdiomId) 2f else 1f)
            // The label may be wider than a short musical range. Size its mask from the glyph-like
            // character widths instead of clamping it to the bracket, otherwise every title over
            // that limit gets the same narrow mask and is ellipsized by both platform adapters.
            val labelWidth = idiomLabelWidth(range.title)
            val labelBounds = PracticeTimelineBounds(
                bounds.x + (bounds.width - labelWidth) / 2f,
                bounds.y + 6f,
                labelWidth,
                16f,
            )
            draw += PracticeTimelineDrawObject("$id:text:mask", PracticeTimelineDrawKind.ROUND_RECT,
                labelBounds, 31, fill = palette.surfaceDark, radius = 3f)
            draw += PracticeTimelineDrawObject("$id:text", PracticeTimelineDrawKind.TEXT,
                labelBounds, 32, fill = idiomColor, text = range.title, fontFamily = "system-ui",
                fontSize = IDIOM_LABEL_FONT_SIZE, fontWeight = 600, textAlign = "center")
            hit += PracticeTimelineHitObject(id, PracticeTimelineHitKind.IDIOM, range.id, bounds, "pointer", listOf("select"))
            hover[id] = listOf(
                PracticeTimelineDrawObject("$id:hover", PracticeTimelineDrawKind.BRACKET, bracketBounds,
                    HOVER_Z, stroke = palette.orangeLight, strokeWidth = 2f),
            )
            a11y += PracticeTimelineAccessibilityObject(id, "button", range.title, bounds,
                selected = range.id == request.selectedIdiomId, actions = listOf("select"))
        }

        val orderedSlots = request.timeline.slots.sortedBy { it.onset }
        val gaps = buildList {
            orderedSlots.firstOrNull()?.takeIf { it.onset.isPositive }?.let { add(Fraction.ZERO to it.onset) }
            orderedSlots.zipWithNext().forEach { (left, right) ->
                val gapStart = left.onset + left.duration
                if (gapStart < right.onset) add(gapStart to (right.onset - gapStart))
            }
        }
        gaps.forEachIndexed { index, (onset, duration) ->
            addInsertAffordance(
                draw = draw,
                hit = hit,
                hover = hover,
                a11y = a11y,
                id = "insert-gap:$index",
                onset = onset,
                duration = duration,
                bounds = PracticeTimelineBounds(
                    request.contentOriginX + timeScale.x(onset),
                    chordY,
                    (timeScale.x(onset + duration) - timeScale.x(onset)).coerceAtLeast(18f),
                    chordHeight,
                ),
                palette = palette,
                accessibilityLabel = "插入和弦槽",
            )
        }

        addInsertAffordance(
            draw = draw,
            hit = hit,
            hover = hover,
            a11y = a11y,
            id = "append",
            onset = appendOnset,
            duration = request.defaultChordDuration,
            bounds = appendBounds,
            palette = palette,
            accessibilityLabel = "追加和弦槽",
        )

        request.selectedSlotId?.takeIf { request.showRemoveAction }?.let { selectedId ->
            val selected = request.timeline.slots.firstOrNull { it.id.value == selectedId }
            if (selected?.capabilities?.canRemove == true) {
                val removeBounds = PracticeTimelineBounds(
                    (request.scrollLeft + request.viewportWidth - 118f).coerceAtLeast(request.contentOriginX),
                    1f,
                    110f,
                    18f,
                )
                draw += PracticeTimelineDrawObject("remove:$selectedId", PracticeTimelineDrawKind.ROUND_RECT,
                    removeBounds, 45, fill = palette.surfaceLight, stroke = palette.orange, strokeWidth = 1f, radius = 4f)
                draw += PracticeTimelineDrawObject("remove:$selectedId:text", PracticeTimelineDrawKind.TEXT,
                    removeBounds, 46, fill = palette.orange, text = "删除当前和弦槽", fontFamily = "system-ui",
                    fontSize = 10f, textAlign = "center")
                hit += PracticeTimelineHitObject("remove:$selectedId", PracticeTimelineHitKind.REMOVE_SLOT,
                    selectedId, removeBounds, "pointer", listOf("activate"))
                hover["remove:$selectedId"] = listOf(
                    PracticeTimelineDrawObject("remove:$selectedId:hover", PracticeTimelineDrawKind.ROUND_RECT,
                        removeBounds, HOVER_Z, fill = alpha(palette.orange, 0.22f), stroke = palette.orangeLight,
                        strokeWidth = 1f, radius = 4f),
                )
                a11y += PracticeTimelineAccessibilityObject("remove:$selectedId", "button", "删除当前和弦槽",
                    removeBounds, actions = listOf("activate"))
            }
        }

        return PracticeTimelineScene(
            generation = generation,
            revision = request.revision,
            axisRevision = request.axisRevision,
            viewportWidth = request.viewportWidth,
            contentOriginX = request.contentOriginX,
            contentWidth = contentWidth,
            contentHeight = height,
            scrollExtent = (contentWidth - request.viewportWidth).coerceAtLeast(0f),
            drawObjects = draw.sortedBy(PracticeTimelineDrawObject::z),
            hitObjects = hit,
            // Sorted by descending hit priority so a platform's whole hover rule is "first target
            // whose bounds contain the pointer" — no duplicated priority table on either shell.
            hoverTargets = hit
                .sortedByDescending { timelineHitPriority(it.kind) }
                .map { target ->
                    PracticeTimelineHoverTarget(
                        hitId = target.id,
                        kind = target.kind,
                        targetId = target.targetId,
                        bounds = target.bounds,
                        cursor = target.cursor,
                        overlay = hover[target.id].orEmpty(),
                    )
                },
            accessibility = a11y,
            contentAnchors = PracticeTimelineContentAnchors(
                scoreOriginX = request.contentOriginX,
                timeZeroX = request.contentOriginX + timeScale.x(Fraction.ZERO),
                contentEndX = request.contentOriginX + timeScale.x(request.timeline.end),
                appendX = appendBounds.x,
            ),
            gestureState = request.gesture,
        )
    }

    private fun addCenteredLabel(
        draw: MutableList<PracticeTimelineDrawObject>,
        id: String,
        label: String,
        bounds: PracticeTimelineBounds,
        accent: String,
        palette: PracticeTimelinePalette,
        z: Int,
    ) {
        val width = (label.length * 5.5f + 12f).coerceIn(18f, bounds.width)
        val labelBounds = PracticeTimelineBounds(
            bounds.x + (bounds.width - width) / 2f,
            bounds.y + 1f,
            width,
            (bounds.height - 2f).coerceAtLeast(1f),
        )
        draw += PracticeTimelineDrawObject(
            "$id:background",
            PracticeTimelineDrawKind.ROUND_RECT,
            labelBounds,
            z,
            fill = alpha(palette.surfaceDark, 0.96f),
            stroke = alpha(accent, 0.65f),
            strokeWidth = 1f,
            radius = 4f,
        )
        draw += PracticeTimelineDrawObject(
            "$id:text",
            PracticeTimelineDrawKind.TEXT,
            labelBounds,
            z + 1,
            fill = accent,
            text = label,
            fontFamily = "system-ui",
            fontSize = 9f,
            fontWeight = 700,
            textAlign = "center",
        )
    }

    private fun idiomLabelWidth(label: String): Float =
        label.sumOf { character ->
            when {
                character.isWhitespace() -> IDIOM_LABEL_FONT_SIZE * 0.35
                character.code <= 0x7F -> IDIOM_LABEL_FONT_SIZE * 0.62
                else -> IDIOM_LABEL_FONT_SIZE.toDouble()
            }
        }.toFloat().plus(12f).coerceAtLeast(18f)

    private fun addSlotText(
        draw: MutableList<PracticeTimelineDrawObject>,
        id: String,
        slot: PracticeTimelineSlotView,
        bounds: PracticeTimelineBounds,
        toneMode: PracticeTimelineToneLabelMode,
        palette: PracticeTimelinePalette,
    ) {
        val content = PracticeTimelineBounds(
            bounds.x + HANDLE_WIDTH + 3f,
            bounds.y,
            (bounds.width - (HANDLE_WIDTH + 3f) * 2f).coerceAtLeast(1f),
            bounds.height,
        )
        if (slot.readings.size > 1) {
            val lineHeight = 16f
            val top = bounds.y + (bounds.height - lineHeight * slot.readings.size) / 2f
            slot.readings.forEachIndexed { index, reading ->
                val tones = when (toneMode) {
                    PracticeTimelineToneLabelMode.RELATIVE -> reading.relativeTones
                    PracticeTimelineToneLabelMode.ABSOLUTE -> reading.absoluteTones
                }
                val text = buildString {
                    append(reading.keyLabel)
                    append(": ")
                    append(reading.functionalSymbol)
                    if (tones.isNotEmpty()) {
                        append(" · ")
                        append(tones.joinToString("–"))
                    }
                }
                draw += PracticeTimelineDrawObject(
                    "$id:reading:$index",
                    PracticeTimelineDrawKind.TEXT,
                    PracticeTimelineBounds(content.x, top + index * lineHeight, content.width, lineHeight),
                    22,
                    fill = palette.textPrimary,
                    text = text,
                    fontFamily = "system-ui",
                    fontSize = 10f,
                    fontWeight = 600,
                    textAlign = "center",
                )
            }
        } else {
            val symbol = slot.symbol ?: "选择和弦"
            val tones = when (toneMode) {
                PracticeTimelineToneLabelMode.RELATIVE -> slot.relativeTones
                PracticeTimelineToneLabelMode.ABSOLUTE -> slot.absoluteTones
            }
            val symbolHeight = 18f
            val hasTones = tones.isNotEmpty()
            val symbolY = if (hasTones) bounds.y + bounds.height / 2f - 15f else bounds.y + (bounds.height - symbolHeight) / 2f
            draw += PracticeTimelineDrawObject(
                "$id:symbol",
                PracticeTimelineDrawKind.TEXT,
                PracticeTimelineBounds(content.x, symbolY, content.width, symbolHeight),
                22,
                fill = if (slot.symbol == null) palette.textMuted else palette.textPrimary,
                text = symbol,
                fontFamily = "system-ui",
                fontSize = if (slot.symbol == null) 10f else 13f,
                fontWeight = 700,
                textAlign = "center",
            )
            if (hasTones) draw += PracticeTimelineDrawObject(
                "$id:tones",
                PracticeTimelineDrawKind.TEXT,
                PracticeTimelineBounds(content.x, symbolY + 17f, content.width, 16f),
                22,
                fill = palette.textMuted,
                text = tones.joinToString("–"),
                fontFamily = "system-ui",
                fontSize = 10f,
                textAlign = "center",
            )
        }
        if (slot.isPivotChord && slot.capabilities.canTranslate) draw += PracticeTimelineDrawObject(
            "$id:pivot",
            PracticeTimelineDrawKind.TEXT,
            PracticeTimelineBounds(content.x, bounds.y + bounds.height - 12f, content.width, 10f),
            22,
            fill = palette.emeraldLight,
            text = "枢纽",
            fontFamily = "system-ui",
            fontSize = 8f,
            textAlign = "center",
        )
    }

    private fun addInsertAffordance(
        draw: MutableList<PracticeTimelineDrawObject>,
        hit: MutableList<PracticeTimelineHitObject>,
        a11y: MutableList<PracticeTimelineAccessibilityObject>,
        hover: MutableMap<String, List<PracticeTimelineDrawObject>>,
        id: String,
        onset: Fraction,
        duration: Fraction,
        bounds: PracticeTimelineBounds,
        palette: PracticeTimelinePalette,
        accessibilityLabel: String,
    ) {
        val painted = insetHorizontally(bounds, 3f)
        draw += PracticeTimelineDrawObject(id, PracticeTimelineDrawKind.ROUND_RECT, painted, 40,
            fill = alpha(palette.emerald, 0.14f), stroke = alpha(palette.emerald, 0.60f),
            strokeWidth = 1f, radius = 6f)
        draw += PracticeTimelineDrawObject("$id:text", PracticeTimelineDrawKind.TEXT, painted, 41,
            fill = palette.emeraldLight, text = "＋", fontFamily = "system-ui", fontSize = 18f,
            textAlign = "center")
        hit += PracticeTimelineHitObject(
            id,
            PracticeTimelineHitKind.APPEND,
            id,
            bounds,
            "pointer",
            listOf("activate"),
            insertOnset = onset,
            insertDuration = duration,
        )
        hover[id] = listOf(
            PracticeTimelineDrawObject("$id:hover", PracticeTimelineDrawKind.ROUND_RECT, painted,
                HOVER_Z, fill = alpha(palette.emerald, 0.28f), stroke = palette.emeraldLight,
                strokeWidth = 1.5f, radius = 6f),
        )
        a11y += PracticeTimelineAccessibilityObject(id, "button", accessibilityLabel, bounds,
            actions = listOf("activate"))
    }

    private fun handleHighlight(
        id: String,
        bounds: PracticeTimelineBounds,
        accent: String,
        radius: Float,
    ): PracticeTimelineDrawObject = PracticeTimelineDrawObject(
        id,
        PracticeTimelineDrawKind.ROUND_RECT,
        bounds,
        HOVER_Z,
        fill = alpha(accent, 0.85f),
        radius = radius,
    )

    private fun boundaryRail(
        id: String,
        x: Float,
        top: Float,
        height: Float,
        stroke: String,
        strokeWidth: Float,
    ): PracticeTimelineDrawObject = PracticeTimelineDrawObject(
        id,
        PracticeTimelineDrawKind.LINE,
        PracticeTimelineBounds(x, top, 0f, height),
        HOVER_Z,
        stroke = stroke,
        strokeWidth = strokeWidth,
    )

    private fun PracticeTonalLayoutView.toKey(): ModulationKey = ModulationKey(fifths, mode.toTheory())

    /** Workbench (dark-chrome) rendering of the shared key-accent assignment. */
    private fun accent(
        fifths: Int,
        mode: WorkspaceKeyMode,
        palette: PracticeTimelinePalette,
    ): String = when (HarmonyKeyAccent.of(ModulationKey(fifths, mode.toTheory()))) {
        HarmonyKeyAccent.PRIMARY -> palette.primaryLight
        HarmonyKeyAccent.EMERALD -> palette.emeraldLight
        HarmonyKeyAccent.ORANGE -> palette.orangeLight
    }

    private fun insetHorizontally(bounds: PracticeTimelineBounds, amount: Float): PracticeTimelineBounds =
        PracticeTimelineBounds(
            bounds.x + amount,
            bounds.y,
            (bounds.width - amount * 2f).coerceAtLeast(1f),
            bounds.height,
        )

    private fun alpha(color: String, value: Float): String {
        if (!color.startsWith('#') || (color.length != 7 && color.length != 9)) return color
        val alpha = (value.coerceIn(0f, 1f) * 255f).roundToInt()
            .toString(16)
            .padStart(2, '0')
            .uppercase()
        return color.take(7) + alpha
    }

    private fun generation(request: PracticeTimelineSceneRequest): Long =
        (((request.revision * 31L + request.axisRevision) * 31L + request.viewportWidth.roundToInt()) * 31L +
            request.scrollLeft.roundToInt()) * 31L + request.displayMode.ordinal

    internal class TimeScale(private val request: PracticeTimelineSceneRequest) {
        /**
         * Anchors that can actually carry a musical position.
         *
         * The resolved axis ends with barline anchors collapsed onto the following measure
         * boundary: a whole measure of musical time inside a couple of pixels. Interpolating inside
         * such a segment turns a chord edit into a two-pixel move — a drag preview that cannot be
         * seen — while its inverse turns every pointer pixel into several measures. The requested
         * spacing is the axis' own target density, so a segment that carries a small fraction of it
         * is a degenerate anchor rather than notation spacing; drop that tail and let the
         * extrapolation below take over, which keeps [x] and [time] exact inverses there.
         */
        private val anchors = request.axisAnchors
            .map { anchor ->
                request.measureBoundaries.firstOrNull { it.time == anchor.time }
                    ?.let { boundary -> anchor.copy(x = boundary.x) }
                    ?: anchor
            }
            .sortedBy { it.time }.let { sorted ->
            if (request.pixelsPerWhole <= 0f) return@let sorted
            val firstCollapsed = (1 until sorted.size).firstOrNull { index ->
                collapsed(sorted[index - 1], sorted[index])
            }
            if (firstCollapsed == null) sorted else sorted.take(firstCollapsed)
        }

        private fun collapsed(
            previous: PracticeTimelineAxisAnchor,
            next: PracticeTimelineAxisAnchor,
        ): Boolean {
            val span = (next.time - previous.time).toFloat()
            if (span <= 0f) return false
            return (next.x - previous.x) / span < request.pixelsPerWhole * COLLAPSED_SEGMENT_RATIO
        }

        /**
         * Spacing used outside the anchored range, in x units per whole note.
         *
         * The outermost anchor pair is a poor ruler for it: the trailing barline anchor collapses
         * onto the following measure boundary, so its x gap can be a couple of pixels while its time
         * gap is a whole measure. Extrapolating from that pair made the pointer jump several
         * measures per pixel past the content end and squeezed the trailing chord of a Ctrl-drag.
         *
         * Beyond the score there is no notation to space, so the requested proportional spacing is
         * the right ruler — widened to the axis' own mean density when the resolver had to stretch
         * the score past it, which keeps the extension from looking compressed against its
         * neighbours. Both bounds are global, so [x] and [time] stay exact inverses on either side.
         */
        private val extrapolationUnitsPerWhole: Float = run {
            val first = anchors.firstOrNull()
            val last = anchors.lastOrNull()
            val span = if (first == null || last == null) 0f else (last.time - first.time).toFloat()
            val distance = if (first == null || last == null) 0f else last.x - first.x
            val mean = if (span > 0f && distance > 0f) distance / span else 0f
            maxOf(mean, request.pixelsPerWhole).takeIf { it > 0f } ?: 1f
        }

        fun x(time: Fraction): Float {
            if (anchors.size < 2) return time.toFloat() * request.pixelsPerWhole
            val first = anchors.first()
            val last = anchors.last()
            if (time <= first.time) {
                return first.x - (first.time - time).toFloat() * extrapolationUnitsPerWhole
            }
            if (time >= last.time) {
                return last.x + (time - last.time).toFloat() * extrapolationUnitsPerWhole
            }
            val before = anchors.last { it.time <= time }
            val after = anchors.first { it.time >= time }
            if (before.time == after.time) return before.x
            val ratio = (time - before.time).toFloat() / (after.time - before.time).toFloat()
            return before.x + (after.x - before.x) * ratio
        }

        fun time(x: Float): Fraction {
            if (anchors.size < 2) return decimalFraction(x / request.pixelsPerWhole)
            val first = anchors.first()
            val last = anchors.last()
            if (x <= first.x) {
                return first.time - decimalFraction((first.x - x) / extrapolationUnitsPerWhole)
            }
            if (x >= last.x) {
                return last.time + decimalFraction((x - last.x) / extrapolationUnitsPerWhole)
            }
            val before = anchors.last { it.x <= x }
            val after = anchors.first { it.x >= x }
            if (abs(after.x - before.x) < 0.0001f) return before.time
            val ratio = (x - before.x) / (after.x - before.x)
            return before.time + decimalFraction((after.time - before.time).toFloat() * ratio)
        }
    }

    private data class IdiomRange(val id: String, val title: String, val start: Fraction, val end: Fraction, val lane: Int)

    private data class TonalLanes(
        val manual: List<Int>,
        val derived: List<Int>,
        val laneCount: Int,
    )

    private data class TonalInterval(
        val sourceIndex: Int,
        val derived: Boolean,
        val start: Fraction,
        val end: Fraction,
    )

    /**
     * Packs non-overlapping manual and derived tonal spans onto the same row.
     *
     * The packing itself is [LanePacker], shared with the score-analysis harmony timeline; only the
     * placement order is local, because this surface packs two parallel view lists whose identity is
     * their position rather than a stable string id.
     */
    private fun tonalLanes(timeline: PracticeTimelineView, displayEnd: Fraction): TonalLanes {
        val intervals = buildList {
            timeline.tonalLayouts.forEachIndexed { index, layout ->
                add(TonalInterval(index, false, layout.start, layout.end ?: displayEnd))
            }
            timeline.derivedTonalSpans.forEachIndexed { index, span ->
                add(TonalInterval(index, true, span.start, span.end))
            }
        }
        val lanes = LanePacker.pack(
            size = intervals.size,
            order = intervals.indices.sortedWith(
                compareBy<Int> { intervals[it].start }
                    .thenByDescending { intervals[it].end }
                    .thenBy { intervals[it].derived }
                    .thenBy { intervals[it].sourceIndex },
            ),
            start = { intervals[it].start },
            end = { intervals[it].end },
        )
        val manual = MutableList(timeline.tonalLayouts.size) { 0 }
        val derived = MutableList(timeline.derivedTonalSpans.size) { 0 }
        intervals.forEachIndexed { index, interval ->
            if (interval.derived) {
                derived[interval.sourceIndex] = lanes[index]
            } else {
                manual[interval.sourceIndex] = lanes[index]
            }
        }
        return TonalLanes(manual, derived, LanePacker.laneCount(lanes))
    }

    private fun compactIdiomLabels(timeline: PracticeTimelineView): Map<String, List<String>> =
        timeline.idioms.mapNotNull { idiom ->
            val firstSlot = timeline.slots
                .filter { it.id in idiom.slotIds }
                .minByOrNull { it.onset }
                ?: return@mapNotNull null
            firstSlot.id.value to (idiom.title ?: idiom.definitionId)
        }.groupBy({ it.first }, { it.second })

    /** Bracket rows for idiom instances; packed in catalog order, not sorted. */
    private fun idiomRanges(timeline: PracticeTimelineView): List<IdiomRange> {
        val resolved = timeline.idioms.mapNotNull { idiom ->
            val members = timeline.slots.filter { it.id in idiom.slotIds }
            val start = idiom.start ?: members.minOfOrNull { it.onset } ?: return@mapNotNull null
            val end = idiom.end ?: members.maxOfOrNull { it.onset + it.duration } ?: return@mapNotNull null
            Triple(idiom, start, end)
        }
        val lanes = LanePacker.pack(
            size = resolved.size,
            order = resolved.indices.toList(),
            start = { resolved[it].second },
            end = { resolved[it].third },
        )
        return resolved.mapIndexed { index, (idiom, start, end) ->
            IdiomRange(idiom.id.value, idiom.title ?: idiom.definitionId, start, end, lanes[index])
        }
    }

    internal fun decimalFraction(value: Float): Fraction = Fraction((value * 1_000_000f).roundToInt(), 1_000_000).simplified()
}

/** Shared hit-testing, quantization and gesture reducer. */
object FreePracticeTimelineController {
    fun handle(scene: PracticeTimelineScene, request: PracticeTimelineSceneRequest, input: PracticeTimelineInput): PracticeTimelineInteractionResult {
        val pointerLifecycle = input.type == PracticeTimelineInputType.MOVE ||
            input.type == PracticeTimelineInputType.UP ||
            input.type == PracticeTimelineInputType.CANCEL
        val continuesActiveGesture = pointerLifecycle && request.gesture?.pointerId == input.pointerId
        if (input.sceneGeneration != scene.generation && !continuesActiveGesture) {
            // MOVE/UP can already be queued when a preview, scroll or resize reprojects the scene.
            // They never hit-test again: the gesture owns a stable target and original music bounds,
            // so the matching pointer must be allowed to finish against the current request. A stale
            // lifecycle event without an active gesture is harmless and must not become a UI alert.
            return if (pointerLifecycle) ignored("stale_scene")
            else PracticeTimelineInteractionResult(false, "stale_scene")
        }
        return when (input.type) {
            PracticeTimelineInputType.DOWN -> down(scene, request, input)
            PracticeTimelineInputType.MOVE -> move(request, input)
            PracticeTimelineInputType.UP -> up(request, input)
            PracticeTimelineInputType.CANCEL -> PracticeTimelineInteractionResult(true,
                effects = listOf(PracticeTimelinePlatformEffect("releasePointer", input.pointerId)))
            PracticeTimelineInputType.ACTIVATE -> activate(scene, request, input)
            PracticeTimelineInputType.KEY -> key(scene, request, input)
            PracticeTimelineInputType.WHEEL -> PracticeTimelineInteractionResult(true,
                effects = listOf(PracticeTimelinePlatformEffect("scroll", deltaX = input.deltaX, deltaY = input.deltaY)))
        }
    }

    private fun down(scene: PracticeTimelineScene, request: PracticeTimelineSceneRequest, input: PracticeTimelineInput): PracticeTimelineInteractionResult {
        if (input.button != 0 || input.pointerId == null) return ignored("unsupported_pointer")
        val target = scene.hitObjects.filter { it.bounds.contains(input.x, input.y) }
            .maxByOrNull { hitPriority(it.kind) }
            ?: return ignored("no_target")
        if (target.kind == PracticeTimelineHitKind.APPEND || target.kind == PracticeTimelineHitKind.REMOVE_SLOT) {
            return activate(scene, request, input.copy(actionTargetId = target.id))
        }
        if (target.kind == PracticeTimelineHitKind.TONAL_LAYOUT) return PracticeTimelineInteractionResult(true, selectTonalLayoutId = target.targetId)
        if (target.kind == PracticeTimelineHitKind.IDIOM) return PracticeTimelineInteractionResult(true, selectIdiomId = target.targetId)
        if (target.kind == PracticeTimelineHitKind.TONAL_START || target.kind == PracticeTimelineHitKind.TONAL_END) {
            val layout = request.timeline.tonalLayouts.firstOrNull { it.id.value == target.targetId }
                ?: return PracticeTimelineInteractionResult(false, "missing_target")
            val end = layout.end ?: request.timeline.end
            val gesture = PracticeTimelineGestureState(
                pointerId = input.pointerId,
                sceneGeneration = scene.generation,
                mode = if (target.kind == PracticeTimelineHitKind.TONAL_START) {
                    PracticeTimelineGestureMode.TONAL_START
                } else {
                    PracticeTimelineGestureMode.TONAL_END
                },
                slotId = layout.id.value,
                startX = input.x,
                originalOnset = layout.start,
                originalDuration = end - layout.start,
                openEnded = layout.end == null,
            )
            return PracticeTimelineInteractionResult(
                true,
                gesture = gesture,
                selectTonalLayoutId = layout.id.value,
                effects = listOf(PracticeTimelinePlatformEffect("capturePointer", input.pointerId, target.cursor, target.id)),
            )
        }
        val slot = request.timeline.slots.firstOrNull { it.id.value == target.targetId }
            ?: return PracticeTimelineInteractionResult(false, "missing_target")
        val mode = when (target.kind) {
            PracticeTimelineHitKind.SLOT -> PracticeTimelineGestureMode.TRANSLATE
            PracticeTimelineHitKind.SLOT_START -> PracticeTimelineGestureMode.RESIZE_START
            PracticeTimelineHitKind.SLOT_END -> PracticeTimelineGestureMode.RESIZE_END
            PracticeTimelineHitKind.SHARED_BOUNDARY -> PracticeTimelineGestureMode.SHARED_BOUNDARY
            else -> return PracticeTimelineInteractionResult(true, selectSlotId = slot.id.value)
        }
        val gesture = PracticeTimelineGestureState(input.pointerId, scene.generation, mode, slot.id.value,
            target.secondaryTargetId, input.x, slot.onset, slot.duration, input.ctrl || input.meta)
        return PracticeTimelineInteractionResult(true, gesture = gesture, selectSlotId = slot.id.value,
            effects = listOf(PracticeTimelinePlatformEffect("capturePointer", input.pointerId, target.cursor, target.id)))
    }

    private fun move(request: PracticeTimelineSceneRequest, input: PracticeTimelineInput): PracticeTimelineInteractionResult {
        val gesture = request.gesture ?: return ignored("no_gesture")
        if (input.pointerId != gesture.pointerId) return ignored("wrong_pointer")
        val scale = PracticeTimelineSceneProjector.TimeScale(request)
        val delta = snap(scale.time(input.x - request.contentOriginX) - scale.time(gesture.startX - request.contentOriginX), request.gridUnit)
        val minDuration = request.gridUnit
        val edit = when (gesture.mode) {
            PracticeTimelineGestureMode.TRANSLATE -> PracticeTimelineEdit.TranslateChordRange(
                WorkspaceSlotId(gesture.slotId), delta, gesture.includeFollowing)
            PracticeTimelineGestureMode.RESIZE_START -> {
                val onset = (gesture.originalOnset + delta).coerceIn(Fraction.ZERO, gesture.originalOnset + gesture.originalDuration - minDuration)
                PracticeTimelineEdit.PlaceChordRange(WorkspaceSlotId(gesture.slotId), onset,
                    gesture.originalOnset + gesture.originalDuration - onset)
            }
            PracticeTimelineGestureMode.RESIZE_END -> PracticeTimelineEdit.PlaceChordRange(
                WorkspaceSlotId(gesture.slotId), gesture.originalOnset,
                maxOf(minDuration, gesture.originalDuration + delta))
            PracticeTimelineGestureMode.SHARED_BOUNDARY -> PracticeTimelineEdit.MoveSharedBoundary(
                WorkspaceSlotId(gesture.slotId), snap(gesture.originalOnset + gesture.originalDuration + delta, request.gridUnit))
            PracticeTimelineGestureMode.TONAL_START -> {
                val end = gesture.originalOnset + gesture.originalDuration
                val start = (gesture.originalOnset + delta).coerceIn(Fraction.ZERO, end - minDuration)
                PracticeTimelineEdit.SetTonalLayoutBounds(
                    WorkspaceTonalLayoutId(gesture.slotId),
                    start,
                    end.takeUnless { gesture.openEnded },
                )
            }
            PracticeTimelineGestureMode.TONAL_END -> PracticeTimelineEdit.SetTonalLayoutBounds(
                WorkspaceTonalLayoutId(gesture.slotId),
                gesture.originalOnset,
                maxOf(
                    gesture.originalOnset + minDuration,
                    gesture.originalOnset + gesture.originalDuration + delta,
                ),
            )
        }
        val changed = edit != gesture.edit
        val next = gesture.copy(moved = gesture.moved || delta != Fraction.ZERO, edit = edit)
        val cursor = if (gesture.mode == PracticeTimelineGestureMode.TRANSLATE) "grab" else "ew-resize"
        return PracticeTimelineInteractionResult(true, gesture = next, previewEdit = edit.takeIf { changed },
            effects = listOf(PracticeTimelinePlatformEffect("cursor", input.pointerId, cursor)))
    }

    private fun up(request: PracticeTimelineSceneRequest, input: PracticeTimelineInput): PracticeTimelineInteractionResult {
        val gesture = request.gesture ?: return ignored("no_gesture")
        if (input.pointerId != gesture.pointerId) return ignored("wrong_pointer")
        val tonal = gesture.mode == PracticeTimelineGestureMode.TONAL_START ||
            gesture.mode == PracticeTimelineGestureMode.TONAL_END
        return PracticeTimelineInteractionResult(true, commitEdit = gesture.edit.takeIf { gesture.moved },
            selectSlotId = gesture.slotId.takeUnless { tonal },
            selectTonalLayoutId = gesture.slotId.takeIf { tonal },
            effects = listOf(PracticeTimelinePlatformEffect("releasePointer", gesture.pointerId)))
    }

    private fun activate(scene: PracticeTimelineScene, request: PracticeTimelineSceneRequest, input: PracticeTimelineInput): PracticeTimelineInteractionResult {
        val id = input.actionTargetId ?: return PracticeTimelineInteractionResult(false, "missing_target")
        val target = scene.hitObjects.firstOrNull { it.id == id } ?: return PracticeTimelineInteractionResult(false, "missing_target")
        return when (target.kind) {
            PracticeTimelineHitKind.APPEND -> PracticeTimelineInteractionResult(
                true,
                appendAt = target.insertOnset ?: request.timeline.end,
                appendDuration = target.insertDuration ?: request.defaultChordDuration,
            )
            PracticeTimelineHitKind.SLOT -> PracticeTimelineInteractionResult(true, selectSlotId = target.targetId)
            PracticeTimelineHitKind.TONAL_LAYOUT -> PracticeTimelineInteractionResult(true, selectTonalLayoutId = target.targetId)
            PracticeTimelineHitKind.IDIOM -> PracticeTimelineInteractionResult(true, selectIdiomId = target.targetId)
            PracticeTimelineHitKind.REMOVE_SLOT -> PracticeTimelineInteractionResult(true, removeSlotId = target.targetId)
            else -> PracticeTimelineInteractionResult(false, "unsupported_action")
        }
    }

    private fun key(scene: PracticeTimelineScene, request: PracticeTimelineSceneRequest, input: PracticeTimelineInput): PracticeTimelineInteractionResult {
        if (input.key == "Escape") return PracticeTimelineInteractionResult(true,
            effects = listOf(PracticeTimelinePlatformEffect("releasePointer", request.gesture?.pointerId)))
        if (input.key == "Enter" || input.key == " ") return activate(scene, request, input.copy(type = PracticeTimelineInputType.ACTIVATE))
        val target = input.actionTargetId?.let { id -> scene.hitObjects.firstOrNull { it.id == id } }
        val keyDelta = when (input.key) {
            "ArrowLeft" -> -request.gridUnit
            "ArrowRight" -> request.gridUnit
            else -> null
        }
        if (keyDelta != null && target?.kind in setOf(PracticeTimelineHitKind.TONAL_START, PracticeTimelineHitKind.TONAL_END)) {
            val layout = request.timeline.tonalLayouts.firstOrNull { it.id.value == target?.targetId }
                ?: return PracticeTimelineInteractionResult(false, "missing_target")
            val end = layout.end ?: request.timeline.end
            val edit = if (target?.kind == PracticeTimelineHitKind.TONAL_START) {
                val start = (layout.start + keyDelta).coerceIn(Fraction.ZERO, end - request.gridUnit)
                PracticeTimelineEdit.SetTonalLayoutBounds(layout.id, start, layout.end)
            } else {
                PracticeTimelineEdit.SetTonalLayoutBounds(
                    layout.id,
                    layout.start,
                    maxOf(layout.start + request.gridUnit, end + keyDelta),
                )
            }
            return PracticeTimelineInteractionResult(
                true,
                commitEdit = edit,
                selectTonalLayoutId = layout.id.value,
            )
        }
        val slot = target?.let { hit -> request.timeline.slots.firstOrNull { it.id.value == hit.targetId } }
        if ((input.key == "ArrowLeft" || input.key == "ArrowRight") && slot != null) {
            val delta = if (input.key == "ArrowLeft") -request.gridUnit else request.gridUnit
            val edit = when (target?.kind) {
                PracticeTimelineHitKind.SLOT -> slot.capabilities.canTranslate.takeIf { it }?.let {
                    PracticeTimelineEdit.TranslateChordRange(slot.id, delta, input.ctrl || input.meta)
                }
                PracticeTimelineHitKind.SLOT_START -> slot.capabilities.canResizeStart.takeIf { it }?.let {
                    val onset = (slot.onset + delta).coerceIn(
                        Fraction.ZERO,
                        slot.onset + slot.duration - request.gridUnit,
                    )
                    PracticeTimelineEdit.PlaceChordRange(
                        slot.id,
                        onset,
                        slot.onset + slot.duration - onset,
                    )
                }
                PracticeTimelineHitKind.SLOT_END -> slot.capabilities.canResizeEnd.takeIf { it }?.let {
                    PracticeTimelineEdit.PlaceChordRange(
                        slot.id,
                        slot.onset,
                        maxOf(request.gridUnit, slot.duration + delta),
                    )
                }
                PracticeTimelineHitKind.SHARED_BOUNDARY -> PracticeTimelineEdit.MoveSharedBoundary(
                    slot.id,
                    snap(slot.onset + slot.duration + delta, request.gridUnit),
                )
                else -> null
            } ?: return ignored("unsupported_key")
            return PracticeTimelineInteractionResult(true, commitEdit = edit, selectSlotId = slot.id.value)
        }
        if ((input.key == "Delete" || input.key == "Backspace") && slot?.capabilities?.canRemove == true) {
            return PracticeTimelineInteractionResult(true, removeSlotId = slot.id.value)
        }
        return ignored("unsupported_key")
    }

    /** Input that is not addressed to the timeline; rejected without being reported as an error. */
    private fun ignored(reason: String): PracticeTimelineInteractionResult =
        PracticeTimelineInteractionResult(false, reason, ignored = true)

    private fun snap(value: Fraction, grid: Fraction): Fraction {
        val steps = (value.toDouble() / grid.toDouble()).roundToInt()
        return grid * steps
    }

    /**
     * Pointer feedback for [x], [y] against a projected scene. Desktop calls this directly; the
     * browser shell replays the same rule over the pre-sorted [PracticeTimelineScene.hoverTargets]
     * so hover never has to round-trip through the engine Worker.
     */
    fun hoverTarget(scene: PracticeTimelineScene, x: Float, y: Float): PracticeTimelineHoverTarget? =
        scene.hoverTargets.firstOrNull { it.bounds.contains(x, y) }

    private fun hitPriority(kind: PracticeTimelineHitKind): Int = timelineHitPriority(kind)
}

private fun timelineHitPriority(kind: PracticeTimelineHitKind): Int = when (kind) {
    PracticeTimelineHitKind.SHARED_BOUNDARY -> 120
    PracticeTimelineHitKind.SLOT_START, PracticeTimelineHitKind.SLOT_END,
    PracticeTimelineHitKind.TONAL_START, PracticeTimelineHitKind.TONAL_END -> 110
    PracticeTimelineHitKind.REMOVE_SLOT -> 100
    PracticeTimelineHitKind.SLOT -> 80
    PracticeTimelineHitKind.TONAL_LAYOUT, PracticeTimelineHitKind.IDIOM -> 60
    PracticeTimelineHitKind.APPEND -> 40
}
