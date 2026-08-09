package com.mecon.desktop.ui.dialogs

import com.mecon.api.storage.InstrumentTemplate
import com.mecon.api.storage.StaffGroupTemplate
import com.mecon.api.storage.StaffTemplate
import com.mecon.api.storage.tracks.BracketStyle
import com.mecon.api.storage.tracks.Clef

internal data class ScorePreset(
    val id: String,
    val category: String,
    val label: String,
    val instruments: List<InstrumentTemplate>,
    val groups: List<StaffGroupTemplate> = emptyList()
)

internal fun ScorePreset.editableGroups(): List<EditableGroup> {
    val staffStarts = buildList {
        var total = 0
        instruments.forEach { add(total); total += it.staves.size }
    }
    val mapped = groups.filter { it.bracket != BracketStyle.NONE }.mapNotNull { group ->
        val start = staffStarts.indexOfLast { it <= group.startStaffIndex }.coerceAtLeast(0)
        val end = staffStarts.indexOfLast { it <= group.endStaffIndex }.coerceAtLeast(start)
        val automaticBrace = start == end && instruments[start].staves.size > 1 && group.bracket == BracketStyle.BRACE
        if (automaticBrace) null else EditableGroup(start, end, group.bracket)
    }
    return mapped
}

private fun instrument(
    id: String,
    displayName: String? = null,
    staves: List<StaffTemplate>? = null
): InstrumentTemplate {
    val definition = requireNotNull(ScoreInstrumentCatalog.byId(id))
    val name = displayName ?: definition.name
    return definition.template(name).let { template ->
        if (staves == null) template else template.copy(staves = staves)
    }
}

private fun familyGroups(instruments: List<InstrumentTemplate>): List<StaffGroupTemplate> {
    var staff = 0
    return instruments.map { item ->
        val start = staff
        staff += item.staves.size
        StaffGroupTemplate(
            startStaffIndex = start,
            endStaffIndex = staff - 1,
            bracket = if (item.staves.size > 1) BracketStyle.BRACE else BracketStyle.NONE,
            barlineConnect = item.staves.size > 1
        )
    }
}

private fun instrumentRangeGroup(
    instruments: List<InstrumentTemplate>,
    startInstrument: Int,
    endInstrument: Int,
    bracket: BracketStyle = BracketStyle.SQUARE,
    label: String? = null
): StaffGroupTemplate {
    val startStaff = instruments.take(startInstrument).sumOf { it.staves.size }
    val endStaff = instruments.take(endInstrument + 1).sumOf { it.staves.size } - 1
    return StaffGroupTemplate(startStaff, endStaff, bracket, label)
}

internal object ScorePresetCatalog {
    const val SINGLE = "dialog.new.category.single"
    const val CHAMBER = "dialog.new.category.chamber"
    const val ORCHESTRA = "dialog.new.category.orchestra"

    private val treble = instrument("piano", staves = listOf(StaffTemplate("Piano", Clef.TREBLE)))
    private val bass = instrument("piano", staves = listOf(StaffTemplate("Piano", Clef.BASS)))
    private val piano = instrument(
        "piano",
        staves = listOf(StaffTemplate("Piano RH", Clef.TREBLE), StaffTemplate("Piano LH", Clef.BASS))
    )
    private val organ = instrument(
        "organ",
        staves = listOf(StaffTemplate("Great", Clef.TREBLE), StaffTemplate("Swell", Clef.TREBLE), StaffTemplate("Pedal", Clef.BASS))
    )

    private val strings = listOf(
        instrument("violin", "Violin I"),
        instrument("violin", "Violin II"),
        instrument("viola"),
        instrument("cello"),
        instrument("double_bass")
    )
    private val winds = listOf(
        instrument("flute"),
        instrument("oboe"),
        instrument("clarinet"),
        instrument("bassoon")
    )
    private val brass = listOf(
        instrument("trumpet", "Trumpet I"),
        instrument("trumpet", "Trumpet II"),
        instrument("horn"),
        instrument("trombone"),
        instrument("tuba")
    )

    val all: List<ScorePreset> = buildList {
        add(ScorePreset("treble", SINGLE, "dialog.new.preset.treble", listOf(treble)))
        add(ScorePreset("bass", SINGLE, "dialog.new.preset.bass", listOf(bass)))
        add(ScorePreset("piano", SINGLE, "dialog.new.preset.piano", listOf(piano), familyGroups(listOf(piano))))
        add(ScorePreset("organ", SINGLE, "dialog.new.preset.organ", listOf(organ), familyGroups(listOf(organ))))

        val stringQuartet = strings.take(4)
        add(ScorePreset("string_quartet", CHAMBER, "dialog.new.preset.stringQuartet", stringQuartet,
            listOf(StaffGroupTemplate(0, 3, BracketStyle.SQUARE, barlineConnect = false))))
        val pianoQuartet = listOf(piano, strings[0], strings[2], strings[3])
        add(ScorePreset("piano_quartet", CHAMBER, "dialog.new.preset.pianoQuartet", pianoQuartet, familyGroups(pianoQuartet)))
        add(ScorePreset("brass_quintet", CHAMBER, "dialog.new.preset.brassQuintet", brass,
            listOf(StaffGroupTemplate(0, 4, BracketStyle.SQUARE))))
        add(ScorePreset("wind_quintet", CHAMBER, "dialog.new.preset.windQuintet",
            winds + instrument("horn"),
            listOf(StaffGroupTemplate(0, 4, BracketStyle.SQUARE))))

        add(ScorePreset("string_orchestra", ORCHESTRA, "dialog.new.preset.stringOrchestra", strings,
            listOf(StaffGroupTemplate(0, 4, BracketStyle.SQUARE))))
        val classical = winds + brass.take(3) + listOf(instrument("timpani")) + strings
        add(ScorePreset("classical_orchestra", ORCHESTRA, "dialog.new.preset.classicalOrchestra", classical,
            listOf(
                instrumentRangeGroup(classical, 0, 3, label = "Woodwinds"),
                instrumentRangeGroup(classical, 4, 6, label = "Brass"),
                instrumentRangeGroup(classical, 8, 12, label = "Strings")
            )))
        val romantic = listOf(
            instrument("piccolo"),
            instrument("flute", "Flutes", listOf(
                StaffTemplate("Flute I", Clef.TREBLE), StaffTemplate("Flute II", Clef.TREBLE)
            )),
            instrument("oboe", "Oboes", listOf(
                StaffTemplate("Oboe I", Clef.TREBLE), StaffTemplate("Oboe II", Clef.TREBLE)
            )),
            instrument("english_horn"),
            instrument("clarinet", "Clarinets", listOf(
                StaffTemplate("Clarinet I", Clef.TREBLE), StaffTemplate("Clarinet II", Clef.TREBLE)
            )),
            instrument("bass_clarinet"),
            instrument("bassoon", "Bassoons", listOf(
                StaffTemplate("Bassoon I", Clef.BASS), StaffTemplate("Bassoon II", Clef.BASS)
            )),
            instrument("contrabassoon"),
            instrument("horn", "Horns", listOf(
                StaffTemplate("Horn I", Clef.TREBLE), StaffTemplate("Horn II", Clef.TREBLE),
                StaffTemplate("Horn III", Clef.TREBLE), StaffTemplate("Horn IV", Clef.TREBLE)
            )),
            instrument("trumpet", "Trumpets", listOf(
                StaffTemplate("Trumpet I", Clef.TREBLE), StaffTemplate("Trumpet II", Clef.TREBLE)
            )),
            instrument("trombone", "Trombones", listOf(
                StaffTemplate("Trombone I", Clef.TENOR), StaffTemplate("Trombone II", Clef.TENOR)
            )),
            instrument("bass_trombone"), instrument("tuba"),
            instrument("timpani"), instrument("harp", staves =
                listOf(StaffTemplate("Harp RH", Clef.TREBLE), StaffTemplate("Harp LH", Clef.BASS)))
        ) + strings
        add(ScorePreset(
            "romantic_orchestra", ORCHESTRA, "dialog.new.preset.romanticOrchestra", romantic,
            listOf(
                instrumentRangeGroup(romantic, 0, 7, label = "Woodwinds"),
                instrumentRangeGroup(romantic, 8, 12, label = "Brass"),
                instrumentRangeGroup(romantic, 14, 19, label = "Strings")
            )
        ))
    }

    val categories = listOf(SINGLE, CHAMBER, ORCHESTRA)
}
