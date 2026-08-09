package com.mecon.core.engine.edit

import com.mecon.api.runtime.RuntimeScore
import com.mecon.api.storage.NavigationMark
import com.mecon.api.storage.NavigationMarkOffset

/** Immutable edits for volta endings and D.C./D.S. navigation marks. */
object RepeatStructureEditEngine {
    data class Result(
        val score: RuntimeScore,
        val affectedMeasures: IntRange,
    )

    /**
     * Toggle an ending beginning in the measure after [boundaryMeasure]. The
     * bracket is extended through the next backward-repeat boundary.
     */
    fun toggleVolta(
        score: RuntimeScore,
        boundaryMeasure: Int,
        number: Int,
    ): Result? {
        require(number >= 1)
        val measures = score.measures.map { it.value }.sortedBy { it.number }
        val start = (boundaryMeasure + 1).coerceAtLeast(1)
        if (measures.none { it.number == start }) return null
        val end = measures.firstOrNull { it.number >= start && it.repeatEnd }?.number
            ?: measures.last().number
        val alreadyStartsHere = score.getMeasure(start)?.voltaNumbers?.contains(number) == true &&
            (start == 1 || score.getMeasure(start - 1)?.voltaNumbers?.contains(number) != true)

        val updated = measures.map { measure ->
            val cleared = measure.voltaNumbers - number
            if (!alreadyStartsHere && measure.number in start..end) {
                measure.copy(voltaNumbers = cleared + number)
            } else if (cleared != measure.voltaNumbers) {
                measure.copy(voltaNumbers = cleared)
            } else {
                measure
            }
        }
        if (updated == measures) return null
        return Result(score.replaceMeasures(updated), start..end)
    }

    /**
     * Toggle a conventional 1st/2nd-ending pair. The first ending reaches the
     * next backward repeat; the second begins immediately after it and starts
     * one measure wide so its right edge can be extended interactively.
     */
    fun toggleVoltaPair(
        score: RuntimeScore,
        boundaryMeasure: Int,
    ): Result? {
        val measures = score.measures.map { it.value }.sortedBy { it.number }
        val firstStart = (boundaryMeasure + 1).coerceAtLeast(1)
        val firstEnd = measures.firstOrNull { it.number >= firstStart && it.repeatEnd }?.number
            ?: return null
        val secondStart = firstEnd + 1
        if (score.getMeasure(firstStart) == null || score.getMeasure(secondStart) == null) return null
        val alreadyStartsHere = 1 in score.getMeasure(firstStart)!!.voltaNumbers &&
            (firstStart == 1 || 1 !in score.getMeasure(firstStart - 1)!!.voltaNumbers)
        val oldFirst = contiguousVoltaRange(score, firstStart, 1)
        val oldSecondStart = oldFirst?.last?.plus(1)
        val oldSecond = oldSecondStart?.let { contiguousVoltaRange(score, it, 2) }
        val affectedEnd = maxOf(secondStart, oldSecond?.last ?: secondStart)
        val updated = measures.map { measure ->
            val inOldPair = oldFirst?.contains(measure.number) == true ||
                oldSecond?.contains(measure.number) == true
            val inNewPair = measure.number in firstStart..secondStart
            val cleared = if (inOldPair || inNewPair) measure.voltaNumbers - setOf(1, 2)
                else measure.voltaNumbers
            when {
                alreadyStartsHere -> if (cleared == measure.voltaNumbers) measure
                    else measure.copy(voltaNumbers = cleared)
                measure.number in firstStart..firstEnd ->
                    measure.copy(voltaNumbers = cleared + 1)
                measure.number == secondStart ->
                    measure.copy(voltaNumbers = cleared + 2)
                cleared != measure.voltaNumbers ->
                    measure.copy(voltaNumbers = cleared)
                else -> measure
            }
        }
        return Result(score.replaceMeasures(updated), firstStart..affectedEnd)
    }

    /** Move the right edge of the second ending while keeping its start fixed. */
    fun resizeSecondVolta(
        score: RuntimeScore,
        startMeasure: Int,
        endMeasure: Int,
    ): Result? {
        val oldRange = contiguousVoltaRange(score, startMeasure, 2) ?: return null
        val maxMeasure = score.measures.maxOfOrNull { it.value.number } ?: return null
        val nextEnd = endMeasure.coerceIn(startMeasure, maxMeasure)
        if (oldRange.last == nextEnd) return null
        val updated = score.measures.map { it.value }.map { measure ->
            val without = if (measure.number in oldRange) measure.voltaNumbers - 2 else measure.voltaNumbers
            val withNew = if (measure.number in startMeasure..nextEnd) without + 2 else without
            if (withNew == measure.voltaNumbers) measure else measure.copy(voltaNumbers = withNew)
        }
        return Result(
            score.replaceMeasures(updated),
            minOf(oldRange.first, startMeasure)..maxOf(oldRange.last, nextEnd),
        )
    }

    /** Move the left edge of the first ending while keeping its right edge fixed. */
    fun resizeFirstVoltaStart(
        score: RuntimeScore,
        startMeasure: Int,
        newStartMeasure: Int,
    ): Result? {
        val oldRange = contiguousVoltaRange(score, startMeasure, 1) ?: return null
        val nextStart = newStartMeasure.coerceIn(1, oldRange.last)
        if (oldRange.first == nextStart) return null
        val updated = score.measures.map { it.value }.map { measure ->
            val without = if (measure.number in oldRange) measure.voltaNumbers - 1 else measure.voltaNumbers
            val withNew = if (measure.number in nextStart..oldRange.last) without + 1 else without
            if (withNew == measure.voltaNumbers) measure else measure.copy(voltaNumbers = withNew)
        }
        return Result(
            score.replaceMeasures(updated),
            minOf(oldRange.first, nextStart)..oldRange.last,
        )
    }

    /** Delete exactly one selected ending bracket. */
    fun deleteVolta(
        score: RuntimeScore,
        startMeasure: Int,
        endMeasure: Int,
        numbers: Set<Int>,
    ): Result? {
        if (numbers.isEmpty()) return null
        var changed = false
        val updated = score.measures.map { it.value }.map { measure ->
            if (measure.number !in startMeasure..endMeasure) return@map measure
            val next = measure.voltaNumbers - numbers
            if (next == measure.voltaNumbers) measure else {
                changed = true
                measure.copy(voltaNumbers = next)
            }
        }
        return if (changed) Result(score.replaceMeasures(updated), startMeasure..endMeasure) else null
    }

    private fun contiguousVoltaRange(score: RuntimeScore, startMeasure: Int, number: Int): IntRange? {
        if (number !in score.getMeasure(startMeasure)?.voltaNumbers.orEmpty()) return null
        var end = startMeasure
        while (number in score.getMeasure(end + 1)?.voltaNumbers.orEmpty()) end++
        return startMeasure..end
    }

    /** Toggle one navigation sign/instruction at [boundaryMeasure]. */
    fun toggleNavigationMark(
        score: RuntimeScore,
        boundaryMeasure: Int,
        mark: NavigationMark,
    ): Result? {
        val measure = score.getMeasure(boundaryMeasure) ?: return null
        val nextMarks = if (mark in measure.navigationMarks) {
            measure.navigationMarks - mark
        } else {
            // One jump instruction per boundary; location signs may coexist.
            val jumpMarks = setOf(
                NavigationMark.DA_CAPO,
                NavigationMark.DAL_SEGNO,
                NavigationMark.DA_CAPO_AL_FINE,
                NavigationMark.DAL_SEGNO_AL_FINE,
                NavigationMark.DA_CAPO_AL_CODA,
                NavigationMark.DAL_SEGNO_AL_CODA,
            )
            (if (mark in jumpMarks) measure.navigationMarks - jumpMarks else measure.navigationMarks) + mark
        }
        val updated = score.measures.map { it.value }.map {
            if (it.number == boundaryMeasure) {
                it.copy(
                    navigationMarks = nextMarks,
                    navigationMarkOffsets = it.navigationMarkOffsets.filterKeys { mark -> mark in nextMarks },
                )
            } else it
        }
        return Result(score.replaceMeasures(updated), boundaryMeasure..boundaryMeasure)
    }

    /** Persist a navigation mark's manual displacement from its automatic boundary anchor. */
    fun moveNavigationMark(
        score: RuntimeScore,
        boundaryMeasure: Int,
        targetBoundaryMeasure: Int,
        mark: NavigationMark,
        offset: NavigationMarkOffset,
    ): Result? {
        val source = score.getMeasure(boundaryMeasure) ?: return null
        val target = score.getMeasure(targetBoundaryMeasure) ?: return null
        if (mark !in source.navigationMarks) return null
        if (boundaryMeasure == targetBoundaryMeasure && source.navigationMarkOffsets[mark] == offset) return null
        val jumpMarks = setOf(
            NavigationMark.DA_CAPO,
            NavigationMark.DAL_SEGNO,
            NavigationMark.DA_CAPO_AL_FINE,
            NavigationMark.DAL_SEGNO_AL_FINE,
            NavigationMark.DA_CAPO_AL_CODA,
            NavigationMark.DAL_SEGNO_AL_CODA,
        )
        val updated = score.measures.map { it.value }.map {
            when {
                it.number == boundaryMeasure && boundaryMeasure == targetBoundaryMeasure ->
                    it.copy(navigationMarkOffsets = it.navigationMarkOffsets + (mark to offset))
                it.number == boundaryMeasure -> it.copy(
                    navigationMarks = it.navigationMarks - mark,
                    navigationMarkOffsets = it.navigationMarkOffsets - mark,
                )
                it.number == targetBoundaryMeasure -> {
                    val retained = if (mark in jumpMarks) it.navigationMarks - jumpMarks
                        else it.navigationMarks
                    it.copy(
                        navigationMarks = retained + mark,
                        navigationMarkOffsets = it.navigationMarkOffsets
                            .filterKeys { existing -> existing in retained }
                            .plus(mark to offset),
                    )
                }
                else -> it
            }
        }
        return Result(
            score.replaceMeasures(updated),
            minOf(boundaryMeasure, targetBoundaryMeasure)..maxOf(boundaryMeasure, targetBoundaryMeasure),
        )
    }

    fun deleteNavigationMark(
        score: RuntimeScore,
        boundaryMeasure: Int,
        mark: NavigationMark,
    ): Result? {
        val measure = score.getMeasure(boundaryMeasure) ?: return null
        if (mark !in measure.navigationMarks) return null
        val updated = score.measures.map { it.value }.map {
            if (it.number == boundaryMeasure) {
                it.copy(
                    navigationMarks = it.navigationMarks - mark,
                    navigationMarkOffsets = it.navigationMarkOffsets - mark,
                )
            } else it
        }
        return Result(score.replaceMeasures(updated), boundaryMeasure..boundaryMeasure)
    }
}
