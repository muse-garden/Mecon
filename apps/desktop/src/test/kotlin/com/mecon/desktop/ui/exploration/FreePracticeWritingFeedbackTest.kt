package com.mecon.desktop.ui.exploration

import com.mecon.features.freepractice.PracticeWritingOutcome
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FreePracticeWritingFeedbackTest {
    @Test
    fun `successful auto writing is not presented as an operation error`() {
        val outcome = PracticeWritingOutcome.Solved(scope = emptyList(), replayRange = null)

        assertNull(writingOperationError("auto writing completed", outcome))
    }

    @Test
    fun `unsuccessful auto writing keeps its diagnostic`() {
        assertEquals(
            "no solution",
            writingOperationError("no solution", PracticeWritingOutcome.NoSolution),
        )
    }
}
