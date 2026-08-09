package com.mecon.renderer.smufl

import com.mecon.renderer.geometry.StaffSpace

/**
 * Bravura font wrapper providing access to SMuFL glyphs and metadata.
 */
class BravuraFont(
    val metadata: SmuflMetadata,
    private val glyphNames: Map<String, GlyphNameEntry>
) {
    /**
     * Engraving defaults from the font metadata.
     */
    val engravingDefaults: EngravingDefaults get() = metadata.engravingDefaults

    /**
     * Get the codepoint character for a glyph by name.
     */
    fun getCodepoint(glyphName: String): Char? {
        return glyphNames[glyphName]?.toChar()
    }

    /**
     * Get the advance width for a glyph.
     */
    fun getAdvanceWidth(glyphName: String): StaffSpace {
        return metadata.getAdvanceWidth(glyphName)
    }

    /**
     * Get the bounding box for a glyph.
     */
    fun getBBox(glyphName: String): GlyphBBox? {
        return metadata.getBBox(glyphName)
    }

    /**
     * Get the glyph string for rendering.
     */
    fun getGlyphString(glyph: GlyphInfo): String {
        return glyph.codepoint.toString()
    }

    /**
     * Get the advance width for a GlyphInfo.
     */
    fun getAdvanceWidth(glyph: GlyphInfo): StaffSpace {
        return getAdvanceWidth(glyph.name)
    }

    /**
     * Get the bounding box for a GlyphInfo.
     */
    fun getBBox(glyph: GlyphInfo): GlyphBBox? {
        return getBBox(glyph.name)
    }

    /**
     * Get notehead width for a specific notehead glyph.
     */
    fun getNoteheadWidth(glyphName: String): StaffSpace =
        metadata.getNoteheadWidth(glyphName)

    /**
     * Get notehead height for a specific notehead glyph.
     */
    fun getNoteheadHeight(glyphName: String): StaffSpace =
        metadata.getNoteheadHeight(glyphName)

    /**
     * Get glyph anchors for a glyph.
     */
    fun getGlyphAnchors(glyphName: String): GlyphAnchors? =
        metadata.getGlyphAnchors(glyphName)

    /**
     * Get stem-up attachment point (stemUpSE) for a notehead glyph.
     * Returns the anchor point in SMuFL coordinates (Y up).
     */
    fun getStemUpAttachment(glyphName: String): GlyphPoint? =
        metadata.getGlyphAnchors(glyphName)?.stemUpSE

    /**
     * Get stem-down attachment point (stemDownNW) for a notehead glyph.
     * Returns the anchor point in SMuFL coordinates (Y up).
     */
    fun getStemDownAttachment(glyphName: String): GlyphPoint? =
        metadata.getGlyphAnchors(glyphName)?.stemDownNW

    companion object {
        /**
         * Create a BravuraFont from JSON strings.
         */
        fun fromJson(metadataJson: String, glyphNamesJson: String): BravuraFont {
            val metadata = SmuflMetadata.fromJson(metadataJson)
            val glyphNames = GlyphNamesParser.parse(glyphNamesJson)
            return BravuraFont(metadata, glyphNames)
        }
    }
}

/**
 * Interface for platform-specific font loading.
 */
expect class BravuraFontLoader() {
    /**
     * Load the Bravura font from resources.
     * Returns null if loading fails.
     */
    suspend fun load(): BravuraFont?
}
