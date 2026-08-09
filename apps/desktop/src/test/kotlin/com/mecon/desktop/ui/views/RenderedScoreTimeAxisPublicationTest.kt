package com.mecon.desktop.ui.views

import com.mecon.api.primitive.Fraction
import com.mecon.api.primitive.TimeCode
import com.mecon.renderer.geometry.StaffSpace
import com.mecon.renderer.layout.AlignedTimeAxisRequest
import com.mecon.renderer.layout.TimeAxisSegmentRequest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RenderedScoreTimeAxisPublicationTest {
    @Test
    fun retainsLastAxisWhileReplacementAlignedRenderIsPending() {
        val request = AlignedTimeAxisRequest(
            segments = listOf(
                TimeAxisSegmentRequest(
                    start = TimeCode.of(1, Fraction.ZERO),
                    end = TimeCode.of(1, Fraction.QUARTER),
                    preferredWidth = StaffSpace(10f),
                )
            )
        )

        assertFalse(shouldPublishResolvedTimeAxis(request, nextAxis = null))
        assertTrue(shouldPublishResolvedTimeAxis(request = null, nextAxis = null))
    }
}
