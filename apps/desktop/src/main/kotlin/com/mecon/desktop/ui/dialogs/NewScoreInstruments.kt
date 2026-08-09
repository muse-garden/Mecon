package com.mecon.desktop.ui.dialogs

import com.mecon.api.storage.InstrumentTemplate
import com.mecon.api.storage.StaffTemplate
import com.mecon.api.storage.tracks.Clef

/** One selectable notation instrument. Playback choices intentionally stay separate. */
internal data class ScoreInstrumentDefinition(
    val id: String,
    val name: String,
    val nameKey: String,
    val category: InstrumentCategory,
    val abbreviation: String,
    val defaultStaves: List<StaffTemplate>,
    val msBasicPresetIds: List<String>
) {
    fun template(displayName: String = name, staffCount: Int = defaultStaves.size): InstrumentTemplate {
        val staves = List(staffCount.coerceAtLeast(1)) { index ->
            defaultStaves.getOrElse(index) { defaultStaves.last() }.copy(
                name = if (staffCount == 1) displayName else "$displayName ${index + 1}"
            )
        }
        return InstrumentTemplate(
            name = displayName,
            abbreviation = abbreviation,
            staves = staves,
            midiProgram = MsBasicCatalog.defaultProgramFor(id),
            catalogId = id,
            labelInHeader = true
        )
    }
}

internal enum class InstrumentCategory(val labelKey: String) {
    WOODWIND("dialog.new.instrumentCategory.woodwind"),
    BRASS("dialog.new.instrumentCategory.brass"),
    STRINGS("dialog.new.instrumentCategory.strings"),
    PERCUSSION("dialog.new.instrumentCategory.percussion"),
    KEYBOARD("dialog.new.instrumentCategory.keyboard")
}

/** A patch exposed by the bundled MS Basic SoundFont (bank/program are zero-based). */
internal data class MsBasicPreset(
    val id: String,
    val name: String,
    val bank: Int,
    val program: Int
)

/**
 * Bundled SoundFont mapping is many-to-many: an instrument can use several patches,
 * and ensemble patches can serve several notation instruments. For now creation uses
 * the first patch; articulation-aware selection can choose among the same list later.
 */
internal object MsBasicCatalog {
    val presets = listOf(
        MsBasicPreset("acoustic_grand_piano", "Acoustic Grand Piano", 0, 0),
        MsBasicPreset("church_organ", "Church Organ", 0, 19),
        MsBasicPreset("orchestral_harp", "Orchestral Harp", 0, 46),
        MsBasicPreset("timpani", "Timpani", 0, 47),
        MsBasicPreset("string_ensemble_1", "String Ensemble 1", 0, 48),
        MsBasicPreset("string_ensemble_2", "String Ensemble 2", 0, 49),
        MsBasicPreset("choir_aahs", "Choir Aahs", 0, 52),
        MsBasicPreset("trumpet", "Trumpet", 0, 56),
        MsBasicPreset("trombone", "Trombone", 0, 57),
        MsBasicPreset("tuba", "Tuba", 0, 58),
        MsBasicPreset("french_horn", "French Horn", 0, 60),
        MsBasicPreset("oboe", "Oboe", 0, 68),
        MsBasicPreset("english_horn", "English Horn", 0, 69),
        MsBasicPreset("bassoon", "Bassoon", 0, 70),
        MsBasicPreset("clarinet", "Clarinet", 0, 71),
        MsBasicPreset("piccolo", "Piccolo", 0, 72),
        MsBasicPreset("flute", "Flute", 0, 73),
        MsBasicPreset("violin", "Violin", 0, 40),
        MsBasicPreset("viola", "Viola", 0, 41),
        MsBasicPreset("cello", "Cello", 0, 42),
        MsBasicPreset("contrabass", "Contrabass", 0, 43)
    )

    private val byId = presets.associateBy { it.id }

    fun presetsFor(instrumentId: String): List<MsBasicPreset> =
        ScoreInstrumentCatalog.byId(instrumentId)?.msBasicPresetIds.orEmpty().mapNotNull(byId::get)

    fun instrumentsFor(presetId: String): List<ScoreInstrumentDefinition> =
        ScoreInstrumentCatalog.all.filter { presetId in it.msBasicPresetIds }

    fun defaultProgramFor(instrumentId: String): Int = presetsFor(instrumentId).firstOrNull()?.program ?: 0
}

internal object ScoreInstrumentCatalog {
    private fun definition(
        id: String,
        name: String,
        category: InstrumentCategory,
        abbreviation: String,
        clefs: List<Clef>,
        vararg presetIds: String
    ) = ScoreInstrumentDefinition(
        id, name, "dialog.new.instrument.$id", category, abbreviation,
        clefs.mapIndexed { index, clef -> StaffTemplate(if (clefs.size == 1) name else "$name ${index + 1}", clef) },
        presetIds.toList()
    )

    val all = listOf(
        definition("piano", "Piano", InstrumentCategory.KEYBOARD, "Pno.", listOf(Clef.TREBLE, Clef.BASS), "acoustic_grand_piano"),
        definition("organ", "Organ", InstrumentCategory.KEYBOARD, "Org.", listOf(Clef.TREBLE, Clef.TREBLE, Clef.BASS), "church_organ"),
        definition("harp", "Harp", InstrumentCategory.STRINGS, "Hp.", listOf(Clef.TREBLE, Clef.BASS), "orchestral_harp"),
        definition("piccolo", "Piccolo", InstrumentCategory.WOODWIND, "Picc.", listOf(Clef.TREBLE), "piccolo", "flute"),
        definition("flute", "Flute", InstrumentCategory.WOODWIND, "Fl.", listOf(Clef.TREBLE), "flute", "piccolo"),
        definition("oboe", "Oboe", InstrumentCategory.WOODWIND, "Ob.", listOf(Clef.TREBLE), "oboe"),
        definition("english_horn", "English Horn", InstrumentCategory.WOODWIND, "E.H.", listOf(Clef.TREBLE), "english_horn", "oboe"),
        definition("clarinet", "Clarinet", InstrumentCategory.WOODWIND, "Cl.", listOf(Clef.TREBLE), "clarinet"),
        definition("bass_clarinet", "Bass Clarinet", InstrumentCategory.WOODWIND, "B.Cl.", listOf(Clef.TREBLE), "clarinet"),
        definition("bassoon", "Bassoon", InstrumentCategory.WOODWIND, "Bsn.", listOf(Clef.BASS), "bassoon"),
        definition("contrabassoon", "Contrabassoon", InstrumentCategory.WOODWIND, "Cbsn.", listOf(Clef.BASS), "bassoon"),
        definition("horn", "Horn", InstrumentCategory.BRASS, "Hn.", listOf(Clef.TREBLE), "french_horn"),
        definition("trumpet", "Trumpet", InstrumentCategory.BRASS, "Tpt.", listOf(Clef.TREBLE), "trumpet"),
        definition("trombone", "Trombone", InstrumentCategory.BRASS, "Tbn.", listOf(Clef.BASS), "trombone"),
        definition("bass_trombone", "Bass Trombone", InstrumentCategory.BRASS, "B.Tbn.", listOf(Clef.BASS), "trombone"),
        definition("tuba", "Tuba", InstrumentCategory.BRASS, "Tba.", listOf(Clef.BASS), "tuba"),
        definition("timpani", "Timpani", InstrumentCategory.PERCUSSION, "Tmp.", listOf(Clef.BASS), "timpani"),
        definition("violin", "Violin", InstrumentCategory.STRINGS, "Vln.", listOf(Clef.TREBLE), "violin", "string_ensemble_1", "string_ensemble_2"),
        definition("viola", "Viola", InstrumentCategory.STRINGS, "Vla.", listOf(Clef.ALTO), "viola", "string_ensemble_1", "string_ensemble_2"),
        definition("cello", "Cello", InstrumentCategory.STRINGS, "Vc.", listOf(Clef.BASS), "cello", "string_ensemble_1", "string_ensemble_2"),
        definition("double_bass", "Double Bass", InstrumentCategory.STRINGS, "Cb.", listOf(Clef.BASS), "contrabass", "string_ensemble_1", "string_ensemble_2")
    )

    private val indexed = all.associateBy { it.id }
    fun byId(id: String?): ScoreInstrumentDefinition? = id?.let(indexed::get)

    fun template(id: String, displayName: String? = null, staffCount: Int? = null): InstrumentTemplate {
        val definition = requireNotNull(byId(id)) { "Unknown instrument id: $id" }
        return definition.template(displayName ?: definition.name, staffCount ?: definition.defaultStaves.size)
    }
}
