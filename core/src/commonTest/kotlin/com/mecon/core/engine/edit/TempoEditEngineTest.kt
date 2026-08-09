package com.mecon.core.engine.edit

import com.mecon.api.primitive.Fraction
import com.mecon.api.primitive.TimeCode
import com.mecon.api.runtime.RuntimeScore
import com.mecon.api.runtime.resolvedTempoKeyframes
import com.mecon.api.storage.StorageScore
import com.mecon.api.storage.events.TempoDisplayStyle
import com.mecon.api.storage.events.TempoMarkType
import com.mecon.api.storage.events.TempoTransition
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class TempoEditEngineTest {
    @Test
    fun referencedMarksFollowOpeningTempo() {
        var runtime = RuntimeScore.fromStorage(StorageScore.create(StorageScore.CreationOptions(tempo = 100f)))
        val opening = runtime.globalTrack.tempoEvents.single()
        runtime = assertNotNull(TempoEditEngine.addMark(
            runtime,
            TimeCode.of(1, Fraction.QUARTER),
            TempoMarkType.TEMPO_I,
        )).score
        val tempoI = runtime.globalTrack.tempoEvents.last()

        runtime = assertNotNull(TempoEditEngine.update(runtime, opening.id, effectiveBpm = 132f)).score
        val resolved = runtime.resolvedTempoKeyframes().associate { it.source.id to it.effectiveBpm }

        assertEquals(132f, resolved[opening.id])
        assertEquals(132f, resolved[tempoI.id])
    }

    @Test
    fun gradualRangeCreatesLinkedEndpointAndCannotMoveOpening() {
        val runtime = RuntimeScore.fromStorage(StorageScore.create(StorageScore.CreationOptions(tempo = 120f)))
        val result = assertNotNull(TempoEditEngine.addGradual(
            runtime,
            TimeCode.of(1, Fraction.QUARTER),
            TimeCode.of(1, Fraction.HALF),
            TempoMarkType.RITARDANDO,
        ))
        val events = result.score.globalTrack.tempoEvents.sortedBy { it.onset }
        val gradual = events[1]
        val endpoint = events[2]

        assertEquals(TempoDisplayStyle.GRADUAL_TEXT, gradual.displayStyle)
        assertEquals(TempoTransition.LINEAR, gradual.transitionToNext)
        assertEquals(gradual.id, endpoint.referenceEventId)
        assertEquals(0.85f, endpoint.referenceRatio)
        assertNull(TempoEditEngine.move(result.score, events.first().id, TimeCode.ofMeasure(2)))
    }

    @Test
    fun aTempoAfterGradualReferencesItsStartingTempo() {
        var runtime = RuntimeScore.fromStorage(StorageScore.create(StorageScore.CreationOptions(tempo = 120f)))
        runtime = assertNotNull(TempoEditEngine.addGradual(
            runtime,
            TimeCode.of(1, Fraction.QUARTER),
            TimeCode.of(1, Fraction.HALF),
            TempoMarkType.RITARDANDO,
        )).score
        val gradual = runtime.globalTrack.tempoEvents.first { it.markType == TempoMarkType.RITARDANDO }
        runtime = assertNotNull(TempoEditEngine.addMark(
            runtime,
            TimeCode.of(1, Fraction(3, 4)),
            TempoMarkType.A_TEMPO,
        )).score
        val aTempo = runtime.globalTrack.tempoEvents.first { it.markType == TempoMarkType.A_TEMPO }

        assertEquals(gradual.id, aTempo.referenceEventId)
        assertEquals(120f, runtime.resolvedTempoKeyframes().first { it.source.id == aTempo.id }.effectiveBpm)
    }
}
