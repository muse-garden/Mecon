package com.mecon.features.freepractice

import com.mecon.api.primitive.NoteName
import com.mecon.theory.KeySignatureMode
import com.mecon.theory.ModulationKey
import com.mecon.theory.harmony.ChordCatalogSnapshot
import com.mecon.theory.harmony.ChordDetailModel
import com.mecon.theory.harmony.ChordKnowledgeContext
import com.mecon.theory.harmony.ChordSelectionCatalog
import com.mecon.theory.harmony.SoundingInterpretationQuery
import com.mecon.theory.schoenberg.SchoenbergAugmentedSixthChapter
import com.mecon.theory.schoenberg.SchoenbergHarmonicTreatments
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Formatting contract of the shared chord-detail read model.
 *
 * These scenarios were originally written against the desktop-only `ChordDetailUiMapper` overload
 * that took a raw `ChordDetailModel`. Once both the committed selection and the desktop pre-commit
 * preview started consuming [PracticeChordDetailProjector], that overload had no production caller
 * left, so its coverage moved here — where it also runs on Kotlin/JS and therefore guards the
 * formatting both platforms actually see.
 */
class PracticeChordDetailProjectorTest {
    private val key = ModulationKey(0, KeySignatureMode.MAJOR)
    private val context = ChordKnowledgeContext(key.tonalContext("chord-selection"))

    private fun snapshot() = ChordCatalogSnapshot.create(
        key,
        treatmentRegistry = SchoenbergHarmonicTreatments.registry,
    )

    private fun detail(
        choice: com.mecon.theory.harmony.ChordSelectionChoice,
        snapshot: ChordCatalogSnapshot = snapshot(),
    ): ChordDetailModel = snapshot.resolve(
        SoundingInterpretationQuery(choice.audibleKey, choice.origin),
        context,
    )

    @Test
    fun mapsMultiRouteKnowledgeAndSourcesWithoutChapterSpecificUiLogic() {
        val choice = ChordSelectionCatalog.groups(key)
            .single { it.category.id == "rootless-dominant-ninth" }
            .chords
            .first { it.interpretationRefs.size > 1 }

        val view = PracticeChordDetailProjector.map(detail(choice)) { it }

        assertEquals(1, view.explanations.size)
        val explanation = view.explanations.single()
        assertEquals(choice.interpretationRefs.size, explanation.routes.size)
        assertTrue(explanation.commonSections.isNotEmpty())
        assertTrue(explanation.routes.all { it.sections.isNotEmpty() })
        assertTrue(explanation.sources.isNotEmpty())
        // Every route must be dispatchable, not merely displayable — see PracticeChordDetailRouteView.
        assertTrue(explanation.routes.all { it.interpretationRef != null })
        assertEquals(
            choice.interpretationRefs.toSet(),
            explanation.routes.mapNotNull { it.interpretationRef }.toSet(),
        )
    }

    @Test
    fun mapsTypedConstructionToOneSentenceAndOneNotationModel() {
        val localize: (String) -> String = { keyValue ->
            when (keyValue) {
                "exploration.chordDetail.construction.omittedFromFormula" ->
                    "{basis}{symbol} ({formula}) 省略根音 ({root})"
                "exploration.chordDetail.dominantNinth.primaryName" -> "属九和弦"
                "exploration.chordDetail.dominantNinth.secondaryName" -> "{degree}级副属九和弦"
                "exploration.chordDetail.degree.5" -> "五"
                "exploration.chordDetail.degree.7" -> "七"
                "exploration.chordDetail.route.primaryBadge" -> "本调"
                "exploration.chordDetail.route.secondaryBadge" -> "副属"
                "exploration.chordDetail.route.primarySubtitle" -> "当前调性的属功能构造"
                "exploration.chordDetail.route.secondarySubtitle" -> "临时指向{degree}级的属功能构造"
                "exploration.chordDetail.substitution.dominant" -> "替代属和弦功能。"
                "exploration.chordDetail.substitution.secondaryDominant" ->
                    "替代指向第 {degree} 级的副属和弦功能。"
                else -> keyValue
            }
        }
        val primaryChoice = ChordSelectionCatalog.groups(key)
            .single { it.category.id == "rootless-dominant-ninth" }
            .chords.first { chord ->
                chord.interpretationRefs.any { it.interpretationId.value.contains(".as-dominant.1.") }
            }

        val view = PracticeChordDetailProjector.map(detail(primaryChoice), localize)
        val route = view.explanations
            .single { it.id.startsWith("schoenberg.diminished-seventh.") }
            .routes.single { candidate ->
                candidate.sections.any { "替代属和弦功能。" in it.lines }
            }
        val construction = assertNotNull(route.construction)
        assertEquals("属九和弦V9 (5-7-2-4-b6) 省略根音 (5)", route.title)
        assertEquals("本调", route.badge)
        assertEquals("当前调性的属功能构造", route.subtitle)
        assertEquals(route.title, construction.description)
        val tones = construction.events.single().tones
        assertEquals(5, tones.size)
        assertTrue(tones.first().muted)
        assertEquals(4, tones.first().pitch.octave)
        assertTrue(tones.drop(1).none { it.muted })
        assertTrue(tones.zipWithNext().all { (left, right) -> left.pitch < right.pitch })

        val secondaryChoice = ChordSelectionCatalog.groups(key)
            .single { it.category.id == "rootless-dominant-ninth" }
            .chords.first { chord ->
                chord.interpretationRefs.any { it.interpretationId.value.contains(".as-dominant.5.") }
            }
        val secondary = PracticeChordDetailProjector.map(detail(secondaryChoice), localize)
            .explanations
            .single { it.id.startsWith("schoenberg.diminished-seventh.") }
            .routes.single { it.title.startsWith("五级副属九和弦") }
        val secondaryConstruction = assertNotNull(secondary.construction)
        assertNotEquals(route.title, secondary.title)
        assertEquals("五级副属九和弦V9/V (2-#4-6-1-b3) 省略根音 (2)", secondary.title)
        assertEquals("副属", secondary.badge)
        assertEquals("临时指向五级的属功能构造", secondary.subtitle)
        assertEquals(secondary.title, secondaryConstruction.description)
        assertEquals(4, secondaryConstruction.events.single().tones.first().pitch.octave)
    }

    @Test
    fun mapsModalScaleAndMinorSubdominantRelationWithoutHostBranches() {
        val snapshot = snapshot()
        val groups = ChordSelectionCatalog.groups(key)
        val secondary = groups.single { it.category.id == "secondary-dominants" }
            .chords.single { it.functionalSymbol == "V/V" }
        val modalConstruction = assertNotNull(
            PracticeChordDetailProjector.map(detail(secondary, snapshot)) { it }
                .explanations
                .single { secondary.interpretationRefs.single().interpretationId.value in it.id }
                .routes.single().construction
        )
        assertEquals(7, modalConstruction.events.size)
        assertTrue(modalConstruction.events.all { it.tones.size == 1 })
        assertEquals(3, modalConstruction.events.count { event -> event.tones.single().muted.not() })
        assertEquals(0, modalConstruction.keySignatureFifths)

        val neapolitan = groups.single { it.category.id == "neapolitan" }.chords.single { chord ->
            chord.interpretationRefs.any { it.interpretationId.value.endsWith("major.triad") }
        }
        val relation = assertNotNull(
            PracticeChordDetailProjector.map(detail(neapolitan, snapshot)) { it }
                .explanations.single { it.id == "schoenberg.neapolitan" }
                .routes.single().construction
        )
        assertEquals(-4, relation.keySignatureFifths)
        assertEquals(2, relation.events.size)
        assertTrue(relation.events.first().tones.all { it.muted })
        assertTrue(relation.events.last().tones.none { it.muted })
        assertEquals(listOf(0, 2, 4), relation.events.first().tones.map { it.pitch.diatonicSteps })
        assertEquals(listOf(1, 3, 5), relation.events.last().tones.map { it.pitch.diatonicSteps })
        val allPitches = relation.events.flatMap { it.tones }.map { it.pitch.diatonicSteps }
        assertTrue(allPitches.all { it in 0..11 })
        assertTrue(allPitches.max() - allPitches.min() <= 5)
    }

    @Test
    fun mapsAugmentedSixthConstructionToScoreEventsWithoutFamilySpecificHostLogic() {
        val snapshot = ChordCatalogSnapshot.create(
            key,
            selectionProviders = listOf(SchoenbergAugmentedSixthChapter),
            knowledgeProviders = listOf(SchoenbergAugmentedSixthChapter),
            treatmentRegistry = SchoenbergHarmonicTreatments.registry,
        )
        val choices = ChordSelectionCatalog.groups(
            key,
            providers = listOf(SchoenbergAugmentedSixthChapter),
        ).flatMap { it.chords }

        val localize: (String) -> String = { value ->
            when (value) {
                "exploration.chordDetail.construction.augmentedSixth" ->
                    "{kind}：{descending} 与 {ascending} 构成增六，并共同解决到 {resolution}"
                "exploration.chordDetail.construction.augmentedSixth.german" -> "德国增六"
                "exploration.chordDetail.construction.augmentedSixth.rootlessProcess" ->
                    "{basis}{basisSymbol} ({basisFormula}) 省略根音 ({root})，得到{intermediateName} " +
                        "({intermediateFormula})；{alteration}，得到 {resultSymbol} ({resultFormula})"
                "exploration.chordDetail.dominantNinth.secondaryName" -> "{degree}级副属九和弦"
                "exploration.chordDetail.degree.5" -> "五"
                "exploration.chordDetail.degree.7" -> "七"
                "exploration.chordDetail.construction.augmentedSixth.diminishedSeventh" -> "减七和弦"
                "exploration.chordDetail.construction.augmentedSixth.alteration.german" -> "三音再下行半音"
                else -> value
            }
        }

        fun construction(symbol: String): Pair<String, PracticeChordDetailConstructionView> {
            val choice = choices.single { it.functionalSymbol == symbol }
            val route = PracticeChordDetailProjector.map(detail(choice, snapshot), localize)
                .explanations.single().routes.single()
            return route.title to assertNotNull(route.construction)
        }

        val (germanTitle, german) = construction("Ger+6")
        assertEquals("德国增六：A♭ 与 F♯ 构成增六，并共同解决到 G", germanTitle)
        assertTrue(german.showDescription)
        assertEquals(
            "五级副属九和弦V9/V (2-#4-6-1-b3) 省略根音 (2)，" +
                "得到减七和弦 (#4-6-1-b3)；三音再下行半音，" +
                "得到 Ger+6 (#4-b6-1-b3)",
            german.description,
        )
        assertEquals(3, german.events.size)
        assertEquals(5, german.events[0].tones.size)
        assertEquals(4, german.events[1].tones.size)
        assertEquals(2, german.events[2].tones.size)
        assertTrue(german.events[0].tones.first().muted)
        assertTrue(german.events[0].tones.drop(1).none { it.muted })
        assertTrue(german.events.drop(1).flatMap { it.tones }.none { it.muted })
        assertTrue(
            german.events[0].tones.first().pitch <
                german.events[0].tones.drop(1).minOf { it.pitch }
        )
        assertEquals(
            german.events[0].tones.drop(1).map { it.pitch.diatonicSteps },
            german.events[1].tones.map { it.pitch.diatonicSteps },
        )
        assertEquals(
            german.events[1].tones.first().pitch.midiNumber - 1,
            german.events[2].tones.first().pitch.midiNumber,
        )
        assertEquals(
            german.events[1].tones.last().pitch.midiNumber + 1,
            german.events[2].tones.last().pitch.midiNumber,
        )
        assertEquals(
            12,
            german.events[2].tones[1].pitch.midiNumber - german.events[2].tones[0].pitch.midiNumber,
        )

        val italian = construction("It+6").second
        assertEquals(listOf(4, 3, 2), italian.events.map { it.tones.size })
        assertTrue(italian.events.first().tones.first().muted)

        val french = construction("Fr+6").second
        assertEquals(listOf(4, 4, 2), french.events.map { it.tones.size })
        assertTrue(french.events.flatMap { it.tones }.none { it.muted })
        assertEquals(
            french.events[0].tones.map { it.pitch.diatonicSteps },
            french.events[1].tones.map { it.pitch.diatonicSteps },
        )

        val halfDiminished = construction("ø+6").second
        assertEquals(2, halfDiminished.events.size)
        assertEquals(4, halfDiminished.events[0].tones.size)
        assertEquals(2, halfDiminished.events[1].tones.size)

        val germanLeading = construction("Ger+6/VII").second
        assertEquals(
            "七级副属九和弦V9/VII (#4-#6-#1-3-5) 省略根音 (#4)，" +
                "得到减七和弦 (#6-#1-3-5)；三音再下行半音，" +
                "得到 Ger+6/VII (#6-1-3-5)",
            germanLeading.description,
        )
        listOf("It+6/VII", "Ger+6/VII").forEach { symbol ->
            val preview = construction(symbol).second
            val sourceC = preview.events[0].tones.single { it.pitch.noteName == NoteName.C }
            val resultC = preview.events[1].tones.single { it.pitch.noteName == NoteName.C }
            assertEquals(1, sourceC.pitch.chromaticOffset, symbol)
            assertEquals(0, resultC.pitch.chromaticOffset, symbol)
        }

        val rootlessAugmentedSixths = choices.filter {
            it.functionalSymbol.startsWith("It+6") || it.functionalSymbol.startsWith("Ger+6")
        }
        assertTrue(
            rootlessAugmentedSixths.map { it.functionalSymbol }.containsAll(
                setOf("It+6/I", "It+6/VII", "Ger+6/VII")
            )
        )
        rootlessAugmentedSixths.forEach { choice ->
            val preview = assertNotNull(
                PracticeChordDetailProjector.map(detail(choice, snapshot), localize)
                    .explanations.single().routes.single().construction
            )
            assertEquals(3, preview.events.size, choice.functionalSymbol)
            val source = preview.events[0].tones
            val augmentedSixth = preview.events[1].tones
            val resolution = preview.events[2].tones
            assertTrue(source.first().muted, choice.functionalSymbol)
            assertTrue(
                source.first().pitch < source.drop(1).minOf { it.pitch },
                choice.functionalSymbol,
            )
            assertEquals(
                source.drop(1).map { it.pitch.diatonicSteps },
                augmentedSixth.map { it.pitch.diatonicSteps },
                choice.functionalSymbol,
            )
            assertEquals(
                augmentedSixth.first().pitch.midiNumber - 1,
                resolution.first().pitch.midiNumber,
                choice.functionalSymbol,
            )
            assertEquals(
                augmentedSixth.last().pitch.midiNumber + 1,
                resolution.last().pitch.midiNumber,
                choice.functionalSymbol,
            )
            assertEquals(
                12,
                resolution.last().pitch.midiNumber - resolution.first().pitch.midiNumber,
                choice.functionalSymbol,
            )
        }
    }

    @Test
    fun mapsMissingKnowledgeToFallback() {
        val view = PracticeChordDetailProjector.map(ChordDetailModel(definitions = emptyList())) { it }

        assertNotNull(view.missingKnowledgeMessage)
        assertTrue(view.routes.isEmpty())
    }
}
