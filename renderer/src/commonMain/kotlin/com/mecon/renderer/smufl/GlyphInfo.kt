package com.mecon.renderer.smufl

import com.mecon.renderer.geometry.StaffSpace
import kotlinx.serialization.Serializable

/**
 * Information about a single SMuFL glyph.
 */
@Serializable
data class GlyphInfo(
    val name: String,
    val codepoint: Char,
    val description: String = "",
    val advanceWidth: StaffSpace = StaffSpace.ONE
)

/**
 * Common SMuFL glyph names used in music notation.
 * Codepoints follow the SMuFL specification.
 */
@Suppress("unused")
object SmuflGlyphs {
    // Staff brackets and dividers (U+E000–U+E00F)
    val brace = GlyphInfo("brace", '\uE000', "Brace (piano curly brace)")
    // Bravura's recommended brace stylistic-alternate slots. These are exposed
    // as direct codepoints because the renderer does not apply OpenType salt.
    val braceSmall = GlyphInfo("braceSmall", '\uF400', "Brace (small)")
    val braceLarge = GlyphInfo("braceLarge", '\uF401', "Brace (large)")
    val braceLarger = GlyphInfo("braceLarger", '\uF402', "Brace (larger)")
    val braceFlat = GlyphInfo("braceFlat", '\uF403', "Brace (flat)")
    val reversedBrace = GlyphInfo("reversedBrace", '\uE001', "Reversed brace")
    val bracketTop = GlyphInfo("bracketTop", '\uE003', "Square bracket — top end cap")
    val bracketBottom = GlyphInfo("bracketBottom", '\uE004', "Square bracket — bottom end cap")
    val subBracket = GlyphInfo("subBracket", '\uE005', "Reversed bracket top (sub-bracket)")

    // Noteheads - Standard noteheads (U+E0A0-U+E0FF)
    val noteheadDoubleWhole = GlyphInfo("noteheadDoubleWhole", '\uE0A0', "Double whole note (breve)")
    val noteheadDoubleWholeSquare = GlyphInfo("noteheadDoubleWholeSquare", '\uE0A1', "Double whole note (square)")
    val noteheadWhole = GlyphInfo("noteheadWhole", '\uE0A2', "Whole note (semibreve)")
    val noteheadHalf = GlyphInfo("noteheadHalf", '\uE0A3', "Half note (minim)")
    val noteheadBlack = GlyphInfo("noteheadBlack", '\uE0A4', "Filled (black) note")
    val noteheadNull = GlyphInfo("noteheadNull", '\uE0A5', "Null notehead")

    // Noteheads - Parentheses and circles
    val noteheadParenthesis = GlyphInfo("noteheadParenthesis", '\uE0CE', "Parenthesis notehead")
    val noteheadParenthesisLeft = GlyphInfo("noteheadParenthesisLeft", '\uE0CE', "Left parenthesis")
    val noteheadParenthesisRight = GlyphInfo("noteheadParenthesisRight", '\uE0CF', "Right parenthesis")

    // Individual notes (U+E1D0-U+E1EF)
    val noteDoubleWhole = GlyphInfo("noteDoubleWhole", '\uE1D0', "Double whole note")
    val noteWhole = GlyphInfo("noteWhole", '\uE1D2', "Whole note")
    val noteHalfUp = GlyphInfo("noteHalfUp", '\uE1D3', "Half note, stem up")
    val noteHalfDown = GlyphInfo("noteHalfDown", '\uE1D4', "Half note, stem down")
    val noteQuarterUp = GlyphInfo("noteQuarterUp", '\uE1D5', "Quarter note, stem up")
    val metNoteWhole = GlyphInfo("metNoteWhole", '\uECA2', "Metronome whole note")
    val metNoteHalfUp = GlyphInfo("metNoteHalfUp", '\uECA3', "Metronome half note, stem up")
    val metNoteQuarterUp = GlyphInfo("metNoteQuarterUp", '\uECA5', "Metronome quarter note, stem up")
    val metNote8thUp = GlyphInfo("metNote8thUp", '\uECA7', "Metronome eighth note, stem up")
    val noteQuarterDown = GlyphInfo("noteQuarterDown", '\uE1D6', "Quarter note, stem down")
    val note8thUp = GlyphInfo("note8thUp", '\uE1D7', "8th note, stem up")
    val note8thDown = GlyphInfo("note8thDown", '\uE1D8', "8th note, stem down")
    val note16thUp = GlyphInfo("note16thUp", '\uE1D9', "16th note, stem up")
    val note16thDown = GlyphInfo("note16thDown", '\uE1DA', "16th note, stem down")
    val note32ndUp = GlyphInfo("note32ndUp", '\uE1DB', "32nd note, stem up")
    val note32ndDown = GlyphInfo("note32ndDown", '\uE1DC', "32nd note, stem down")
    val note64thUp = GlyphInfo("note64thUp", '\uE1DD', "64th note, stem up")
    val note64thDown = GlyphInfo("note64thDown", '\uE1DE', "64th note, stem down")
    val note128thUp = GlyphInfo("note128thUp", '\uE1DF', "128th note, stem up")
    val note128thDown = GlyphInfo("note128thDown", '\uE1E0', "128th note, stem down")

    // Flags (U+E240-U+E25F)
    val flag8thUp = GlyphInfo("flag8thUp", '\uE240', "8th flag up")
    val flag8thDown = GlyphInfo("flag8thDown", '\uE241', "8th flag down")
    val flag16thUp = GlyphInfo("flag16thUp", '\uE242', "16th flag up")
    val flag16thDown = GlyphInfo("flag16thDown", '\uE243', "16th flag down")
    val flag32ndUp = GlyphInfo("flag32ndUp", '\uE244', "32nd flag up")
    val flag32ndDown = GlyphInfo("flag32ndDown", '\uE245', "32nd flag down")
    val flag64thUp = GlyphInfo("flag64thUp", '\uE246', "64th flag up")
    val flag64thDown = GlyphInfo("flag64thDown", '\uE247', "64th flag down")
    val flag128thUp = GlyphInfo("flag128thUp", '\uE248', "128th flag up")
    val flag128thDown = GlyphInfo("flag128thDown", '\uE249', "128th flag down")
    val flag256thUp = GlyphInfo("flag256thUp", '\uE24A', "256th flag up")
    val flag256thDown = GlyphInfo("flag256thDown", '\uE24B', "256th flag down")

    // Rests (U+E4E0-U+E4FF)
    val restMaxima = GlyphInfo("restMaxima", '\uE4E0', "Maxima rest")
    val restLonga = GlyphInfo("restLonga", '\uE4E1', "Longa rest")
    val restDoubleWhole = GlyphInfo("restDoubleWhole", '\uE4E2', "Double whole rest")
    val restWhole = GlyphInfo("restWhole", '\uE4E3', "Whole rest")
    val restHalf = GlyphInfo("restHalf", '\uE4E4', "Half rest")
    val restQuarter = GlyphInfo("restQuarter", '\uE4E5', "Quarter rest")
    val rest8th = GlyphInfo("rest8th", '\uE4E6', "8th rest")
    val rest16th = GlyphInfo("rest16th", '\uE4E7', "16th rest")
    val rest32nd = GlyphInfo("rest32nd", '\uE4E8', "32nd rest")
    val rest64th = GlyphInfo("rest64th", '\uE4E9', "64th rest")
    val rest128th = GlyphInfo("rest128th", '\uE4EA', "128th rest")
    val rest256th = GlyphInfo("rest256th", '\uE4EB', "256th rest")

    // Accidentals - Standard (U+E260-U+E26F)
    val accidentalFlat = GlyphInfo("accidentalFlat", '\uE260', "Flat")
    val accidentalNatural = GlyphInfo("accidentalNatural", '\uE261', "Natural")
    val accidentalSharp = GlyphInfo("accidentalSharp", '\uE262', "Sharp")
    val accidentalDoubleSharp = GlyphInfo("accidentalDoubleSharp", '\uE263', "Double sharp")
    val accidentalDoubleFlat = GlyphInfo("accidentalDoubleFlat", '\uE264', "Double flat")
    val accidentalTripleSharp = GlyphInfo("accidentalTripleSharp", '\uE265', "Triple sharp")
    val accidentalTripleFlat = GlyphInfo("accidentalTripleFlat", '\uE266', "Triple flat")
    val accidentalNaturalFlat = GlyphInfo("accidentalNaturalFlat", '\uE267', "Natural flat")
    val accidentalNaturalSharp = GlyphInfo("accidentalNaturalSharp", '\uE268', "Natural sharp")
    val accidentalSharpSharp = GlyphInfo("accidentalSharpSharp", '\uE269', "Sharp sharp")

    // Accidentals - Parenthesized
    val accidentalParensLeft = GlyphInfo("accidentalParensLeft", '\uE26A', "Left parenthesis for accidental")
    val accidentalParensRight = GlyphInfo("accidentalParensRight", '\uE26B', "Right parenthesis for accidental")

    // Clefs - G clef (U+E050-U+E05F)
    val gClef = GlyphInfo("gClef", '\uE050', "G clef (treble clef)")
    val gClef8vb = GlyphInfo("gClef8vb", '\uE052', "G clef ottava bassa")
    val gClef8va = GlyphInfo("gClef8va", '\uE053', "G clef ottava alta")
    val gClef15mb = GlyphInfo("gClef15mb", '\uE051', "G clef quindicesima bassa")
    val gClef15ma = GlyphInfo("gClef15ma", '\uE054', "G clef quindicesima alta")

    // Clefs - C clef (U+E05C-U+E05F)
    val cClef = GlyphInfo("cClef", '\uE05C', "C clef")
    val cClef8vb = GlyphInfo("cClef8vb", '\uE05D', "C clef ottava bassa")

    // Clefs - F clef (U+E062-U+E06F)
    val fClef = GlyphInfo("fClef", '\uE062', "F clef (bass clef)")
    val fClef8vb = GlyphInfo("fClef8vb", '\uE064', "F clef ottava bassa")
    val fClef8va = GlyphInfo("fClef8va", '\uE065', "F clef ottava alta")
    val fClef15mb = GlyphInfo("fClef15mb", '\uE063', "F clef quindicesima bassa")
    val fClef15ma = GlyphInfo("fClef15ma", '\uE066', "F clef quindicesima alta")

    // Clefs - Percussion
    val unpitchedPercussionClef1 = GlyphInfo("unpitchedPercussionClef1", '\uE069', "Unpitched percussion clef 1")
    val unpitchedPercussionClef2 = GlyphInfo("unpitchedPercussionClef2", '\uE06A', "Unpitched percussion clef 2")

    // Time signatures (U+E080-U+E09F)
    val timeSig0 = GlyphInfo("timeSig0", '\uE080', "Time signature 0")
    val timeSig1 = GlyphInfo("timeSig1", '\uE081', "Time signature 1")
    val timeSig2 = GlyphInfo("timeSig2", '\uE082', "Time signature 2")
    val timeSig3 = GlyphInfo("timeSig3", '\uE083', "Time signature 3")
    val timeSig4 = GlyphInfo("timeSig4", '\uE084', "Time signature 4")
    val timeSig5 = GlyphInfo("timeSig5", '\uE085', "Time signature 5")
    val timeSig6 = GlyphInfo("timeSig6", '\uE086', "Time signature 6")
    val timeSig7 = GlyphInfo("timeSig7", '\uE087', "Time signature 7")
    val timeSig8 = GlyphInfo("timeSig8", '\uE088', "Time signature 8")
    val timeSig9 = GlyphInfo("timeSig9", '\uE089', "Time signature 9")
    val timeSigCommon = GlyphInfo("timeSigCommon", '\uE08A', "Common time")
    val timeSigCutCommon = GlyphInfo("timeSigCutCommon", '\uE08B', "Cut time")

    // Barlines (U+E030-U+E03F)
    val barlineSingle = GlyphInfo("barlineSingle", '\uE030', "Single barline")
    val barlineDouble = GlyphInfo("barlineDouble", '\uE031', "Double barline")
    val barlineFinal = GlyphInfo("barlineFinal", '\uE032', "Final barline")
    val barlineReverseFinal = GlyphInfo("barlineReverseFinal", '\uE033', "Reverse final barline")
    val barlineDashed = GlyphInfo("barlineDashed", '\uE036', "Dashed barline")
    val barlineDotted = GlyphInfo("barlineDotted", '\uE037', "Dotted barline")
    val barlineShort = GlyphInfo("barlineShort", '\uE038', "Short barline")
    val barlineTick = GlyphInfo("barlineTick", '\uE039', "Tick barline")

    // Repeats (U+E040-U+E04F)
    val repeatLeft = GlyphInfo("repeatLeft", '\uE040', "Left repeat sign")
    val repeatRight = GlyphInfo("repeatRight", '\uE041', "Right repeat sign")
    val repeatRightLeft = GlyphInfo("repeatRightLeft", '\uE042', "Right-left repeat sign")
    val repeatDot = GlyphInfo("repeatDot", '\uE044', "Repeat dot")
    val repeatDots = GlyphInfo("repeatDots", '\uE043', "Repeat dots")
    val dalSegno = GlyphInfo("dalSegno", '\uE045', "Dal segno")
    val daCapo = GlyphInfo("daCapo", '\uE046', "Da capo")
    val segno = GlyphInfo("segno", '\uE047', "Segno")
    val coda = GlyphInfo("coda", '\uE048', "Coda")

    // Dynamics (U+E520-U+E54F)
    val dynamicPiano = GlyphInfo("dynamicPiano", '\uE520', "p")
    val dynamicMezzo = GlyphInfo("dynamicMezzo", '\uE521', "m")
    val dynamicForte = GlyphInfo("dynamicForte", '\uE522', "f")
    val dynamicRinforzando = GlyphInfo("dynamicRinforzando", '\uE523', "r")
    val dynamicSforzando = GlyphInfo("dynamicSforzando", '\uE524', "s")
    val dynamicZ = GlyphInfo("dynamicZ", '\uE525', "z")
    val dynamicNiente = GlyphInfo("dynamicNiente", '\uE526', "n")
    val dynamicPPPPPP = GlyphInfo("dynamicPPPPPP", '\uE527', "pppppp")
    val dynamicPPPPP = GlyphInfo("dynamicPPPPP", '\uE528', "ppppp")
    val dynamicPPPP = GlyphInfo("dynamicPPPP", '\uE529', "pppp")
    val dynamicPPP = GlyphInfo("dynamicPPP", '\uE52A', "ppp")
    val dynamicPP = GlyphInfo("dynamicPP", '\uE52B', "pp")
    val dynamicMP = GlyphInfo("dynamicMP", '\uE52C', "mp")
    val dynamicMF = GlyphInfo("dynamicMF", '\uE52D', "mf")
    val dynamicPF = GlyphInfo("dynamicPF", '\uE52E', "pf")
    val dynamicFF = GlyphInfo("dynamicFF", '\uE52F', "ff")
    val dynamicFFF = GlyphInfo("dynamicFFF", '\uE530', "fff")
    val dynamicFFFF = GlyphInfo("dynamicFFFF", '\uE531', "ffff")
    val dynamicFFFFF = GlyphInfo("dynamicFFFFF", '\uE532', "fffff")
    val dynamicFFFFFF = GlyphInfo("dynamicFFFFFF", '\uE533', "ffffff")
    val dynamicFortePiano = GlyphInfo("dynamicFortePiano", '\uE534', "fp")
    val dynamicForzando = GlyphInfo("dynamicForzando", '\uE535', "fz")
    val dynamicSforzando1 = GlyphInfo("dynamicSforzando1", '\uE536', "sf")
    val dynamicSforzandoPiano = GlyphInfo("dynamicSforzandoPiano", '\uE537', "sfp")
    val dynamicSforzandoPianissimo = GlyphInfo("dynamicSforzandoPianissimo", '\uE538', "sfpp")
    val dynamicSforzato = GlyphInfo("dynamicSforzato", '\uE539', "sfz")
    val dynamicSforzatoPiano = GlyphInfo("dynamicSforzatoPiano", '\uE53A', "sfzp")
    val dynamicSforzatoFF = GlyphInfo("dynamicSforzatoFF", '\uE53B', "sffz")
    val dynamicRinforzando1 = GlyphInfo("dynamicRinforzando1", '\uE53C', "rf")
    val dynamicRinforzando2 = GlyphInfo("dynamicRinforzando2", '\uE53D', "rfz")
    val dynamicCrescendoHairpin = GlyphInfo("dynamicCrescendoHairpin", '\uE53E', "Crescendo")
    val dynamicDiminuendoHairpin = GlyphInfo("dynamicDiminuendoHairpin", '\uE53F', "Diminuendo")

    // Articulations (U+E4A0-U+E4BF)
    val articAccentAbove = GlyphInfo("articAccentAbove", '\uE4A0', "Accent above")
    val articAccentBelow = GlyphInfo("articAccentBelow", '\uE4A1', "Accent below")
    val articStaccatoAbove = GlyphInfo("articStaccatoAbove", '\uE4A2', "Staccato above")
    val articStaccatoBelow = GlyphInfo("articStaccatoBelow", '\uE4A3', "Staccato below")
    val articTenutoAbove = GlyphInfo("articTenutoAbove", '\uE4A4', "Tenuto above")
    val articTenutoBelow = GlyphInfo("articTenutoBelow", '\uE4A5', "Tenuto below")
    val articStaccatissimoAbove = GlyphInfo("articStaccatissimoAbove", '\uE4A6', "Staccatissimo above")
    val articStaccatissimoBelow = GlyphInfo("articStaccatissimoBelow", '\uE4A7', "Staccatissimo below")
    val articStaccatissimoWedgeAbove = GlyphInfo("articStaccatissimoWedgeAbove", '\uE4A8', "Staccatissimo wedge above")
    val articStaccatissimoWedgeBelow = GlyphInfo("articStaccatissimoWedgeBelow", '\uE4A9', "Staccatissimo wedge below")
    val articStaccatissimoStrokeAbove = GlyphInfo("articStaccatissimoStrokeAbove", '\uE4AA', "Staccatissimo stroke above")
    val articStaccatissimoStrokeBelow = GlyphInfo("articStaccatissimoStrokeBelow", '\uE4AB', "Staccatissimo stroke below")
    val articMarcatoAbove = GlyphInfo("articMarcatoAbove", '\uE4AC', "Marcato above")
    val articMarcatoBelow = GlyphInfo("articMarcatoBelow", '\uE4AD', "Marcato below")
    val articMarcatoStaccatoAbove = GlyphInfo("articMarcatoStaccatoAbove", '\uE4AE', "Marcato-staccato above")
    val articMarcatoStaccatoBelow = GlyphInfo("articMarcatoStaccatoBelow", '\uE4AF', "Marcato-staccato below")
    val articAccentStaccatoAbove = GlyphInfo("articAccentStaccatoAbove", '\uE4B0', "Accent-staccato above")
    val articAccentStaccatoBelow = GlyphInfo("articAccentStaccatoBelow", '\uE4B1', "Accent-staccato below")
    val articTenutoStaccatoAbove = GlyphInfo("articTenutoStaccatoAbove", '\uE4B2', "Tenuto-staccato (louré) above")
    val articTenutoStaccatoBelow = GlyphInfo("articTenutoStaccatoBelow", '\uE4B3', "Tenuto-staccato (louré) below")
    val articTenutoAccentAbove = GlyphInfo("articTenutoAccentAbove", '\uE4B4', "Tenuto-accent above")
    val articTenutoAccentBelow = GlyphInfo("articTenutoAccentBelow", '\uE4B5', "Tenuto-accent below")
    val articStressAbove = GlyphInfo("articStressAbove", '\uE4B6', "Stress above")
    val articStressBelow = GlyphInfo("articStressBelow", '\uE4B7', "Stress below")
    val articUnstressAbove = GlyphInfo("articUnstressAbove", '\uE4B8', "Unstress above")
    val articUnstressBelow = GlyphInfo("articUnstressBelow", '\uE4B9', "Unstress below")

    // Ornaments (U+E560-U+E59F)
    val ornamentTrill = GlyphInfo("ornamentTrill", '\uE566', "Trill")
    val ornamentTurn = GlyphInfo("ornamentTurn", '\uE567', "Turn")
    val ornamentTurnInverted = GlyphInfo("ornamentTurnInverted", '\uE568', "Inverted turn")
    val ornamentTurnSlash = GlyphInfo("ornamentTurnSlash", '\uE569', "Turn with slash")
    val ornamentTurnUp = GlyphInfo("ornamentTurnUp", '\uE56A', "Turn up")
    val ornamentShortTrill = GlyphInfo("ornamentShortTrill", '\uE56C', "Short trill / inverted mordent")
    val ornamentMordent = GlyphInfo("ornamentMordent", '\uE56D', "Mordent")
    val ornamentTremblement = GlyphInfo("ornamentTremblement", '\uE56E', "Tremblement")
    val ornamentTremblementCouperin = GlyphInfo("ornamentTremblementCouperin", '\uE589', "Tremblement appuyé (Couperin)")
    val ornamentPrecompMordentRelease = GlyphInfo("ornamentPrecompMordentRelease", '\uE5C5', "Mordent with release")
    val ornamentPrecompMordentUpperPrefix = GlyphInfo("ornamentPrecompMordentUpperPrefix", '\uE5C6', "Mordent with upper prefix")
    val ornamentPrecompInvertedMordentUpperPrefix = GlyphInfo("ornamentPrecompInvertedMordentUpperPrefix", '\uE5C7', "Inverted mordent with upper prefix")
    val wiggleTrill = GlyphInfo("wiggleTrill", '\uEAA4', "Trill wiggle segment")
    val arpeggiato = GlyphInfo("arpeggiato", '\uE63C', "Arpeggiato")
    val arpeggiatoUp = GlyphInfo("arpeggiatoUp", '\uE634', "Arpeggiato up")
    val arpeggiatoDown = GlyphInfo("arpeggiatoDown", '\uE635', "Arpeggiato down")
    val wiggleArpeggiatoUpArrow = GlyphInfo("wiggleArpeggiatoUpArrow", '\uEAAD', "Arpeggiato arrowhead up")
    val wiggleArpeggiatoDownArrow = GlyphInfo("wiggleArpeggiatoDownArrow", '\uEAAE', "Arpeggiato arrowhead down")

    // Holds and pauses (U+E4C0-U+E4DF)
    val fermataAbove = GlyphInfo("fermataAbove", '\uE4C0', "Fermata above")
    val fermataBelow = GlyphInfo("fermataBelow", '\uE4C1', "Fermata below")
    val fermataVeryShortAbove = GlyphInfo("fermataVeryShortAbove", '\uE4C2', "Very short fermata above")
    val fermataVeryShortBelow = GlyphInfo("fermataVeryShortBelow", '\uE4C3', "Very short fermata below")
    val fermataShortAbove = GlyphInfo("fermataShortAbove", '\uE4C4', "Short fermata above")
    val fermataShortBelow = GlyphInfo("fermataShortBelow", '\uE4C5', "Short fermata below")
    val fermataLongAbove = GlyphInfo("fermataLongAbove", '\uE4C6', "Long fermata above")
    val fermataLongBelow = GlyphInfo("fermataLongBelow", '\uE4C7', "Long fermata below")
    val fermataVeryLongAbove = GlyphInfo("fermataVeryLongAbove", '\uE4C8', "Very long fermata above")
    val fermataVeryLongBelow = GlyphInfo("fermataVeryLongBelow", '\uE4C9', "Very long fermata below")
    val breathMarkComma = GlyphInfo("breathMarkComma", '\uE4CE', "Breath mark (comma)")
    val breathMarkTick = GlyphInfo("breathMarkTick", '\uE4CF', "Breath mark (tick)")
    val breathMarkUpbow = GlyphInfo("breathMarkUpbow", '\uE4D0', "Breath mark (upbow)")
    val breathMarkSalzedo = GlyphInfo("breathMarkSalzedo", '\uE4D5', "Breath mark (Salzedo)")
    val caesura = GlyphInfo("caesura", '\uE4D1', "Caesura")
    val caesuraThick = GlyphInfo("caesuraThick", '\uE4D2', "Thick caesura")
    val caesuraShort = GlyphInfo("caesuraShort", '\uE4D3', "Short caesura")
    val caesuraCurved = GlyphInfo("caesuraCurved", '\uE4D4', "Curved caesura")

    // Augmentation dot
    val augmentationDot = GlyphInfo("augmentationDot", '\uE1E7', "Augmentation dot")

    // Tuplet numbers
    val tuplet0 = GlyphInfo("tuplet0", '\uE880', "Tuplet 0")
    val tuplet1 = GlyphInfo("tuplet1", '\uE881', "Tuplet 1")
    val tuplet2 = GlyphInfo("tuplet2", '\uE882', "Tuplet 2")
    val tuplet3 = GlyphInfo("tuplet3", '\uE883', "Tuplet 3")
    val tuplet4 = GlyphInfo("tuplet4", '\uE884', "Tuplet 4")
    val tuplet5 = GlyphInfo("tuplet5", '\uE885', "Tuplet 5")
    val tuplet6 = GlyphInfo("tuplet6", '\uE886', "Tuplet 6")
    val tuplet7 = GlyphInfo("tuplet7", '\uE887', "Tuplet 7")
    val tuplet8 = GlyphInfo("tuplet8", '\uE888', "Tuplet 8")
    val tuplet9 = GlyphInfo("tuplet9", '\uE889', "Tuplet 9")
    val tupletColon = GlyphInfo("tupletColon", '\uE88A', "Tuplet colon")

    /**
     * Get time signature digit glyph.
     */
    fun timeSigDigit(digit: Int): GlyphInfo = when (digit) {
        0 -> timeSig0
        1 -> timeSig1
        2 -> timeSig2
        3 -> timeSig3
        4 -> timeSig4
        5 -> timeSig5
        6 -> timeSig6
        7 -> timeSig7
        8 -> timeSig8
        9 -> timeSig9
        else -> throw IllegalArgumentException("Invalid digit: $digit")
    }

    /**
     * Get tuplet digit glyph.
     */
    fun tupletDigit(digit: Int): GlyphInfo = when (digit) {
        0 -> tuplet0
        1 -> tuplet1
        2 -> tuplet2
        3 -> tuplet3
        4 -> tuplet4
        5 -> tuplet5
        6 -> tuplet6
        7 -> tuplet7
        8 -> tuplet8
        9 -> tuplet9
        else -> throw IllegalArgumentException("Invalid digit: $digit")
    }
}
