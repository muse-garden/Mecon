package com.mecon.desktop.ui.exploration

import com.mecon.theory.freepractice.WorkspaceSlotId
import kotlin.test.Test
import kotlin.test.assertEquals

class FreePracticeEditorSelectionTest {
    private val first = WorkspaceSlotId("slot-1")
    private val previous = WorkspaceSlotId("slot-2")
    private val inserted = WorkspaceSlotId("slot-3")

    @Test
    fun sessionSelectionWinsWhileMirroredAdapterSelectionIsStale() {
        assertEquals(
            inserted,
            resolvePracticeSelectedSlotId(
                availableSlotIds = listOf(first, previous, inserted),
                sessionSelectedSlotId = inserted,
                mirroredSelectedSlotId = first,
            ),
        )
    }

    @Test
    fun validMirroredSelectionIsUsedBeforeSessionIsAvailable() {
        assertEquals(
            previous,
            resolvePracticeSelectedSlotId(
                availableSlotIds = listOf(first, previous),
                sessionSelectedSlotId = null,
                mirroredSelectedSlotId = previous,
            ),
        )
    }
}
