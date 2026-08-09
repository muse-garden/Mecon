package com.mecon.core.engine.edit

import com.mecon.api.primitive.Duration
import com.mecon.api.primitive.DurationBase
import com.mecon.api.primitive.Fraction

/**
 * Decomposes an arbitrary positive duration (expressed as a [Fraction] of a whole note)
 * into a list of standard, renderable [Duration]s, largest first.
 *
 * Used by the editing pipeline whenever a desired span is not itself a single note value —
 * e.g. the remnants left by [clearInterval][NoteEditEngine] after carving out an interval, or
 * a note whose length crosses a barline (each measure-local segment is decomposed separately
 * and the pieces are tied together by the caller).
 *
 * Strategy: greedy largest-first using plain (un-dotted) power-of-two pieces — for any input
 * whose denominator is a power of two this reproduces the binary expansion and is guaranteed
 * to terminate and sum exactly. A second pass then merges an adjacent `(value, value/2)` pair
 * into a single dotted note, which is how musicians actually write `3/4 = dotted half`, etc.
 *
 * Tuplets are intentionally out of scope here: the editor never produces tuplet remnants
 * through this path.
 */
object DurationDecomposer {

    /** Plain note bases from longest to shortest, paired with their whole-note fraction. */
    private val BASES_DESC: List<Pair<DurationBase, Fraction>> =
        DurationBase.entries
            .map { it to it.toFraction() }
            .sortedByDescending { it.second }

    /**
     * Decompose [length] (whole-note units, must be > 0) into standard durations, largest first.
     *
     * Any residue smaller than the shortest representable note value (128th) is dropped; callers
     * only feed in spans built from real note values and barline positions, so this never bites in
     * practice but keeps the function total.
     */
    fun decompose(length: Fraction): List<Duration> {
        require(length.isPositive) { "Duration length must be positive, got $length" }

        val plain = ArrayList<DurationBase>()
        var remaining = length
        while (remaining.isPositive) {
            val pick = BASES_DESC.firstOrNull { it.second <= remaining }
                ?: break // residue below a 128th note — unrepresentable, drop it
            plain.add(pick.first)
            remaining = remaining - pick.second
        }

        return mergeDots(plain)
    }

    /**
     * Merge each adjacent `(base, base/2)` plain pair into a single one-dot note
     * (`base • = base + base/2`). One left-to-right pass is enough for the binary-expansion
     * output produced above (at most one dot per note).
     */
    private fun mergeDots(plain: List<DurationBase>): List<Duration> {
        val out = ArrayList<Duration>()
        var i = 0
        while (i < plain.size) {
            val cur = plain[i]
            val next = plain.getOrNull(i + 1)
            if (next != null && cur.toFraction() == next.toFraction() * 2) {
                out.add(Duration(cur, dots = 1))
                i += 2
            } else {
                out.add(Duration(cur))
                i += 1
            }
        }
        return out
    }
}
