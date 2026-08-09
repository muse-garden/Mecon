package com.mecon.theory.constraint

import com.mecon.theory.Key
import com.mecon.theory.harmony.ChordCatalog

/**
 * Expands every catalog interpretation into concrete inversion-aware search targets.
 *
 * Collection must happen before this projection so equal sounding constructions share one
 * sonority while each functional reading remains an independent search branch.
 */
internal fun ChordCatalog.toInterpretedTargets(
    compatibilityKey: Key,
    includeInversions: Boolean = true,
): List<InterpretedChordTarget> =
    entries.flatMap { entry ->
        entry.interpretations.flatMap { interpretation ->
            val bassTones = if (includeInversions) {
                interpretation.structuralToneOrder
            } else {
                interpretation.structuralToneOrder.take(1)
            }
            bassTones.map { bassTone ->
                InterpretedChordTarget(
                    key = compatibilityKey,
                    entry = entry,
                    interpretation = interpretation,
                    bassToneId = bassTone,
                )
            }
        }
    }
