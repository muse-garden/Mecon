package com.mecon.desktop

import com.mecon.api.interaction.BarlineSection
import com.mecon.api.interaction.BarlineVisualPlacement
import com.mecon.api.interaction.EventSection
import com.mecon.api.interaction.VoiceSlurSection
import com.mecon.api.interaction.VoiceTieSection
import com.mecon.api.storage.tracks.MeasureRange
import com.mecon.core.engine.edit.ExpressionEditEngine
import com.mecon.core.engine.edit.NoteEditEngine
import com.mecon.core.engine.edit.TempoEditEngine
import com.mecon.desktop.service.ScoreSession
import com.mecon.desktop.ui.components.inspector.BarlinePropertiesActions
import com.mecon.desktop.ui.components.inspector.CurvePropertiesActions
import com.mecon.desktop.ui.components.inspector.GraceGroupPropertiesActions
import com.mecon.desktop.ui.components.inspector.PerformancePropertiesActions
import com.mecon.desktop.ui.components.inspector.SelectionInspectorActions
import com.mecon.desktop.ui.components.inspector.SelectionInspectorContext
import com.mecon.desktop.ui.components.inspector.StaffVisibilityPropertiesActions
import com.mecon.desktop.ui.components.inspector.TempoPropertiesActions
import com.mecon.desktop.ui.components.inspector.OrnamentPropertiesActions

internal fun selectionInspectorContext(
    selection: Set<EventSection>,
    session: ScoreSession,
    maxMeasure: Int,
): SelectionInspectorContext = SelectionInspectorContext(
    selection = selection,
    runtimeScore = session.runtimeScore,
    computedScore = session.computedScore,
    runtimeGeometry = session.runtimeScore?.geometry,
    renderedGeometry = session.lastRenderedGeometry,
    maxMeasure = maxMeasure,
)

internal fun selectionInspectorActions(
    session: ScoreSession,
    selection: Set<EventSection>,
    onSelectionChange: (Set<EventSection>) -> Unit,
    onApplyExpressionResult: (ExpressionEditEngine.Result?) -> Unit,
    onAfterEdit: (Set<EventSection>) -> Unit,
    revealStaff: (List<com.mecon.api.primitive.TrackId>, MeasureRange) -> Unit,
    deleteSelection: () -> Unit,
): SelectionInspectorActions = SelectionInspectorActions(
    delete = deleteSelection,
    curves = CurvePropertiesActions(
        changeSlurDirection = changeSlurDirection@{ above ->
            val section = selection.singleOrNull() as? VoiceSlurSection
                ?: return@changeSlurDirection
            val slur = resolveSlur(section, session.computedScore)
                ?: return@changeSlurDirection
            session.applySlurDirection(slur.slurId, above)
        },
        changeTieDirection = changeTieDirection@{ above ->
            val section = selection.singleOrNull() as? VoiceTieSection
                ?: return@changeTieDirection
            session.applyTieDirection(
                section.sourceEvent.id,
                section.sourcePitchIndex,
                above,
            )
        },
    ),
    tempo = TempoPropertiesActions(
        changeBpm = { id, bpm ->
            session.runtimeScore?.let { runtime ->
                onApplyExpressionResult(TempoEditEngine.update(runtime, id, effectiveBpm = bpm))
            }
        },
        changeDisplayStyle = { id, style ->
            session.runtimeScore?.let { runtime ->
                onApplyExpressionResult(TempoEditEngine.update(runtime, id, displayStyle = style))
            }
        },
        changeTransition = { id, transition ->
            session.runtimeScore?.let { runtime ->
                onApplyExpressionResult(TempoEditEngine.update(runtime, id, transition = transition))
            }
        },
    ),
    barline = BarlinePropertiesActions(
        changeRepeatCount = { boundary, count ->
            session.applyBarlineRepeatCountEdit(boundary, count) { newSelection ->
                val previous = selection.singleOrNull() as? BarlineSection
                val updated = newSelection.singleOrNull() as? BarlineSection
                onSelectionChange(
                    updated?.let {
                        setOf(
                            it.copy(
                                systemIndex = previous?.systemIndex,
                                visualPlacement = previous?.visualPlacement
                                    ?: BarlineVisualPlacement.INLINE,
                            )
                        )
                    }.orEmpty()
                )
            }
        },
    ),
    staffVisibility = StaffVisibilityPropertiesActions(revealStaff = revealStaff),
    performance = PerformancePropertiesActions(
        changeAmount = { id, amount ->
            session.runtimeScore?.let { runtime ->
                onApplyExpressionResult(
                    ExpressionEditEngine.updatePerformanceMark(runtime, id, amount)
                )
            }
        },
    ),
    graceGroup = GraceGroupPropertiesActions(
        changeGroup = { eventId, totalDuration, stealFrom ->
            val voiceId = session.runtimeScore?.voiceTrackIdOf(eventId)
            if (voiceId != null) {
                session.applyGraceGroupEdits(
                    listOf(
                        NoteEditEngine.GraceGroupEdit(
                            voiceTrackId = voiceId,
                            eventId = eventId,
                            totalDuration = totalDuration,
                            stealFrom = stealFrom,
                        )
                    ),
                    onAfter = onAfterEdit,
                )
            }
        },
    ),
    ornaments = OrnamentPropertiesActions(
        changeUpperAccidental = { id, accidental ->
            session.runtimeScore?.let { runtime ->
                onApplyExpressionResult(ExpressionEditEngine.updateOrnament(
                    runtime, id, upperAccidental = accidental, updateUpperAccidental = true,
                ))
            }
        },
        changeLowerAccidental = { id, accidental ->
            session.runtimeScore?.let { runtime ->
                onApplyExpressionResult(ExpressionEditEngine.updateOrnament(
                    runtime, id, lowerAccidental = accidental, updateLowerAccidental = true,
                ))
            }
        },
        changeElementDuration = { id, duration ->
            session.runtimeScore?.let { runtime ->
                onApplyExpressionResult(ExpressionEditEngine.updateOrnament(
                    runtime, id, elementDuration = duration,
                ))
            }
        },
        changeOscillations = { id, count ->
            session.runtimeScore?.let { runtime ->
                onApplyExpressionResult(ExpressionEditEngine.updateOrnament(
                    runtime, id, oscillations = count,
                ))
            }
        },
        changeTrillPlaybackMode = { id, mode ->
            session.runtimeScore?.let { runtime ->
                onApplyExpressionResult(ExpressionEditEngine.updateOrnament(
                    runtime, id, trillPlaybackMode = mode,
                ))
            }
        },
    ),
)
