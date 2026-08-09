package com.mecon.renderer.interaction

import com.mecon.api.interaction.*
import com.mecon.api.render.RenderColor
import com.mecon.api.primitive.*
import com.mecon.api.runtime.events.RuntimeVoiceEvent
import com.mecon.api.runtime.events.RuntimePitchEvent
import com.mecon.api.runtime.RuntimeScore
import com.mecon.renderer.render.RenderElementId
import kotlin.test.*

// ============================================================
// EventSection.sectionId
// ============================================================

class EventSectionIdTest {

    @Test
    fun numericIdIsStableAcrossEquivalentSectionInstances() {
        val first = VoiceNoteSection(createDummyComputedVoiceEvent("ev1"), pitchIndex = 1)
        val second = VoiceNoteSection(createDummyComputedVoiceEvent("ev1"), pitchIndex = 1)

        assertEquals(first.id, second.id)
    }

    @Test
    fun numericIdDistinguishesSectionKindAndLocalIndex() {
        val event = createDummyComputedVoiceEvent("ev1")

        assertNotEquals(VoiceEventSection(event).id, VoiceStemSection(event).id)
        assertNotEquals(VoiceNoteSection(event, 0).id, VoiceNoteSection(event, 1).id)
    }

    @Test
    fun voiceEventSectionId() {
        val event = createDummyComputedVoiceEvent("ev1")
        val section = VoiceEventSection(event)
        assertEquals("ev1:event", section.sectionId)
    }

    @Test
    fun voiceNoteSectionId() {
        val event = createDummyComputedVoiceEvent("ev2")
        val section = VoiceNoteSection(event, pitchIndex = 1)
        assertEquals("ev2:notehead:1", section.sectionId)
    }

    @Test
    fun voiceStemSectionId() {
        val event = createDummyComputedVoiceEvent("ev3")
        val section = VoiceStemSection(event)
        assertEquals("ev3:stem", section.sectionId)
    }

    @Test
    fun voiceFlagSectionId() {
        val event = createDummyComputedVoiceEvent("ev4")
        val section = VoiceFlagSection(event)
        assertEquals("ev4:flag", section.sectionId)
    }
}

// ============================================================
// StyleSnapshot
// ============================================================

class StyleSnapshotTest {

    private val eventId = EventId("ev1")
    private val eventSectionId = EventSectionId.voiceEvent(eventId)
    private val noteSectionId = EventSectionId.voiceNote(eventId, 0)
    private val stemSectionId = EventSectionId.voiceStem(eventId)

    @Test
    fun emptySnapshotReturnsNull() {
        val snapshot = StyleSnapshot.EMPTY
        assertNull(snapshot.getOverride(listOf(EventSectionId(1))))
    }

    @Test
    fun getOverrideMatchesSectionId() {
        val override = StyleOverride(fillColor = RenderColor.RED)
        val snapshot = StyleSnapshot(
            mapOf(eventSectionId to OrderedOverride(0, override))
        )
        val result = snapshot.getOverride(listOf(eventSectionId))
        assertEquals(RenderColor.RED, result?.fillColor)
    }

    @Test
    fun getOverrideReturnsNullForNoMatch() {
        val snapshot = StyleSnapshot(
            mapOf(eventSectionId to OrderedOverride(0, StyleOverride(fillColor = RenderColor.RED)))
        )
        assertNull(snapshot.getOverride(listOf(EventSectionId.voiceEvent(EventId("ev2")))))
    }

    @Test
    fun getOverridePicksHighestOrder() {
        val snapshot = StyleSnapshot(mapOf(
            eventSectionId to OrderedOverride(0, StyleOverride(fillColor = RenderColor.RED)),
            noteSectionId to OrderedOverride(5, StyleOverride(fillColor = RenderColor.BLUE)),
            stemSectionId to OrderedOverride(2, StyleOverride(fillColor = RenderColor.GREEN))
        ))

        // Element belongs to all three sections; notehead:0 has highest order (5)
        val result = snapshot.getOverride(listOf(eventSectionId, noteSectionId, stemSectionId))
        assertEquals(RenderColor.BLUE, result?.fillColor)
    }

    @Test
    fun getOverrideWithEmptySectionIdsReturnsNull() {
        val snapshot = StyleSnapshot(
            mapOf(eventSectionId to OrderedOverride(0, StyleOverride(fillColor = RenderColor.RED)))
        )
        assertNull(snapshot.getOverride(emptyList()))
    }

    @Test
    fun isEmptyOnEmptySnapshot() {
        assertTrue(StyleSnapshot.EMPTY.isEmpty)
        assertFalse(StyleSnapshot(mapOf(EventSectionId(1) to OrderedOverride(0, StyleOverride(fillColor = RenderColor.RED)))).isEmpty)
    }
}

// ============================================================
// StyleOverrideManager - declarative registry
// ============================================================

class StyleOverrideManagerTest {

    @Test
    fun initialSnapshotIsEmpty() {
        val manager = StyleOverrideManager()
        assertTrue(manager.snapshot().isEmpty)
    }

    @Test
    fun createTrackChangesSnapshotOnSubmit() {
        val manager = StyleOverrideManager()
        val track = manager.createTrack(0)
        
        val event = createDummyComputedVoiceEvent("ev1")
        track.setStyle(VoiceEventSection(event), StyleOverride(fillColor = RenderColor.RED))
        
        // Before submit, snapshot shouldn't change
        assertTrue(manager.snapshot().isEmpty)
        
        track.submit()
        
        assertFalse(manager.snapshot().isEmpty)
        assertEquals(RenderColor.RED, manager.snapshot().getOverride(listOf(EventSectionId.voiceEvent(event.id)))?.fillColor)
    }

    @Test
    fun removeTrackRevertsStyles() {
        val manager = StyleOverrideManager()
        val track = manager.createTrack(0)
        
        val event = createDummyComputedVoiceEvent("ev1")
        track.setStyle(VoiceEventSection(event), StyleOverride(fillColor = RenderColor.RED))
        track.submit()
        
        assertFalse(manager.snapshot().isEmpty)

        manager.removeTrack(track)
        assertTrue(manager.snapshot().isEmpty)
    }

    @Test
    fun higherPriorityTrackOverridesLowerPriority() {
        val manager = StyleOverrideManager()
        
        val trackLow = manager.createTrack(0)
        val trackHigh = manager.createTrack(10)

        val event = createDummyComputedVoiceEvent("ev1")
        
        trackLow.setStyle(VoiceEventSection(event), StyleOverride(fillColor = RenderColor.RED))
        trackHigh.setStyle(VoiceEventSection(event), StyleOverride(fillColor = RenderColor.BLUE))
        
        trackLow.submit()
        trackHigh.submit()
        
        val snapshot = manager.snapshot()
        val result = snapshot.getOverride(listOf(EventSectionId.voiceEvent(event.id)))
        assertEquals(RenderColor.BLUE, result?.fillColor)
    }
    
    @Test
    fun clearTrackRemovesStylesLocally() {
        val manager = StyleOverrideManager()
        val track = manager.createTrack(0)
        
        val event = createDummyComputedVoiceEvent("ev1")
        track.setStyle(VoiceEventSection(event), StyleOverride(fillColor = RenderColor.RED))
        track.submit()
        
        assertEquals(RenderColor.RED, manager.snapshot().getOverride(listOf(EventSectionId.voiceEvent(event.id)))?.fillColor)
        
        track.clear()
        track.submit()
        
        assertTrue(manager.snapshot().isEmpty)
    }

    @Test
    fun patchUpdatesOnlyNamedSectionIds() {
        val manager = StyleOverrideManager()
        val track = manager.createTrack(0) as StyleTrackImpl
        val first = EventSectionId.voiceNote(EventId("first"), 0)
        val second = EventSectionId.voiceNote(EventId("second"), 0)
        track.applyPatchBySectionId(
            upserts = mapOf(first to StyleOverride(fillColor = RenderColor.RED)),
            removes = emptySet(),
        )
        track.submit()
        track.applyPatchBySectionId(
            upserts = mapOf(second to StyleOverride(fillColor = RenderColor.BLUE)),
            removes = setOf(first),
        )
        track.submit()

        assertNull(manager.snapshot().getOverride(listOf(first)))
        assertEquals(RenderColor.BLUE, manager.snapshot().getOverride(listOf(second))?.fillColor)
    }
}

// ============================================================
// SectionIndex - sectionId lookups
// ============================================================

class SectionIndexSectionIdTest {

    private fun id(ordinal: Int) = RenderElementId.system(systemIndex = 2, localOrdinal = ordinal)

    @Test
    fun sectionIdsForReturnsAllSectionIds() {
        val event = createDummyComputedVoiceEvent("ev1")
        val builder = SectionIndexBuilder()
        builder.register(VoiceEventSection(event), id(0))
        builder.register(VoiceNoteSection(event, 0), id(0))

        val index = builder.build()
        val sectionIds = index.sectionIdsFor(id(0))

        assertEquals(2, sectionIds.size)
        assertTrue(EventSectionId.voiceEvent(event.id) in sectionIds)
        assertTrue(EventSectionId.voiceNote(event.id, 0) in sectionIds)
    }

    @Test
    fun sectionIdsForUnknownElementReturnsEmpty() {
        val index = SectionIndex.EMPTY
        assertTrue(index.sectionIdsFor(id(99)).isEmpty())
    }

    @Test
    fun elementsForSectionIdReturnsMatchingElements() {
        val event = createDummyComputedVoiceEvent("ev1")
        val builder = SectionIndexBuilder()
        builder.register(VoiceEventSection(event), id(0))
        builder.register(VoiceEventSection(event), id(1))
        builder.register(VoiceNoteSection(event, 0), id(0))

        val index = builder.build()

        // VoiceEventSection covers elem_0 and elem_1
        val eventElements = index.elementsForSectionId(EventSectionId.voiceEvent(event.id))
        assertEquals(2, eventElements.elementIds.size)
        assertTrue(id(0) in eventElements.elementIds)
        assertTrue(id(1) in eventElements.elementIds)

        // VoiceNoteSection covers only elem_0
        val noteElements = index.elementsForSectionId(EventSectionId.voiceNote(event.id, 0))
        assertEquals(1, noteElements.elementIds.size)
        assertTrue(id(0) in noteElements.elementIds)
    }

    @Test
    fun elementsForSectionIdUnknownReturnsEmpty() {
        val index = SectionIndex.EMPTY
        assertTrue(index.elementsForSectionId(EventSectionId(123)).isEmpty)
    }

    @Test
    fun sectionIdsAreDeduplicatedPerElement() {
        val event = createDummyComputedVoiceEvent("ev1")
        val builder = SectionIndexBuilder()

        // Register the same section to the same element twice
        builder.register(VoiceEventSection(event), id(0))
        builder.register(VoiceEventSection(event), id(0))

        val index = builder.build()
        val sectionIds = index.sectionIdsFor(id(0))

        // sectionId should appear only once
        assertEquals(1, sectionIds.count { it == EventSectionId.voiceEvent(event.id) })
    }

    @Test
    fun spliceWindowPreservesSharedSectionOrderAndReverseMappings() {
        val event = createDummyComputedVoiceEvent("ev1")
        val eventSection = VoiceEventSection(event)
        val noteSection = VoiceNoteSection(event, 0)
        val builder = SectionIndexBuilder()
        builder.register(eventSection, id(0))
        builder.register(eventSection, id(1))
        builder.register(noteSection, id(1))
        builder.register(eventSection, id(2))

        val spliced = builder.build().spliceWindow(
            removedElementIds = setOf(id(1)),
            added = listOf(
                eventSection to id(3),
                eventSection to id(4),
                noteSection to id(4),
            ),
        )

        assertEquals(
            listOf(id(0), id(2), id(3), id(4)),
            spliced.elementsFor(eventSection).elementIds,
        )
        assertEquals(listOf(id(4)), spliced.elementsFor(noteSection).elementIds)
        assertEquals(listOf(eventSection, noteSection), spliced.sectionsFor(id(4)))
        assertEquals(
            listOf(eventSection.id, noteSection.id),
            spliced.sectionIdsFor(id(4)),
        )
        assertTrue(spliced.sectionsFor(id(1)).isEmpty())
    }

    @Test
    fun replaceSystemsReusesUnaffectedBucketsAndKeepsOldSnapshotReadable() {
        val event = createDummyComputedVoiceEvent("ev1")
        val section = VoiceEventSection(event)
        val system0 = RenderElementId.system(0, 0)
        val oldSystem1 = RenderElementId.system(1, 0)
        val newSystem1 = RenderElementId.system(1, 0, generation = 7)
        val old = SectionIndexBuilder().apply {
            register(section, system0)
            register(section, oldSystem1)
        }.build()

        val next = old.replaceSystems(
            affectedSystems = setOf(1),
            added = listOf(section to newSystem1),
            elementId = { it.second },
            sections = { listOf(it.first) },
        )

        assertSame(old.systemBuckets[0], next.systemBuckets[0])
        assertNotSame(old.systemBuckets[1], next.systemBuckets[1])
        assertEquals(listOf(section), next.sectionsFor(newSystem1))
        assertTrue(next.sectionsFor(oldSystem1).isEmpty())
        assertEquals(listOf(section), old.sectionsFor(oldSystem1))
    }
}

// ============================================================
// Test helpers
// ============================================================

/**
 * Minimal ComputedVoiceEvent stub for testing sectionId generation.
 * Only `id` and `pitchData` are relevant for the tests here.
 */
private fun createDummyComputedVoiceEvent(idValue: String): com.mecon.api.computed.ComputedVoiceEvent {
    return com.mecon.api.computed.ComputedVoiceEvent(
        id = EventId(idValue),
        onset = TimeCode.of(1, Fraction.ZERO),
        duration = Duration.QUARTER,
        pitchData = listOf(
            com.mecon.api.computed.ComputedPitchData(
                pitch = Pitch.C4,
                midiPitch = 60,
                staffPosition = 0,
                effectiveAccidental = null,
                needsLedgerLine = false
            )
        ),
        measurePosition = com.mecon.api.computed.MeasurePosition(
            measure = 1,
            beatPosition = Fraction.ZERO,
            absolutePosition = Fraction.ZERO
        ),
        isRest = false,
        beamInfo = null
    )
}
