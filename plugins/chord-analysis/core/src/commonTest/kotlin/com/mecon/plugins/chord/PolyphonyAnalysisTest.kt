package com.mecon.plugins.chord

import com.mecon.api.computed.ComputedEventStore
import com.mecon.api.computed.ComputedPitchData
import com.mecon.api.computed.ComputedPluginEvent
import com.mecon.api.computed.ComputedScore
import com.mecon.api.computed.ComputedVoiceEvent
import com.mecon.api.computed.MeasurePosition
import com.mecon.api.computed.tracks.ComputedPluginTrack
import com.mecon.api.primitive.Duration
import com.mecon.api.primitive.EventId
import com.mecon.api.primitive.Fraction
import com.mecon.api.primitive.KeySignature
import com.mecon.api.primitive.Pitch
import com.mecon.api.primitive.TimeCode
import com.mecon.api.primitive.TrackId
import com.mecon.api.plugin.AnnotationElement
import com.mecon.api.plugin.AnnotationLayoutContext
import com.mecon.api.interaction.VoiceNoteSection
import com.mecon.api.render.RenderColor
import com.mecon.api.runtime.RuntimeScore
import com.mecon.api.runtime.TimeIndexedList
import com.mecon.api.runtime.events.RuntimePluginEvent
import com.mecon.api.storage.StorageScore
import com.mecon.api.storage.events.StoragePluginEvent
import com.mecon.theory.ChordQuality
import com.mecon.theory.ChordSymbolDisplayStyle
import com.mecon.theory.KeySignatureMode
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PolyphonyAnalysisTest {
    private val runtime: RuntimeScore by lazy {
        RuntimeScore.fromStorage(StorageScore.create(StorageScore.CreationOptions("polyphony")))
    }

    @BeforeTest
    fun reset() {
        PolyphonyAnalysisEngine.resetForTesting()
        PolyphonyDisplaySettings.isEnabled = false
        PolyphonyDisplaySettings.showDegreeTrack = true
        PolyphonyDisplaySettings.showPassingChords = true
        PolyphonyDisplaySettings.showSelectedDegrees = true
    }

    @Test
    fun exactActiveSonorityIsRecognizedAtVoiceMovement() {
        val time = tc(0)
        val score = score(
            listOf(
                voice("c", time, Pitch.C4),
                voice("e", time, Pitch.E4),
                voice("g", time, Pitch.G4),
            )
        )

        val frame = PolyphonyAnalysisEngine.compute(score).getValue(time)

        assertEquals(listOf(Pitch.C4, Pitch.E4, Pitch.G4), frame.activePitches.map { it.pitch })
        assertEquals(ChordQuality.MAJOR, frame.recognizedChord?.quality)
        assertEquals(0, frame.recognizedChord?.root?.value)
    }

    @Test
    fun explicitNonChordSliceIsExcludedFromPassingChordRecognition() {
        val time = tc(0)
        val d = voice("d", time, Pitch.D4)
        val mark = StorageNonChordToneEvent.create(
            onset = time,
            endOnset = d.endTime,
            voiceEventId = d.id,
            pitchIndex = 0,
        )
        val score = score(
            events = listOf(
                voice("c", time, Pitch.C4),
                d,
                voice("e", time, Pitch.E4),
                voice("g", time, Pitch.G4),
            ),
            pluginTracks = listOf(pluginTrack(StorageNonChordToneEvent.TRACK_TYPE, listOf(mark))),
        )

        val chord = PolyphonyAnalysisEngine.compute(score).getValue(time).recognizedChord

        assertEquals(ChordQuality.MAJOR, chord?.quality)
        assertEquals(0, chord?.root?.value)
    }

    @Test
    fun ambiguousRegionShowsEveryKeyAndResolvesAfterItsEnd() {
        val start = tc(0)
        val end = tc(2)
        val cMajor = PolyphonyTonalKey(0, KeySignatureMode.MAJOR)
        val gMajor = PolyphonyTonalKey(1, KeySignatureMode.MAJOR)
        val region = StorageTonalRegionEvent.create(
            onset = start,
            endOnset = end,
            keys = listOf(cMajor, gMajor),
            resolvedKey = gMajor,
        )
        val score = score(
            events = emptyList(),
            pluginTracks = listOf(pluginTrack(StorageTonalRegionEvent.TRACK_TYPE, listOf(region))),
        )

        assertEquals(
            listOf(cMajor, gMajor),
            PolyphonyTonalContextResolver.keysAt(score, tc(1)).map(PolyphonyTonalKey::from),
        )
        assertEquals(
            listOf(gMajor),
            PolyphonyTonalContextResolver.keysAt(score, tc(3)).map(PolyphonyTonalKey::from),
        )
        assertEquals(
            listOf(cMajor, gMajor),
            PolyphonyTonalContextResolver.keysAtSelection(score.runtime, tc(1), listOf(region))
                .map(PolyphonyTonalKey::from),
        )
        assertEquals(
            listOf(gMajor),
            PolyphonyTonalContextResolver.keysAtSelection(score.runtime, tc(3), listOf(region))
                .map(PolyphonyTonalKey::from),
        )
        assertEquals(
            "C:1 · G:4",
            PolyphonyDegreeFormatter.format(
                listOf(cMajor.toModulationKey(), gMajor.toModulationKey()),
                Pitch.C4,
            ),
        )
    }

    @Test
    fun explicitRegionKeepsTheScoreSignatureAsASecondaryReading() {
        val cMajor = PolyphonyTonalKey(0, KeySignatureMode.MAJOR)
        val gMajor = PolyphonyTonalKey(1, KeySignatureMode.MAJOR)
        val region = StorageTonalRegionEvent.create(
            onset = tc(0),
            endOnset = tc(2),
            keys = listOf(gMajor),
        )
        val score = score(
            events = emptyList(),
            pluginTracks = listOf(pluginTrack(StorageTonalRegionEvent.TRACK_TYPE, listOf(region))),
        )

        assertEquals(
            listOf(cMajor, gMajor),
            PolyphonyTonalContextResolver.keysAt(score, tc(1)).map(PolyphonyTonalKey::from),
        )
        assertEquals(
            listOf(cMajor, gMajor),
            PolyphonyTonalContextResolver.keysAtSelection(score.runtime, tc(1), listOf(region))
                .map(PolyphonyTonalKey::from),
        )
    }

    @Test
    fun editableScoreBaselineAndInsertedRegionOnlyDoubleInsideTheirIntersection() {
        val cMajor = PolyphonyTonalKey(0, KeySignatureMode.MAJOR)
        val gMajor = PolyphonyTonalKey(1, KeySignatureMode.MAJOR)
        val baseline = StorageTonalRegionEvent.create(
            onset = tc(0),
            endOnset = tc(2),
            keys = listOf(cMajor),
            resolvedKey = null,
            role = TonalRegionRole.SCORE_KEY_BASELINE,
        )
        val inserted = StorageTonalRegionEvent.create(
            onset = tc(1),
            endOnset = tc(4),
            keys = listOf(gMajor),
        )
        val regions = listOf(baseline, inserted)
        val score = score(
            events = emptyList(),
            pluginTracks = listOf(pluginTrack(StorageTonalRegionEvent.TRACK_TYPE, regions)),
        )

        assertEquals(
            listOf(cMajor),
            PolyphonyTonalContextResolver.keysAt(score, tc(0), regions)
                .map(PolyphonyTonalKey::from),
        )
        assertEquals(
            listOf(cMajor, gMajor),
            PolyphonyTonalContextResolver.keysAt(score, tc(1), regions)
                .map(PolyphonyTonalKey::from),
        )
        assertEquals(
            listOf(gMajor),
            PolyphonyTonalContextResolver.keysAt(score, tc(3), regions)
                .map(PolyphonyTonalKey::from),
        )
        assertEquals(
            listOf(gMajor),
            PolyphonyTonalContextResolver.keysAtSelection(score.runtime, tc(3), regions)
                .map(PolyphonyTonalKey::from),
        )
    }

    @Test
    fun singlePitchEditPatchesOnlyItsSoundingWindow() {
        val events = (1..80).map { measure ->
            voice("v$measure", TimeCode.ofMeasure(measure), Pitch.C4)
        }
        val initial = score(events)
        val initialFrames = PolyphonyAnalysisEngine.compute(initial)
        val changedTime = TimeCode.ofMeasure(41)
        val changedEvent = voice("v41", changedTime, Pitch.F4)
        val changed = initial.copy(
            computedEvents = initial.computedEvents.put(changedEvent)
        )

        PolyphonyAnalysisEngine.compute(changed)

        assertTrue(PolyphonyAnalysisEngine.lastRecomputedFrameCount < initialFrames.size)
        assertEquals(Pitch.F4, PolyphonyAnalysisEngine.compute(changed)
            .getValue(changedTime).activePitches.single().pitch)
    }

    @Test
    fun ambiguousIncompleteSonorityIsNotAutoRecognized() {
        val time = tc(0)
        val frame = PolyphonyAnalysisEngine.compute(
            score(listOf(voice("c", time, Pitch.C4), voice("g", time, Pitch.G4)))
        ).getValue(time)
        assertNull(frame.recognizedChord)
    }

    @Test
    fun nativeKeySignatureEditInvalidatesTonalFrameCache() {
        val time = tc(0)
        val events = listOf(voice("c", time, Pitch.C4))
        val initial = score(events)
        assertEquals(0, PolyphonyAnalysisEngine.compute(initial).getValue(time).tonalKeys.single().fifths)

        val changedMeasure = runtime.getMeasure(1)!!.copy(keySignature = KeySignature.G_MAJOR)
        val changedRuntime = runtime.copy(
            defaultKeySignature = KeySignature.G_MAJOR,
            measures = runtime.measures.put(1, changedMeasure),
        )
        val changed = score(events, scoreRuntime = changedRuntime)

        assertEquals(1, PolyphonyAnalysisEngine.compute(changed).getValue(time).tonalKeys.single().fifths)
        assertTrue(PolyphonyAnalysisEngine.lastRecomputedFrameCount > 0)
    }

    @Test
    fun selectedDegreesUseNonSpacingProviderWithoutEnablingAssistant() {
        val c = voice("c", tc(0), Pitch.C4)
        val region = StorageTonalRegionEvent.create(
            onset = tc(0),
            endOnset = tc(1),
            keys = listOf(PolyphonyTonalKey(0, KeySignatureMode.MAJOR)),
        )
        val score = score(
            events = listOf(c),
            pluginTracks = listOf(pluginTrack(StorageTonalRegionEvent.TRACK_TYPE, listOf(region))),
        )
        val ctx = object : AnnotationLayoutContext {
            override val computedScore = score
            override fun xForTime(time: TimeCode): Float? = null
        }
        val oldMode = ChordSymbolDisplaySettings.scoreDisplayMode
        val oldSelectedDegrees = PolyphonyDisplaySettings.showSelectedDegrees
        try {
            ChordSymbolDisplaySettings.scoreDisplayMode = ChordAnalysisScoreDisplayMode.CLASSIC

            val texts = PolyphonyAnnotationProvider.layout(ctx)
                .filterIsInstance<AnnotationElement.Text>()
            val labels = ChordSelectionDegreeLabelProvider.labels(
                score,
                setOf(VoiceNoteSection(c, 0)),
            )

            assertEquals(listOf("1"), labels.map { it.text })
            assertTrue(texts.none { it.text == "1" })
            assertTrue(texts.any { it.sourceEventId == region.id })

            PolyphonyDisplaySettings.showSelectedDegrees = false
            assertTrue(
                ChordSelectionDegreeLabelProvider.labels(
                    score,
                    setOf(VoiceNoteSection(c, 0)),
                ).isEmpty()
            )
        } finally {
            ChordSymbolDisplaySettings.scoreDisplayMode = oldMode
            PolyphonyDisplaySettings.showSelectedDegrees = oldSelectedDegrees
        }
    }

    @Test
    fun selectedDegreesRemainAvailableFromNativeKeyWithoutStoredRegion() {
        val c = voice("c", tc(0), Pitch.C4)
        val gRuntime = runtime.copy(
            defaultKeySignature = KeySignature.G_MAJOR,
            measures = runtime.measures.put(
                1,
                runtime.getMeasure(1)!!.copy(keySignature = KeySignature.G_MAJOR),
            ),
        )

        val labels = ChordSelectionDegreeLabelProvider.labels(
            score(listOf(c), scoreRuntime = gRuntime),
            setOf(VoiceNoteSection(c, 0)),
        )

        assertEquals(listOf("4"), labels.map { it.text })
    }

    @Test
    fun selectedDegreesIncludeOverlappingTonalLinesInStartOrder() {
        val c = voice("c", tc(2), Pitch.C4)
        val cRegion = StorageTonalRegionEvent.create(
            onset = tc(0),
            endOnset = tc(4),
            keys = listOf(PolyphonyTonalKey(0, KeySignatureMode.MAJOR)),
        )
        val gRegion = StorageTonalRegionEvent.create(
            onset = tc(1),
            endOnset = tc(3),
            keys = listOf(PolyphonyTonalKey(1, KeySignatureMode.MAJOR)),
        )
        val score = score(
            events = listOf(c),
            pluginTracks = listOf(
                pluginTrack(StorageTonalRegionEvent.TRACK_TYPE, listOf(gRegion, cRegion)),
            ),
        )

        val label = ChordSelectionDegreeLabelProvider.labels(
            score,
            setOf(VoiceNoteSection(c, 0)),
        ).single().text

        assertEquals("C:1 · G:4", label)
    }

    @Test
    fun scoreTimelineUsesSharedReadingsAndDurationRanges() {
        val c = StorageChordEvent.create(tc(0), 0, ChordQuality.MAJOR)
        val g = StorageChordEvent.create(tc(2), 7, ChordQuality.MAJOR)
        val cMajor = PolyphonyTonalKey(0, KeySignatureMode.MAJOR)
        val gMajor = PolyphonyTonalKey(1, KeySignatureMode.MAJOR)
        val region = StorageTonalRegionEvent.create(
            onset = tc(0),
            endOnset = tc(2),
            keys = listOf(cMajor, gMajor),
            resolvedKey = gMajor,
        )
        val score = score(
            events = emptyList(),
            pluginTracks = listOf(
                pluginTrack(StorageChordEvent.TRACK_TYPE, listOf(c, g)),
                pluginTrack(StorageTonalRegionEvent.TRACK_TYPE, listOf(region)),
            ),
        )
        val ctx = object : AnnotationLayoutContext {
            override val computedScore = score
            override fun xForTime(time: TimeCode): Float? = null
        }
        val oldMode = ChordSymbolDisplaySettings.scoreDisplayMode
        val oldStyle = ChordSymbolDisplaySettings.style
        try {
            ChordSymbolDisplaySettings.scoreDisplayMode = ChordAnalysisScoreDisplayMode.TIMELINE
            ChordSymbolDisplaySettings.style = ChordSymbolDisplayStyle.SCALE_DEGREE

            val ranges = ChordTimelineAnnotationProvider.layout(ctx)
                .filterIsInstance<AnnotationElement.Range>()
            val chordIds = setOf(c.id, g.id)
            val cards = ranges.filter { it.sourceEventId in chordIds }
            val explicitTonalLines = ranges.filter {
                it.sourceEventId == region.id && it.relativeY > 0f
            }

            assertEquals(2, cards.size)
            assertEquals(2, explicitTonalLines.size)
            assertTrue(explicitTonalLines.all(AnnotationElement.Range::interactive))
            assertTrue(explicitTonalLines.all { it.relativeY > 0f })
            assertEquals(g.onset, cards.first { it.sourceEventId == c.id }.endTime)
            val scoreEnd = TimeCode.of(score.runtime.measures.maxOf { it.key } + 1, Fraction.ZERO)
            assertEquals(scoreEnd, cards.first { it.sourceEventId == g.id }.endTime)
            val cCardLines = cards.first { it.sourceEventId == c.id }.lines
                .map { it.content.plainText }
            assertEquals(2, cCardLines.size)
            assertTrue("C: I" in cCardLines[0])
            assertTrue("G: IV" in cCardLines[1])
            cards.forEach { card ->
                assertEquals(RenderColor.rgb(220, 234, 254), card.fillColor)
                assertEquals(RenderColor.rgb(96, 165, 250), card.strokeColor)
                assertTrue(card.lines.all { it.color == RenderColor.rgb(30, 41, 59) })
            }
            val tonalRanges = ranges.filter { it.relativeY == 0f }
            assertTrue(tonalRanges.isNotEmpty())
            assertTrue(tonalRanges.all { it.relativeY == 0f })
            assertTrue(
                tonalRanges.any {
                    it.strokeColor?.alpha == 70 && it.sourceEventId == region.id && it.interactive
                },
                "the light key-signature segment must remain on top and resize the region",
            )

            ChordSymbolDisplaySettings.style = ChordSymbolDisplayStyle.LETTER
            val letterLines = ChordTimelineAnnotationProvider.layout(ctx)
                .filterIsInstance<AnnotationElement.Range>()
                .filter { it.sourceEventId in chordIds }
                .flatMap { it.lines }
            assertTrue(letterLines.any { it.color == RenderColor.rgb(30, 41, 59) })
            assertTrue(letterLines.any { it.color == RenderColor.rgb(100, 116, 139) })
        } finally {
            ChordSymbolDisplaySettings.scoreDisplayMode = oldMode
            ChordSymbolDisplaySettings.style = oldStyle
        }
    }

    @Test
    fun scoreTimelineUsesTheEditableBaselineIntersectionAsTheDoubleTonalityRange() {
        val before = StorageChordEvent.create(tc(0), 0, ChordQuality.MAJOR)
        val overlap = StorageChordEvent.create(tc(1), 0, ChordQuality.MAJOR)
        val after = StorageChordEvent.create(tc(3), 0, ChordQuality.MAJOR)
        val baseline = StorageTonalRegionEvent.create(
            onset = tc(0),
            endOnset = tc(2),
            keys = listOf(PolyphonyTonalKey(0, KeySignatureMode.MAJOR)),
            resolvedKey = null,
            role = TonalRegionRole.SCORE_KEY_BASELINE,
        )
        val inserted = StorageTonalRegionEvent.create(
            onset = tc(1),
            endOnset = tc(4),
            keys = listOf(PolyphonyTonalKey(1, KeySignatureMode.MAJOR)),
        )
        val score = score(
            events = emptyList(),
            pluginTracks = listOf(
                pluginTrack(StorageChordEvent.TRACK_TYPE, listOf(before, overlap, after)),
                pluginTrack(StorageTonalRegionEvent.TRACK_TYPE, listOf(baseline, inserted)),
            ),
        )
        val ctx = object : AnnotationLayoutContext {
            override val computedScore = score
            override fun xForTime(time: TimeCode): Float? = null
        }
        val oldMode = ChordSymbolDisplaySettings.scoreDisplayMode
        val oldStyle = ChordSymbolDisplaySettings.style
        try {
            ChordSymbolDisplaySettings.scoreDisplayMode = ChordAnalysisScoreDisplayMode.TIMELINE
            ChordSymbolDisplaySettings.style = ChordSymbolDisplayStyle.SCALE_DEGREE

            val ranges = ChordTimelineAnnotationProvider.layout(ctx)
                .filterIsInstance<AnnotationElement.Range>()
            val cards = ranges.filter { it.sourceEventId in setOf(before.id, overlap.id, after.id) }
            assertEquals(1, cards.first { it.sourceEventId == before.id }.lines.size)
            assertEquals(2, cards.first { it.sourceEventId == overlap.id }.lines.size)
            assertEquals(1, cards.first { it.sourceEventId == after.id }.lines.size)

            val baselinePieces = ranges.filter { it.sourceEventId == baseline.id }
            assertTrue(baselinePieces.isNotEmpty())
            assertTrue(baselinePieces.all { it.relativeY == 0f && it.interactive })
            assertEquals(Fraction.ZERO, baselinePieces.minBy { it.time }.time.beat?.simplified())
            assertEquals(Fraction.HALF, baselinePieces.maxBy { it.endTime }.endTime.beat?.simplified())
            assertTrue(baselinePieces.any { it.strokeColor?.alpha == 70 })

            val insertedLines = ranges.filter { it.sourceEventId == inserted.id }
            assertTrue(insertedLines.all { it.relativeY > 0f && it.interactive })
        } finally {
            ChordSymbolDisplaySettings.scoreDisplayMode = oldMode
            ChordSymbolDisplaySettings.style = oldStyle
        }
    }

    /**
     * `MeasureEditEngine` does not remap plugin tracks, so deleting trailing measures leaves tonal
     * regions anchored past the new score end. Clipping such a region to that end collapses it to
     * an empty interval — which must drop the marking, not fail: this runs inside
     * `AnnotationStaffProvider.layout`, where an exception takes down the whole render frame.
     */
    @Test
    fun tonalRegionsStrandedPastTheScoreEndAreDroppedInsteadOfFailingTheLayout() {
        val chord = StorageChordEvent.create(tc(0), 0, ChordQuality.MAJOR)
        val stranded = StorageTonalRegionEvent.create(
            onset = TimeCode.of(5, Fraction.ZERO),
            endOnset = TimeCode.of(7, Fraction.ZERO),
            keys = listOf(PolyphonyTonalKey(1, KeySignatureMode.MAJOR)),
        )
        val score = score(
            events = emptyList(),
            pluginTracks = listOf(
                pluginTrack(StorageChordEvent.TRACK_TYPE, listOf(chord)),
                pluginTrack(StorageTonalRegionEvent.TRACK_TYPE, listOf(stranded)),
            ),
        )
        val ctx = object : AnnotationLayoutContext {
            override val computedScore = score
            override fun xForTime(time: TimeCode): Float? = null
        }
        val oldMode = ChordSymbolDisplaySettings.scoreDisplayMode
        try {
            ChordSymbolDisplaySettings.scoreDisplayMode = ChordAnalysisScoreDisplayMode.TIMELINE

            val ranges = ChordTimelineAnnotationProvider.layout(ctx)
                .filterIsInstance<AnnotationElement.Range>()
            val tonalText = ranges.filter { it.relativeY == 0f }
                .flatMap { range -> range.lines.map { it.content.plainText } }

            assertEquals(1, ranges.count { it.sourceEventId == chord.id })
            // The rest of the timeline still engraves: only the stranded G-major band is gone.
            assertTrue(tonalText.isNotEmpty(), "the key-signature baseline must survive")
            assertTrue(tonalText.none { "G" in it }, "stranded region leaked into $tonalText")
            // The analysis side reads the same stored regions and must tolerate them too.
            assertEquals(
                listOf(com.mecon.theory.ModulationKey(0, KeySignatureMode.MAJOR)),
                PolyphonyTonalContextResolver.keysAt(score, tc(0)),
            )
        } finally {
            ChordSymbolDisplaySettings.scoreDisplayMode = oldMode
        }
    }

    private fun score(
        events: List<ComputedVoiceEvent>,
        pluginTracks: List<ComputedPluginTrack<*>> = emptyList(),
        scoreRuntime: RuntimeScore = runtime,
    ) = ComputedScore(
        runtime = scoreRuntime,
        computedEvents = ComputedEventStore.of(events),
        pluginTracks = pluginTracks.associateBy { it.id },
    )

    private fun voice(id: String, onset: TimeCode, pitch: Pitch) = ComputedVoiceEvent(
        id = EventId(id),
        onset = onset,
        duration = Duration.QUARTER,
        pitchData = listOf(
            ComputedPitchData(
                pitch = pitch,
                midiPitch = pitch.midiNumber,
                staffPosition = 0,
                effectiveAccidental = null,
                needsLedgerLine = false,
            )
        ),
        measurePosition = MeasurePosition(1, onset.beat ?: Fraction.ZERO, Fraction.ZERO),
        isRest = false,
        beamInfo = null,
    )

    private fun <T : StoragePluginEvent> pluginTrack(
        type: String,
        events: List<T>,
    ): ComputedPluginTrack<T> {
        val computed = events.map { storage ->
            val runtimeEvent = object : RuntimePluginEvent<T> {
                override val id = storage.id
                override val onset = storage.onset
                override val storageEvent = storage
            }
            object : ComputedPluginEvent<T> {
                override val id = storage.id
                override val onset = storage.onset
                override val runtimeEvent = runtimeEvent
            }
        }
        return ComputedPluginTrack(
            id = TrackId(type),
            name = type,
            type = type,
            events = TimeIndexedList.of(computed),
        )
    }

    private fun tc(quarter: Int): TimeCode =
        TimeCode.of(1, Fraction(quarter, 4))
}
