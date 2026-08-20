package com.mecon.desktop.ui.components

import com.mecon.api.primitive.BarlineType
import com.mecon.api.primitive.DurationBase
import com.mecon.api.storage.events.DynamicLevel
import com.mecon.api.storage.tracks.Clef
import com.mecon.features.scoreediting.ScoreInteractionFamily
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class NoteToolStateCompositionTest {
    @Test
    fun featureStateOwnsDefaultsExposedByRootFacade() {
        val state = NoteToolState()

        state.note.durationBase = DurationBase.HALF
        state.notation.selectedClef = Clef.BASS
        state.expression.selectedDynamic = DynamicLevel.FF
        state.structure.selectedBarlineType = BarlineType.DOUBLE

        assertEquals(DurationBase.HALF, state.durationBase)
        assertEquals(Clef.BASS, state.selectedClef)
        assertEquals(DynamicLevel.FF, state.selectedDynamic)
        assertEquals(BarlineType.DOUBLE, state.selectedBarlineType)
    }

    @Test
    fun documentResetDelegatesToEveryFeatureState() {
        val state = NoteToolState().apply {
            enterGraceEntry()
            note.restMode = true
            notation.selectedClef = Clef.BASS
            expression.selectedDynamic = DynamicLevel.FF
            structure.selectedVoltaNumber = 2
            scoreElementPaletteExpanded = true
        }

        state.resetForDocumentSwitch()

        assertEquals(EditTool.SELECT, state.tool)
        assertEquals(DurationBase.QUARTER, state.note.durationBase)
        assertFalse(state.note.restMode)
        assertEquals(Clef.TREBLE, state.notation.selectedClef)
        assertEquals(DynamicLevel.MF, state.expression.selectedDynamic)
        assertNull(state.structure.selectedVoltaNumber)
        assertFalse(state.scoreElementPaletteExpanded)
    }

    @Test
    fun desktopToolsUseSharedInteractionFamilies() {
        val state = NoteToolState()
        assertEquals(ScoreInteractionFamily.N, state.activeInteractionSpec.family)
        state.tool = EditTool.NOTE
        assertEquals(ScoreInteractionFamily.E, state.activeInteractionSpec.family)
        state.tool = EditTool.HAIRPIN
        assertEquals(ScoreInteractionFamily.S, state.activeInteractionSpec.family)
        state.tool = EditTool.BARLINE
        assertEquals(ScoreInteractionFamily.B, state.activeInteractionSpec.family)
    }
}
