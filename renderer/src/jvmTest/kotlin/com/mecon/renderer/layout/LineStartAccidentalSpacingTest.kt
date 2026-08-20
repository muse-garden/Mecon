package com.mecon.renderer.layout

import com.mecon.api.primitive.Duration
import com.mecon.api.primitive.Fraction
import com.mecon.api.primitive.KeySignature
import com.mecon.api.primitive.Pitch
import com.mecon.api.primitive.TimeCode
import com.mecon.api.primitive.TimeSignature
import com.mecon.api.runtime.RuntimeScore
import com.mecon.api.storage.StorageScore
import com.mecon.api.storage.tracks.StorageSystemBreak
import com.mecon.core.engine.computeScore
import com.mecon.core.engine.edit.NoteEditEngine
import com.mecon.renderer.geometry.StaffSpace
import com.mecon.renderer.snapshot.loadFont
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class LineStartAccidentalSpacingTest {
    private val config = RenderLayoutConfig.DEFAULT
    private val page = PageGeometry(
        paginated = true,
        lineWidth = StaffSpace(80f),
        pageContentHeight = StaffSpace(200f),
        paperWidth = StaffSpace(90f),
        paperHeight = StaffSpace(220f),
        leftMargin = StaffSpace(2f),
        topMargin = StaffSpace(2f),
    )

    @Test
    fun firstAccidentalClearsRestatedKeySignature() {
        val font = loadFont() ?: return
        val storage = StorageScore.create(
            StorageScore.CreationOptions(
                title = "Line-start accidental",
                measureCount = 2,
                timeSignature = TimeSignature.COMMON,
                keySignature = KeySignature.F_MAJOR,
            ),
        ).let { score ->
            score.copy(
                globalTrack = score.globalTrack.copy(
                    events = score.globalTrack.events + StorageSystemBreak(TimeCode.ofMeasure(2)),
                ),
            )
        }
        var runtime = RuntimeScore.fromStorage(storage)
        val voice = runtime.voiceTracks.keys.first()
        runtime = assertNotNull(
            NoteEditEngine.insert(
                runtime,
                NoteEditEngine.Insertion(
                    voiceTrackId = voice,
                    start = TimeCode.of(1, Fraction.ZERO),
                    duration = Duration.WHOLE,
                    pitch = Pitch.C4,
                ),
            ),
        ).score
        val lineStart = TimeCode.of(2, Fraction.ZERO)
        runtime = assertNotNull(
            NoteEditEngine.insert(
                runtime,
                NoteEditEngine.Insertion(
                    voiceTrackId = voice,
                    start = lineStart,
                    duration = Duration.QUARTER,
                    pitch = Pitch(diatonicSteps = 0, chromaticOffset = 1),
                ),
            ),
        ).score

        val layout = with(font) {
            UnifiedLayoutComputer(config).computeLayout(
                computeScore(runtime),
                runtime,
                pageGeometry = page,
            )
        }
        val secondSystem = layout.systems.single { it.measureRange.first == 2 }
        val header = secondSystem.lineStartHeaders.single()
        val key = assertNotNull(header.keySignature)
        val slot = assertNotNull(layout.timeSlotMap.atTime(lineStart))
        val note = slot.noteEvents().single()
        assertTrue(note.noteBody.accidentals.isNotEmpty(), "fixture must render a line-start accidental")

        val headerEnd = header.baseX + key.relativeX + key.minimumWidth + config.spaceAfterKeySignature
        val accidentalLeft = slot.x + note.relativeX +
            note.noteBody.accidentals.minOf { it.geometry.bounds.left }
        assertTrue(
            accidentalLeft.value + 0.0001f >= headerEnd.value,
            "line-start accidental must clear the restated key: accidental=$accidentalLeft headerEnd=$headerEnd",
        )
    }
}
