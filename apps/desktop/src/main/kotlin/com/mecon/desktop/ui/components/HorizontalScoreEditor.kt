package com.mecon.desktop.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.mecon.desktop.ui.components.lefttoolbar.HorizontalNotePalette
import com.mecon.desktop.ui.rememberBravuraFont
import com.mecon.desktop.ui.views.RenderedScoreView
import com.mecon.desktop.ui.views.RenderedScoreViewConfig

/**
 * Reusable score editor with a flat, wrapping toolbar above the notation surface.
 *
 * Feature workbenches provide only the score-view contract and edit callbacks; layout, font
 * loading and wrapping remain shared here.
 */
@Composable
fun HorizontalScoreEditor(
    state: NoteToolState,
    scoreViewConfig: RenderedScoreViewConfig,
    selection: LeftToolbarSelectionState = LeftToolbarSelectionState(),
    actions: LeftToolbarActions = LeftToolbarActions(),
    showScoreElementTool: Boolean = true,
    voiceNumbers: List<Int> = emptyList(),
    voiceSelectionInfo: PaletteSelectionInfo = selection.notes,
    onVoiceEdit: (Int) -> Unit = actions.notes.editVoice,
    modifier: Modifier = Modifier,
) {
    val bravura = rememberBravuraFont()
    Column(modifier.fillMaxSize()) {
        HorizontalNotePalette(
            state = state,
            bravura = bravura,
            selectionInfo = selection.notes,
            actions = actions.notes,
            showScoreElementTool = showScoreElementTool,
            voiceNumbers = voiceNumbers,
            voiceSelectionInfo = voiceSelectionInfo,
            onVoiceEdit = onVoiceEdit,
        )
        Box(Modifier.fillMaxWidth().weight(1f)) {
            RenderedScoreView(
                config = scoreViewConfig,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}
