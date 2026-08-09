package com.mecon.theory.freepractice

import com.mecon.theory.KeySignatureMode
import com.mecon.theory.ModulationKey
import com.mecon.theory.constraint.ChordSelectionTargetCatalog
import com.mecon.theory.harmony.ChordSelectionCatalog
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class FreePracticeBassSelectionTest {
    private val key = ModulationKey(0, KeySignatureMode.MAJOR)
    private val tonic = ChordSelectionCatalog.choices(key)
        .first { it.functionalSymbol == "I" }

    @Test
    fun `open bass keeps every inversion and fixed bass keeps only that member`() {
        val targets = ChordSelectionTargetCatalog.targets(key)
        val openChoice = WorkspaceChordChoice.of(tonic.pitchClasses, tonic.origin)
        val rootBassChoice = openChoice.copy(bassPitchClass = tonic.rootPitchClass)

        val openTargets = targets.filter { it.matchesWorkspaceChordChoice(key, openChoice) }
        val rootBassTargets = targets.filter { it.matchesWorkspaceChordChoice(key, rootBassChoice) }

        assertTrue(openTargets.map { it.bassPitchClass.value }.distinct().size > 1)
        assertTrue(rootBassTargets.isNotEmpty())
        assertTrue(rootBassTargets.size < openTargets.size)
        assertEquals(
            setOf(tonic.rootPitchClass),
            rootBassTargets.mapTo(linkedSetOf()) { it.bassPitchClass.value },
        )
    }

    @Test
    fun `fixed bass must belong to the chord`() {
        assertFailsWith<IllegalArgumentException> {
            WorkspaceChordChoice.of(tonic.pitchClasses, bassPitchClass = 1)
        }
    }
}
