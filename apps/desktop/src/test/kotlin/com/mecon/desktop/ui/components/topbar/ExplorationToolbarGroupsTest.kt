package com.mecon.desktop.ui.components.topbar

import kotlin.test.Test
import kotlin.test.assertEquals

class ExplorationToolbarGroupsTest {
    @Test
    fun autoSolveKeepsTheDesktopModeSwitchWithoutFreePracticeGroups() {
        assertEquals(
            listOf("file", "history", "mode"),
            explorationToolbarGroupIds(
                hasExplorationController = true,
                hasFreePracticeController = false,
            ),
        )
    }

    @Test
    fun freePracticeAddsEverySessionBackedGroupAfterTheModeSwitch() {
        assertEquals(
            listOf("file", "history", "mode", "writing", "structure", "playback"),
            explorationToolbarGroupIds(
                hasExplorationController = true,
                hasFreePracticeController = true,
            ),
        )
    }
}
