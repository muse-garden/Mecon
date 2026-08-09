package com.mecon.desktop.ui.exploration

import com.mecon.theory.KeySignatureMode
import com.mecon.theory.ModulationKey
import com.mecon.theory.harmony.ChordSelectionCatalog
import com.mecon.desktop.uikit.components.ChordToneLabelMode
import com.mecon.api.primitive.Fraction
import com.mecon.theory.freepractice.WorkspaceChordChoice
import com.mecon.theory.freepractice.WorkspaceHarmonySlot
import com.mecon.theory.freepractice.WorkspaceSlotId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ChordPickerSelectionStateTest {
    private val choices = ChordSelectionCatalog.choices(
        ModulationKey(0, KeySignatureMode.MAJOR),
    )

    @Test
    fun `route preview takes selection highlight over committed chord`() {
        val committed = choices.first { it.interpretationRefs.size == 1 }
        val diminished = choices.first { it.interpretationRefs.size > 1 }

        assertEquals(
            diminished.id.value,
            chordPickerSelectedIdentity(diminished.id.value, committed),
        )
    }

    @Test
    fun `selecting direct chord immediately commits a free audible choice`() {
        val directChoice = choices.first { it.interpretationRefs.size == 1 }

        val action = chordPickerSelectionAction(directChoice)

        assertEquals(directChoice.id.value, action.previewChoiceId)
        assertNull(action.interpretationRef)
        assertEquals(directChoice.pitchClasses.sorted(), action.choice.pitchClasses)
        assertNull(action.choice.pinnedInterpretationRef)
        assertNull(action.choice.bassPitchClass)
    }

    @Test
    fun `selecting multi-route diminished chord also immediately commits freely`() {
        val diminished = choices.first { it.interpretationRefs.size > 1 }

        val action = chordPickerSelectionAction(diminished)

        assertEquals(diminished.id.value, action.previewChoiceId)
        assertNull(action.interpretationRef)
        assertEquals(diminished.pitchClasses.sorted(), action.choice.pitchClasses)
    }

    @Test
    fun `first chord selection defaults to root bass`() {
        val tonic = choices.first { it.functionalSymbol == "I" }

        val action = chordPickerSelectionAction(tonic, defaultBassToRoot = true)

        assertEquals(tonic.rootPitchClass, action.choice.bassPitchClass)
    }

    @Test
    fun `new workspace starts with tonic in root position`() {
        val tonic = choices.first { it.functionalSymbol == "I" }

        assertEquals(
            tonic.rootPitchClass,
            initialWorkspace(4).slots.single().chordChoice?.bassPitchClass,
        )
    }

    @Test
    fun `bass labels follow the shared relative and absolute tone mode`() {
        val tonic = choices.first { it.functionalSymbol == "I" }

        assertEquals(
            listOf("1", "3", "5"),
            chordBassOptions(tonic, ChordToneLabelMode.RELATIVE).map { it.label },
        )
        assertEquals(
            listOf("C", "E", "G"),
            chordBassOptions(tonic, ChordToneLabelMode.ABSOLUTE).map { it.label },
        )
    }

    @Test
    fun `customary bass guidance uses the active tone labels`() {
        val pitchClass = 5

        assertEquals(
            "进行提示：常用低音为4，可自行修改。",
            customaryBassGuidanceText(setOf(pitchClass), listOf(ChordBassOption(pitchClass, "4"))),
        )
        assertEquals(
            "进行提示：常用低音为F，可自行修改。",
            customaryBassGuidanceText(setOf(pitchClass), listOf(ChordBassOption(pitchClass, "F"))),
        )
    }

    @Test
    fun `switching filter key preserves sounding pitches and resolves the new degree labels`() {
        // C: 3-#5-7 sounds E-G#-B; in A the same pitch classes read as 5-7-2.
        val cChoice = choices.first { it.pitchClasses == setOf(4, 8, 11) }
        val slot = WorkspaceHarmonySlot(
            id = WorkspaceSlotId("switch-key"),
            onset = Fraction.ZERO,
            duration = Fraction.QUARTER,
            chordChoice = WorkspaceChordChoice.of(cChoice.pitchClasses, cChoice.origin),
        )

        val aReading = ChordSelectionCatalog.choices(
            ModulationKey(3, KeySignatureMode.MAJOR),
        ).matchingChoice(slot)

        assertEquals(cChoice.pitchClasses, aReading?.pitchClasses)
        assertEquals(listOf("5", "7", "2"), aReading?.relativeTones)
    }

    @Test
    fun `plan tolerates inserted selection before workspace frame catches up`() {
        assertNull(selectedPlanChordChoice(choices, selectedSlot = null))
    }
}
