package com.mecon.core.engine.edit

import com.mecon.api.interaction.LayoutBreakKind
import com.mecon.api.runtime.RuntimeScore
import com.mecon.api.storage.StorageScore
import com.mecon.api.storage.tracks.StoragePageBreak
import com.mecon.api.storage.tracks.StorageSystemBreak
import kotlin.test.Test
import kotlin.test.assertEquals

class LayoutBreakEditEngineTest {
    private fun score() = RuntimeScore.fromStorage(StorageScore.create(StorageScore.CreationOptions(measureCount = 4)))

    @Test
    fun boundaryIsMutuallyExclusiveAndCanBeCleared() {
        val system = LayoutBreakEditEngine.set(score(), 3, LayoutBreakKind.SYSTEM)!!
        assertEquals(1, system.globalTrack.events.filterIsInstance<StorageSystemBreak>().size)
        assertEquals(setOf(3), system.forcedSystemBreaks)

        val page = LayoutBreakEditEngine.set(system, 3, LayoutBreakKind.PAGE)!!
        assertEquals(0, page.globalTrack.events.filterIsInstance<StorageSystemBreak>().size)
        assertEquals(1, page.globalTrack.events.filterIsInstance<StoragePageBreak>().size)
        assertEquals(setOf(3), page.forcedPageBreaks)
        assertEquals(setOf(3), page.forcedSystemBreaks)

        val cleared = LayoutBreakEditEngine.set(page, 3, null)!!
        assertEquals(emptySet(), cleared.forcedSystemBreaks)
        assertEquals(emptySet(), cleared.forcedPageBreaks)
    }
}
