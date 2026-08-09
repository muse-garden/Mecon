package com.mecon.desktop.ui.harmony

import com.mecon.api.computed.ComputedVoiceEvent
import com.mecon.api.primitive.Accidental
import com.mecon.api.primitive.Pitch
import com.mecon.core.engine.computeScore
import com.mecon.desktop.uikit.components.ChordDetailUiConstruction
import com.mecon.desktop.uikit.components.ChordDetailUiConstructionEvent
import com.mecon.desktop.uikit.components.ChordDetailUiConstructionTone
import kotlin.test.Test
import kotlin.test.assertEquals

class ChordConstructionScorePreviewTest {

    @Test
    fun restartsAccidentalContextForEveryConstructionChord() {
        val repeatedSharp = ChordDetailUiConstructionEvent(
            tones = listOf(ChordDetailUiConstructionTone(Pitch(0, 1), muted = false)),
        )
        val preview = ChordDetailUiConstruction(
            description = "",
            events = listOf(repeatedSharp, repeatedSharp),
            caption = "",
        ).toPreviewScore()

        val computedEvents = computeScore(preview.score).computedEvents.values
            .filterIsInstance<ComputedVoiceEvent>()
            .sortedBy { it.onset }

        assertEquals(listOf(1, 2), computedEvents.map { it.onset.measure })
        assertEquals(
            listOf(Accidental.SHARP, Accidental.SHARP),
            computedEvents.map { it.pitchData.single().effectiveAccidental },
        )
    }
}
