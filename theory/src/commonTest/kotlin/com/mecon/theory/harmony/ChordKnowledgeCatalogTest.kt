package com.mecon.theory.harmony

import com.mecon.theory.KeySignatureMode
import com.mecon.theory.Mode
import com.mecon.theory.ModulationKey
import com.mecon.theory.schoenberg.SchoenbergDiminishedSeventhChapter
import com.mecon.theory.schoenberg.SchoenbergHarmonicTreatments
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class ChordKnowledgeCatalogTest {
    private val key = ModulationKey(0, KeySignatureMode.MAJOR)
    private val context = ChordKnowledgeContext(key.tonalContext("chord-selection"))

    @Test
    fun discoveredCatalogResolvesNaturalAndDiminishedInterpretations() {
        val selectionGroups = ChordSelectionCatalog.groups(key)
        val natural = selectionGroups
            .single { it.category.id == "diatonic-triads" }
            .chords
            .first { it.functionalSymbol == "I" }
        val diminished = selectionGroups
            .single { it.category.id == "rootless-dominant-ninth" }
            .chords
            .first()
        val snapshot = snapshot()

        val naturalDetail = snapshot.resolve(
            SoundingInterpretationQuery(natural.audibleKey, natural.origin),
            context,
        )
        assertTrue(naturalDetail.definitions.isNotEmpty())
        assertTrue(
            natural.interpretationRefs.single() in
                naturalDetail.definitions.first().interpretationRefs
        )

        val diminishedDetail = snapshot.resolve(
            SoundingInterpretationQuery(diminished.audibleKey, diminished.origin),
            context,
        )
        val diminishedExplanation = diminishedDetail.explanations.single {
            it.id.value.startsWith("schoenberg.diminished-seventh.")
        }
        assertEquals(
            diminished.interpretationRefs.toSet(),
            diminishedExplanation.interpretationRefs.toSet(),
        )
        assertTrue(diminishedExplanation.routes.size > 1)
        assertTrue(diminishedDetail.definitions.all { it.routes.isNotEmpty() })
        assertTrue(diminishedDetail.definitions.all { it.sourceRefs.isNotEmpty() })
    }

    @Test
    fun diminishedRoutesExposeValidatedDominantSubstitutionAndDynamicFormula() {
        val snapshot = snapshot()
        val choice = snapshot.selectionGroups
            .single { it.category.id == "rootless-dominant-ninth" }
            .chords.first { chord ->
                chord.interpretationRefs.any { it.interpretationId.value.contains(".as-dominant.1.") }
            }
        val routes = snapshot.resolve(
            SoundingInterpretationQuery(choice.audibleKey, choice.origin),
            context,
        ).explanations.single { it.id.value.startsWith("schoenberg.diminished-seventh.") }.routes

        val primary = routes.single { route ->
            route.functionRelations
                .filterIsInstance<ChordFunctionRelation.SubstitutesFor>()
                .single().tonicizedDegree == 1
        }
        val relation = primary.functionRelations
            .filterIsInstance<ChordFunctionRelation.SubstitutesFor>().single()
        assertEquals(SchoenbergHarmonicTreatments.DIATONIC_DOMINANT, relation.targetTreatmentId)
        assertEquals(HarmonicFunction.DOMINANT, relation.function)
        val construction = primary.construction as ChordConstructionDetail.OmittedFromFormula
        assertEquals(ChordConstructionBases.DOMINANT_NINTH, construction.basis.definition)
        assertEquals("V9", construction.basis.symbol)
        assertEquals(listOf(5, 7, 2, 4, 6), construction.tones.map { it.degree })
        assertEquals(listOf(0, 0, 0, 0, -1), construction.tones.map { it.alteration })
        assertEquals(ConstructionTonePresence.OMITTED, construction.tones.first().presence)
        assertTrue(construction.tones.drop(1).all { it.presence == ConstructionTonePresence.SOUNDING })

        val secondaryChoice = snapshot.selectionGroups
            .single { it.category.id == "rootless-dominant-ninth" }
            .chords.first { chord ->
                chord.interpretationRefs.any { it.interpretationId.value.contains(".as-dominant.5.") }
            }
        val secondary = snapshot.resolve(
            SoundingInterpretationQuery(secondaryChoice.audibleKey, secondaryChoice.origin),
            context,
        ).explanations.single { it.id.value.startsWith("schoenberg.diminished-seventh.") }.routes.single { route ->
            route.functionRelations
                .filterIsInstance<ChordFunctionRelation.SubstitutesFor>()
                .single().tonicizedDegree == 5
        }
        val secondaryConstruction = secondary.construction as ChordConstructionDetail.OmittedFromFormula
        assertEquals("V9/V", secondaryConstruction.basis.symbol)
        assertEquals(listOf(2, 4, 6, 1, 3), secondaryConstruction.tones.map { it.degree })
        assertEquals(listOf(0, 1, 0, 0, -1), secondaryConstruction.tones.map { it.alteration })
    }

    @Test
    fun neapolitanAloneDeclaresPredominantSubstitution() {
        val snapshot = snapshot()
        val neapolitans = snapshot.selectionGroups.single { it.category.id == "neapolitan" }.chords
        val neapolitan = neapolitans.single { chord ->
            chord.interpretationRefs.any { it.interpretationId.value.endsWith("major.triad") }
        }
        neapolitans.forEach { chord ->
            val chordDetail = snapshot.resolve(
                SoundingInterpretationQuery(chord.audibleKey, chord.origin),
                context,
            )
            val chordExplanation = chordDetail.explanations.single { it.id.value == "schoenberg.neapolitan" }
            val chordRelation = chordExplanation.routes.single().functionRelations
                .filterIsInstance<ChordFunctionRelation.SubstitutesFor>().single()
            assertEquals(SchoenbergHarmonicTreatments.DIATONIC_PREDOMINANT, chordRelation.targetTreatmentId)
            assertEquals(HarmonicFunction.PREDOMINANT, chordRelation.function)
        }
        val detail = snapshot.resolve(
            SoundingInterpretationQuery(neapolitan.audibleKey, neapolitan.origin),
            context,
        )
        val explanation = detail.explanations.single { it.id.value == "schoenberg.neapolitan" }
        val relation = explanation.routes.single().functionRelations
            .filterIsInstance<ChordFunctionRelation.SubstitutesFor>().single()
        assertEquals(SchoenbergHarmonicTreatments.DIATONIC_PREDOMINANT, relation.targetTreatmentId)
        assertEquals(HarmonicFunction.PREDOMINANT, relation.function)
        assertTrue(
            SchoenbergHarmonicTreatments.registry.resolve(setOf(SchoenbergHarmonicTreatments.NEAPOLITAN))
                .substitutionTargets.contains(SchoenbergHarmonicTreatments.DIATONIC_PREDOMINANT)
        )
        val neapolitanConstruction = explanation.routes.single().construction as
            ChordConstructionDetail.MinorSubdominantRelation
        assertEquals(Mode.AEOLIAN, neapolitanConstruction.sourceMode)
        assertEquals(-4, neapolitanConstruction.sourceKeySignatureFifths)
        assertEquals(HarmonicFunction.TONIC, neapolitanConstruction.referenceFunction)
        assertEquals(listOf("C", "E", "G"), neapolitanConstruction.referenceTones.map { it.spelling.displayName() })
        assertEquals(listOf("D♭", "F", "A♭"), neapolitanConstruction.borrowedTones.map { it.spelling.displayName() })

        val genericBorrowing = snapshot.selectionGroups
            .single { it.category.id == "minor-subdominant-related" }
            .chords.first { chord ->
            chord.interpretationRefs.any { it.interpretationId.value == "minor-subdominant.4.0.minor.triad" }
        }
        val genericDetail = snapshot.resolve(
            SoundingInterpretationQuery(genericBorrowing.audibleKey, genericBorrowing.origin),
            context,
        )
        assertTrue(genericDetail.explanations.none { explanationItem ->
            explanationItem.routes.any { it.functionRelations.isNotEmpty() }
        })
        val genericRoutes = genericDetail.explanations
            .single { it.id.value == "schoenberg.minor-subdominant.4.0.minor.triad" }
            .routes
        assertEquals(2, genericRoutes.size)
        assertEquals(
            setOf(-3, -4),
            genericRoutes.map {
                (it.construction as ChordConstructionDetail.MinorSubdominantRelation)
                    .sourceKeySignatureFifths
            }.toSet(),
        )
        val constructionsBySource = genericRoutes.associate { route ->
            val construction = route.construction as ChordConstructionDetail.MinorSubdominantRelation
            construction.sourceKeySignatureFifths to construction
        }
        assertEquals(HarmonicFunction.DOMINANT, constructionsBySource.getValue(-3).referenceFunction)
        assertEquals(
            listOf("G", "B", "D"),
            constructionsBySource.getValue(-3).referenceTones.map { it.spelling.displayName() },
        )
        assertEquals(HarmonicFunction.TONIC, constructionsBySource.getValue(-4).referenceFunction)
        assertEquals(
            listOf("C", "E", "G"),
            constructionsBySource.getValue(-4).referenceTones.map { it.spelling.displayName() },
        )
    }

    @Test
    fun secondaryHarmonyExposesModalScaleConstruction() {
        val snapshot = snapshot()
        val choice = snapshot.selectionGroups
            .first { it.category.id == "secondary-dominants" }
            .chords.first { it.functionalSymbol == "V/V" }
        val detail = snapshot.resolve(
            SoundingInterpretationQuery(choice.audibleKey, choice.origin),
            context,
        )

        val explanation = detail.explanations.single { item ->
            choice.interpretationRefs.single() in item.interpretationRefs
        }
        val construction = explanation.routes.single().construction as
            ChordConstructionDetail.ModalScaleDegrees
        assertEquals(Mode.MIXOLYDIAN, construction.mode)
        assertEquals(5, construction.tonicizedDegree)
        assertEquals(7, construction.degrees.size)
        assertEquals(
            choice.pitchClasses,
            construction.degrees.filter { it.chordTone }
                .mapTo(linkedSetOf()) { it.spelling.pitchClass.value },
        )
        assertTrue(detail.missingKnowledgeRefs.isEmpty())
    }

    @Test
    fun commonCatalogAcceptsInjectedProvidersWithoutDiscovery() {
        val diminished = ChordSelectionCatalog.groups(
            key,
            providers = listOf(SchoenbergDiminishedSeventhChapter),
        ).single().chords.first()
        val snapshot = ChordCatalogSnapshot.create(
            key,
            selectionProviders = listOf(SchoenbergDiminishedSeventhChapter),
            knowledgeProviders = listOf(SchoenbergDiminishedSeventhChapter),
            treatmentRegistry = SchoenbergHarmonicTreatments.registry,
        )

        val detail = snapshot.resolve(
            SoundingInterpretationQuery(diminished.audibleKey, diminished.origin),
            context,
        )
        assertEquals(1, detail.explanations.size)
    }

    @Test
    fun duplicateContributionIdsAreRejected() {
        assertFailsWith<IllegalArgumentException> {
            ChordKnowledgeCatalog.create(
                context,
                providers = listOf(
                    SchoenbergDiminishedSeventhChapter,
                    SchoenbergDiminishedSeventhChapter,
                ),
            )
        }
    }

    private fun snapshot(): ChordCatalogSnapshot = ChordCatalogSnapshot.create(
        key,
        treatmentRegistry = SchoenbergHarmonicTreatments.registry,
    )

    @Test
    fun chapterCannotDescribeAnInterpretationOutsideItsVocabulary() {
        val base = SchoenbergDiminishedSeventhChapter.chordKnowledgeContributions.single()
        val missing = ChordInterpretationRef(
            SonorityId("sonority.outside"),
            InterpretationId("interpretation.outside"),
        )
        val provider = object : ChordKnowledgeChapterProvider {
            override val chordKnowledgeContributions = listOf(
                base.copy(
                    id = ChordKnowledgeContributionId("test.dangling"),
                    details = { _, catalog ->
                        val toneId = catalog.entries.first().sonority.tones.first().id
                        listOf(
                            ChordDetailDefinition(
                                interpretationRef = missing,
                                summary = ChordSummary("test.name"),
                                structure = ChordStructureDetail(listOf(toneId)),
                                function = ChordFunctionDetail(HarmonicFunction.OTHER),
                                voiceLeading = ChordVoiceLeadingDetail(),
                                routes = listOf(
                                    ConstructionRoute(
                                        id = ConstructionRouteId("test.route"),
                                        interpretationRef = missing,
                                        formulaKey = "test.formula",
                                        steps = listOf(ConstructionOperation.LegacyTrace("test")),
                                        sourceRefs = listOf(
                                            TheorySourceRef("test", "edition", "topic")
                                        ),
                                    )
                                ),
                                sourceRefs = listOf(
                                    TheorySourceRef("test", "edition", "topic")
                                ),
                            )
                        )
                    },
                )
            )
        }

        assertFailsWith<IllegalArgumentException> {
            ChordKnowledgeCatalog.create(context, listOf(provider))
        }
    }
}
