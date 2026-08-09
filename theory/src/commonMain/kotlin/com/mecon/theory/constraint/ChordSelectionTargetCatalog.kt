package com.mecon.theory.constraint

import com.mecon.theory.ModulationKey
import com.mecon.theory.harmony.ChordCatalogChapterDiscovery
import com.mecon.theory.harmony.ChordCatalogChapterProvider
import com.mecon.theory.harmony.ChordCatalogCollector
import com.mecon.theory.harmony.chordSelectionTonalContext

/**
 * Solver-facing projection of the same discoverable catalog used by the free-practice picker.
 * This keeps selectable sounding chords and inversion-aware solver targets on one source of truth.
 */
object ChordSelectionTargetCatalog {
    fun targets(
        key: ModulationKey,
        providers: List<ChordCatalogChapterProvider> = ChordCatalogChapterDiscovery.discover(),
        includeInversions: Boolean = true,
    ): List<InterpretedChordTarget> {
        val context = key.chordSelectionTonalContext()
        return providers
            .flatMap(ChordCatalogChapterProvider::chordCatalogContributions)
            .flatMap { contribution ->
                ChordCatalogCollector.collect(contribution.construct(context, key.key))
                    .toInterpretedTargets(key.key, includeInversions)
            }
            .distinctBy(InterpretedChordTarget::identityKey)
    }
}
