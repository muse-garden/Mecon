package com.mecon.api.primitive

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class TimeCodeSerializationTest {
    @Test
    fun zeroSurvivesSerializationRoundTrip() {
        val encoded = Json.encodeToString(TimeCode.ZERO)

        assertEquals(TimeCode.ZERO, Json.decodeFromString<TimeCode>(encoded))
    }

    @Test
    fun browserZeroObjectDecodesToCanonicalOrigin() {
        val encoded = """{"measure":0,"beat":{"numerator":0,"denominator":1}}"""

        assertEquals(TimeCode.ZERO, Json.decodeFromString<TimeCode>(encoded))
    }

    @Test
    fun nonzeroPositionPreservesItsComponentDepth() {
        val position = TimeCode.of(2, Fraction.ZERO)

        assertEquals(position, Json.decodeFromString<TimeCode>(Json.encodeToString(position)))
    }
}
