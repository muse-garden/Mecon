package com.mecon.renderer.smufl

import com.mecon.renderer.geometry.StaffSpace
import kotlin.test.Test
import kotlin.test.assertEquals

class EngravingDefaultsTest {

    @Test
    fun testBravuraDefaults() {
        val defaults = EngravingDefaults.BRAVURA

        assertEquals(StaffSpace(0.13f), defaults.staffLineThickness)
        assertEquals(StaffSpace(0.12f), defaults.stemThickness)
        assertEquals(StaffSpace(0.5f), defaults.beamThickness)
        assertEquals(StaffSpace(0.25f), defaults.beamSpacing)
        assertEquals(StaffSpace(0.4f), defaults.legerLineExtension)
        assertEquals(StaffSpace(0.16f), defaults.legerLineThickness)
    }

    @Test
    fun testSlurThickness() {
        val defaults = EngravingDefaults.BRAVURA

        assertEquals(StaffSpace(0.1f), defaults.slurEndpointThickness)
        assertEquals(StaffSpace(0.22f), defaults.slurMidpointThickness)
    }

    @Test
    fun testBarlineThickness() {
        val defaults = EngravingDefaults.BRAVURA

        assertEquals(StaffSpace(0.16f), defaults.thinBarlineThickness)
        assertEquals(StaffSpace(0.5f), defaults.thickBarlineThickness)
        assertEquals(StaffSpace(0.4f), defaults.barlineSeparation)
    }

    @Test
    fun testCustomDefaults() {
        val custom = EngravingDefaults(
            staffLineThickness = StaffSpace(0.15f),
            stemThickness = StaffSpace(0.14f)
        )

        assertEquals(StaffSpace(0.15f), custom.staffLineThickness)
        assertEquals(StaffSpace(0.14f), custom.stemThickness)
        // Other values should use defaults
        assertEquals(StaffSpace(0.5f), custom.beamThickness)
    }
}
