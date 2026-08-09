package com.mecon.theory.harmony

/** Reusable named/symbolic bases referenced by chapter-owned construction routes. */
object ChordConstructionBases {
    val DOMINANT_SEVENTH = ChordConstructionBasisDefinition(
        id = ChordConstructionBasisId("dominant-seventh"),
        primaryNameKey = "chordDetail.dominantSeventh.primaryName",
        secondaryNameKey = "chordDetail.dominantSeventh.secondaryName",
        romanNumeral = "V7",
    )

    val DOMINANT_NINTH = ChordConstructionBasisDefinition(
        id = ChordConstructionBasisId("dominant-ninth"),
        primaryNameKey = "chordDetail.dominantNinth.primaryName",
        secondaryNameKey = "chordDetail.dominantNinth.secondaryName",
        romanNumeral = "V9",
    )
}
