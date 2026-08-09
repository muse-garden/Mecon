package com.mecon.theory.freepractice

import com.mecon.theory.KeySignatureMode
import com.mecon.theory.ModulationKey
import com.mecon.theory.harmony.ChordInterpretationRef
import com.mecon.theory.harmony.ChordSelectionCatalog
import kotlin.math.abs

/** One exact key-specific interpretation available for a committed audible chord. */
data class WorkspaceChordTonalityOption(
    val key: ModulationKey,
    val interpretationRef: ChordInterpretationRef,
    val functionalSymbol: String,
    val absoluteTones: List<String>,
    val relativeTones: List<String>,
) {
    fun toReading(): WorkspaceChordTonalReading =
        WorkspaceChordTonalReading.of(key, interpretationRef)
}

/**
 * Enumerates every exact tonal interpretation of this chord. Results are nearest-first by key
 * signature change from [referenceKey], then flatward before sharpward at equal distance.
 */
fun WorkspaceHarmonySlot.tonalityOptions(
    referenceKey: ModulationKey,
): List<WorkspaceChordTonalityOption> {
    val pitchClasses = chordChoice?.pitchClasses?.toSet().orEmpty()
    if (pitchClasses.isEmpty()) return emptyList()
    return buildList {
        KeySignatureMode.entries.forEach { mode ->
            (-7..7).forEach { fifths ->
                val key = ModulationKey(fifths, mode)
                ChordSelectionCatalog.choices(key)
                    .filter { it.pitchClasses == pitchClasses }
                    .forEach { choice ->
                        choice.interpretationRefs.zip(choice.interpretationSymbols)
                            .forEach { (ref, symbol) ->
                                add(
                                    WorkspaceChordTonalityOption(
                                        key = key,
                                        interpretationRef = ref,
                                        functionalSymbol = symbol,
                                        absoluteTones = choice.absoluteTones,
                                        relativeTones = choice.relativeTones,
                                    )
                                )
                            }
                    }
            }
        }
    }.distinctBy { it.key to it.interpretationRef }
        .sortedWith(
            compareBy<WorkspaceChordTonalityOption>(
                { abs(it.key.fifths - referenceKey.fifths) },
                { it.key.fifths - referenceKey.fifths },
                { if (it.key.mode == referenceKey.mode) 0 else 1 },
                { it.functionalSymbol },
            )
        )
}
