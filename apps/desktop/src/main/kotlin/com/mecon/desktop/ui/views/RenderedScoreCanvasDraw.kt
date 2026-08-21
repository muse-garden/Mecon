package com.mecon.desktop.ui.views

import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.*
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mecon.api.interaction.*
import com.mecon.api.primitive.EventId
import com.mecon.api.primitive.Pitch
import com.mecon.api.primitive.TimeCode
import com.mecon.api.render.RenderColor
import com.mecon.api.runtime.orderedStaffs
import com.mecon.api.runtime.RuntimeScore
import com.mecon.api.storage.tracks.Clef
import com.mecon.audio.engine.PlaybackState
import com.mecon.desktop.ui.views.drag.*
import com.mecon.desktop.uikit.theme.MeconColors
import com.mecon.renderer.geometry.*
import com.mecon.renderer.interaction.StyleSnapshot
import com.mecon.renderer.render.*
import com.mecon.renderer.render.edit.GhostClef
import com.mecon.renderer.render.edit.GhostExpressionSpan
import com.mecon.renderer.render.edit.GhostKeySignature
import com.mecon.renderer.render.edit.GhostNote
import com.mecon.renderer.render.edit.GhostTimeSignature
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

internal fun DrawScope.drawRenderedScore(request: RenderedScoreCanvasDrawRequest) {
    val composeRendererSource = request.render.renderer
    val renderResult = request.render.result
    val textMeasurer = request.render.textMeasurer
    val displayStyleSnapshot = request.render.styleSnapshot
    val density = request.render.density
    val pages = request.page.pages
    val pageSlots = request.page.pageSlots
    val editorMarkersByPage = request.page.markersByPage
    val editorMarkers = request.page.continuousMarkers
    val stalePageIndices = request.page.stalePageIndices
    val paginatedView = request.page.paginated
    val showEditorMarkers = request.page.showEditorMarkers
    val preparingLoadedDocument = request.page.preparingLoadedDocument
    val score = request.selection.score
    val selection = request.selection.selection
    val highlightedElements = request.selection.highlightedElements
    val selectedAnnotationEventId = request.selection.selectedAnnotationEventId
    val resizableAnnotationEventIds = request.selection.resizableAnnotationEventIds
    val noteheadBackgroundGroups = request.selection.noteheadBackgroundGroups
    val noteheadCenterMarkers = request.selection.noteheadCenterMarkers
    val noteSelectionLabels = request.selection.noteSelectionLabels
    val ghost = request.ghosts.note
    val clefGhost = request.ghosts.clef
    val timeGhost = request.ghosts.timeSignature
    val keyGhost = request.ghosts.keySignature
    val expressionSpanGhost = request.ghosts.expressionSpan
    val ghostColor = request.ghosts.color
    val annotationRangeDrag = request.drags.annotationRange
    val playbackState = request.playback.state
    val tickToXMapping = request.playback.tickToX
    val scorePositionTicks = request.playback.positionTicks
    val documentVersion = request.lifecycle.documentVersion
    val readyFrameScheduledVersion = request.lifecycle.readyFrameScheduledVersion
    val scope = request.lifecycle.scope
    val currentOnDocumentInteractive = request.lifecycle.onDocumentInteractive
    val scale = request.scale
    val offset = request.offset
    // composeRenderer is non-null whenever renderResult is (both derive from the
    // loaded font); guard for the compiler and skip the frame otherwise.
    val composeRenderer = composeRendererSource ?: return

    // Visible viewport in design-space (the coord space of element.hitBox, i.e. value
    // units before the renderer's density scale). screen_px = design*density*scale +
    // offset  ⇒  design = (screen - offset)/(scale*density). The drawn picture is far
    // larger than the viewport, so we cull to the visible rect (+ margin) to keep each
    // draw to ~one screenful. Reading offset/scale here invalidates only the draw (not
    // recomposition), and the layer already redraws on every pan frame regardless.
    val invSD = 1f / (scale * density)
    val cullMargin = 200f
    val visibleRect = com.mecon.renderer.geometry.AbsoluteRect(
        origin = com.mecon.renderer.geometry.AbsolutePoint(
            Pixels((-offset.x) * invSD - cullMargin),
            Pixels((-offset.y) * invSD - cullMargin),
        ),
        width = Pixels(size.width * invSD + cullMargin * 2),
        height = Pixels(size.height * invSD + cullMargin * 2),
    )

    // Merge selection snapshot with external style overrides.
    // sectionIndex is null while the render is still in-flight (streaming pages only);
    // drawCachedPage accepts null and skips style-override application in that case.
    val sectionIndex = renderResult?.sectionIndex
    val snapshot = displayStyleSnapshot.takeIf { !it.isEmpty }

    // Screen top-left for an element bounds, mapping global → laid-out page space
    // in paginated mode. Returns null when the bounds fall outside every page.
    fun screenTopLeft(bounds: com.mecon.renderer.geometry.AbsoluteRect): Offset? =
        if (paginatedView) {
            globalToDesign(bounds.origin.x.value, bounds.origin.y.value, pages, pageSlots)
                ?.let { Offset(it.x * density, it.y * density) }
        } else {
            Offset(bounds.origin.x.value * density, bounds.origin.y.value * density)
        }

    fun DrawScope.drawNoteheadBackgrounds(pageIndex: Int? = null) {
        if (renderResult == null || noteheadBackgroundGroups.isEmpty()) return
        val noteheadElements = renderResult.elements.filter { it.type == RenderElementType.NOTEHEAD }
        val elementsByNote = noteheadElements
            .mapNotNull { element ->
                val note = renderResult.sectionIndex.sectionsFor(element.id)
                    .filterIsInstance<VoiceNoteSection>()
                    .firstOrNull()
                    ?: return@mapNotNull null
                com.mecon.api.storage.NoteRef(note.event.id, note.pitchIndex) to element
            }
            .groupBy({ it.first }, { it.second })

        fun pageFor(bounds: AbsoluteRect): Int? = pages.indexOfFirst { page ->
            val pageTop = page.contentOffsetY.value
            bounds.origin.y.value in pageTop..(pageTop + page.height.value)
        }.takeIf { it >= 0 }

        fun localPoint(x: Float, y: Float): Offset? {
            val design = if (paginatedView) {
                globalToDesign(x, y, pages, pageSlots)
            } else {
                Offset(x, y)
            } ?: return null
            return if (pageIndex != null) design - pageSlots[pageIndex] else design
        }

        noteheadBackgroundGroups.forEach { group ->
            val blobs = group.notes
                .flatMap { note -> elementsByNote[note].orEmpty().map { note.eventId to it } }
                .groupBy({ it.first }, { it.second })
                .values
                .map { elements ->
                    val left = elements.minOf { it.hitBox.origin.x.value }
                    val top = elements.minOf { it.hitBox.origin.y.value }
                    val right = elements.maxOf { it.hitBox.bottomRight.x.value }
                    val bottom = elements.maxOf { it.hitBox.bottomRight.y.value }
                    NoteheadBackgroundBlob(
                        systemIndex = elements.firstOrNull()?.systemIndex,
                        staffIndex = elements.firstOrNull()?.staffIndex,
                        bounds = AbsoluteRect(
                            origin = AbsolutePoint(Pixels(left), Pixels(top)),
                            width = Pixels(right - left),
                            height = Pixels(bottom - top),
                        ),
                    )
                }
                .filter { pageIndex == null || pageFor(it.bounds) == pageIndex }

            // A narrow, rounded S-curve ties consecutive events into one visual object. Connections
            // stay inside a system/staff lane; system breaks start a fresh segment instead of drawing
            // a distracting diagonal across the page.
            val connectorColor = Color(group.color.toArgb()).let {
                it.copy(alpha = it.alpha * 0.78f)
            }
            blobs
                .groupBy { it.systemIndex to it.staffIndex }
                .values
                .forEach { lane ->
                    lane.sortedBy { it.bounds.origin.x.value }
                        .zipWithNext()
                        .forEach { (from, to) ->
                            val start = localPoint(
                                from.bounds.center.x.value,
                                from.bounds.center.y.value,
                            ) ?: return@forEach
                            val end = localPoint(
                                to.bounds.center.x.value,
                                to.bounds.center.y.value,
                            ) ?: return@forEach
                            val startPx = start * density
                            val endPx = end * density
                            val middleX = (startPx.x + endPx.x) / 2f
                            val path = Path().apply {
                                moveTo(startPx.x, startPx.y)
                                cubicTo(
                                    middleX,
                                    startPx.y,
                                    middleX,
                                    endPx.y,
                                    endPx.x,
                                    endPx.y,
                                )
                            }
                            drawPath(
                                path = path,
                                color = connectorColor,
                                style = Stroke(width = 5f * density, cap = StrokeCap.Round),
                            )
                        }
                }

            blobs.forEach { blob ->
                val elementsBounds = blob.bounds
                val pageAdjusted = localPoint(
                    elementsBounds.origin.x.value,
                    elementsBounds.origin.y.value,
                ) ?: return@forEach
                val paddingX = 2.5f * density
                val paddingY = 1.5f * density
                drawRoundRect(
                    color = Color(group.color.toArgb()),
                    topLeft = Offset(
                        pageAdjusted.x * density - paddingX,
                        pageAdjusted.y * density - paddingY,
                    ),
                    size = Size(
                        elementsBounds.width.value * density + paddingX * 2f,
                        elementsBounds.height.value * density + paddingY * 2f,
                    ),
                    cornerRadius = CornerRadius(4.5f * density),
                )
            }
        }
    }

    fun DrawScope.drawNoteheadCenterMarkers(pageIndex: Int? = null) {
        if (noteheadCenterMarkers.isEmpty()) return

        fun pageFor(y: Float): Int? = pages.indexOfFirst { page ->
            val pageTop = page.contentOffsetY.value
            y in pageTop..(pageTop + page.height.value)
        }.takeIf { it >= 0 }

        noteheadCenterMarkers.forEach { marker ->
            if (pageIndex != null && pageFor(marker.center.y.value) != pageIndex) return@forEach
            val design = if (paginatedView) {
                globalToDesign(marker.center.x.value, marker.center.y.value, pages, pageSlots)
            } else {
                Offset(marker.center.x.value, marker.center.y.value)
            } ?: return@forEach
            val local = if (pageIndex != null) design - pageSlots[pageIndex] else design
            drawCircle(
                color = Color(marker.color.toArgb()),
                radius = marker.radius.value * density,
                center = local * density,
            )
        }
    }

    // Render the score with style overrides applied.
    if (paginatedView) {
        // Each page is its own paper sheet: shadow + white fill + border, then
        // the page's page-local elements drawn at the arrangement slot. Per-page Skia
        // Picture cache: pages reused by reference across an edit replay unchanged,
        // only the edited page re-records. Drop caches for pages that no longer exist.
        composeRenderer.prunePageCaches(pages.mapTo(HashSet()) { it.pageIndex })
        for (i in pages.indices) {
            val page = pages[i]
            val slot = pageSlots[i]
            // Skip pages fully outside the viewport; element-level culling for visible
            // pages is handled by each page Picture's RTree at replay time.
            val pageVisX0 = visibleRect.origin.x.value - slot.x
            val pageVisY0 = visibleRect.origin.y.value - slot.y
            val pageOutside = pageVisX0 > page.width.value || pageVisY0 > page.height.value ||
                pageVisX0 + visibleRect.width.value < 0f || pageVisY0 + visibleRect.height.value < 0f
            // A document load is not interaction-ready until every page Picture is
            // recorded. Otherwise the first pan pays the lazy-recording cost as new
            // pages enter the viewport and visibly stalls on large scores.
            if (pageOutside && !preparingLoadedDocument) continue
            translate(left = slot.x * density, top = slot.y * density) {
                val w = page.width.value * density
                val h = page.height.value * density
                drawRect(
                    color = Color(0x22000000),
                    topLeft = Offset(3f, 3f),
                    size = Size(w, h)
                )
                drawRect(color = Color.White, topLeft = Offset.Zero, size = Size(w, h))
                drawRect(
                    color = Color(0xFFCBD5E1),
                    topLeft = Offset.Zero,
                    size = Size(w, h),
                    style = Stroke(width = 1f)
                )
                drawNoteheadBackgrounds(i)
                composeRenderer.drawCachedPage(this, page, textMeasurer, snapshot, sectionIndex)
                drawNoteheadCenterMarkers(i)
                if (showEditorMarkers) {
                    composeRenderer.render(
                        this,
                        editorMarkersByPage[page.pageIndex].orEmpty(),
                        textMeasurer,
                        snapshot,
                        sectionIndex,
                    )
                }
                // Stale overlay: page existed before this render started but has not
                // been refreshed yet — dim it so the user sees the work is in progress.
                if (page.pageIndex in stalePageIndices) {
                    drawRect(
                        color = Color(0x44808080),
                        topLeft = Offset.Zero,
                        size = Size(w, h),
                    )
                }
            }
        }
    } else {
        // Continuous mode: replay a cached whole-score Picture. The owned layer
        // re-records every pan/zoom frame, but the re-record is now a single
                // drawPicture op — Skia replays the prebuilt picture (RTree-culled to the
        // visible ops, glyphs served from the GPU atlas) instead of re-running the
        // render loop + per-glyph allocations. visibleRect is unused here.
        // renderResult is non-null when paginatedView is false and hasContent is true.
        val rr = renderResult ?: return
        drawNoteheadBackgrounds()
        composeRenderer.drawCachedScore(
            this, rr.elements, rr.bounds,
            textMeasurer, snapshot, sectionIndex,
        )
        drawNoteheadCenterMarkers()
        if (showEditorMarkers) {
            composeRenderer.render(
                this,
                editorMarkers,
                textMeasurer,
                snapshot,
                sectionIndex,
                visibleRect,
            )
        }
    }

    // Highlights and annotation overlays require the settled RenderResult (they index
    // into renderResult.elements). Skip during in-flight streaming.
    val rr = renderResult ?: return

    // Hidden-staff grey-out: cells on a staff that is hidden here but still laid out on this
    // line (partial hide) are dimmed. Fully-hidden staves collapse out of the system instead
    // (dashed HiddenStaffMarker), so they have no staff region here and are skipped.
    val orderedStaffsForHide = score?.orderedStaffs().orEmpty()
    if (showEditorMarkers && orderedStaffsForHide.any { it.hiddenRanges.isNotEmpty() }) {
        val regionBySysStaff = HashMap<Pair<Int, Int>, com.mecon.renderer.render.spatial.StaffRegion>()
        for (system in rr.spatialIndex.allSystems()) {
            for (region in system.staffRegions) {
                regionBySysStaff[system.systemIndex to region.staffIndex] = region
            }
        }
        for (mb in rr.measureBounds) {
            for ((staffIndex, staff) in orderedStaffsForHide.withIndex()) {
                if (staff.hiddenRanges.isEmpty() || !staff.isHidden(mb.measureNumber)) continue
                val region = regionBySysStaff[mb.systemIndex to staffIndex] ?: continue
                val bounds = rr.transformerSnapshot.toAbsolute(RelativeRect(
                    RelativePoint(mb.leftX, region.topY),
                    mb.rightX - mb.leftX,
                    region.bottomY - region.topY
                ))
                val topLeft = screenTopLeft(bounds) ?: continue
                drawRect(
                    color = Color(0x99B4B4B4),
                    topLeft = topLeft,
                    size = Size(bounds.width.value * density, bounds.height.value * density),
                )
            }
        }
    }

    // Staff-measure selection has no corresponding score element to style. Paint the
    // resolved spatial cell directly; it stays behind the voice-colored note styling above.
    val selectedCells = selection.filterIsInstance<MeasureStaffSection>()
    if (selectedCells.isNotEmpty()) {
        val orderedStaffs = score?.orderedStaffs().orEmpty()
        for (system in rr.spatialIndex.allSystems()) {
            for (cell in selectedCells) {
                val measureBounds = rr.measureBounds.firstOrNull {
                    it.systemIndex == system.systemIndex && it.measureNumber == cell.measureNumber
                }
                val staffIndex = orderedStaffs.indexOfFirst { it.id == cell.staffTrackId }
                val region = system.staffRegions.firstOrNull { it.staffIndex == staffIndex }
                if (measureBounds == null || region == null) continue
                val bounds = rr.transformerSnapshot.toAbsolute(RelativeRect(
                    RelativePoint(measureBounds.leftX, region.topY),
                    measureBounds.rightX - measureBounds.leftX,
                    region.bottomY - region.topY
                ))
                val topLeft = screenTopLeft(bounds) ?: continue
                drawRect(
                    color = MeconColors.voiceSelectionColor(1).copy(alpha = 0.20f),
                    topLeft = topLeft,
                    size = Size(bounds.width.value * density, bounds.height.value * density),
                )
            }
        }
    }

    // Plugin selection labels are a pure canvas overlay. Resolve selected noteheads through the
    // stable section index, then place each white capsule above the highest selected staff in its
    // system. Nothing here participates in engraving bounds, pagination, or hit testing.
    if (noteSelectionLabels.isNotEmpty()) {
        data class PositionedLabel(
            val x: Float,
            val text: String,
            val staffIndex: Int,
        )

        val noteSections = buildMap<Pair<EventId, Int>, VoiceNoteSection> {
            selection.forEach { section ->
                when (section) {
                    is VoiceNoteSection -> put(section.event.id to section.pitchIndex, section)
                    is VoiceEventSection -> section.event.pitchData.indices.forEach { pitchIndex ->
                        put(section.event.id to pitchIndex, VoiceNoteSection(section.event, pitchIndex))
                    }
                    else -> Unit
                }
            }
        }
        val positionedBySystem = linkedMapOf<Int, MutableList<PositionedLabel>>()
        noteSelectionLabels.forEach { label ->
            val section = noteSections[label.eventId to label.pitchIndex] ?: return@forEach
            val notehead = rr.sectionIndex.elementsFor(section).elementIds
                .asSequence()
                .mapNotNull(rr::elementById)
                .firstOrNull { it.type == RenderElementType.NOTEHEAD }
                ?: return@forEach
            val systemIndex = notehead.systemIndex ?: return@forEach
            val staffIndex = notehead.staffIndex ?: return@forEach
            positionedBySystem.getOrPut(systemIndex, ::mutableListOf) += PositionedLabel(
                x = notehead.center.x.value,
                text = label.text,
                staffIndex = staffIndex,
            )
        }

        val systems = rr.spatialIndex.allSystems().associateBy { it.systemIndex }
        val labelStyle = TextStyle(color = Color(0xFFEA580C), fontSize = 11.sp)
        positionedBySystem.forEach { (systemIndex, labels) ->
            val system = systems[systemIndex] ?: return@forEach
            val selectedStaffs = labels.mapTo(hashSetOf()) { it.staffIndex }
            val topStaff = system.staffRegions
                .filter { it.staffIndex in selectedStaffs }
                .minByOrNull { it.centerY.value }
                ?: return@forEach
            // StaffRegion.topY is the expanded occupied bound (notes, stems, ledger lines, etc.),
            // not merely the top staff line. Anchor the capsule above it so selection labels never
            // cover ledger-line notation.
            val occupiedTopY = rr.transformerSnapshot.toAbsolute(
                RelativePoint(StaffSpace(0f), topStaff.topY)
            ).y.value

            val columns = labels.mapNotNull { label ->
                val design = if (paginatedView) {
                    globalToDesign(label.x, occupiedTopY, pages, pageSlots)
                } else {
                    Offset(label.x, occupiedTopY)
                } ?: return@mapNotNull null
                design.x to label.text
            }.groupBy { (x, _) -> (x / 3f).roundToInt() }

            columns.values.forEach { column ->
                val x = column.map { it.first }.average().toFloat() * density
                val layouts = column.map { it.second }.distinct().map { text ->
                    textMeasurer.measure(text = text, style = labelStyle)
                }
                val paddingX = 5f * density
                val paddingY = 2.5f * density
                val rowGap = 1.5f * density
                val boxWidth = layouts.maxOf { it.size.width } + paddingX * 2f
                val boxHeight = layouts.sumOf { it.size.height } +
                    rowGap * (layouts.size - 1).coerceAtLeast(0) + paddingY * 2f
                val designTop = if (paginatedView) {
                    globalToDesign(labels.first().x, occupiedTopY, pages, pageSlots)?.y
                } else occupiedTopY
                val boxTop = (designTop ?: return@forEach) * density - boxHeight - 3f * density
                val boxLeft = x - boxWidth / 2f
                drawRoundRect(
                    color = Color.White.copy(alpha = 0.97f),
                    topLeft = Offset(boxLeft, boxTop),
                    size = Size(boxWidth, boxHeight),
                    cornerRadius = CornerRadius(4f * density),
                )
                drawRoundRect(
                    color = Color(0xFFD1D5DB),
                    topLeft = Offset(boxLeft, boxTop),
                    size = Size(boxWidth, boxHeight),
                    cornerRadius = CornerRadius(4f * density),
                    style = Stroke(width = density.coerceAtLeast(1f)),
                )
                var rowTop = boxTop + paddingY
                layouts.forEach { layout ->
                    drawText(
                        textLayoutResult = layout,
                        topLeft = Offset(
                            x - layout.size.width / 2f,
                            rowTop,
                        ),
                    )
                    rowTop += layout.size.height + rowGap
                }
            }
        }
    }

    // Draw highlights - multiply by density to match scaled render
    for (elementId in highlightedElements) {
        val element = rr.elements.find { it.id == elementId }
        element?.let { elem ->
            val bounds = elem.hitBox
            val tl = screenTopLeft(bounds) ?: return@let
            drawRect(
                color = Color(0x33F59E0B),  // Semi-transparent amber
                topLeft = tl,
                size = Size(bounds.width.value * density, bounds.height.value * density)
            )
        }
    }

    // Draw annotation selection highlight and range endpoint handles.
    if (selectedAnnotationEventId != null) {
        rr.elements
            .filter {
                it.type == RenderElementType.TEXT_ANNOTATION &&
                it.eventId == selectedAnnotationEventId
            }
            .forEach { elem ->
                val bounds = elem.hitBox
                val tl = screenTopLeft(bounds) ?: return@forEach
                drawRect(
                    color = Color(0x402563EB),
                    topLeft = tl,
                    size = Size(bounds.width.value * density, bounds.height.value * density)
                )
            }
        if (selectedAnnotationEventId in resizableAnnotationEventIds) {
            annotationRangeDrag?.takeIf { it.eventId == selectedAnnotationEventId }?.let { drag ->
                fun design(point: AbsolutePoint): Offset? = if (paginatedView) {
                    globalToDesign(point.x.value, point.y.value, pages, pageSlots)
                } else {
                    Offset(point.x.value, point.y.value)
                }
                val start = design(drag.originalPoint)
                val current = design(drag.currentPoint)
                if (start != null && current != null) {
                    val startPx = start * density
                    val currentPx = current * density
                    drawLine(
                        color = Color(0xFF2563EB),
                        start = startPx,
                        end = currentPx,
                        strokeWidth = 2f * density,
                        pathEffect = PathEffect.dashPathEffect(
                            floatArrayOf(5f * density, 3f * density),
                        ),
                    )
                    drawCircle(
                        color = Color(0x332563EB),
                        radius = 8f * density,
                        center = currentPx,
                    )
                    drawCircle(
                        color = Color(0xFF2563EB),
                        radius = 5f * density,
                        center = currentPx,
                        style = Stroke(width = 1.5f * density),
                    )
                }
            }
            annotationRangeEndpointPoints(rr, selectedAnnotationEventId).forEach { handle ->
                val design = if (paginatedView) {
                    globalToDesign(
                        handle.point.x.value,
                        handle.point.y.value,
                        pages,
                        pageSlots,
                    )
                } else {
                    Offset(handle.point.x.value, handle.point.y.value)
                } ?: return@forEach
                val center = Offset(design.x * density, design.y * density)
                drawCircle(Color.White, radius = 4.5f * density, center = center)
                drawCircle(
                    Color(0xFF2563EB),
                    radius = 4.5f * density,
                    center = center,
                    style = Stroke(width = 1.5f * density),
                )
            }
        }
    }

    // Draw playhead. Each TimeCodePosition's Y band is confined to its own system
    // (see RenderEngine), so in paginated mode both endpoints map onto a single page.
    val showPlayhead = playbackState == PlaybackState.PLAYING || playbackState == PlaybackState.PAUSED
    if (showPlayhead && tickToXMapping != null && tickToXMapping.isNotEmpty()) {
        val matchIdx = tickToXMapping.indexOfLast { it.first <= scorePositionTicks }
        // Before the first note, snap to it.
        val pos = if (matchIdx == -1) tickToXMapping.first().second
                  else tickToXMapping[matchIdx].second

        // Resolve the line's two endpoints to canvas design-space. In paginated mode
        // the global span is mapped onto its owning page; null → endpoint off-page.
        val top: Offset?
        val bottom: Offset?
        if (paginatedView) {
            top = globalToDesign(pos.x, pos.topY, pages, pageSlots)
            bottom = globalToDesign(pos.x, pos.bottomY, pages, pageSlots)
        } else {
            top = Offset(pos.x, pos.topY)
            bottom = Offset(pos.x, pos.bottomY)
        }

        if (top != null && bottom != null) {
            drawLine(
                color = MeconColors.Playhead,
                start = Offset(top.x * density, top.y * density),
                end = Offset(bottom.x * density, bottom.y * density),
                strokeWidth = 2.dp.toPx()
            )
        }
    }

    // Ghost-note preview at the snapped insertion position. The geometry is in global
    // render coordinates; continuous mode draws it directly, paginated mode shifts it
    // onto the page that owns the anchor (same mapping as the playhead).
    ghost?.let { g ->
        if (paginatedView) {
            val designAnchor = globalToDesign(
                g.anchor.x.value, g.anchor.y.value, pages, pageSlots
            ) ?: return@let
            val dx = designAnchor.x - g.anchor.x.value
            val dy = designAnchor.y - g.anchor.y.value
            translate(left = dx * density, top = dy * density) {
                composeRenderer.renderCommandsTinted(this, g.commands, textMeasurer, ghostColor)
            }
        } else {
            composeRenderer.renderCommandsTinted(this, g.commands, textMeasurer, ghostColor)
        }
    }

    clefGhost?.let { g ->
        if (paginatedView) {
            val designAnchor = globalToDesign(
                g.anchor.x.value, g.anchor.y.value, pages, pageSlots
            ) ?: return@let
            val dx = designAnchor.x - g.anchor.x.value
            val dy = designAnchor.y - g.anchor.y.value
            translate(left = dx * density, top = dy * density) {
                composeRenderer.renderCommandsTinted(this, g.commands, textMeasurer, ghostColor)
            }
        } else {
            composeRenderer.renderCommandsTinted(this, g.commands, textMeasurer, ghostColor)
        }
    }

    timeGhost?.let { g ->
        if (paginatedView) {
            val designAnchor = globalToDesign(
                g.anchor.x.value, g.anchor.y.value, pages, pageSlots
            ) ?: return@let
            val dx = designAnchor.x - g.anchor.x.value
            val dy = designAnchor.y - g.anchor.y.value
            translate(left = dx * density, top = dy * density) {
                composeRenderer.renderCommandsTinted(this, g.commands, textMeasurer, ghostColor)
            }
        } else {
            composeRenderer.renderCommandsTinted(this, g.commands, textMeasurer, ghostColor)
        }
    }

    keyGhost?.let { g ->
        if (paginatedView) {
            val designAnchor = globalToDesign(
                g.anchor.x.value, g.anchor.y.value, pages, pageSlots
            ) ?: return@let
            val dx = designAnchor.x - g.anchor.x.value
            val dy = designAnchor.y - g.anchor.y.value
            translate(left = dx * density, top = dy * density) {
                composeRenderer.renderCommandsTinted(this, g.commands, textMeasurer, ghostColor)
            }
        } else {
            composeRenderer.renderCommandsTinted(this, g.commands, textMeasurer, ghostColor)
        }
    }

    // Expression-span insertion preview: the same settled attachment geometries
    // are drawn in ghost colour while the user drags the end point.
    expressionSpanGhost?.let { g ->
        if (paginatedView) {
            val designAnchor = globalToDesign(
                g.anchor.x.value, g.anchor.y.value, pages, pageSlots
            ) ?: return@let
            val dx = designAnchor.x - g.anchor.x.value
            val dy = designAnchor.y - g.anchor.y.value
            translate(left = dx * density, top = dy * density) {
                composeRenderer.renderCommandsTinted(this, g.commands, textMeasurer, ghostColor)
            }
        } else {
            composeRenderer.renderCommandsTinted(this, g.commands, textMeasurer, ghostColor)
        }
    }

    drawScoreDragOverlays(request, rr, composeRenderer)

    // Picture recording above is synchronous. Post completion to the UI queue only
    // after the settled frame (and, while loading, every paginated page) has passed
    // through the cache, so hiding the loader is a real interaction-ready boundary.
    if (preparingLoadedDocument && readyFrameScheduledVersion[0] != documentVersion) {
        readyFrameScheduledVersion[0] = documentVersion
        scope.launch {
            kotlinx.coroutines.yield()
            currentOnDocumentInteractive(documentVersion)
        }
    }
}
