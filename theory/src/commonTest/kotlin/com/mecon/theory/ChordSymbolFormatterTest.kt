package com.mecon.theory

import com.mecon.api.primitive.KeySignature
import com.mecon.api.primitive.PitchClass
import com.mecon.theory.harmony.FunctionalChordSymbol
import kotlin.test.Test
import kotlin.test.assertEquals

class ChordSymbolFormatterTest {
    @Test
    fun formatsFunctionalAndAppliedRomanSymbols() {
        assertEquals(
            "V7/V",
            FunctionalChordSymbolFormatter.format(
                FunctionalChordSymbol(
                    degree = 5,
                    quality = ChordQuality.DOMINANT7,
                    arity = ChordArity.SEVENTH,
                    appliedToDegree = 5,
                )
            ),
        )
        assertEquals(
            "vii°7/II",
            FunctionalChordSymbolFormatter.format(
                FunctionalChordSymbol(
                    degree = 7,
                    quality = ChordQuality.DIMINISHED7,
                    arity = ChordArity.SEVENTH,
                    appliedToDegree = 2,
                )
            ),
        )
    }

    @Test
    fun letterStyleMatchesExistingChordSymbols() {
        val chord = Chord(PitchClass(2), ChordQuality.MINOR, bass = PitchClass(5))

        assertEquals("Dm/F", ChordSymbolFormatter.format(chord))
    }

    @Test
    fun scaleDegreeStyleUsesKeyRelativeRootAndBass() {
        val chord = Chord(PitchClass(2), ChordQuality.MINOR, bass = PitchClass(5))

        assertEquals(
            "2m/4",
            ChordSymbolFormatter.format(chord, ChordSymbolDisplayStyle.SCALE_DEGREE, KeySignature.C_MAJOR),
        )
    }

    @Test
    fun scaleDegreeStyleRendersAccidentals() {
        val chord = Chord(PitchClass(1), ChordQuality.DOMINANT7_FLAT5)

        assertEquals(
            "♯17♭5",
            ChordSymbolFormatter.format(chord, ChordSymbolDisplayStyle.SCALE_DEGREE, KeySignature.C_MAJOR),
        )
    }

    @Test
    fun scaleDegreeStyleUsesCurrentKeySignature() {
        val chord = Chord(PitchClass(0), ChordQuality.MAJOR7)

        assertEquals(
            "4maj7",
            ChordSymbolFormatter.format(chord, ChordSymbolDisplayStyle.SCALE_DEGREE, KeySignature.G_MAJOR),
        )
    }

    @Test
    fun specialQualitySuffixProtocolIsCentralized() {
        assertEquals("7°7", ChordSymbolFormatter.format(Chord(PitchClass(11), ChordQuality.DIMINISHED7), ChordSymbolDisplayStyle.SCALE_DEGREE))
        assertEquals("7ø7", ChordSymbolFormatter.format(Chord(PitchClass(11), ChordQuality.HALF_DIMINISHED7), ChordSymbolDisplayStyle.SCALE_DEGREE))
    }

    @Test
    fun scaleDegreeStyleReturnsStructuredRootAndQualityPartsWithoutGap() {
        val symbol = ChordSymbolFormatter.formatSymbol(
            Chord(PitchClass(7), ChordQuality.DOMINANT7),
            ChordSymbolDisplayStyle.SCALE_DEGREE,
            KeySignature.C_MAJOR,
        )

        assertEquals("57", symbol.plainText)
        assertEquals(
            listOf(ChordSymbolPartRole.ROOT, ChordSymbolPartRole.QUALITY),
            symbol.parts.map { it.role },
        )
    }

    @Test
    fun toFormattedTextStylesRootBoldInDegreeModeAndShrinksQuality() {
        val symbol = ChordSymbolFormatter.formatSymbol(
            Chord(PitchClass(7), ChordQuality.DOMINANT7),
            ChordSymbolDisplayStyle.SCALE_DEGREE,
            KeySignature.C_MAJOR,
        )
        val formatted = symbol.toFormattedText(ChordSymbolDisplayStyle.SCALE_DEGREE)

        assertEquals("57", formatted.plainText)
        // Root numeral is bold at full size; quality suffix is shrunk.
        assertEquals(true, formatted.runs.first().style.bold)
        assertEquals(1f, formatted.runs.first().style.sizeScale)
        assertEquals(ChordSymbol.QUALITY_SIZE_SCALE, formatted.runs.last().style.sizeScale)

        // Letter mode keeps the shrunk quality but does not bold the root.
        val letter = ChordSymbolFormatter.formatSymbol(Chord(PitchClass(2), ChordQuality.MINOR7))
            .toFormattedText(ChordSymbolDisplayStyle.LETTER)
        assertEquals(false, letter.runs.first().style.bold)
        assertEquals(ChordSymbol.QUALITY_SIZE_SCALE, letter.runs.last().style.sizeScale)
    }
}
