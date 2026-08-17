package com.mecon.desktop.ui.exploration

import com.mecon.api.primitive.TimeCode
import com.mecon.api.primitive.TimeSignature
import com.mecon.desktop.service.PracticeWritingState
import com.mecon.exploration.FreePracticeWritingSettings
import com.mecon.theory.ModulationKey
import com.mecon.theory.writing.GrandStaffVoiceLayout
import com.mecon.features.freepractice.FreePracticeIntent
import com.mecon.features.freepractice.PracticeStructureView

data class ExplorationToolbarController(
    val freePracticeMode: Boolean,
    val changeMode: (Boolean) -> Unit,
)

enum class FreePracticeWorkbenchLayout {
    CLASSIC,
    WRITING_WITH_LOWER_PANELS,
}

enum class FreePracticeWritingSurface {
    SCORE,
    PIANO_ROLL,
}

data class FreePracticeToolbarController(
    val workbenchLayout: FreePracticeWorkbenchLayout,
    val voiceCount: Int,
    val staffVoices: GrandStaffVoiceLayout,
    val initialKey: ModulationKey,
    val writingSettings: FreePracticeWritingSettings,
    val writingState: PracticeWritingState,
    val selectionTimeCode: TimeCode?,
    val hasSelection: Boolean,
    val gridUnit: com.mecon.api.primitive.Fraction,
    val defaultChordBeats: Int,
    val structure: PracticeStructureView,
    val changeWorkbenchLayout: (FreePracticeWorkbenchLayout) -> Unit,
    val changeVoiceCount: (Int) -> Unit,
    val changeStaffVoices: (GrandStaffVoiceLayout) -> Unit,
    val changeInitialKey: (ModulationKey) -> Unit,
    val changeWritingSettings: (FreePracticeWritingSettings) -> Unit,
    val changeGridUnit: (com.mecon.api.primitive.Fraction) -> Unit,
    val changeDefaultChordBeats: (Int) -> Unit,
    val setTimeSignature: (TimeSignature) -> Unit,
    val insertMeasures: (FreePracticeIntent.MeasureInsertionPosition, Int, Int) -> Unit,
    val rewriteSelection: () -> Unit,
    val alternate: () -> Unit,
    val cancelWriting: () -> Unit,
)
