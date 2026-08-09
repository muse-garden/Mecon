package com.mecon.core.musicxml.model

/**
 * MusicXML intermediate model for score-partwise format.
 *
 * This intermediate representation bridges raw XML parsing and our StorageScore model.
 * It closely mirrors MusicXML structure for accurate parsing and writing.
 */
data class MusicXmlScore(
    /** Work title (from <work-title>) */
    val workTitle: String? = null,
    /** Movement title (from <movement-title>) */
    val movementTitle: String? = null,
    /** Identification info */
    val identification: MusicXmlIdentification? = null,
    /** Score defaults such as MusicXML scaling and page layout */
    val defaults: MusicXmlDefaults? = null,
    /** Page credits / title block text (from <credit>) */
    val credits: List<MusicXmlCredit> = emptyList(),
    /** Part list defining parts and their attributes */
    val partList: List<MusicXmlPartInfo> = emptyList(),
    /** Actual parts with measures and notes */
    val parts: List<MusicXmlPart> = emptyList()
) {
    /**
     * Get the effective title (work title > movement title).
     */
    fun getTitle(): String =
        workTitle?.takeUnlessBlank()
            ?: creditTextOfType("title")
            ?: movementTitle?.takeUnlessBlank()
            ?: inferredTitleCredit()?.primaryText()
            ?: "Untitled"

    /**
     * Get the effective subtitle.
     */
    fun getSubtitle(): String? =
        creditTextOfType("subtitle")
            ?: movementTitle?.takeUnlessBlank()?.takeIf { workTitle?.takeUnlessBlank() != null }
            ?: inferredSubtitleCredit()?.primaryText()

    /**
     * Get a creator by MusicXML type, falling back to typed or inferred credits.
     */
    fun getCreator(type: String): String? =
        identification?.creators
            ?.firstOrNull { it.type.equals(type, ignoreCase = true) }
            ?.name
            ?.takeUnlessBlank()
            ?: creditTextOfType(type)
            ?: when (type.lowercase()) {
                "composer" -> inferredComposerCredit()?.primaryText()
                "lyricist" -> inferredLyricistCredit()?.primaryText()
                else -> null
            }

    private fun creditTextOfType(type: String): String? =
        firstPageCredits()
            .firstOrNull { credit ->
                credit.creditTypes.any { it.equals(type, ignoreCase = true) }
            }
            ?.primaryText()

    private fun inferredTitleCredit(): MusicXmlCredit? {
        val candidates = firstPageCredits().filter { it.primaryText() != null }
        val centered = candidates.filter { it.isCenteredLike() }
        return centered.maxWithOrNull(compareBy<MusicXmlCredit> { it.primaryFontSize() ?: Float.NEGATIVE_INFINITY }
            .thenBy { it.primaryY() ?: Float.NEGATIVE_INFINITY })
            ?: candidates.maxByOrNull { it.primaryY() ?: Float.NEGATIVE_INFINITY }
    }

    private fun inferredSubtitleCredit(): MusicXmlCredit? {
        val titleCredit = inferredTitleCredit()
        val candidates = firstPageCredits()
            .filter { it != titleCredit && it.primaryText() != null && it.isCenteredLike() }
        return candidates.maxByOrNull { it.primaryY() ?: Float.NEGATIVE_INFINITY }
    }

    private fun inferredComposerCredit(): MusicXmlCredit? {
        val candidates = firstPageCredits().filter { it.primaryText() != null }
        val rightAligned = candidates.filter { it.isRightAlignedLike() }
        return rightAligned.maxByOrNull { it.primaryY() ?: Float.NEGATIVE_INFINITY }
            ?: candidates.firstOrNull { !it.isCenteredLike() }
    }

    private fun inferredLyricistCredit(): MusicXmlCredit? {
        val candidates = firstPageCredits().filter { it.primaryText() != null }
        val leftAligned = candidates.filter { it.isLeftAlignedLike() }
        return leftAligned.maxByOrNull { it.primaryY() ?: Float.NEGATIVE_INFINITY }
    }

    private fun firstPageCredits(): List<MusicXmlCredit> =
        credits.filter { it.page == null || it.page == 1 }
}

/**
 * Identification section containing creator and encoding info.
 */
data class MusicXmlIdentification(
    val creators: List<MusicXmlCreator> = emptyList(),
    val rights: String? = null,
    val encoding: MusicXmlEncoding? = null,
    val source: String? = null,
    /** Miscellaneous text that doesn't fit elsewhere */
    val miscellaneous: List<String> = emptyList()
)

/**
 * Creator entry (composer, lyricist, arranger, etc.).
 */
data class MusicXmlCreator(
    val type: String,  // "composer", "lyricist", "arranger", etc.
    val name: String
)

/**
 * Encoding information.
 */
data class MusicXmlEncoding(
    val software: String? = null,
    val encodingDate: String? = null,
    val supports: List<String> = emptyList()
)

/**
 * Credit block shown on a page, typically for title / subtitle / composer.
 */
data class MusicXmlCredit(
    val page: Int? = null,
    val creditTypes: List<String> = emptyList(),
    val words: List<MusicXmlCreditWords> = emptyList()
) {
    fun primaryText(): String? = words.firstNotNullOfOrNull { it.text.takeUnlessBlank() }

    fun isCenteredLike(): Boolean = words.any { it.justify.equals("center", true) || it.halign.equals("center", true) }

    fun isRightAlignedLike(): Boolean = words.any { it.justify.equals("right", true) || it.halign.equals("right", true) }

    fun isLeftAlignedLike(): Boolean = words.any { it.justify.equals("left", true) || it.halign.equals("left", true) }

    fun primaryFontSize(): Float? = words.firstNotNullOfOrNull { it.fontSize }

    fun primaryY(): Float? = words.firstNotNullOfOrNull { it.defaultY }
}

/**
 * One textual line inside a credit block.
 */
data class MusicXmlCreditWords(
    val text: String,
    val defaultX: Float? = null,
    val defaultY: Float? = null,
    val justify: String? = null,
    val halign: String? = null,
    val valign: String? = null,
    val fontFamily: String? = null,
    val fontSize: Float? = null
)

/**
 * Score-wide layout defaults.
 *
 * MusicXML physical layout dimensions are expressed in tenths. [scaling]
 * maps those tenths to millimetres.
 */
data class MusicXmlDefaults(
    val scaling: MusicXmlScaling? = null,
    val pageLayout: MusicXmlPageLayout? = null
)

data class MusicXmlScaling(
    val millimeters: Float,
    val tenths: Float
)

data class MusicXmlPageLayout(
    val pageHeight: Float? = null,
    val pageWidth: Float? = null,
    val margins: MusicXmlPageMargins? = null
)

data class MusicXmlPageMargins(
    val type: String? = null,
    val leftMargin: Float? = null,
    val rightMargin: Float? = null,
    val topMargin: Float? = null,
    val bottomMargin: Float? = null
)

/**
 * Part info from part-list (defines the part before its content).
 */
data class MusicXmlPartInfo(
    val id: String,
    val partName: String,
    val partAbbreviation: String? = null,
    val scoreInstrument: MusicXmlScoreInstrument? = null,
    val midiInstrument: MusicXmlMidiInstrument? = null
)

/**
 * Score instrument definition.
 */
data class MusicXmlScoreInstrument(
    val id: String,
    val instrumentName: String,
    val instrumentAbbreviation: String? = null
)

/**
 * MIDI instrument mapping (for playback).
 */
data class MusicXmlMidiInstrument(
    val id: String,
    val midiChannel: Int? = null,
    val midiProgram: Int? = null,
    val midiUnpitched: Int? = null,
    val volume: Float? = null,
    val pan: Float? = null
)

private fun String.takeUnlessBlank(): String? = takeIf { it.isNotBlank() }
