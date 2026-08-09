package com.mecon.core.engine.edit

import com.mecon.api.primitive.KeySignature
import com.mecon.api.primitive.TimeCode
import com.mecon.api.runtime.RuntimeScore
import com.mecon.api.storage.tracks.StorageKeySignatureChange

/** Pure edits for score key-signature changes. */
object KeySignatureEditEngine {
    data class Target(
        /**
         * The visual key-signature section being edited. [TimeCode.ZERO] means the opening key;
         * later line-start restatements use the visible line's starting measure.
         */
        val onset: TimeCode,
    )

    data class Result(
        val score: RuntimeScore,
        val editedMeasure: Int,
        val editedOnset: TimeCode,
    )

    fun setKeySignature(
        score: RuntimeScore,
        target: Target,
        keySignature: KeySignature,
    ): Result? {
        val measureNumber = target.onset.measure
        return if (measureNumber <= 0) {
            setInitialKeySignature(score, keySignature)
        } else {
            setMeasureKeySignature(score, measureNumber, target.onset, keySignature)
        }
    }

    private fun setInitialKeySignature(score: RuntimeScore, keySignature: KeySignature): Result? {
        if (score.defaultKeySignature == keySignature &&
            score.staffTracks.values.all { it.keySignature == keySignature }
        ) return null

        val updatedStaffs = score.staffTracks.mapValues { (_, staff) ->
            staff.copy(keySignature = keySignature)
        }
        var inherited = true
        val cleanedMeasures = score.measures.map { entry ->
            val measure = entry.value
            if (measure.number > 1 && measure.hasExplicitKeySignature) inherited = false
            when {
                measure.number == 1 -> measure.copy(
                    keySignature = keySignature,
                    hasExplicitKeySignature = false,
                )
                inherited -> measure.copy(keySignature = keySignature)
                else -> measure
            }
        }
        val cleanedGlobal = score.globalTrack.copy(
            events = score.globalTrack.events.filterNot {
                it is StorageKeySignatureChange && it.onset.measure <= 1
            }
        )
        return Result(
            score = score.copy(
                defaultKeySignature = keySignature,
                globalTrack = cleanedGlobal,
            ).replaceMeasures(cleanedMeasures).replaceTracks(staffTracks = updatedStaffs),
            editedMeasure = 1,
            editedOnset = TimeCode.ZERO,
        )
    }

    private fun setMeasureKeySignature(
        score: RuntimeScore,
        measureNumber: Int,
        onset: TimeCode,
        keySignature: KeySignature,
    ): Result? {
        if (measureNumber < 1) return null
        if (score.getKeySignatureAt(measureNumber) == keySignature) return null

        var inherited = false
        val updatedMeasures = score.measures.map { entry ->
            val measure = entry.value
            when {
                measure.number == measureNumber -> {
                    inherited = true
                    measure.copy(keySignature = keySignature, hasExplicitKeySignature = true)
                }
                measure.number > measureNumber && measure.hasExplicitKeySignature -> {
                    inherited = false
                    measure
                }
                inherited -> measure.copy(keySignature = keySignature)
                else -> measure
            }
        }
        val cleanedGlobal = score.globalTrack.copy(
            events = score.globalTrack.events.filterNot {
                it is StorageKeySignatureChange && it.onset.measure == measureNumber
            }
        )
        return Result(
            score = score.copy(globalTrack = cleanedGlobal).replaceMeasures(updatedMeasures),
            editedMeasure = measureNumber,
            editedOnset = onset,
        )
    }
}
