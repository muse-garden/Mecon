package com.mecon.input

import com.mecon.api.primitive.Duration
import com.mecon.api.primitive.Fraction
import com.mecon.api.primitive.KeySignature
import com.mecon.api.primitive.TimeCode
import com.mecon.api.primitive.TimeSignature
import com.mecon.api.runtime.RuntimeScore
import com.mecon.api.storage.StorageScore
import kotlin.test.Test
import kotlin.test.assertEquals

class NoteEntryNavigationTest {
    private val score = RuntimeScore.fromStorage(
        StorageScore.create(
            StorageScore.CreationOptions(
                title = "navigation",
                timeSignature = TimeSignature.COMMON,
                keySignature = KeySignature.C_MAJOR,
                measureCount = 2,
            )
        )
    )

    @Test
    fun `advance and retreat normalize across barlines`() {
        assertEquals(
            TimeCode.of(2, Fraction.ZERO),
            NoteEntryNavigation.advance(score, TimeCode.of(1, Fraction(3, 4)), Duration.QUARTER),
        )
        assertEquals(
            TimeCode.of(1, Fraction(3, 4)),
            NoteEntryNavigation.retreat(score, TimeCode.of(2, Fraction.ZERO), Duration.QUARTER),
        )
    }
}
