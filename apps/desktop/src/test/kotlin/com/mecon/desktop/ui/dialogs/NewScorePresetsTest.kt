package com.mecon.desktop.ui.dialogs

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class NewScorePresetsTest {
    @Test
    fun catalogueContainsAllRequestedPresets() {
        assertEquals(11, ScorePresetCatalog.all.size)
        assertEquals(
            setOf("treble", "bass", "piano", "organ", "string_quartet", "piano_quartet",
                "brass_quintet", "wind_quintet", "string_orchestra", "classical_orchestra",
                "romantic_orchestra"),
            ScorePresetCatalog.all.map { it.id }.toSet()
        )
        assertTrue(ScorePresetCatalog.all.all { preset ->
            preset.instruments.isNotEmpty() && preset.instruments.all {
                it.staves.isNotEmpty() && ScoreInstrumentCatalog.byId(it.catalogId) != null
            }
        })
    }

    @Test
    fun sameInstrumentCanHaveDifferentDisplayNames() {
        val quartet = ScorePresetCatalog.all.first { it.id == "string_quartet" }
        val first = quartet.instruments[0]
        val second = quartet.instruments[1]

        assertEquals("violin", first.catalogId)
        assertEquals("violin", second.catalogId)
        assertNotEquals(first.name, second.name)
        assertEquals(first.midiProgram, second.midiProgram)
    }

    @Test
    fun msBasicMappingIsManyToMany() {
        val violinPresets = MsBasicCatalog.presetsFor("violin")
        assertTrue(violinPresets.size > 1)
        val ensembleUsers = MsBasicCatalog.instrumentsFor("string_ensemble_1").map { it.id }.toSet()
        assertTrue(setOf("violin", "viola", "cello", "double_bass").all { it in ensembleUsers })
        assertEquals(40, MsBasicCatalog.defaultProgramFor("violin"))
    }

    @Test
    fun instrumentCatalogueCoversTheFivePickerCategories() {
        assertEquals(InstrumentCategory.entries.toSet(), ScoreInstrumentCatalog.all.map { it.category }.toSet())
        assertTrue(ScoreInstrumentCatalog.all.all { it.nameKey.startsWith("dialog.new.instrument.") })
    }

    @Test
    fun bracketRangesCanNestButCannotCross() {
        val nested = listOf(
            EditableGroup(0, 7, com.mecon.api.storage.tracks.BracketStyle.SQUARE),
            EditableGroup(0, 3, com.mecon.api.storage.tracks.BracketStyle.SUB_BRACKET),
            EditableGroup(1, 2, com.mecon.api.storage.tracks.BracketStyle.SUB_BRACKET)
        )
        assertTrue(nested.areLaminar())
        assertFalse(listOf(
            EditableGroup(0, 3, com.mecon.api.storage.tracks.BracketStyle.SQUARE),
            EditableGroup(2, 5, com.mecon.api.storage.tracks.BracketStyle.SQUARE)
        ).areLaminar())
    }

    @Test
    fun orchestraPresetsUseSquareFamilyGroups() {
        listOf("classical_orchestra", "romantic_orchestra").forEach { id ->
            val preset = ScorePresetCatalog.all.first { it.id == id }
            val groups = preset.editableGroups()
            assertEquals(3, groups.size)
            assertTrue(groups.all { it.bracket == com.mecon.api.storage.tracks.BracketStyle.SQUARE })
            assertFalse(groups.any { it.startInstrument == 0 && it.endInstrument == preset.instruments.lastIndex })
        }
    }

    @Test
    fun romanticOrchestraUsesMultiStaffInstrumentsForDividedWinds() {
        val romantic = ScorePresetCatalog.all.first { it.id == "romantic_orchestra" }
        mapOf("flute" to 2, "oboe" to 2, "clarinet" to 2, "bassoon" to 2, "horn" to 4).forEach { (id, staves) ->
            val matches = romantic.instruments.filter { it.catalogId == id }
            assertEquals(1, matches.size, "$id should be one instrument")
            assertEquals(staves, matches.single().staves.size)
        }
    }
}
