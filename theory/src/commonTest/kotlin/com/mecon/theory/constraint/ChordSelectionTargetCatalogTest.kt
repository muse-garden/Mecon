package com.mecon.theory.constraint

import com.mecon.api.primitive.PitchClass
import com.mecon.theory.KeySignatureMode
import com.mecon.theory.ModulationKey
import com.mecon.theory.harmony.ChordSelectionCatalog
import kotlin.test.Test
import kotlin.test.assertTrue

class ChordSelectionTargetCatalogTest {
    @Test
    fun everySelectableChordHasSolverTargetsInTheSameKey() {
        listOf(KeySignatureMode.MAJOR, KeySignatureMode.MINOR).forEach { mode ->
            val key = ModulationKey(0, mode)
            val targets = ChordSelectionTargetCatalog.targets(key)

            ChordSelectionCatalog.choices(key).forEach { choice ->
                assertTrue(
                    targets.any { target ->
                        target.key == key.key &&
                            target.sonority.pitchClasses.map(PitchClass::value).toSet() == choice.pitchClasses
                    },
                    "No solver target for selectable chord ${choice.id}: ${choice.pitchClasses}",
                )
                choice.interpretationRefs.forEach { ref ->
                    assertTrue(
                        targets.any { target ->
                            target.entry.sonority.id == ref.sonorityId &&
                                target.interpretation.id == ref.interpretationId
                        },
                        "No solver target for persisted interpretation $ref",
                    )
                }
            }
        }
    }
}
