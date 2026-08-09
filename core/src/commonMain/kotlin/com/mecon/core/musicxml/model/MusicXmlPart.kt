package com.mecon.core.musicxml.model

/**
 * A part containing measures with notes.
 */
data class MusicXmlPart(
    /** Part ID (matches id in part-list) */
    val id: String,
    /** Measures in this part */
    val measures: List<MusicXmlMeasure> = emptyList()
)
