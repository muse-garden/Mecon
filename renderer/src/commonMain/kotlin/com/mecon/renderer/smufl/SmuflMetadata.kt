package com.mecon.renderer.smufl

import com.mecon.renderer.geometry.StaffSpace
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.float
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Parsed SMuFL font metadata.
 */
data class SmuflMetadata(
    val fontName: String,
    val fontVersion: Float,
    val engravingDefaults: EngravingDefaults,
    val glyphAdvanceWidths: Map<String, StaffSpace>,
    val glyphBBoxes: Map<String, GlyphBBox>,
    val glyphAnchors: Map<String, GlyphAnchors> = emptyMap()
) {
    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        /** Default notehead width (for black/half noteheads from Bravura) */
        val DEFAULT_NOTEHEAD_WIDTH = StaffSpace(1.18f)

        /** Default notehead height (for all noteheads from Bravura) */
        val DEFAULT_NOTEHEAD_HEIGHT = StaffSpace(1.0f)

        /**
         * Parse metadata from JSON string.
         */
        fun fromJson(jsonString: String): SmuflMetadata {
            val jsonElement = json.parseToJsonElement(jsonString)
            val obj = jsonElement.jsonObject

            val fontName = obj["fontName"]?.jsonPrimitive?.content ?: "Unknown"
            val fontVersion = obj["fontVersion"]?.jsonPrimitive?.float ?: 1.0f

            val engravingDefaults = parseEngravingDefaults(obj["engravingDefaults"]?.jsonObject)
            val glyphAdvanceWidths = parseGlyphAdvanceWidths(obj["glyphAdvanceWidths"]?.jsonObject)
            val glyphBBoxes = parseGlyphBBoxes(obj["glyphBBoxes"]?.jsonObject)
            val glyphAnchors = parseGlyphAnchors(obj["glyphsWithAnchors"]?.jsonObject)

            return SmuflMetadata(
                fontName = fontName,
                fontVersion = fontVersion,
                engravingDefaults = engravingDefaults,
                glyphAdvanceWidths = glyphAdvanceWidths,
                glyphBBoxes = glyphBBoxes,
                glyphAnchors = glyphAnchors
            )
        }

        private fun parseEngravingDefaults(obj: JsonObject?): EngravingDefaults {
            if (obj == null) return EngravingDefaults.BRAVURA

            fun getStaffSpace(key: String, default: Float): StaffSpace =
                StaffSpace(obj[key]?.jsonPrimitive?.float ?: default)

            return EngravingDefaults(
                arrowShaftThickness = getStaffSpace("arrowShaftThickness", 0.16f),
                barlineSeparation = getStaffSpace("barlineSeparation", 0.4f),
                beamSpacing = getStaffSpace("beamSpacing", 0.25f),
                beamThickness = getStaffSpace("beamThickness", 0.5f),
                bracketThickness = getStaffSpace("bracketThickness", 0.5f),
                dashedBarlineDashLength = getStaffSpace("dashedBarlineDashLength", 0.5f),
                dashedBarlineGapLength = getStaffSpace("dashedBarlineGapLength", 0.25f),
                dashedBarlineThickness = getStaffSpace("dashedBarlineThickness", 0.16f),
                hBarThickness = getStaffSpace("hBarThickness", 1.0f),
                hairpinThickness = getStaffSpace("hairpinThickness", 0.16f),
                legerLineExtension = getStaffSpace("legerLineExtension", 0.4f),
                legerLineThickness = getStaffSpace("legerLineThickness", 0.16f),
                lyricLineThickness = getStaffSpace("lyricLineThickness", 0.16f),
                octaveLineThickness = getStaffSpace("octaveLineThickness", 0.16f),
                pedalLineThickness = getStaffSpace("pedalLineThickness", 0.16f),
                repeatBarlineDotSeparation = getStaffSpace("repeatBarlineDotSeparation", 0.16f),
                repeatEndingLineThickness = getStaffSpace("repeatEndingLineThickness", 0.16f),
                slurEndpointThickness = getStaffSpace("slurEndpointThickness", 0.1f),
                slurMidpointThickness = getStaffSpace("slurMidpointThickness", 0.22f),
                staffLineThickness = getStaffSpace("staffLineThickness", 0.13f),
                stemThickness = getStaffSpace("stemThickness", 0.12f),
                subBracketThickness = getStaffSpace("subBracketThickness", 0.16f),
                textEnclosureThickness = getStaffSpace("textEnclosureThickness", 0.16f),
                thickBarlineThickness = getStaffSpace("thickBarlineThickness", 0.5f),
                thinBarlineThickness = getStaffSpace("thinBarlineThickness", 0.16f),
                tieEndpointThickness = getStaffSpace("tieEndpointThickness", 0.1f),
                tieMidpointThickness = getStaffSpace("tieMidpointThickness", 0.22f),
                tupletBracketThickness = getStaffSpace("tupletBracketThickness", 0.16f)
            )
        }

        private fun parseGlyphAdvanceWidths(obj: JsonObject?): Map<String, StaffSpace> {
            if (obj == null) return emptyMap()
            return obj.entries.associate { (key, value) ->
                key to StaffSpace(value.jsonPrimitive.float)
            }
        }

        private fun parseGlyphBBoxes(obj: JsonObject?): Map<String, GlyphBBox> {
            if (obj == null) return emptyMap()
            return obj.entries.mapNotNull { (key, value) ->
                val bbox = value.jsonObject
                val swArray = bbox["bBoxSW"]?.let { parseCoordinateArray(it) }
                val neArray = bbox["bBoxNE"]?.let { parseCoordinateArray(it) }
                if (swArray != null && neArray != null) {
                    key to GlyphBBox(
                        southWest = GlyphPoint(StaffSpace(swArray[0]), StaffSpace(swArray[1])),
                        northEast = GlyphPoint(StaffSpace(neArray[0]), StaffSpace(neArray[1]))
                    )
                } else null
            }.toMap()
        }

        private fun parseCoordinateArray(element: JsonElement): FloatArray? {
            return try {
                val arr = element as? kotlinx.serialization.json.JsonArray ?: return null
                if (arr.size >= 2) {
                    floatArrayOf(
                        arr[0].jsonPrimitive.float,
                        arr[1].jsonPrimitive.float
                    )
                } else null
            } catch (e: Exception) {
                null
            }
        }

        private fun parseGlyphAnchors(obj: JsonObject?): Map<String, GlyphAnchors> {
            if (obj == null) return emptyMap()
            return obj.entries.mapNotNull { (glyphName, value) ->
                val glyphObj = value.jsonObject
                val anchors = GlyphAnchors(
                    stemUpSE = glyphObj["stemUpSE"]?.let { parseCoordinateArray(it) }
                        ?.let { GlyphPoint(StaffSpace(it[0]), StaffSpace(it[1])) },
                    stemDownNW = glyphObj["stemDownNW"]?.let { parseCoordinateArray(it) }
                        ?.let { GlyphPoint(StaffSpace(it[0]), StaffSpace(it[1])) },
                    stemUpNW = glyphObj["stemUpNW"]?.let { parseCoordinateArray(it) }
                        ?.let { GlyphPoint(StaffSpace(it[0]), StaffSpace(it[1])) },
                    stemDownSW = glyphObj["stemDownSW"]?.let { parseCoordinateArray(it) }
                        ?.let { GlyphPoint(StaffSpace(it[0]), StaffSpace(it[1])) }
                )
                // Only include if at least one stem anchor is present
                if (anchors.stemUpSE != null || anchors.stemDownNW != null) {
                    glyphName to anchors
                } else null
            }.toMap()
        }
    }

    /**
     * Get advance width for a glyph.
     */
    fun getAdvanceWidth(glyphName: String): StaffSpace =
        glyphAdvanceWidths[glyphName] ?: StaffSpace.ONE

    /**
     * Get bounding box for a glyph.
     */
    fun getBBox(glyphName: String): GlyphBBox? = glyphBBoxes[glyphName]

    /**
     * Get notehead width for a specific notehead type.
     * Falls back to default if bbox not found.
     */
    fun getNoteheadWidth(noteheadGlyphName: String): StaffSpace {
        val bbox = getBBox(noteheadGlyphName) ?: return Companion.DEFAULT_NOTEHEAD_WIDTH
        return bbox.width
    }

    /**
     * Get notehead height for a specific notehead type.
     * Falls back to default if bbox not found.
     */
    fun getNoteheadHeight(noteheadGlyphName: String): StaffSpace {
        val bbox = getBBox(noteheadGlyphName) ?: return Companion.DEFAULT_NOTEHEAD_HEIGHT
        return bbox.height
    }

    /**
     * Get anchors for a glyph.
     */
    fun getGlyphAnchors(glyphName: String): GlyphAnchors? = glyphAnchors[glyphName]
}

/**
 * Glyph bounding box.
 */
@Serializable
data class GlyphBBox(
    val southWest: GlyphPoint,
    val northEast: GlyphPoint
) {
    val width: StaffSpace get() = northEast.x - southWest.x
    val height: StaffSpace get() = northEast.y - southWest.y
}

/**
 * Point for glyph coordinates.
 */
@Serializable
data class GlyphPoint(
    val x: StaffSpace,
    val y: StaffSpace
)

/**
 * Glyph anchor points for stem attachment.
 *
 * Coordinates are in SMuFL convention (Y increases upward).
 * - stemUpSE: Bottom-right corner of upward stem (south-east)
 * - stemDownNW: Top-left corner of downward stem (north-west)
 * - stemUpNW: Extension for up-stem to connect with flag
 * - stemDownSW: Extension for down-stem to connect with flag
 */
@Serializable
data class GlyphAnchors(
    val stemUpSE: GlyphPoint? = null,
    val stemDownNW: GlyphPoint? = null,
    val stemUpNW: GlyphPoint? = null,
    val stemDownSW: GlyphPoint? = null
)

/**
 * Raw glyph name entry from glyphnames.json.
 */
@Serializable
data class GlyphNameEntry(
    val codepoint: String,
    val description: String = ""
) {
    /**
     * Parse the codepoint string (e.g., "U+E0A0") to a Char.
     */
    fun toChar(): Char {
        val hex = codepoint.removePrefix("U+")
        return hex.toInt(16).toChar()
    }
}

/**
 * Parser for glyphnames.json file.
 */
object GlyphNamesParser {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Parse glyphnames.json to a map of glyph name to GlyphNameEntry.
     */
    fun parse(jsonString: String): Map<String, GlyphNameEntry> {
        return json.decodeFromString(jsonString)
    }

    /**
     * Get a specific glyph's codepoint character.
     */
    fun getCodepoint(glyphNames: Map<String, GlyphNameEntry>, name: String): Char? {
        return glyphNames[name]?.toChar()
    }
}
