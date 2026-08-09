package com.mecon.desktop.service

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DocumentLoadTrackerTest {
    @Test
    fun `loading remains until installed document frame is displayed`() {
        val tracker = DocumentLoadTracker()

        tracker.begin()
        tracker.frameDisplayed(4L)
        assertTrue(tracker.isLoading)

        tracker.documentInstalled(5L)
        assertEquals(5L, tracker.targetDocumentVersion)
        tracker.frameDisplayed(4L)
        assertTrue(tracker.isLoading)

        tracker.frameDisplayed(5L)
        assertFalse(tracker.isLoading)
    }

    @Test
    fun `new load invalidates previous installed version`() {
        val tracker = DocumentLoadTracker()

        tracker.begin()
        tracker.documentInstalled(5L)
        tracker.begin()
        tracker.frameDisplayed(5L)

        assertTrue(tracker.isLoading)
    }

    @Test
    fun `failed load clears loading state`() {
        val tracker = DocumentLoadTracker()
        tracker.begin()

        tracker.cancel()

        assertFalse(tracker.isLoading)
    }
}
