package com.mecon.web

import com.mecon.api.primitive.Duration
import com.mecon.api.primitive.Fraction
import com.mecon.api.primitive.Pitch
import com.mecon.api.primitive.TimeCode
import com.mecon.api.storage.StorageScore
import com.mecon.core.serializer.ScoreSerializer
import com.mecon.features.scoreediting.ScoreEditCodec
import com.mecon.features.scoreediting.ScoreEditEffectKind
import com.mecon.features.scoreediting.ScoreEditIntent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class MeconScoreEditorTest {
    @Test
    fun facadeKeepsEditingBoundaryStringOnlyAndClosesExplicitly() {
        val score = StorageScore.create(StorageScore.CreationOptions(measureCount = 1))
        val voiceId = score.voiceTracks.keys.single()
        val editor = MeconScoreEditor(ScoreSerializer.toJson(score))
        assertEquals(0, ScoreEditCodec.decodeUpdate(editor.initialUpdateJson()).revision)

        val update = ScoreEditCodec.decodeUpdate(
            editor.dispatchJson(
                ScoreEditCodec.encodeIntent(
                    ScoreEditIntent.InsertNote(
                        expectedRevision = 0,
                        voiceTrackId = voiceId,
                        start = TimeCode.of(1, Fraction.ZERO),
                        duration = Duration.QUARTER,
                        pitch = Pitch.C4,
                    ),
                ),
            ),
        )
        assertEquals(1, update.revision)
        assertEquals(ScoreEditEffectKind.APPLIED, update.effect.kind)

        editor.close()
        assertFailsWith<IllegalStateException> { editor.initialUpdateJson() }
    }
}
