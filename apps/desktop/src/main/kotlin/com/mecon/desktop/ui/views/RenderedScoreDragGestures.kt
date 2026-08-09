package com.mecon.desktop.ui.views

import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.Modifier
import com.mecon.api.interaction.*
import com.mecon.api.primitive.EventId
import com.mecon.api.primitive.TimeCode
import com.mecon.api.storage.BeamGeometry
import com.mecon.api.storage.NavigationMarkOffset
import com.mecon.api.storage.SlurGeometry
import com.mecon.core.engine.edit.NoteEditEngine
import com.mecon.desktop.ui.components.EditTool
import com.mecon.renderer.geometry.*
import com.mecon.renderer.render.*
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.reflect.KProperty

internal class LiveValue<T>(private val read: () -> T) {
    operator fun getValue(thisRef: Any?, property: KProperty<*>): T = read()
}

internal class MutableLiveValue<T>(
    private val read: () -> T,
    private val write: (T) -> Unit,
) {
    operator fun getValue(thisRef: Any?, property: KProperty<*>): T = read()
    operator fun setValue(thisRef: Any?, property: KProperty<*>, value: T) = write(value)
}

internal fun Modifier.scoreDragGestures(request: DragGestureRequest): Modifier {
    val renderResultIdentityKey = request.frame.resultIdentityKey
    val insertionToolActive = request.mode.insertionToolActive
    val paginatedView = request.frame.paginated
    val panEnabled = request.mode.panEnabled
    val renderResult = request.frame.result
    val pages = request.frame.pages
    val pageSlots = request.frame.pageSlots
    val noteTool = request.mode.noteTool
    val readOnly = request.mode.readOnly
    val score = request.document.score
    val currentScore = request.document.currentScore
    val currentComputed = request.document.computed
    val renderEngine = request.document.engine
    val beamGeometry = request.document.beamGeometry
    val selectedBeamControls = request.document.selectedBeamControls
    val marqueeSelectableTypes = request.mode.marqueeSelectableTypes
    val currentOnSelectionChange = request.actions.selection.selectionChange
    val currentOnAuditionNote = request.actions.selection.auditionNote
    val currentOnTranspose = request.actions.notes.transpose
    val currentOnMoveRest = request.actions.notes.moveRest
    val currentOnMoveBeam = request.actions.notes.moveBeam
    val currentOnMoveAttachment = request.actions.expressions.moveAttachment
    val currentOnAdjustTieCurve = request.actions.expressions.adjustTieCurve
    val currentOnAdjustSlurCurve = request.actions.expressions.adjustSlurCurve
    val currentOnResizeSecondVolta = request.actions.structure.resizeSecondVolta
    val currentOnResizeFirstVoltaStart = request.actions.structure.resizeFirstVoltaStart
    val currentOnMoveNavigationMark = request.actions.structure.moveNavigationMark
    var offset by MutableLiveValue(
        read = { request.state.viewport.offset },
        write = { request.state.viewport.offset = it },
    )
    val scale by LiveValue { request.state.viewport.scale }
    val ctrlHeld by LiveValue { request.state.viewport.ctrlHeld }
    val shiftHeld by LiveValue { request.state.viewport.shiftHeld }
    var followPlayback by MutableLiveValue(
        read = { request.state.viewport.followPlayback },
        write = { request.state.viewport.followPlayback = it },
    )
    val currentSelection by LiveValue { request.state.selection.current }
    var marqueeRect by MutableLiveValue(
        read = { request.state.selection.marquee },
        write = { request.state.selection.marquee = it },
    )
    var transposeDrag by MutableLiveValue(
        read = { request.state.previews.transpose },
        write = { request.state.previews.transpose = it },
    )
    var beamDrag by MutableLiveValue(
        read = { request.state.previews.beam },
        write = { request.state.previews.beam = it },
    )
    var attachmentDrag by MutableLiveValue(
        read = { request.state.previews.attachment },
        write = { request.state.previews.attachment = it },
    )
    var voltaDrag by MutableLiveValue(
        read = { request.state.previews.volta },
        write = { request.state.previews.volta = it },
    )
    var navigationDrag by MutableLiveValue(
        read = { request.state.previews.navigation },
        write = { request.state.previews.navigation = it },
    )
    var curveDrag by MutableLiveValue(
        read = { request.state.previews.curve },
        write = { request.state.previews.curve = it },
    )
    return this
                        //   Marquee (no Ctrl) → marquee rubber-band, or transpose a selected note.
                        //   Marquee + Ctrl    → pan, or transpose when the drag grabs a note.
    // Insertion tools own their own drags, so this handler stands down for them.
    .pointerInput(
        renderResultIdentityKey,
        insertionToolActive,
        paginatedView,
        panEnabled,
    ) {
        if (insertionToolActive) return@pointerInput
        val result = renderResult ?: return@pointerInput
                        // Raw pointer → canvas design space (undo pan, zoom, density).
        fun toDesign(p: Offset) =
            Offset((p.x - offset.x) / scale / density, (p.y - offset.y) / scale / density)
        var mode = DragMode.NONE
        var startRaw = Offset.Zero
        var lastRaw = Offset.Zero
        var startRelY = 0f
        var startRelX = 0f
        var tieCurveStart: Pair<EventId, com.mecon.api.storage.TieGeometry>? = null
        var slurCurveStart: Pair<EventId, com.mecon.api.storage.SlurGeometry>? = null
        var curveApex = 0f
        detectDragGestures(
            onDragStart = { raw ->
                startRaw = raw; lastRaw = raw
                mode = DragMode.NONE
                            // Ctrl swaps Select↔Marquee for this drag; the note pen never reaches here.
                val marqueeMode = when (noteTool?.tool) {
                    EditTool.MARQUEE -> !ctrlHeld
                    EditTool.SELECT -> ctrlHeld
                    else -> false
                }
                val abs = rawToAbsolutePoint(raw, offset, scale, density, paginatedView, pages, pageSlots)
                val hitResult = abs?.let(result::hitTest)
                val hitSections = hitResult?.allSections().orEmpty()
                val picked = hitSections
                    .filter {
                        it is VoltaEndingSection || it is NavigationMarkSection
                    }
                    .selectByPriority()
                    ?: hitSections.selectByPriority()
                val attachmentSection = picked as? StaffAttachmentSection
                val voltaSection = picked as? VoltaEndingSection
                val navigationSection = picked as? NavigationMarkSection
                val attachmentElement = attachmentSection?.let { section ->
                    result.sectionIndex.elementsFor(section).elementIds
                        .mapNotNull(result::elementById).firstOrNull()
                }
                val beamHit = hitResult?.let { hit ->
                    hit.elements.asReversed()
                        .firstOrNull { it.metadata["groupId"] != null }
                }
                val controlHitRadius = BEAM_CONTROL_HIT_RADIUS / scale
                // Endpoint handles are editor overlays and do not exist in the spatial
                // index. Probe a small region around the pointer to discover an unselected
                // beam, then derive and test its endpoints in the same initial gesture.
                val nearbyBeamSections = if (abs != null) {
                    result.hitTestRegion(
                        AbsoluteRect(
                            AbsolutePoint(
                                Pixels(abs.x.value - controlHitRadius),
                                Pixels(abs.y.value - controlHitRadius),
                            ),
                            Pixels(controlHitRadius * 2f),
                            Pixels(controlHitRadius * 2f),
                        ),
                        setOf(RenderElementType.BEAM),
                    ).asReversed().flatMap { hit ->
                        hit.sections.filterIsInstance<VoiceBeamSection>()
                    }.distinctBy { it.groupId }
                } else emptyList()
                val controlHit = if (abs != null) {
                    buildList {
                        selectedBeamControls?.let(::add)
                        nearbyBeamSections.forEach { section ->
                            findBeamControlPoints(result, section)?.let(::add)
                        }
                    }.distinctBy { it.section.groupId }.firstNotNullOfOrNull { controls ->
                        hitBeamControlPoint(abs, controls, controlHitRadius)?.let { endpoint ->
                            controls to endpoint
                        }
                    }
                } else null
                val controlEndpoint = controlHit?.second
                val beamSection = when {
                    controlHit != null -> controlHit.first.section
                    beamHit != null -> beamHit.sections
                        .filterIsInstance<VoiceBeamSection>()
                        .firstOrNull()
                    else -> null
                }
                val movable = !readOnly && abs != null && picked != null && picked.movableEvent() != null
                // A rest is dragged vertically (move its display position) instead of
                // transposed; it follows the same gesture rules as a movable note.
                val restGrab = !readOnly && abs != null && picked != null && picked.restEvent() != null
                // Start a note-transpose drag over [moveSections]; returns true if it engaged.
                fun startTranspose(moveSections: Set<EventSection>): Boolean {
                    val targets = buildTransposeTargets(moveSections, currentScore)
                    if (targets.isEmpty()) return false
                    mode = DragMode.TRANSPOSE
                    startRelY = result.transformerSnapshot.toRelative(abs!!).y.value
                    transposeDrag = TransposeDragState(
                        previewTargets = targets.associate { it.eventId to it.pitchIndices },
                        transposeTargets = targets,
                        auditionTarget = picked?.dragAuditionTarget(targets),
                    )
                    return true
                }
                // Start a rest-move drag over [moveSections]; returns true if it engaged.
                fun startRestMove(moveSections: Set<EventSection>): Boolean {
                    val info = buildRestMoveInfo(moveSections, currentScore)
                    if (info.targets.isEmpty()) return false
                    mode = DragMode.REST_MOVE
                    startRelY = result.transformerSnapshot.toRelative(abs!!).y.value
                    transposeDrag = TransposeDragState(
                        previewTargets = info.targets.associate { it.eventId to null },
                        transposeTargets = emptyList(),
                        restMove = info,
                    )
                    return true
                }
                fun startBeam(): Boolean {
                    val section = beamSection
                    if (readOnly || section == null || score == null || abs == null) return false
                    val beamGroupId = section.groupId.value
                    val stored = beamGeometry?.beams?.get(beamGroupId)
                        ?: score.geometry?.beams?.get(beamGroupId)
                        ?: BeamGeometry(0f, 0f)
                    val controls = controlHit?.first
                        ?: selectedBeamControls
                        ?.takeIf { it.section.groupId == section.groupId }
                        ?: findBeamControlPoints(result, section)
                    val staffCenters = controls?.staffCenters.orEmpty()
                    val dragStart = normalizeCrossStaffBeamGeometry(stored, staffCenters)
                    if (section !in currentSelection) {
                        currentOnSelectionChange(setOf(section))
                    }
                    mode = DragMode.BEAM
                    startRelY = result.transformerSnapshot.toRelative(abs).y.value
                    beamDrag = BeamDragState(
                        groupId = beamGroupId,
                        endpoint = controlEndpoint,
                        start = dragStart,
                        current = dragStart,
                        staffCenters = staffCenters,
                    )
                    return true
                }
                fun startAttachment(): Boolean {
                    val section = attachmentSection ?: return false
                    val element = attachmentElement ?: return false
                    val point = abs ?: return false
                    if (readOnly || score == null) return false
                    val attachment = section.attachment
                    if (attachment is com.mecon.api.computed.ComputedTempoKeyframe &&
                        attachment.time.measure == 1 &&
                        (attachment.time.beat?.numerator ?: 0) == 0) return false
                    val isSpan = attachment is com.mecon.api.computed.ComputedHairpin ||
                        attachment is com.mecon.api.computed.ComputedOctaveShift ||
                        (attachment is com.mecon.api.computed.ComputedOrnamentMark &&
                            attachment.endTime != null) ||
                        (attachment is com.mecon.api.computed.ComputedTempoKeyframe && attachment.isGradual)
                    val endpoint = if (!isSpan) "start" else {
                        val radius = ATTACHMENT_CONTROL_HIT_RADIUS / scale
                        when {
                            kotlin.math.abs(point.x.value - element.hitBox.origin.x.value) <= radius -> "start"
                            kotlin.math.abs(point.x.value - element.hitBox.bottomRight.x.value) <= radius -> "end"
                            attachment is com.mecon.api.computed.ComputedHairpin ||
                                attachment is com.mecon.api.computed.ComputedTempoKeyframe -> "body"
                            else -> return false // octave spans remain endpoint-only
                        }
                    }
                    val stored = score.geometry?.attachments?.get(section.attachment.id)
                        ?: beamGeometry?.attachments?.get(section.attachment.id)
                        ?: deriveAttachmentGeometry(result, section, element)
                        ?: return false
                    if (section !in currentSelection) currentOnSelectionChange(setOf(section))
                    val rel = result.transformerSnapshot.toRelative(point)
                    val elementTop = result.transformerSnapshot.toRelative(element.hitBox.origin)
                    val elementBottom = result.transformerSnapshot.toRelative(element.hitBox.bottomRight)
                    val system = result.spatialIndex.allSystems().firstOrNull { it.systemIndex == element.systemIndex }
                    val staffCenter = system?.staffRegions
                        ?.firstOrNull { it.staffIndex == attachment.staffIndex }?.centerY?.value ?: 0f
                    val noteBoxes = result.elements.asSequence().filter {
                        it.systemIndex == element.systemIndex && it.staffIndex == attachment.staffIndex &&
                            it.type in setOf(RenderElementType.NOTEHEAD, RenderElementType.REST)
                    }.map { note ->
                        result.transformerSnapshot.toRelative(note.hitBox.origin).y.value - staffCenter to
                            result.transformerSnapshot.toRelative(note.hitBox.bottomRight).y.value - staffCenter
                    }.toList()
                    val halfHeight = (elementBottom.y.value - elementTop.y.value) / 2f
                    val topLimit = minOf(-2f, noteBoxes.minOfOrNull { it.first } ?: -2f) - halfHeight - 0.2f
                    val bottomLimit = maxOf(2f, noteBoxes.maxOfOrNull { it.second } ?: 2f) + halfHeight + 0.2f
                    startRelX = rel.x.value
                    startRelY = rel.y.value
                    mode = DragMode.ATTACHMENT
                    attachmentDrag = AttachmentDragState(
                        attachment.id,
                        endpoint,
                        isHairpin = (attachment as? com.mecon.api.computed.ComputedHairpin)?.style ==
                            com.mecon.api.storage.events.HairpinStyle.WEDGE,
                        isBreath = attachment is com.mecon.api.computed.ComputedBreathMark,
                        start = stored,
                        current = stored,
                        staffId = attachment.staffTrackId,
                        startTime = attachment.time,
                        endTime = when (attachment) {
                            is com.mecon.api.computed.ComputedHairpin -> attachment.endTime
                            is com.mecon.api.computed.ComputedOctaveShift -> attachment.endTime
                            is com.mecon.api.computed.ComputedOrnamentMark -> attachment.endTime
                            is com.mecon.api.computed.ComputedTempoKeyframe ->
                                attachment.nextTime.takeIf { attachment.isGradual }
                            else -> null
                        },
                        originalStartTime = attachment.time,
                        originalEndTime = when (attachment) {
                            is com.mecon.api.computed.ComputedHairpin -> attachment.endTime
                            is com.mecon.api.computed.ComputedOctaveShift -> attachment.endTime
                            is com.mecon.api.computed.ComputedOrnamentMark -> attachment.endTime
                            is com.mecon.api.computed.ComputedTempoKeyframe ->
                                attachment.nextTime.takeIf { attachment.isGradual }
                            else -> null
                        },
                        elementId = element.id,
                        originalStartPoint = AbsolutePoint(
                            element.hitBox.origin.x,
                            element.center.y,
                        ),
                        originalEndPoint = if (isSpan) AbsolutePoint(
                            element.hitBox.bottomRight.x,
                            element.center.y,
                        ) else null,
                        systemIndex = element.systemIndex,
                        topLimit = topLimit,
                        bottomLimit = bottomLimit,
                    )
                    return true
                }
                fun startVolta(): Boolean {
                    val section = voltaSection ?: return false
                    val point = abs ?: return false
                    val endpoint = when (section.ending.numbers) {
                        setOf(1) -> VoltaEndpoint.START
                        setOf(2) -> VoltaEndpoint.END
                        else -> return false
                    }
                    if (readOnly) return false
                    val elements = result.sectionIndex.elementsFor(section).elementIds
                        .mapNotNull(result::elementById)
                    val element = when (endpoint) {
                        VoltaEndpoint.START -> elements.firstOrNull()
                        VoltaEndpoint.END -> elements.lastOrNull()
                    } ?: return false
                    val line = element.commands.filterIsInstance<DrawLine>()
                        .maxByOrNull { kotlin.math.abs(it.end.x.value - it.start.x.value) }
                        ?: return false
                    val handle = when (endpoint) {
                        VoltaEndpoint.START -> line.start
                        VoltaEndpoint.END -> line.end
                    }
                    val radius = VOLTA_CONTROL_HIT_RADIUS / scale
                    if (kotlin.math.abs(point.x.value - handle.x.value) > radius ||
                        kotlin.math.abs(point.y.value - handle.y.value) > radius
                    ) return false
                    if (section !in currentSelection) currentOnSelectionChange(setOf(section))
                    mode = DragMode.VOLTA
                    voltaDrag = VoltaDragState(
                        endpoint = endpoint,
                        originalStartMeasure = section.ending.startMeasure,
                        currentStartMeasure = section.ending.startMeasure,
                        originalEndMeasure = section.ending.endMeasure,
                        currentEndMeasure = section.ending.endMeasure,
                    )
                    return true
                }
                fun startNavigation(): Boolean {
                    val section = navigationSection ?: return false
                    val point = abs ?: return false
                    if (readOnly) return false
                    val element = result.sectionIndex.elementsFor(section).elementIds
                        .mapNotNull(result::elementById)
                        .firstOrNull() ?: return false
                    if (section !in currentSelection) currentOnSelectionChange(setOf(section))
                    val rel = result.transformerSnapshot.toRelative(point)
                    val visualCenter = result.transformerSnapshot.toRelative(element.center)
                    val sourceAnchorY = navigationSystemAnchorY(
                        result,
                        element.systemIndex ?: return false,
                    ) ?: return false
                    startRelX = rel.x.value
                    startRelY = rel.y.value
                    mode = DragMode.NAVIGATION
                    navigationDrag = NavigationDragState(
                        sectionId = section.id,
                        elementId = element.id,
                        boundaryMeasure = section.navigation.boundaryMeasure,
                        mark = section.navigation.mark,
                        start = section.navigation.offset,
                        current = section.navigation.offset,
                        targetBoundaryMeasure = section.navigation.boundaryMeasure,
                        startVisualCenterX = visualCenter.x.value,
                        startAnchorY = sourceAnchorY,
                        targetAnchorY = sourceAnchorY,
                    )
                    return true
                }
                fun startCurve(): Boolean {
                    if (readOnly || abs == null) return false
                    val liveGeometry = beamGeometry ?: score?.geometry ?: return false
                    when (val section = picked) {
                        is VoiceTieSection -> {
                            val geometry = liveGeometry.ties[section.sourceEvent.id]
                                ?.firstOrNull { it.sourcePitchIndex == section.sourcePitchIndex }
                                ?: return false
                            tieCurveStart = section.sourceEvent.id to geometry
                            slurCurveStart = null
                            curveApex = geometry.minApex
                            curveDrag = CurveDragState(
                                kind = CurveKind.TIE,
                                sectionId = section.id,
                                elementIds = result.sectionIndex.elementsFor(section).elementIds,
                                above = geometry.above,
                                startApex = geometry.minApex,
                                currentApex = geometry.minApex,
                                slopeDamping = geometry.slopeDamping,
                                middleStraightening = geometry.middleStraightening,
                            )
                        }
                        is VoiceSlurSection -> {
                            val slurId = section.slurId ?: return false
                            val geometry = liveGeometry.slurs[slurId] ?: return false
                            slurCurveStart = slurId to geometry
                            tieCurveStart = null
                            curveApex = geometry.minApex
                            curveDrag = CurveDragState(
                                kind = CurveKind.SLUR,
                                sectionId = section.id,
                                elementIds = result.sectionIndex.elementsFor(section).elementIds,
                                above = geometry.above,
                                startApex = geometry.minApex,
                                currentApex = geometry.minApex,
                                slopeDamping = geometry.slopeDamping,
                                middleStraightening = geometry.middleStraightening,
                            )
                        }
                        else -> return false
                    }
                    if (picked !in currentSelection) currentOnSelectionChange(setOf(picked))
                    startRelY = result.transformerSnapshot.toRelative(abs).y.value
                    mode = DragMode.CURVE
                    return true
                }
                if (marqueeMode) {
                    // Grabbing an already-selected note/rest moves the whole selection;
                    // anything else rubber-bands.
                    if (startCurve() || startNavigation() || startVolta()) {
                        // handled
                    } else if (startAttachment()) {
                        // handled
                    } else if (beamSection != null) {
                        startBeam()
                    } else if ((movable || restGrab) && picked in currentSelection) {
                        if (!startTranspose(currentSelection)) startRestMove(currentSelection)
                    }
                    if (mode == DragMode.NONE) {
                        mode = DragMode.MARQUEE
                        marqueeRect = Rect(raw.x, raw.y, raw.x, raw.y)
                    }
                } else {
                    // Direct note/rest manipulation is independent of viewport panning. Embedded
                    // editors commonly disable local pan because a sibling timeline owns the shared
                    // horizontal offset, but their score contents must remain draggable.
                    val nonNoteDragStarted = panEnabled &&
                        (startCurve() || startNavigation() || startVolta() ||
                            startAttachment() || startBeam())
                    if (!nonNoteDragStarted && (movable || restGrab)) {
                        // Single grab outside the selection: select just it.
                        if (picked !in currentSelection && !shiftHeld) {
                            currentOnSelectionChange(setOf(picked!!))
                        }
                        val moveSections = resolveMoveSections(picked!!, currentSelection, shiftHeld)
                        if (!startTranspose(moveSections)) startRestMove(moveSections)
                    }
                    if (mode == DragMode.NONE && panEnabled) mode = DragMode.PAN
                }
            },
            onDragCancel = {
                if (mode == DragMode.PAN) followPlayback = true
                marqueeRect = null
                transposeDrag = null
                beamDrag = null
                attachmentDrag = null
                voltaDrag = null
                navigationDrag = null
                curveDrag = null
                mode = DragMode.NONE
            },
            onDragEnd = {
                if (mode == DragMode.PAN) followPlayback = true
                when (mode) {
                    DragMode.TRANSPOSE -> {
                        val d = transposeDrag
                        if (d != null && d.stepDelta != 0 && d.preview != null) {
                            // Capture the displayed frame before starting the edit. A fast render
                            // can otherwise land before the committing LaunchedEffect starts.
                            transposeDrag = d.copy(
                                committing = true,
                                commitBaseline = result,
                                commitStartedAtNanos = System.nanoTime(),
                            )
                            currentOnTranspose(d.transposeTargets, d.stepDelta)
                        } else {
                            transposeDrag = null
                        }
                    }
                    DragMode.REST_MOVE -> {
                        val d = transposeDrag
                        val info = d?.restMove
                        if (d != null && info != null && d.stepDelta != 0 && d.preview != null) {
                            val targets = info.targets.map { ti ->
                                val newPos = ti.startPosition + d.stepDelta
                                NoteEditEngine.RestMoveTarget(
                                    voiceTrackId = ti.voiceTrackId,
                                    eventId = ti.eventId,
                                                        // Back at the type default → clear the override (store null).
                                    staffPosition = if (newPos == ti.defaultPosition) null else newPos,
                                )
                            }
                            transposeDrag = d.copy(
                                committing = true,
                                commitBaseline = result,
                                commitStartedAtNanos = System.nanoTime(),
                            )
                            currentOnMoveRest(targets)
                        } else {
                            transposeDrag = null
                        }
                    }
                    DragMode.BEAM -> {
                        beamDrag?.let { drag ->
                            val changed = drag.current != drag.start
                            if (changed) {
                                beamDrag = drag.copy(
                                    committing = true,
                                    commitBaseline = result,
                                )
                                currentOnMoveBeam(
                                    drag.groupId,
                                    drag.current.copy(manuallyAdjusted = true),
                                )
                            } else {
                                beamDrag = null
                            }
                        }
                    }
                    DragMode.ATTACHMENT -> {
                        attachmentDrag?.let { drag ->
                            if (drag.current != drag.start ||
                                drag.startTime != drag.originalStartTime ||
                                drag.endTime != drag.originalEndTime) {
                                attachmentDrag = drag.copy(
                                    committing = true,
                                    commitBaseline = result,
                                )
                                currentOnMoveAttachment(
                                    drag.id, drag.current, drag.startTime, drag.endTime,
                                )
                            } else {
                                attachmentDrag = null
                            }
                        }
                    }
                    DragMode.CURVE -> {
                        val changed = curveDrag?.let { it.currentApex != it.startApex } == true
                        if (changed) {
                            curveDrag = curveDrag?.copy(
                                committing = true,
                                commitBaseline = result,
                            )
                        }
                        tieCurveStart?.let { (sourceId, startGeometry) ->
                            if (curveApex != startGeometry.minApex) {
                                currentOnAdjustTieCurve(
                                    sourceId,
                                    startGeometry.copy(
                                        minApex = curveApex,
                                        maxApex = curveApex,
                                    ),
                                )
                            }
                        }
                        slurCurveStart?.let { (slurId, startGeometry) ->
                            if (curveApex != startGeometry.minApex) {
                                currentOnAdjustSlurCurve(
                                    slurId,
                                    startGeometry.copy(
                                        minApex = curveApex,
                                        maxApex = curveApex,
                                    ),
                                )
                            }
                        }
                        if (!changed) curveDrag = null
                        tieCurveStart = null
                        slurCurveStart = null
                    }
                    DragMode.VOLTA -> {
                        voltaDrag?.let { drag ->
                            when (drag.endpoint) {
                                VoltaEndpoint.START -> if (
                                    drag.currentStartMeasure != drag.originalStartMeasure
                                ) {
                                    currentOnResizeFirstVoltaStart(
                                        drag.originalStartMeasure,
                                        drag.currentStartMeasure,
                                    )
                                }
                                VoltaEndpoint.END -> if (
                                    drag.currentEndMeasure != drag.originalEndMeasure
                                ) {
                                    currentOnResizeSecondVolta(
                                        drag.originalStartMeasure,
                                        drag.currentEndMeasure,
                                    )
                                }
                            }
                        }
                        voltaDrag = null
                    }
                    DragMode.NAVIGATION -> {
                        navigationDrag?.let { drag ->
                            if (drag.previewDx != 0f || drag.previewDy != 0f) {
                                val committedOffset = NavigationMarkOffset(
                                    dx = 0f,
                                    // Re-anchoring at the target system already applies
                                    // the inter-system distance. Keep only the mark's
                                    // local displacement from that new anchor.
                                    dy = navigationOffsetYAfterSnap(
                                        currentOffsetY = drag.current.dy,
                                        sourceAnchorY = drag.startAnchorY,
                                        targetAnchorY = drag.targetAnchorY,
                                    ),
                                )
                                navigationDrag = drag.copy(
                                    current = committedOffset,
                                    previewDx = drag.targetAnchorX - drag.startVisualCenterX,
                                    committing = true,
                                    commitBaseline = result,
                                )
                                currentOnMoveNavigationMark(
                                    drag.boundaryMeasure,
                                    drag.targetBoundaryMeasure,
                                    drag.mark,
                                    committedOffset,
                                )
                            } else {
                                navigationDrag = null
                            }
                        }
                    }
                    DragMode.MARQUEE -> {
                        val a = toDesign(startRaw)
                        val b = toDesign(lastRaw)
                        val dMinX = min(a.x, b.x); val dMaxX = max(a.x, b.x)
                        val dMinY = min(a.y, b.y); val dMaxY = max(a.y, b.y)
                        // Overlap testing lives in the renderer's spatial index; the view
                        // only maps the marquee rectangle from design space into the
                        // global score coordinates the index uses, then unions the hits.
                        val collected = LinkedHashSet<EventSection>()
                        fun collectFrom(globalRect: AbsoluteRect) {
                            val relA = result.transformerSnapshot.toRelative(globalRect.origin)
                            val relB = result.transformerSnapshot.toRelative(globalRect.bottomRight)
                            for (hit in result.hitTestRegion(globalRect, marqueeSelectableTypes)) {
                                val section = hit.sections.selectByPriority() ?: continue
                                val spanAttachment = (section as? StaffAttachmentSection)?.attachment?.let {
                                    it is com.mecon.api.computed.ComputedHairpin ||
                                        it is com.mecon.api.computed.ComputedOctaveShift ||
                                        (it is com.mecon.api.computed.ComputedTempoKeyframe && it.isGradual)
                                } == true
                                if (spanAttachment) {
                                    val box = hit.boundingBox()
                                    val fullyContained = box.origin.x.value >= minOf(relA.x.value, relB.x.value) &&
                                        box.bottomRight.x.value <= maxOf(relA.x.value, relB.x.value) &&
                                        box.origin.y.value >= minOf(relA.y.value, relB.y.value) &&
                                        box.bottomRight.y.value <= maxOf(relA.y.value, relB.y.value)
                                    if (!fullyContained) continue
                                }
                                collected.add(section)
                            }
                        }
                        if (paginatedView) {
                            // A marquee may straddle several page sheets; clip it to each
                            // page slot and map that slice into the page's global Y band.
                            for (i in pages.indices) {
                                val s = pageSlots[i]
                                val p = pages[i]
                                val ix0 = max(dMinX, s.x); val ix1 = min(dMaxX, s.x + p.width.value)
                                val iy0 = max(dMinY, s.y); val iy1 = min(dMaxY, s.y + p.height.value)
                                if (ix0 >= ix1 || iy0 >= iy1) continue
                                val gx = ix0 - s.x
                                val gy = (iy0 - s.y) + p.contentOffsetY.value
                                collectFrom(
                                    AbsoluteRect(
                                        AbsolutePoint(Pixels(gx), Pixels(gy)),
                                        Pixels(ix1 - ix0), Pixels(iy1 - iy0),
                                    )
                                )
                            }
                        } else {
                            // Continuous mode: design space already is the global space.
                            collectFrom(
                                AbsoluteRect(
                                    AbsolutePoint(Pixels(dMinX), Pixels(dMinY)),
                                    Pixels(dMaxX - dMinX), Pixels(dMaxY - dMinY),
                                )
                            )
                        }
                        marqueeRect = null
                        // Shift unions with the existing selection; otherwise it replaces.
                        currentOnSelectionChange(
                            if (shiftHeld) currentSelection + collected else collected
                        )
                    }
                    else -> {}
                }
                mode = DragMode.NONE
            },
        ) { change, dragAmount ->
            when (mode) {
                DragMode.PAN -> {
                    followPlayback = false
                    offset += dragAmount
                }
                DragMode.TRANSPOSE -> {
                    val cur = transposeDrag
                    val abs = rawToAbsolutePoint(change.position, offset, scale, density, paginatedView, pages, pageSlots)
                    if (cur != null && abs != null) {
                        val curRelY = result.transformerSnapshot.toRelative(abs).y.value
                        val delta = ((startRelY - curRelY) * 2f).roundToInt()
                        if (delta != cur.stepDelta) {
                            val rt = currentScore; val cmp = currentComputed; val eng = renderEngine
                            val preview = if (delta != 0 && rt != null && cmp != null && eng != null)
                                eng.computeTransposePreview(result, rt, cmp, cur.previewTargets, delta) else null
                            val effectiveDelta = if (rt != null && cmp != null) {
                                clampTransposeDelta(rt, cmp, cur.previewTargets, delta)
                            } else delta
                            transposeDrag = cur.copy(
                                stepDelta = delta,
                                preview = preview,
                                auditionStepDelta = effectiveDelta,
                            )
                            if (preview != null && effectiveDelta != cur.auditionStepDelta) {
                                cur.auditionTarget?.let { target ->
                                    currentOnAuditionNote(
                                        target.event,
                                        target.soundingPitchIndices,
                                        target.transposedPitchIndices,
                                        effectiveDelta,
                                    )
                                }
                            }
                        }
                    }
                    change.consume()
                }
                DragMode.REST_MOVE -> {
                    val cur = transposeDrag
                    val info = cur?.restMove
                    val abs = rawToAbsolutePoint(change.position, offset, scale, density, paginatedView, pages, pageSlots)
                    if (cur != null && info != null && abs != null) {
                        val curRelY = result.transformerSnapshot.toRelative(abs).y.value
                        // One staff position step per half staff space (positive = up).
                        val delta = ((startRelY - curRelY) * 2f).roundToInt()
                        if (delta != cur.stepDelta) {
                            val cmp = currentComputed; val eng = renderEngine
                            val preview = if (delta != 0 && cmp != null && eng != null) {
                                val targets = info.targets.associate { it.eventId to (it.startPosition + delta) }
                                eng.computeRestMovePreview(result, cmp, targets)
                            } else null
                            transposeDrag = cur.copy(stepDelta = delta, preview = preview)
                        }
                    }
                    change.consume()
                }
                DragMode.BEAM -> {
                    val drag = beamDrag
                    val abs = rawToAbsolutePoint(change.position, offset, scale, density, paginatedView, pages, pageSlots)
                    if (drag != null && abs != null) {
                        val curRelY = result.transformerSnapshot.toRelative(abs).y.value
                        val deltaY = curRelY - startRelY
                        val current = relocateBeamGeometry(
                            drag.start, drag.endpoint, deltaY, drag.staffCenters
                        )
                        beamDrag = drag.copy(current = current, deltaY = deltaY)
                    }
                    change.consume()
                }
                DragMode.ATTACHMENT -> {
                    val drag = attachmentDrag
                    val abs = rawToAbsolutePoint(change.position, offset, scale, density, paginatedView, pages, pageSlots)
                    if (drag != null && abs != null) {
                        val rel = result.transformerSnapshot.toRelative(abs)
                        val dx = rel.x.value - startRelX
                        val dy = rel.y.value - startRelY
                        val current = when (drag.endpoint) {
                            "body" -> drag.start.copy(
                                startDx = drag.start.startDx + dx,
                                endDx = drag.start.endDx?.plus(dx),
                                startDy = drag.start.startDy + dy,
                                endDy = drag.start.endDy?.plus(dy),
                            )
                            "start" -> if (drag.isHairpin) drag.start.copy(
                                startDx = drag.start.startDx + dx,
                                startDy = drag.start.startDy + dy,
                            ) else drag.start.copy(
                                startDx = drag.start.startDx + dx,
                                startDy = drag.start.startDy + dy,
                                endDy = drag.start.endDy?.plus(dy),
                            )
                            else -> if (drag.isHairpin) drag.start.copy(
                                endDx = drag.start.endDx?.plus(dx),
                                endDy = drag.start.endDy?.plus(dy),
                            ) else drag.start.copy(
                                endDx = drag.start.endDx?.plus(dx),
                                endDy = drag.start.endDy?.plus(dy),
                                startDy = drag.start.startDy + dy,
                            )
                        }
                        fun safeY(value: Float): Float = if (value in drag.topLimit..drag.bottomLimit) {
                            if (kotlin.math.abs(value - drag.topLimit) <= kotlin.math.abs(value - drag.bottomLimit))
                                drag.topLimit else drag.bottomLimit
                        } else value
                        val constrained = if (drag.endpoint == "body") {
                            // Move both ends through the forbidden band as a unit, preserving
                            // a wedge's existing slope while allowing the whole mark to switch
                            // between below and above the staff.
                            val safeStart = safeY(current.startDy)
                            val endOffset = (current.endDy ?: current.startDy) - current.startDy
                            val adjustedStart = if (safeStart <= drag.topLimit) {
                                minOf(safeStart, drag.topLimit - maxOf(0f, endOffset))
                            } else {
                                maxOf(safeStart, drag.bottomLimit - minOf(0f, endOffset))
                            }
                            current.copy(
                                startDy = adjustedStart,
                                endDy = current.endDy?.let { adjustedStart + endOffset },
                            )
                        } else if (drag.isHairpin) current.copy(
                            startDy = safeY(current.startDy),
                            endDy = current.endDy?.let(::safeY),
                        ) else {
                            val y = safeY(if (drag.endpoint == "end") current.endDy ?: current.startDy else current.startDy)
                            current.copy(startDy = y, endDy = current.endDy?.let { y })
                        }
                        val (newStart, newEnd) = when (drag.endpoint) {
                            "body" -> {
                                val dxPixels = result.transformerSnapshot.toPixels(StaffSpace(dx)).value
                                val startCandidate = resolveExpressionTime(
                                    result,
                                    drag.originalStartPoint.x.value + dxPixels,
                                    drag.systemIndex,
                                )
                                val endCandidate = drag.originalEndPoint?.let { endpoint ->
                                    resolveExpressionTime(
                                        result,
                                        endpoint.x.value + dxPixels,
                                        drag.systemIndex,
                                    )
                                }
                                if (startCandidate != null && endCandidate != null && startCandidate < endCandidate) {
                                    startCandidate to endCandidate
                                } else drag.startTime to drag.endTime
                            }
                            "start" -> {
                                val anchor = if (drag.isBreath) {
                                    resolveBreathBoundaryTime(result, abs.x.value, drag.systemIndex)
                                } else {
                                    resolveExpressionTime(result, abs.x.value, drag.systemIndex)
                                }
                                val candidate = anchor ?: drag.startTime
                                if (drag.endTime == null || candidate < drag.endTime) candidate to drag.endTime
                                else drag.startTime to drag.endTime
                            }
                            else -> {
                                val anchor = resolveExpressionTime(result, abs.x.value, drag.systemIndex)
                                val candidate = anchor ?: drag.endTime
                                if (candidate != null && candidate > drag.startTime) drag.startTime to candidate
                                else drag.startTime to drag.endTime
                            }
                        }
                        // TimeCode positions are anchor X values. When an endpoint crosses a
                        // midpoint separator and snaps to the adjacent anchor, compensate the
                        // persisted delta so the symbol itself remains under the pointer rather
                        // than jumping once for the drag and once again for the new anchor.
                        fun anchorX(time: TimeCode?): Float? {
                            val position = time?.let(result.timeCodePositions::get) ?: return null
                            return result.transformerSnapshot.toRelative(
                                AbsolutePoint(Pixels(position.x), abs.y)
                            ).x.value
                        }
                        val xCompensated = when (drag.endpoint) {
                            "body" -> {
                                val oldStartAnchor = anchorX(drag.originalStartTime)
                                val newStartAnchor = anchorX(newStart)
                                val oldEndAnchor = anchorX(drag.originalEndTime)
                                val newEndAnchor = anchorX(newEnd)
                                constrained.copy(
                                    startDx = if (oldStartAnchor != null && newStartAnchor != null) {
                                        oldStartAnchor + drag.start.startDx + dx - newStartAnchor
                                    } else constrained.startDx,
                                    endDx = if (oldEndAnchor != null && newEndAnchor != null) {
                                        oldEndAnchor + (drag.start.endDx ?: 0f) + dx - newEndAnchor
                                    } else constrained.endDx,
                                )
                            }
                            "start" -> {
                                val oldAnchor = anchorX(drag.originalStartTime)
                                val newAnchor = anchorX(newStart)
                                if (oldAnchor != null && newAnchor != null) constrained.copy(
                                    startDx = oldAnchor + drag.start.startDx + dx - newAnchor,
                                ) else constrained
                            }
                            else -> {
                                val oldAnchor = anchorX(drag.originalEndTime)
                                val newAnchor = anchorX(newEnd)
                                if (oldAnchor != null && newAnchor != null) constrained.copy(
                                    endDx = oldAnchor + (drag.start.endDx ?: 0f) + dx - newAnchor,
                                ) else constrained
                            }
                        }
                        attachmentDrag = drag.copy(
                            current = xCompensated,
                            startTime = newStart,
                            endTime = newEnd,
                        )
                    }
                    change.consume()
                }
                DragMode.CURVE -> {
                    val abs = rawToAbsolutePoint(
                        change.position, offset, scale, density,
                        paginatedView, pages, pageSlots,
                    )
                    if (abs != null) {
                        val y = result.transformerSnapshot.toRelative(abs).y.value
                        val above = tieCurveStart?.second?.above ?: slurCurveStart?.second?.above
                        val startApex = tieCurveStart?.second?.minApex
                            ?: slurCurveStart?.second?.minApex
                        if (above != null && startApex != null) {
                            val outwardDelta = if (above) startRelY - y else y - startRelY
                            curveApex = (startApex + outwardDelta)
                                .coerceIn(SlurGeometry.MIN_APEX, SlurGeometry.MAX_APEX)
                            curveDrag = curveDrag?.copy(currentApex = curveApex)
                        }
                    }
                    change.consume()
                }
                DragMode.VOLTA -> {
                    val drag = voltaDrag
                    val abs = rawToAbsolutePoint(
                        change.position, offset, scale, density,
                        paginatedView, pages, pageSlots,
                    )
                    if (drag != null && abs != null) {
                        val rel = result.transformerSnapshot.toRelative(abs)
                        val nearestSystem = nearestDisplayedSystemByStaffCore(
                            result, change.position, offset, scale, density,
                            paginatedView, pages, pageSlots,
                        )
                        val candidates = result.measureBounds.filter {
                            val inRange = when (drag.endpoint) {
                                VoltaEndpoint.START ->
                                    it.measureNumber <= drag.originalEndMeasure
                                VoltaEndpoint.END ->
                                    it.measureNumber >= drag.originalStartMeasure
                            }
                            inRange && (nearestSystem == null || it.systemIndex == nearestSystem)
                        }
                        val target = candidates.minByOrNull {
                            val handleX = when (drag.endpoint) {
                                VoltaEndpoint.START -> it.leftX
                                VoltaEndpoint.END -> it.rightX
                            }
                            kotlin.math.abs(handleX.value - rel.x.value)
                        }?.measureNumber
                        if (target != null) {
                            voltaDrag = when (drag.endpoint) {
                                VoltaEndpoint.START -> drag.copy(currentStartMeasure = target)
                                VoltaEndpoint.END -> drag.copy(currentEndMeasure = target)
                            }
                        }
                    }
                    change.consume()
                }
                DragMode.NAVIGATION -> {
                    val drag = navigationDrag
                    val abs = rawToAbsolutePoint(
                        change.position, offset, scale, density,
                        paginatedView, pages, pageSlots,
                    )
                    if (drag != null && abs != null) {
                        val rel = result.transformerSnapshot.toRelative(abs)
                        val nearestSystem = nearestDisplayedSystemByStaffCore(
                            result, change.position, offset, scale, density,
                            paginatedView, pages, pageSlots,
                        )
                        val target = result.measureBounds
                            .asSequence()
                            .filter {
                                nearestSystem == null || it.systemIndex == nearestSystem
                            }
                            .minByOrNull {
                                kotlin.math.abs(it.rightX.value - rel.x.value)
                            }
                        navigationDrag = drag.copy(
                            current = NavigationMarkOffset(
                                dx = drag.start.dx + rel.x.value - startRelX,
                                dy = drag.start.dy + rel.y.value - startRelY,
                            ),
                            previewDx = rel.x.value - startRelX,
                            previewDy = rel.y.value - startRelY,
                            targetBoundaryMeasure = target?.measureNumber
                                ?: drag.targetBoundaryMeasure,
                            targetAnchorX = target?.rightX?.value
                                ?: drag.targetAnchorX,
                            targetAnchorY = nearestSystem?.let {
                                navigationSystemAnchorY(result, it)
                            } ?: drag.targetAnchorY,
                        )
                    }
                    change.consume()
                }
                DragMode.MARQUEE -> {
                    lastRaw = change.position
                    marqueeRect = Rect(
                        min(startRaw.x, lastRaw.x), min(startRaw.y, lastRaw.y),
                        max(startRaw.x, lastRaw.x), max(startRaw.y, lastRaw.y),
                    )
                    change.consume()
                }
                else -> {}
            }
        }
    }
    // Tap to select (coordinates adjusted for current transform).
    // Hit-test against the displayed RenderResult's own spatial index, so a
            // pick always matches exactly what is on screen — single source of truth.
    // scale/offset are intentionally NOT keys: they change every frame during a
                        // pan/zoom, and keying on them would recompose this Canvas (new draw lambda →
                        // full re-engrave of every element) on each frame, which is what made large
    // scores stutter while dragging. The tap callback reads them live via the
    // captured State delegate, so the latest transform is always used.
}
