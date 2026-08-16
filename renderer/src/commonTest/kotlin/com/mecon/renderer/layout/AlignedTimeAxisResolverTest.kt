package com.mecon.renderer.layout

import com.mecon.api.primitive.Fraction
import com.mecon.api.primitive.BarlineType
import com.mecon.api.primitive.Duration
import com.mecon.api.primitive.EventId
import com.mecon.api.primitive.Pitch
import com.mecon.api.primitive.TimeSignature
import com.mecon.api.primitive.TimeCode
import com.mecon.api.primitive.TrackId
import com.mecon.api.computed.ComputedPitchData
import com.mecon.api.runtime.RuntimeScore
import com.mecon.api.storage.StorageScore
import com.mecon.renderer.elements.BarlineElement
import com.mecon.renderer.elements.NoteElement
import com.mecon.renderer.elements.TimeSignatureElement
import com.mecon.renderer.geometry.StaffSpace
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AlignedTimeAxisResolverTest {
    @Test
    fun scoreOpeningBarlineStaysAtStaffStartWhenFirstBeatSharesItsAbsoluteTime() {
        val runtime = RuntimeScore.fromStorage(
            StorageScore.create(StorageScore.CreationOptions(measureCount = 1))
        )
        val openingTime = TimeCode.ofMeasure(0)
        val firstBeat = TimeCode.of(1, Fraction.ZERO)
        val end = TimeCode.of(1, Fraction.QUARTER)
        val openingBarline = BarlineElement(
            time = openingTime,
            type = BarlineType.SINGLE,
            measureNumber = 0,
            geometryList = emptyList(),
            relativeX = StaffSpace(-2f),
        )
        val intrinsic = UnifiedTimeSlotMap(
            listOf(
                UnifiedTimeSlot(openingTime, listOf(openingBarline), StaffSpace(2f)),
                UnifiedTimeSlot(firstBeat, emptyList(), StaffSpace(10f)),
                UnifiedTimeSlot(end, emptyList(), StaffSpace(20f)),
            )
        )

        val projection = AlignedTimeAxisResolver.resolve(
            runtime = runtime,
            intrinsic = intrinsic,
            request = AlignedTimeAxisRequest(
                segments = listOf(
                    TimeAxisSegmentRequest(firstBeat, end, StaffSpace(10f))
                )
            ),
            systemStartX = StaffSpace.ZERO,
        )

        val projectedOpening = projection.slots.atTime(openingTime)!!
        assertEquals(StaffSpace(2f), projectedOpening.x)
        assertEquals(StaffSpace.ZERO, projectedOpening.x + openingBarline.relativeX)
        assertEquals(StaffSpace(10f), projection.axis.xAt(firstBeat))
    }

    @Test
    fun notationContentMovesInsideRangeWithoutMovingStructuralBoundary() {
        val runtime = RuntimeScore.fromStorage(
            StorageScore.create(StorageScore.CreationOptions(measureCount = 1))
        )
        val start = TimeCode.of(1, Fraction.ZERO)
        val end = TimeCode.of(1, Fraction.QUARTER)
        val note = NoteElement(
            time = start,
            staffIndex = 0,
            eventId = EventId("note"),
            trackId = TrackId("track"),
            duration = Duration.QUARTER,
            measureNumber = 1,
            pitchData = listOf(
                ComputedPitchData(
                    pitch = Pitch(60),
                    midiPitch = 60,
                    staffPosition = 0,
                    effectiveAccidental = null,
                    needsLedgerLine = false,
                )
            ),
            isRest = false,
            beamInfo = null,
            relativeX = StaffSpace(-1.25f),
        )
        val timeSignature = TimeSignatureElement(
            time = start,
            staffIndex = 0,
            timeSignature = TimeSignature(4, 4),
            isInitial = true,
            geometryList = emptyList(),
            relativeX = StaffSpace(-3f),
        )
        val intrinsic = UnifiedTimeSlotMap(
            listOf(
                UnifiedTimeSlot(start, listOf(timeSignature, note), StaffSpace(10f)),
                UnifiedTimeSlot(end, emptyList(), StaffSpace(20f)),
            )
        )

        val projection = AlignedTimeAxisResolver.resolve(
            runtime = runtime,
            intrinsic = intrinsic,
            request = AlignedTimeAxisRequest(
                segments = listOf(
                    TimeAxisSegmentRequest(start, end, StaffSpace(10f))
                ),
                notationContentStartGap = StaffSpace(0.75f),
            ),
            systemStartX = StaffSpace.ZERO,
        )

        val projectedSlot = projection.slots.atTime(start)!!
        assertEquals(projection.axis.xAt(start), projectedSlot.x)
        assertEquals(
            StaffSpace(-3f),
            projectedSlot.events.filterIsInstance<TimeSignatureElement>().single().relativeX,
        )
        assertEquals(
            StaffSpace(0.75f),
            projectedSlot.events.filterIsInstance<NoteElement>().single().relativeX,
        )
    }

    @Test
    fun syntheticTwoFourBarlineDoesNotCreateAThirdBeat() {
        val runtime = RuntimeScore.fromStorage(
            StorageScore.create(
                StorageScore.CreationOptions(
                    timeSignature = TimeSignature(2, 4),
                    measureCount = 2,
                )
            )
        )
        val first = TimeCode.of(1, Fraction.ZERO)
        val second = TimeCode.of(1, Fraction.QUARTER)
        val nextMeasure = TimeCode.of(2, Fraction.ZERO)
        val syntheticBarline = TimeCode.of(1, Fraction(3, 4))
        val barline = BarlineElement(
            time = syntheticBarline,
            type = BarlineType.SINGLE,
            measureNumber = 1,
            geometryList = emptyList(),
            relativeX = StaffSpace(-2.5f),
        )
        val intrinsic = UnifiedTimeSlotMap(
            listOf(
                UnifiedTimeSlot(first, emptyList(), StaffSpace(10f)),
                UnifiedTimeSlot(second, emptyList(), StaffSpace(20f)),
                UnifiedTimeSlot(syntheticBarline, listOf(barline), StaffSpace(30f)),
                UnifiedTimeSlot(nextMeasure, emptyList(), StaffSpace(40f)),
            )
        )
        val request = AlignedTimeAxisRequest(
            segments = listOf(
                TimeAxisSegmentRequest(first, second, StaffSpace(10f)),
                TimeAxisSegmentRequest(second, nextMeasure, StaffSpace(10f)),
            ),
        )

        val projection = AlignedTimeAxisResolver.resolve(
            runtime,
            intrinsic,
            request,
            systemStartX = StaffSpace.ZERO,
        )

        assertEquals(
            projection.axis.xAt(nextMeasure) + StaffSpace(2f),
            projection.slots.atTime(syntheticBarline)?.x,
        )
        assertEquals(
            projection.axis.xAt(nextMeasure) + StaffSpace(2f),
            projection.slots.atTime(nextMeasure)?.x,
        )
        assertEquals(
            Fraction.HALF,
            projection.axis.anchors.first { it.time == nextMeasure }.absoluteTime,
        )
        assertEquals(
            projection.axis.xAt(nextMeasure),
            projection.axis.measureBoundaries.single().x,
        )
    }

    @Test
    fun intrinsicSparseIntervalIsDistributedAcrossExternalChordBoundaries() {
        val runtime = RuntimeScore.fromStorage(
            StorageScore.create(StorageScore.CreationOptions(measureCount = 1))
        )
        val boundaries = listOf(
            Fraction.ZERO,
            Fraction.EIGHTH,
            Fraction.QUARTER,
            Fraction(3, 8),
            Fraction.HALF,
        ).map { TimeCode.of(1, it) }
        val intrinsic = UnifiedTimeSlotMap(
            listOf(
                UnifiedTimeSlot(boundaries.first(), emptyList(), StaffSpace(10f)),
                UnifiedTimeSlot(boundaries.last(), emptyList(), StaffSpace(30f)),
            )
        )
        val request = AlignedTimeAxisRequest(
            segments = boundaries.zipWithNext().map { (start, end) ->
                TimeAxisSegmentRequest(start, end, StaffSpace(3f))
            },
        )

        val projection = AlignedTimeAxisResolver.resolve(
            runtime,
            intrinsic,
            request,
            systemStartX = StaffSpace.ZERO,
        )
        val widths = boundaries.zipWithNext().map { (start, end) ->
            projection.axis.xAt(end).value - projection.axis.xAt(start).value
        }

        assertEquals(listOf(5f, 5f, 5f, 5f), widths)
    }

    @Test
    fun requestExpandsOnlyWhereItExceedsIntrinsicSpacing() {
        val runtime = RuntimeScore.fromStorage(
            StorageScore.create(StorageScore.CreationOptions(measureCount = 1))
        )
        val start = TimeCode.of(1, Fraction.ZERO)
        val quarter = TimeCode.of(1, Fraction.QUARTER)
        val end = TimeCode.of(1, Fraction.HALF)
        val intrinsic = UnifiedTimeSlotMap(
            listOf(
                UnifiedTimeSlot(start, emptyList(), StaffSpace(10f)),
                UnifiedTimeSlot(quarter, emptyList(), StaffSpace(18f)),
                UnifiedTimeSlot(end, emptyList(), StaffSpace(20f)),
            )
        )
        val request = AlignedTimeAxisRequest(
            segments = listOf(
                TimeAxisSegmentRequest(start, quarter, StaffSpace(5f)),
                TimeAxisSegmentRequest(quarter, end, StaffSpace(10f)),
            ),
            revision = 7,
        )

        val projection = AlignedTimeAxisResolver.resolve(
            runtime,
            intrinsic,
            request,
            systemStartX = StaffSpace.ZERO,
        )
        val projected = projection.slots.all()

        assertEquals(8f, projected[1].x.value - projected[0].x.value)
        assertEquals(10f, projected[2].x.value - projected[1].x.value)
        assertEquals(7, projection.axis.revision)
    }

    @Test
    fun extraAnchorReceivesProportionalWidthAndRoundTrips() {
        val runtime = RuntimeScore.fromStorage(
            StorageScore.create(StorageScore.CreationOptions(measureCount = 1))
        )
        val start = TimeCode.of(1, Fraction.ZERO)
        val middle = TimeCode.of(1, Fraction.EIGHTH)
        val end = TimeCode.of(1, Fraction.QUARTER)
        val intrinsic = UnifiedTimeSlotMap(
            listOf(
                UnifiedTimeSlot(start, emptyList(), StaffSpace(4f)),
                UnifiedTimeSlot(end, emptyList(), StaffSpace(6f)),
            )
        )
        val projection = AlignedTimeAxisResolver.resolve(
            runtime,
            intrinsic,
            AlignedTimeAxisRequest(
                segments = listOf(
                    TimeAxisSegmentRequest(start, end, StaffSpace(12f))
                ),
                extraAnchors = setOf(middle),
            ),
            systemStartX = StaffSpace.ZERO,
        )

        assertEquals(6f, projection.axis.xAt(middle).value - projection.axis.xAt(start).value)
        assertTrue(
            projection.axis.timeAt(projection.axis.xAt(middle)).let {
                it.measure == middle.measure && it.beat == middle.beat
            }
        )
    }
}
