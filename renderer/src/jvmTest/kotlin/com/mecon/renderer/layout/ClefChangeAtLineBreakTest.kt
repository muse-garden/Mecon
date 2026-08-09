package com.mecon.renderer.layout

import com.mecon.api.primitive.Duration
import com.mecon.api.primitive.EventId
import com.mecon.api.primitive.Fraction
import com.mecon.api.primitive.KeySignature
import com.mecon.api.primitive.Pitch
import com.mecon.api.primitive.TimeCode
import com.mecon.api.primitive.TimeSignature
import com.mecon.api.runtime.RuntimeScore
import com.mecon.api.runtime.events.RuntimePitchEvent
import com.mecon.api.runtime.events.RuntimeVoiceEvent
import com.mecon.api.storage.StorageScore
import com.mecon.api.storage.tracks.Clef
import com.mecon.core.engine.computeScore
import com.mecon.core.engine.edit.ClefEditEngine
import com.mecon.renderer.geometry.StaffSpace
import com.mecon.renderer.smufl.BravuraFont
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * A clef change whose onset lands exactly on a system (line) break must render **once**, not twice.
 *
 * Before the fix, the changed clef appeared both as the new line's restated header **and** as the
 * in-stream body clef in that line's opening slot. The layout now (a) suppresses the body clef at the
 * break ([UnifiedLayoutResult.suppressedClefTimes]) so the header is the sole clef, and (b) restates the
 * new clef as a courtesy at the *previous* line's right end ([SystemLayout.lineEndClefs]).
 */
class ClefChangeAtLineBreakTest {

    private fun loadFontOrNull(): BravuraFont? {
        val base = File("../apps/desktop/src/main/resources/bravura")
        val meta = File(base, "bravuraMetadata.json")
        val names = File(base, "glyphnames.json")
        if (!meta.exists() || !names.exists()) return null
        return BravuraFont.fromJson(meta.readText(), names.readText())
    }

    private fun emptyScore() =
        RuntimeScore.fromStorage(StorageScore.create(StorageScore.CreationOptions("", TimeSignature.COMMON, KeySignature.C_MAJOR)))

    private fun RuntimeScore.ptId() = pitchTracks.keys.first()
    private fun RuntimeScore.vtId() = voiceTracks.keys.first()

    private fun RuntimeScore.addNote(tag: String, onset: TimeCode, pitch: Pitch, duration: Duration): RuntimeScore {
        val pe = RuntimePitchEvent(EventId("p-$tag"), onset, listOf(pitch))
        val ve = RuntimeVoiceEvent(EventId(tag), onset, pe, duration)
        return addPitchEvent(ptId(), pe).addVoiceEvent(vtId(), ve)
    }

    private val multiSystem = PageGeometry(
        paginated = true,
        lineWidth = StaffSpace(60f),
        pageContentHeight = StaffSpace(4000f),
        paperWidth = StaffSpace(70f),
        paperHeight = StaffSpace(4200f),
        leftMargin = StaffSpace(2f),
        topMargin = StaffSpace(2f),
    )

    private fun layoutOf(score: RuntimeScore, font: BravuraFont): UnifiedLayoutResult =
        with(font) {
            UnifiedLayoutComputer(RenderLayoutConfig.DEFAULT).computeLayout(
                computeScore(score), score, pageGeometry = multiSystem
            )
        }

    @Test
    fun clefChangeOnLineBreakSuppressesBodyClefAndAddsCourtesy() {
        val font = loadFontOrNull() ?: return // skip if font assets unavailable

        var base = emptyScore()
        for (m in 1..24) {
            base = base
                .addNote("n_${m}_0", TimeCode.of(m, Fraction(0, 4)), Pitch.C4, Duration.HALF)
                .addNote("n_${m}_1", TimeCode.of(m, Fraction(2, 4)), Pitch.E4, Duration.HALF)
        }

        // First, find a measure that opens a system (a line break) with no clef change present.
        val plain = layoutOf(base, font)
        assertTrue(plain.systems.size > 1, "score must break into multiple systems")
        assertTrue(plain.suppressedClefTimes.isEmpty(), "no clef change yet — nothing to suppress")
        assertTrue(plain.systems.all { it.lineEndClefs.isEmpty() }, "no clef change yet — no courtesy clef")

        val breakMeasure = plain.systems[1].measureRange.first
        val staffId = base.staffTracks.keys.first()

        // Change the clef exactly at that line-start measure.
        val edited = assertNotNull(
            ClefEditEngine.setClef(
                base,
                ClefEditEngine.Target(staffId, TimeCode.ofMeasure(breakMeasure)),
                Clef.BASS,
            ),
            "clef edit should apply",
        ).score

        val result = layoutOf(edited, font)

        // The break measure still opens a system (widening its first measure keeps it at the break).
        val brokenSystem = result.systems.first { it.measureRange.first == breakMeasure }
        assertTrue(brokenSystem.systemIndex > 0, "changed clef must open a non-first system")

        // (a) the in-stream body clef at the break is suppressed so the header is the sole line-start clef.
        assertTrue(
            result.suppressedClefTimes.any { it.measure == breakMeasure },
            "body clef at the line break must be suppressed; got ${result.suppressedClefTimes}",
        )

        // (b) the previous system restates the new clef as a courtesy at its right end.
        val prev = result.systems[brokenSystem.systemIndex - 1]
        assertTrue(prev.lineEndClefs.isNotEmpty(), "previous line must carry a courtesy clef")
        assertEquals(Clef.BASS, prev.lineEndClefs.first().clef.clef, "courtesy clef must be the new clef")
        assertTrue(
            prev.lineEndClefs.all { it.baseX < prev.lineEndX },
            "courtesy clef sits inside the line, left of the closing barline",
        )
        // Exactly one line carries the courtesy (the one before the break); no others.
        assertEquals(
            1,
            result.systems.count { it.lineEndClefs.isNotEmpty() },
            "only the line before the break restates the courtesy clef",
        )
    }
}
