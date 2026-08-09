package com.mecon.api.storage

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.*

class BeamingInfoTest {

    @Test
    fun testDefaultBeamingInfo() {
        val beaming = BeamingInfo()

        assertFalse(beaming.beamLeft)
        assertFalse(beaming.beamRight)
        assertFalse(beaming.isBeamed)
    }

    @Test
    fun testBeamStart() {
        val beaming = BeamingInfo.start()

        assertFalse(beaming.beamLeft)
        assertTrue(beaming.beamRight)
        assertTrue(beaming.isBeamed)
        assertTrue(beaming.isBeamStart)
        assertFalse(beaming.isBeamEnd)
        assertFalse(beaming.isBeamMiddle)
    }

    @Test
    fun testBeamEnd() {
        val beaming = BeamingInfo.end()

        assertTrue(beaming.beamLeft)
        assertFalse(beaming.beamRight)
        assertTrue(beaming.isBeamed)
        assertFalse(beaming.isBeamStart)
        assertTrue(beaming.isBeamEnd)
        assertFalse(beaming.isBeamMiddle)
    }

    @Test
    fun testBeamMiddle() {
        val beaming = BeamingInfo.middle()

        assertTrue(beaming.beamLeft)
        assertTrue(beaming.beamRight)
        assertTrue(beaming.isBeamed)
        assertFalse(beaming.isBeamStart)
        assertFalse(beaming.isBeamEnd)
        assertTrue(beaming.isBeamMiddle)
    }

    @Test
    fun testNoneConstant() {
        val none = BeamingInfo.NONE

        assertFalse(none.beamLeft)
        assertFalse(none.beamRight)
        assertFalse(none.isBeamed)
        assertFalse(none.isBeamStart)
        assertFalse(none.isBeamEnd)
        assertFalse(none.isBeamMiddle)
    }

    @Test
    fun testDirectConstruction() {
        val beaming = BeamingInfo(beamLeft = true, beamRight = false)

        assertTrue(beaming.beamLeft)
        assertFalse(beaming.beamRight)
        assertTrue(beaming.isBeamEnd)
    }
}

class RenderingPropsTest {

    @Test
    fun testDefaultRenderingProps() {
        val props = RenderingProps()

        assertNull(props.stemDirection)
        assertNull(props.accidentalDisplay)
        assertNull(props.noteheadOverride)
        assertNull(props.beaming)
        assertFalse(props.hidden)
    }

    @Test
    fun testRenderingPropsWithBeaming() {
        val beaming = BeamingInfo.start()
        val props = RenderingProps(beaming = beaming)

        assertNotNull(props.beaming)
        assertFalse(props.beaming!!.beamLeft)
        assertTrue(props.beaming!!.beamRight)
        assertTrue(props.beaming!!.isBeamStart)
    }

    @Test
    fun testRenderingPropsWithMultipleProperties() {
        val props = RenderingProps(
            stemDirection = StemDirection.UP,
            accidentalDisplay = AccidentalDisplay.FORCE,
            beaming = BeamingInfo.middle(),
            color = "#FF0000"
        )

        assertEquals(StemDirection.UP, props.stemDirection)
        assertEquals(AccidentalDisplay.FORCE, props.accidentalDisplay)
        assertNotNull(props.beaming)
        assertTrue(props.beaming!!.beamLeft)
        assertTrue(props.beaming!!.beamRight)
        assertEquals("#FF0000", props.color)
    }

    @Test
    fun testRenderingPropsCopy() {
        val original = RenderingProps(
            stemDirection = StemDirection.DOWN,
            beaming = BeamingInfo.start()
        )

        val modified = original.copy(
            beaming = BeamingInfo.end()
        )

        // Original unchanged
        assertTrue(original.beaming!!.isBeamStart)

        // Modified has new beaming
        assertTrue(modified.beaming!!.isBeamEnd)
        assertEquals(StemDirection.DOWN, modified.stemDirection) // Other props preserved
    }

    @Test
    fun testDefaultConstant() {
        val default = RenderingProps.DEFAULT

        assertNull(default.stemDirection)
        assertNull(default.beaming)
        assertFalse(default.hidden)
    }
}

class BeamingInfoSerializationTest {
    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
    }

    @Test
    fun testBeamingInfoJsonRoundTrip() {
        val original = BeamingInfo(beamLeft = true, beamRight = false)
        val jsonString = json.encodeToString(original)
        val restored = json.decodeFromString<BeamingInfo>(jsonString)

        assertEquals(original.beamLeft, restored.beamLeft)
        assertEquals(original.beamRight, restored.beamRight)
    }

    @Test
    fun testBeamingInfoJsonFormat() {
        val beaming = BeamingInfo.start()
        val jsonString = json.encodeToString(beaming)

        assertTrue(jsonString.contains("\"beamLeft\""))
        assertTrue(jsonString.contains("\"beamRight\""))
        assertTrue(jsonString.contains("false"))  // beamLeft = false
        assertTrue(jsonString.contains("true"))   // beamRight = true
    }

    @Test
    fun testRenderingPropsWithBeamingJsonRoundTrip() {
        val original = RenderingProps(
            stemDirection = StemDirection.UP,
            beaming = BeamingInfo.middle(),
            color = "#00FF00"
        )
        val jsonString = json.encodeToString(original)
        val restored = json.decodeFromString<RenderingProps>(jsonString)

        assertEquals(original.stemDirection, restored.stemDirection)
        assertEquals(original.color, restored.color)
        assertNotNull(restored.beaming)
        assertEquals(original.beaming!!.beamLeft, restored.beaming!!.beamLeft)
        assertEquals(original.beaming!!.beamRight, restored.beaming!!.beamRight)
    }

    @Test
    fun testRenderingPropsNullBeamingJsonRoundTrip() {
        val original = RenderingProps(
            stemDirection = StemDirection.DOWN,
            beaming = null
        )
        val jsonString = json.encodeToString(original)
        val restored = json.decodeFromString<RenderingProps>(jsonString)

        assertEquals(original.stemDirection, restored.stemDirection)
        assertNull(restored.beaming)
    }

    @Test
    fun testAllBeamingFactoryMethodsSerialization() {
        val testCases = listOf(
            BeamingInfo.NONE,
            BeamingInfo.start(),
            BeamingInfo.middle(),
            BeamingInfo.end()
        )

        for (original in testCases) {
            val jsonString = json.encodeToString(original)
            val restored = json.decodeFromString<BeamingInfo>(jsonString)

            assertEquals(original.beamLeft, restored.beamLeft, "beamLeft mismatch for $original")
            assertEquals(original.beamRight, restored.beamRight, "beamRight mismatch for $original")
        }
    }
}
