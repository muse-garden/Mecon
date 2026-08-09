package com.mecon.core.engine

import com.mecon.api.computed.ComputedBarline
import com.mecon.api.computed.ComputedNavigationMark
import com.mecon.api.computed.ComputedVoltaEnding
import com.mecon.api.runtime.RuntimeScore

/** Derives repeat brackets and navigation events; renderers only place these results. */
object RepeatStructureComputer {
    data class Result(
        val endings: List<ComputedVoltaEnding>,
        val navigationMarks: List<ComputedNavigationMark>,
    )

    fun compute(score: RuntimeScore, barlines: List<ComputedBarline>): Result {
        val measures = score.measures.map { it.value }.sortedBy { it.number }
        val endings = mutableListOf<ComputedVoltaEnding>()
        var startIndex = 0
        while (startIndex < measures.size) {
            val numbers = measures[startIndex].voltaNumbers
            if (numbers.isEmpty()) {
                startIndex++
                continue
            }
            var endIndex = startIndex
            while (endIndex + 1 < measures.size &&
                measures[endIndex + 1].number == measures[endIndex].number + 1 &&
                measures[endIndex + 1].voltaNumbers == numbers
            ) {
                endIndex++
            }
            endings += ComputedVoltaEnding(
                startMeasure = measures[startIndex].number,
                endMeasure = measures[endIndex].number,
                numbers = numbers,
            )
            startIndex = endIndex + 1
        }

        val barlineByBoundary = barlines.associateBy { it.measureNumber }
        val navigation = measures.flatMap { measure ->
            val time = barlineByBoundary[measure.number]?.time ?: measure.startTime
            measure.navigationMarks.map { mark ->
                ComputedNavigationMark(
                    time = time,
                    boundaryMeasure = measure.number,
                    mark = mark,
                    offset = measure.navigationMarkOffsets[mark]
                        ?: com.mecon.api.storage.NavigationMarkOffset(),
                )
            }
        }
        return Result(endings, navigation)
    }
}
