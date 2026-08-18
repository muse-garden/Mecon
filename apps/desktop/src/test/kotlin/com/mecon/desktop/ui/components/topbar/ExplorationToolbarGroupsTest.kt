package com.mecon.desktop.ui.components.topbar

import com.mecon.features.freepractice.FreePracticeToolbarSpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

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

    /**
     * The Web shell throws `Unsupported free-practice toolbar controls` when the descriptor names a
     * control it cannot draw. Desktop needs the same visibility: before 74d9978c the `when` simply
     * had no branch for the newly shared `structure` group, so 拍号 / 插入小节 rendered as nothing
     * at all with no error anywhere.
     */
    @Test
    fun aDescriptorGroupWithoutADesktopBranchIsReportedRatherThanDropped() {
        assertEquals(
            listOf("brand-new-group"),
            unsupportedExplorationToolbarGroups(listOf("file", "brand-new-group", "playback")),
        )
    }

    @Test
    fun everyGroupInTheSharedDescriptorHasADesktopBranch() {
        assertTrue(
            unsupportedExplorationToolbarGroups(
                FreePracticeToolbarSpec.descriptor.top.groups.map { it.id }
            ).isEmpty()
        )
    }
}
