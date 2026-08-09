package com.mecon.features.freepractice

import com.mecon.api.primitive.Pitch
import com.mecon.api.primitive.Duration
import com.mecon.api.primitive.Fraction
import com.mecon.api.primitive.TimeCode
import com.mecon.api.runtime.RuntimeScore
import com.mecon.exploration.VoicePlanScoreAssembler
import com.mecon.features.scoreediting.ScoreEditIntent
import com.mecon.theory.freepractice.WorkspaceSlotId
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

class FreePracticeTraceTest {
    @Test
    fun replaysGoldenTrace() {
        val trace = Json.parseToJsonElement(File(requireNotNull(System.getProperty("freepractice.trace.path"))).readText())
            .jsonObject.getValue("steps").jsonArray
        val document = FreePracticePreset.document()
        val score = VoicePlanScoreAssembler.emptyPracticeScore(
            document.workspace,
            com.mecon.api.primitive.KeySignature.majorByFifths(0),
            document.settings.staffVoices,
        )
        val session = FreePracticeSession.open(document, RuntimeScore.fromStorage(score))
        val initial = session.initialUpdate()
        var catalogRequest = initial.catalogRequests.single()
        var request: PracticeBackgroundRequest? = null
        var insertedTonalLayoutId: com.mecon.theory.freepractice.WorkspaceTonalLayoutId? = null
        var insertedIdiomId: com.mecon.theory.freepractice.WorkspaceIdiomInstanceId? = null
        var appendedSlotId: WorkspaceSlotId? = null
        var findingRequest: PracticeFindingRequest? = initial.findingRequests.single()
        var previewRequestId = 0L
        trace.forEach { element ->
            val step = element.jsonObject
            val expectedRevision = step["expectedRevision"]?.jsonPrimitive?.int
            val result = when (step.getValue("kind").jsonPrimitive.content) {
                "staleTarget" -> session.dispatch(
                    FreePracticeIntent.SelectSlot(expectedRevision!!.toLong(), WorkspaceSlotId("missing"))
                )
                "runWriting" -> session.dispatch(
                    FreePracticeIntent.RunWriting(expectedRevision!!.toLong(), WorkspaceSlotId("slot-0"))
                ).also { request = it.requests.single() }
                "setPivotChord" -> session.dispatch(
                    FreePracticeIntent.SetPivotChord(
                        expectedRevision!!.toLong(),
                        WorkspaceSlotId("slot-0"),
                        true,
                    )
                )
                "setTonalLayoutKey" -> session.dispatch(
                    FreePracticeIntent.SetTonalLayoutKey(
                        expectedRevision!!.toLong(),
                        session.frame().document.workspace.tonalLayouts.first().id,
                        step.getValue("fifths").jsonPrimitive.int,
                        com.mecon.theory.freepractice.WorkspaceKeyMode.valueOf(
                            step.getValue("mode").jsonPrimitive.content,
                        ),
                    )
                )
                // Timeline gestures replay through the shared preview/commit shape: the same
                // PracticeTimelineEdit is previewed and then committed, and the two projections
                // must agree on every platform.
                "timelineEdit" -> {
                    val edit = timelineEdit(step, session.frame())
                    val preview = session.previewTimelineEdit(
                        PracticeTimelinePreviewRequest(
                            requestId = ++previewRequestId,
                            baseRevision = session.frame().revision,
                            edit = edit,
                        )
                    )
                    session.dispatch(
                        FreePracticeIntent.TimelineEdit(expectedRevision!!.toLong(), edit)
                    ).also { committed ->
                        if (preview.accepted) {
                            assertEquals(preview.timeline, committed.frame.timeline)
                        }
                    }
                }
                "insertTonalLayout" -> session.dispatch(
                    FreePracticeIntent.InsertTonalLayout(
                        expectedRevision!!.toLong(),
                        step.getValue("fifths").jsonPrimitive.int,
                        com.mecon.theory.freepractice.WorkspaceKeyMode.valueOf(
                            step.getValue("mode").jsonPrimitive.content,
                        ),
                        com.mecon.api.primitive.Fraction.ZERO,
                        com.mecon.api.primitive.Fraction.QUARTER,
                    )
                ).also { result ->
                    insertedTonalLayoutId = result.frame.document.workspace.tonalLayouts
                        .single { it.id.value != "tonal-layout-0" }.id
                }
                "selectChordTonalLayout" -> session.dispatch(
                    FreePracticeIntent.SelectChordTonalLayout(
                        expectedRevision!!.toLong(),
                        WorkspaceSlotId("slot-0"),
                        requireNotNull(insertedTonalLayoutId),
                    )
                )
                "selectTonalLayout" -> session.dispatch(
                    FreePracticeIntent.SelectTonalLayout(
                        expectedRevision!!.toLong(),
                        if (step.getValue("target").jsonPrimitive.content == "inserted") {
                            requireNotNull(insertedTonalLayoutId)
                        } else {
                            session.frame().document.workspace.tonalLayouts.first().id
                        },
                    )
                )
                "setInsertedTonalLayoutKey" -> session.dispatch(
                    FreePracticeIntent.SetTonalLayoutKey(
                        expectedRevision!!.toLong(),
                        requireNotNull(insertedTonalLayoutId),
                        step.getValue("fifths").jsonPrimitive.int,
                        com.mecon.theory.freepractice.WorkspaceKeyMode.valueOf(
                            step.getValue("mode").jsonPrimitive.content,
                        ),
                    )
                )
                "removeTonalLayout" -> session.dispatch(
                    FreePracticeIntent.RemoveTonalLayout(
                        expectedRevision!!.toLong(),
                        requireNotNull(insertedTonalLayoutId),
                    )
                )
                "applyCatalogFixture" -> {
                    val slot = session.frame().document.workspace.slots.single()
                    val choice = session.frame().catalog.chordChoices.first().choice
                    fun variant(id: String) = PracticeIdiomVariantView(
                        id = id,
                        title = id,
                        durations = listOf(com.mecon.api.primitive.Fraction.QUARTER),
                        chordIdentities = listOf("I"),
                        chordChoices = listOf(choice),
                    )
                    session.applyTeachingCatalogResult(
                        PracticeTeachingCatalogResult(
                            requestId = catalogRequest.requestId,
                            baseRevision = catalogRequest.baseRevision,
                            fingerprint = catalogRequest.fingerprint,
                            definitions = listOf(
                                PracticeIdiomDefinitionView(
                                    id = "trace.idiom",
                                    title = "Trace idiom",
                                    sourceExerciseId = "trace-exercise",
                                    sourceChapterId = "trace-chapter",
                                    availableByDefault = true,
                                    variants = listOf(variant("variant-a"), variant("variant-b")),
                                )
                            ),
                        )
                    )
                }
                "insertIdiom" -> session.dispatch(
                    FreePracticeIntent.InsertIdiom(
                        expectedRevision!!.toLong(),
                        WorkspaceSlotId("slot-0"),
                        "trace.idiom",
                        "variant-a",
                    )
                ).also { result ->
                    insertedIdiomId = result.frame.document.workspace.idiomInstances.singleOrNull()?.id
                }
                "replaceIdiom" -> session.dispatch(
                    FreePracticeIntent.ReplaceIdiom(
                        expectedRevision!!.toLong(),
                        requireNotNull(insertedIdiomId),
                        "trace.idiom",
                        "variant-b",
                    )
                )
                "selectIdiom" -> session.dispatch(
                    FreePracticeIntent.SelectIdiom(
                        expectedRevision!!.toLong(),
                        requireNotNull(insertedIdiomId),
                    )
                )
                "removeIdiom" -> session.dispatch(
                    FreePracticeIntent.RemoveIdiom(
                        expectedRevision!!.toLong(),
                        requireNotNull(insertedIdiomId),
                    )
                )
                "updateWritingSettings" -> session.dispatch(
                    FreePracticeIntent.UpdateWritingSettings(
                        expectedRevision!!.toLong(),
                        session.frame().document.settings.writing.copy(
                            autoWritingEnabled = false,
                            backtrackChordCount = 2,
                            replayChordCount = 3,
                            playbackTempoBpm = 96,
                        ),
                    )
                )
                "insertChordRange" -> {
                    val before = session.frame().document.workspace.slots.mapTo(hashSetOf()) { it.id }
                    session.dispatch(
                        FreePracticeIntent.InsertChordRange(
                            expectedRevision!!.toLong(),
                            session.frame().timeline.end,
                            com.mecon.api.primitive.Fraction.QUARTER,
                        )
                    ).also { result ->
                        appendedSlotId = result.frame.document.workspace.slots.single { it.id !in before }.id
                    }
                }
                "removeChordRange" -> session.dispatch(
                    FreePracticeIntent.RemoveChordRange(
                        expectedRevision!!.toLong(),
                        requireNotNull(appendedSlotId),
                    )
                )
                "setCatalogFilter" -> session.dispatch(
                    FreePracticeIntent.SetCatalogFilter(expectedRevision!!.toLong(), true)
                )
                "updateStaffVoices" -> session.dispatch(
                    FreePracticeIntent.UpdateStaffVoices(
                        expectedRevision!!.toLong(),
                        com.mecon.theory.writing.GrandStaffVoiceLayout(
                            step.getValue("upperVoiceCount").jsonPrimitive.int,
                            step.getValue("lowerVoiceCount").jsonPrimitive.int,
                        ),
                    )
                )
                "applyFindingFixture" -> requireNotNull(findingRequest).let { active ->
                    session.applyFindingResult(
                        PracticeFindingResult(
                            requestId = active.requestId,
                            baseRevision = active.baseRevision,
                            fingerprint = active.fingerprint,
                            items = listOf(
                                PracticeFindingView(
                                    messageKey = "freePractice.finding.trace",
                                    severity = PracticeFindingSeverity.INFO,
                                    message = "共享规则提示文字",
                                )
                            ),
                        )
                    )
                }
                "rebuildPractice" -> session.dispatch(
                    FreePracticeIntent.RebuildPractice(
                        expectedRevision!!.toLong(),
                        step.getValue("polyphonyLimit").jsonPrimitive.int,
                        step.getValue("fifths").jsonPrimitive.int,
                        com.mecon.theory.freepractice.WorkspaceKeyMode.valueOf(
                            step.getValue("mode").jsonPrimitive.content,
                        ),
                    )
                )
                "applyFixture" -> {
                    val active = requireNotNull(request)
                    val voices = active.document.workspace.voices.sortedBy { it.order }
                    session.applyBackgroundResult(
                        PracticeBackgroundResult(
                            requestId = active.requestId,
                            baseRevision = active.baseRevision,
                            scopeFingerprint = active.scopeFingerprint,
                            kind = active.kind,
                            candidates = listOf(
                                PracticeVoicingCandidate(
                                    frames = listOf(
                                        PracticeVoicingFrame(
                                            active.triggerSlotId,
                                            voices.mapIndexed { index, voice ->
                                                voice.id to Pitch.fromMidi(72 - index * 7)
                                            }.toMap(),
                                        )
                                    ),
                                    diversityGroupKey = "trace-primary",
                                    score = 0.0,
                                )
                            ),
                            outcome = PracticeWritingOutcome.Solved(active.scopeSlotIds, null),
                        )
                    )
                }
                "insertManualNote" -> session.frame().let { before ->
                    session.dispatch(
                        FreePracticeIntent.Score(
                            expectedRevision = expectedRevision!!.toLong(),
                            inner = ScoreEditIntent.InsertNote(
                                expectedRevision = before.score.revision,
                                voiceTrackId = before.document.workspace.voices.first().id,
                                start = TimeCode.of(1, Fraction.ZERO),
                                duration = Duration.QUARTER,
                                pitch = Pitch.C5,
                            ),
                        ),
                    )
                }
                "rejectPolyphonyChord" -> session.frame().let { before ->
                    session.dispatch(
                        FreePracticeIntent.Score(
                            expectedRevision = expectedRevision!!.toLong(),
                            inner = ScoreEditIntent.InsertChord(
                                expectedRevision = before.score.revision,
                                voiceTrackId = before.document.workspace.voices.first().id,
                                start = TimeCode.of(1, Fraction.ZERO),
                                duration = Duration.QUARTER,
                                pitches = listOf(72, 76, 79, 83).map(Pitch::fromMidi),
                            ),
                        ),
                    )
                }
                "staleScoreRevision" -> session.dispatch(
                    FreePracticeIntent.Score(
                        expectedRevision = expectedRevision!!.toLong(),
                        inner = ScoreEditIntent.Undo(expectedRevision = 0),
                    ),
                )
                "undo" -> session.dispatch(FreePracticeIntent.Undo(expectedRevision!!.toLong()))
                "redo" -> session.dispatch(FreePracticeIntent.Redo(expectedRevision!!.toLong()))
                "cancelWriting" -> session.dispatch(FreePracticeIntent.CancelWriting(expectedRevision!!.toLong()))
                else -> error("Unknown trace step")
            }
            result.catalogRequests.singleOrNull()?.let { catalogRequest = it }
            result.findingRequests.singleOrNull()?.let { findingRequest = it }
            val update = session.toWireUpdate(result)
            assertEquals(FREE_PRACTICE_WIRE_SCHEMA_VERSION, update.schemaVersion)
            assertEquals(step.getValue("effect").jsonPrimitive.content, update.effect.kind.name)
            assertEquals(step.getValue("revision").jsonPrimitive.int.toLong(), update.revision)
            step["editPlayback"]?.jsonPrimitive?.content?.let { expected ->
                val actual = when (update.editPlayback) {
                    is PracticeEditPlayback.Audition -> "audition"
                    is PracticeEditPlayback.Excerpt -> "excerpt"
                    null -> null
                }
                assertEquals(expected, actual)
            }
            step["outcome"]?.jsonPrimitive?.content?.let { assertEquals(it, outcomeType(update.writing.outcome)) }
            step["scoreHasNotes"]?.jsonPrimitive?.boolean?.let { expected ->
                assertEquals(expected, RuntimeScore.fromStorage(update.score.score).getAllVoiceEvents().any { !it.isRest })
            }
            step["assignmentSourceCount"]?.jsonPrimitive?.int?.let { expected ->
                assertEquals(expected, update.document.workspace.voiceAssignmentSources.size)
            }
            step["scoreChanged"]?.jsonPrimitive?.boolean?.let { expected ->
                assertEquals(expected, update.score.scoreChanged)
            }
            step["renderFirstMeasure"]?.jsonPrimitive?.int?.let { expected ->
                assertEquals(expected, update.score.renderHint?.firstMeasure)
            }
            step["renderStructureReflow"]?.jsonPrimitive?.boolean?.let { expected ->
                assertEquals(expected, update.score.renderHint?.structureReflow)
            }
            step["pivot"]?.jsonPrimitive?.boolean?.let { expected ->
                assertEquals(expected, update.plan.pivotEnabled)
            }
            step["timelinePresentationReady"]?.jsonPrimitive?.boolean?.let { expected ->
                val selected = update.timeline.slots.first { it.id == update.selection.slotId }
                assertEquals(expected, selected.symbol != null)
                assertEquals(expected, selected.absoluteTones.isNotEmpty())
                assertEquals(expected, selected.relativeTones.isNotEmpty())
                assertEquals(expected, selected.readings.isNotEmpty())
            }
            if (step.getValue("kind").jsonPrimitive.content == "setTonalLayoutKey") {
                assertEquals(step.getValue("fifths").jsonPrimitive.int, update.plan.currentKey?.fifths)
                assertEquals(step.getValue("mode").jsonPrimitive.content, update.plan.currentKey?.mode?.name)
            }
            step["slots"]?.jsonArray?.let { expected ->
                assertEquals(
                    expected.map { it.jsonPrimitive.content },
                    update.timeline.slots.map { "${it.onset}+${it.duration}" },
                )
            }
            step["layouts"]?.jsonArray?.let { expected ->
                assertEquals(
                    expected.map { it.jsonPrimitive.content },
                    update.timeline.tonalLayouts.map { "${it.start}+${it.end}" },
                )
            }
            step["layoutCount"]?.jsonPrimitive?.int?.let { expected ->
                assertEquals(expected, update.timeline.tonalLayouts.size)
            }
            step["selectedLayout"]?.jsonPrimitive?.content?.let { expected ->
                assertEquals(expected, update.document.workspace.slots.single().tonalLayoutId?.value)
            }
            step["selectionLayout"]?.jsonPrimitive?.content?.let { expected ->
                assertEquals(expected, update.selection.tonalLayoutId?.value)
            }
            step["selectionIdiom"]?.jsonPrimitive?.content?.let { expected ->
                assertEquals(expected, update.selection.idiomInstanceId?.value)
            }
            step["catalogDefinitionCount"]?.jsonPrimitive?.int?.let { expected ->
                assertEquals(expected, update.plan.idiomCatalog.definitions.size)
            }
            step["idiomCount"]?.jsonPrimitive?.int?.let { expected ->
                assertEquals(expected, update.document.workspace.idiomInstances.size)
            }
            step["idiomVariant"]?.jsonPrimitive?.content?.let { expected ->
                assertEquals(expected, update.document.workspace.idiomInstances.single().variantId)
            }
            step["timelineIdiomTitle"]?.jsonPrimitive?.content?.let { expected ->
                assertEquals(expected, update.timeline.idioms.single().title)
            }
            step["timelineIdiomRange"]?.jsonPrimitive?.content?.let { expected ->
                val idiom = update.timeline.idioms.single()
                assertEquals(expected, "${idiom.start}+${idiom.end}")
            }
            step["editableSlotCount"]?.jsonPrimitive?.int?.let { expected ->
                assertEquals(expected, update.timeline.slots.count { it.capabilities.canTranslate })
            }
            step["playbackTempoBpm"]?.jsonPrimitive?.int?.let { expected ->
                assertEquals(expected, update.document.settings.writing.playbackTempoBpm)
                assertEquals(false, update.document.settings.writing.autoWritingEnabled)
            }
            step["slotCount"]?.jsonPrimitive?.int?.let { expected ->
                assertEquals(expected, update.timeline.slots.size)
            }
            step["includeOffKey"]?.jsonPrimitive?.boolean?.let { expected ->
                assertEquals(expected, update.plan.idiomCatalog.includeOffKey)
                assertEquals(expected, update.catalogRequests.single().includeOffKey)
            }
            step["polyphonyLimit"]?.jsonPrimitive?.int?.let { expected ->
                assertEquals(expected, update.document.settings.polyphonyLimit)
                assertEquals(expected, update.document.workspace.voices.size)
                assertEquals(step.getValue("fifths").jsonPrimitive.int, update.document.settings.initialKey.fifths)
                assertEquals(step.getValue("mode").jsonPrimitive.content, update.document.settings.initialKey.mode.name)
            }
            step["findingCount"]?.jsonPrimitive?.int?.let { expected ->
                assertEquals(expected, update.findings.items.size)
                assertEquals(false, update.findings.stale)
            }
            step["findingMessage"]?.jsonPrimitive?.content?.let { expected ->
                assertEquals(expected, update.findings.items.single().message)
            }
            step["upperVoiceCount"]?.jsonPrimitive?.int?.let { expected ->
                assertEquals(expected, update.document.settings.staffVoices.upperVoiceCount)
                assertEquals(
                    step.getValue("lowerVoiceCount").jsonPrimitive.int,
                    update.document.settings.staffVoices.lowerVoiceCount,
                )
            }
        }
    }

    private fun timelineEdit(
        step: kotlinx.serialization.json.JsonObject,
        frame: FreePracticeFrame,
    ): PracticeTimelineEdit {
        val workspace = frame.document.workspace
        fun slot(key: String = "slotIndex") = workspace.slots[step.getValue(key).jsonPrimitive.int].id
        fun layout() = workspace.tonalLayouts[step.getValue("layoutIndex").jsonPrimitive.int].id
        fun fraction(key: String) = step[key]?.jsonPrimitive?.content?.let {
            val (numerator, denominator) = it.split("/")
            Fraction.of(numerator.toInt(), denominator.toInt())
        }
        return when (val edit = step.getValue("edit").jsonPrimitive.content) {
            "placeChordRange" -> PracticeTimelineEdit.PlaceChordRange(
                slot(),
                requireNotNull(fraction("onset")),
                requireNotNull(fraction("duration")),
            )
            "translateChordRange" -> PracticeTimelineEdit.TranslateChordRange(
                slot(),
                requireNotNull(fraction("delta")),
                step["includeFollowing"]?.jsonPrimitive?.boolean ?: false,
            )
            "moveSharedBoundary" -> PracticeTimelineEdit.MoveSharedBoundary(
                slot("leftSlotIndex"),
                requireNotNull(fraction("boundary")),
            )
            "moveBoundaryWithFollowing" -> PracticeTimelineEdit.MoveBoundaryWithFollowing(
                slot("leftSlotIndex"),
                requireNotNull(fraction("boundary")),
            )
            "setTonalLayoutBounds" -> PracticeTimelineEdit.SetTonalLayoutBounds(
                layout(),
                requireNotNull(fraction("start")),
                fraction("end"),
            )
            else -> error("Unknown timeline edit $edit")
        }
    }

    private fun outcomeType(outcome: PracticeWritingOutcome?): String? = when (outcome) {
        is PracticeWritingOutcome.Solved -> "solved"
        PracticeWritingOutcome.NoSolution -> "noSolution"
        PracticeWritingOutcome.BudgetExhausted -> "budgetExhausted"
        PracticeWritingOutcome.Cancelled -> "cancelled"
        is PracticeWritingOutcome.Invalid -> "invalid"
        null -> null
    }
}
