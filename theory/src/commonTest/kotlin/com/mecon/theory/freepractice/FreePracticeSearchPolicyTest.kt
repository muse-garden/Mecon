package com.mecon.theory.freepractice

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FreePracticeSearchPolicyTest {
    @Test
    fun initialPassIsNarrowAndOptimizationOwnsTheWiderSearch() {
        val initial = FreePracticeSearchPolicy.initial
        val optimization = FreePracticeSearchPolicy.optimization(seed = 37L)

        assertEquals(1, initial.maxResults)
        assertEquals(8, initial.prefixDiversity.frontierWidth)
        assertEquals(12, initial.candidateLimit)
        assertTrue(optimization.diversity.enabled)
        assertEquals(37L, optimization.diversity.seed)
        assertEquals(4, optimization.maxResults)
        assertEquals(24, optimization.prefixDiversity.frontierWidth)
        assertEquals(24, optimization.candidateLimit)
    }
}
