package com.mecon.api.state

import com.mecon.api.computed.ComputedEventStore
import com.mecon.api.computed.ComputedScore
import com.mecon.api.runtime.RuntimeScore
import com.mecon.api.runtime.toStorage
import com.mecon.api.storage.PageArrangement
import com.mecon.api.storage.StorageScore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ScoreStateManagerTest {

    /**
     * Regression: a view-preference toggle (page arrangement) must update the runtime score too, not
     * just storage. The edit path recommits via [RuntimeScore.toStorage], which round-trips the
     * arrangement — so a stale runtime copy would silently revert the toggle on the next edit.
     */
    @Test
    fun testUpdateViewPreferencesSyncsRuntime() {
        val storage = StorageScore.create(StorageScore.CreationOptions("T"))
        val runtime = RuntimeScore.fromStorage(storage)
        val manager = ScoreStateManager(runtime, ComputedScore(runtime, ComputedEventStore.EMPTY))

        manager.updateViewPreferences { it.copy(pageArrangement = PageArrangement.HORIZONTAL) }

        assertEquals(
            PageArrangement.HORIZONTAL,
            manager.currentState.runtimeScore.viewPreferences.pageArrangement,
            "runtime view preferences must track the toggle so toStorage keeps it across edits"
        )
        assertEquals(
            PageArrangement.HORIZONTAL,
            manager.currentState.runtimeScore.toStorage().viewPreferences.pageArrangement
        )
        assertTrue(
            manager.currentState.runtimeScore.viewPreferences.showMeasureNumbers,
            "measure numbers are enabled by default",
        )
        assertEquals(
            manager.currentState.runtimeScore.viewPreferences,
            manager.currentState.computedScore.runtime.viewPreferences,
            "computed runtime must carry the display preference used by the final render pass",
        )
    }

    @Test
    fun testMeasureNumberPreferenceUpdatesInPlace() {
        val runtime = RuntimeScore.fromStorage(StorageScore.create(StorageScore.CreationOptions("T")))
        val manager = ScoreStateManager(runtime, ComputedScore(runtime, ComputedEventStore.EMPTY))

        manager.updateViewPreferences { it.copy(showMeasureNumbers = false) }

        assertFalse(manager.currentState.runtimeScore.viewPreferences.showMeasureNumbers)
        assertFalse(manager.currentState.computedScore.runtime.viewPreferences.showMeasureNumbers)
        assertFalse(manager.canUndo(), "display toggles must not enter the undo history")
    }

    @Test
    fun testUndoRedoBasic() {
        // Setup initial scores (empty scores)
        val initialStorage = StorageScore.create(StorageScore.CreationOptions("Initial"))
        val initialRuntime = RuntimeScore.fromStorage(initialStorage)
        val initialComputed = ComputedScore(initialRuntime, ComputedEventStore.EMPTY)

        val manager = ScoreStateManager(initialRuntime, initialComputed)

        // Verify initial state
        assertEquals("Initial", manager.currentState.runtimeScore.metadata.title)
        assertFalse(manager.canUndo())
        assertFalse(manager.canRedo())

        // Create a new state 1
        val storage1 = initialStorage.updateMetadata { it.copy(title = "State 1") }
        val runtime1 = RuntimeScore.fromStorage(storage1)
        val computed1 = ComputedScore(runtime1, ComputedEventStore.EMPTY)
        manager.commitNewState(runtime1, computed1)

        assertEquals("State 1", manager.currentState.runtimeScore.metadata.title)
        assertTrue(manager.canUndo())
        assertFalse(manager.canRedo())

        // Undo to Initial
        manager.undo()
        assertEquals("Initial", manager.currentState.runtimeScore.metadata.title)
        assertFalse(manager.canUndo())
        assertTrue(manager.canRedo())

        // Redo to State 1
        manager.redo()
        assertEquals("State 1", manager.currentState.runtimeScore.metadata.title)
        assertTrue(manager.canUndo())
        assertFalse(manager.canRedo())

        // Create a new state 2 (overwrites redo stack)
        manager.undo() // Back to Initial
        val storage2 = initialStorage.updateMetadata { it.copy(title = "State 2") }
        val runtime2 = RuntimeScore.fromStorage(storage2)
        val computed2 = ComputedScore(runtime2, ComputedEventStore.EMPTY)
        manager.commitNewState(runtime2, computed2)

        assertEquals("State 2", manager.currentState.runtimeScore.metadata.title)
        assertTrue(manager.canUndo())
        assertFalse(manager.canRedo()) // State 1 is gone
    }

    @Test
    fun testEditorStateRidesUndoHistory() {
        val initialStorage = StorageScore.create(StorageScore.CreationOptions("Initial"))
        val initialRuntime = RuntimeScore.fromStorage(initialStorage)
        val initialComputed = ComputedScore(initialRuntime, ComputedEventStore.EMPTY)
        val manager = ScoreStateManager(initialRuntime, initialComputed)

        // A simple editor state: a "selection" string, captured/restored like the real selection set.
        var selection = "none"
        manager.registerEditorState("selection", object : EditorStateController {
            override fun capture(): Any? = selection
            override fun restore(snapshot: Any?) { selection = snapshot as? String ?: "none" }
        })

        fun commit(title: String) {
            val s = initialStorage.updateMetadata { it.copy(title = title) }
            val r = RuntimeScore.fromStorage(s)
            manager.commitNewState(r, ComputedScore(r, ComputedEventStore.EMPTY))
        }

        // State 0 selection = {A,B,C}; edit → State 1, post-edit selection = {A',B',C'}.
        selection = "A,B,C"
        commit("State 1")
        selection = "A',B',C'"

        // Undo restores State 0's selection (the multi-select that was active before the edit).
        manager.undo()
        assertEquals("Initial", manager.currentState.runtimeScore.metadata.title)
        assertEquals("A,B,C", selection)

        // Redo restores State 1's post-edit selection.
        manager.redo()
        assertEquals("A',B',C'", selection)
    }

    @Test
    fun compoundCommitSnapshotsOldAndNewCompanionStateAtomically() {
        val initialStorage = StorageScore.create(StorageScore.CreationOptions("Initial"))
        val initialRuntime = RuntimeScore.fromStorage(initialStorage)
        val manager = ScoreStateManager(
            initialRuntime,
            ComputedScore(initialRuntime, ComputedEventStore.EMPTY),
        )
        var workspace = "before"
        manager.registerEditorState("workspace", object : EditorStateController {
            override fun capture(): Any = workspace
            override fun restore(snapshot: Any?) {
                workspace = snapshot as? String ?: workspace
            }
        })

        val editedStorage = initialStorage.updateMetadata { it.copy(title = "Edited") }
        val editedRuntime = RuntimeScore.fromStorage(editedStorage)
        manager.commitNewState(
            runtimeScore = editedRuntime,
            computedScore = ComputedScore(editedRuntime, ComputedEventStore.EMPTY),
            updateCompanionState = { workspace = "after" },
        )

        manager.undo()
        assertEquals("Initial", manager.currentState.runtimeScore.metadata.title)
        assertEquals("before", workspace)

        manager.redo()
        assertEquals("Edited", manager.currentState.runtimeScore.metadata.title)
        assertEquals("after", workspace)
    }
}
