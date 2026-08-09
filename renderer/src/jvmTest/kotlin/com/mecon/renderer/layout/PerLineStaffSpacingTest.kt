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
import com.mecon.core.engine.computeScore
import com.mecon.renderer.geometry.StaffSpace
import com.mecon.renderer.smufl.BravuraFont
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Verifies that paginated vertical layout is **line-local**: each system is spaced from the vertical
 * extent of its own measures, not a single global maximum shared by every line.
 *
 * A score with one very high note (many ledger lines above the staff) in a single measure must make
 * only the system that contains that measure taller — every other system keeps its plain spacing. Under
 * the previous global model all systems shared the score-wide maximum extent, so this test would fail.
 */
class PerLineStaffSpacingTest {

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

    /** Several measures per system, several systems. */
    private val multiSystem = PageGeometry(
        paginated = true,
        lineWidth = StaffSpace(60f),
        pageContentHeight = StaffSpace(4000f),
        paperWidth = StaffSpace(70f),
        paperHeight = StaffSpace(4200f),
        leftMargin = StaffSpace(2f),
        topMargin = StaffSpace(2f),
    )

    private val C7 = Pitch(21, 0) // three octaves above middle C — far above the treble staff.

    @Test
    fun highNoteOnlyHeightensItsOwnSystem() {
        val font = loadFontOrNull() ?: return // skip if font assets unavailable

        var base = emptyScore()
        val measures = 24
        val highMeasure = 2
        for (m in 1..measures) {
            val first = if (m == highMeasure) C7 else Pitch.C4
            base = base
                .addNote("n_${m}_0", TimeCode.of(m, Fraction(0, 4)), first, Duration.HALF)
                .addNote("n_${m}_1", TimeCode.of(m, Fraction(2, 4)), Pitch.E4, Duration.HALF)
        }

        val computed = computeScore(base)
        val result = with(font) {
            UnifiedLayoutComputer(RenderLayoutConfig.DEFAULT).computeLayout(
                computed, base, pageGeometry = multiSystem
            )
        }

        assertTrue(result.systems.size > 1, "score must break into multiple systems")

        // Top reach (centre → highest occupied Y) of each system's notation staff.
        fun topReach(sys: SystemLayout): Float {
            val staff = sys.staffLayouts.first { it.kind == StaffKind.NOTATION }
            return (staff.centerY - staff.contentTopY).value
        }

        val highSystem = result.systems.first { highMeasure in it.measureRange }
        val plainSystem = result.systems.first { highMeasure !in it.measureRange }

        assertTrue(
            topReach(highSystem) > topReach(plainSystem) + 1f,
            "system with the high note (top reach ${topReach(highSystem)}) must be taller than a plain " +
                "system (${topReach(plainSystem)}) — proving per-line vertical spacing",
        )

        // A plain system stays at the bare-staff reach (2 staff-spaces from centre), confirming other
        // lines are NOT widened by the high note elsewhere in the score.
        assertTrue(
            topReach(plainSystem) < topReach(highSystem),
            "plain systems must not inherit the high note's extent",
        )
    }
}
