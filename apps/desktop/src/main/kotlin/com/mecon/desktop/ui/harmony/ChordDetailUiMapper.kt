package com.mecon.desktop.ui.harmony

import com.mecon.api.primitive.Pitch
import com.mecon.desktop.uikit.components.ChordDetailSeverity
import com.mecon.desktop.uikit.components.ChordDetailUiConstruction
import com.mecon.desktop.uikit.components.ChordDetailUiConstructionEvent
import com.mecon.desktop.uikit.components.ChordDetailUiConstructionTone
import com.mecon.desktop.uikit.components.ChordDetailUiModel
import com.mecon.desktop.uikit.components.ChordDetailUiExplanation
import com.mecon.desktop.uikit.components.ChordDetailUiRoute
import com.mecon.desktop.uikit.components.ChordDetailUiSection
import com.mecon.desktop.uikit.components.ChordDetailUiSource
import com.mecon.theory.harmony.ChordDetailModel
import com.mecon.theory.harmony.ChordConstructionDetail
import com.mecon.theory.harmony.ChordConstructionTone
import com.mecon.theory.harmony.ChordFunctionRelation
import com.mecon.theory.harmony.ConstructionTonePresence
import com.mecon.theory.harmony.HarmonicFunction
import com.mecon.theory.harmony.ModalScalePath
import com.mecon.theory.harmony.AugmentedSixthConstructionOrigin
import com.mecon.theory.harmony.ChordConstructionBasisRef
import com.mecon.theory.harmony.TheoryClaimKind
import com.mecon.theory.harmony.TheorySourceRef
import com.mecon.theory.SpelledPitchClass

internal object ChordDetailUiMapper {
    fun map(view: com.mecon.features.freepractice.PracticeChordDetailView): ChordDetailUiModel =
        ChordDetailUiModel(
            title = view.title,
            subtitle = view.subtitle,
            badges = view.badges,
            commonSections = view.commonSections.map { it.toDesktopUi() },
            routes = view.routes.map { it.toDesktopUi() },
            sources = view.sources.map { it.toDesktopUi() },
            missingKnowledgeMessage = view.missingKnowledgeMessage,
            explanations = view.explanations.map { explanation ->
                ChordDetailUiExplanation(
                    id = explanation.id,
                    title = explanation.title,
                    subtitle = explanation.subtitle,
                    badges = explanation.badges,
                    commonSections = explanation.commonSections.map { it.toDesktopUi() },
                    routes = explanation.routes.map { it.toDesktopUi() },
                    sources = explanation.sources.map { it.toDesktopUi() },
                )
            },
        )

    private fun com.mecon.features.freepractice.PracticeChordDetailSectionView.toDesktopUi() =
        ChordDetailUiSection(
            title = title,
            lines = lines,
            severity = when (severity) {
                com.mecon.features.freepractice.PracticeChordDetailSeverity.INFO ->
                    ChordDetailSeverity.INFO
                com.mecon.features.freepractice.PracticeChordDetailSeverity.RECOMMENDATION ->
                    ChordDetailSeverity.RECOMMENDATION
                com.mecon.features.freepractice.PracticeChordDetailSeverity.REQUIREMENT ->
                    ChordDetailSeverity.REQUIREMENT
            },
        )

    private fun com.mecon.features.freepractice.PracticeChordDetailSourceView.toDesktopUi() =
        ChordDetailUiSource(label, detail)

    private fun com.mecon.features.freepractice.PracticeChordDetailRouteView.toDesktopUi() =
        ChordDetailUiRoute(
            id = id,
            title = title,
            subtitle = subtitle,
            badge = badge,
            sections = sections.map { it.toDesktopUi() },
            construction = construction?.let { construction ->
                ChordDetailUiConstruction(
                    description = construction.description,
                    events = construction.events.map { event ->
                        ChordDetailUiConstructionEvent(
                            event.tones.map { tone ->
                                ChordDetailUiConstructionTone(tone.pitch, tone.muted)
                            }
                        )
                    },
                    keySignatureFifths = construction.keySignatureFifths,
                    caption = construction.caption,
                    showDescription = construction.showDescription,
                )
            },
            sources = sources.map { it.toDesktopUi() },
        )

    fun map(
        model: ChordDetailModel,
        localize: (String) -> String,
    ): ChordDetailUiModel {
        fun text(key: String): String =
            localize(if (key.startsWith("exploration.")) key else "exploration.$key")
        val first = model.definitions.firstOrNull()
        if (first == null) {
            return ChordDetailUiModel(
                title = localize("exploration.chordDetail.unavailableTitle"),
                missingKnowledgeMessage = localize("exploration.chordDetail.unavailable"),
            )
        }
        return ChordDetailUiModel(
            title = text(first.summary.nameKey),
            explanations = model.explanations.map { definition ->
                ChordDetailUiExplanation(
                    id = definition.id.value,
                    title = text(definition.summary.nameKey),
                    subtitle = definition.summary.descriptionKey?.let(::text),
                    badges = definition.summary.tags.map(::text),
                    commonSections = listOf(
                        definition.function.descriptionKey?.let { descriptionKey ->
                            ChordDetailUiSection(
                                title = localize("exploration.chordDetail.function"),
                                lines = listOf(text(descriptionKey)),
                            )
                        },
                        ChordDetailUiSection(
                            title = localize("exploration.chordDetail.structure"),
                            lines = definition.structure.propertyKeys.map(::text),
                        )
                    ).filterNotNull(),
                    routes = definition.routes.map { route ->
                        val construction = route.construction?.toUi(localize, ::text)
                        val basis = (route.construction as? ChordConstructionDetail.OmittedFromFormula)?.basis
                        val augmentedSixth = route.construction as?
                            ChordConstructionDetail.AugmentedSixthDerivation
                        ChordDetailUiRoute(
                            id = route.id.value,
                            title = augmentedSixth?.routeTitle(localize)
                                ?: construction?.description
                                ?: text(route.formulaKey),
                            subtitle = basis?.let {
                                val key = if (it.tonicizedDegree == 1) {
                                    "exploration.chordDetail.route.primarySubtitle"
                                } else {
                                    "exploration.chordDetail.route.secondarySubtitle"
                                }
                                localize(key).replace(
                                    "{degree}",
                                    localize("exploration.chordDetail.degree.${it.tonicizedDegree}"),
                                )
                            },
                            badge = basis?.let {
                                localize(
                                    if (it.tonicizedDegree == 1) {
                                        "exploration.chordDetail.route.primaryBadge"
                                    } else {
                                        "exploration.chordDetail.route.secondaryBadge"
                                    }
                                )
                            },
                            sections = buildList {
                                if (route.functionRelations.isNotEmpty()) {
                                    add(
                                        ChordDetailUiSection(
                                            title = localize("exploration.chordDetail.functionSubstitution"),
                                            lines = route.functionRelations.map { it.label(localize) },
                                        )
                                    )
                                }
                                if (route.tendencyTones.isNotEmpty()) {
                                    add(
                                        ChordDetailUiSection(
                                            title = localize("exploration.chordDetail.tendency"),
                                            lines = route.tendencyTones.map { text(it.descriptionKey) },
                                            severity = ChordDetailSeverity.REQUIREMENT,
                                        )
                                    )
                                }
                            },
                            construction = construction,
                            sources = route.sourceRefs.map { it.toUi(localize) },
                        )
                    },
                    sources = definition.sourceRefs.map { it.toUi(localize) },
                )
            },
            missingKnowledgeMessage = model.missingKnowledgeRefs
                .takeIf { it.isNotEmpty() }
                ?.let { localize("exploration.chordDetail.partial") },
        )
    }

    private fun ChordFunctionRelation.label(localize: (String) -> String): String = when (this) {
        is ChordFunctionRelation.SubstitutesFor -> {
            val key = when {
                function == HarmonicFunction.PREDOMINANT ->
                    "exploration.chordDetail.substitution.predominant"
                tonicizedDegree == null || tonicizedDegree == 1 ->
                    "exploration.chordDetail.substitution.dominant"
                else -> "exploration.chordDetail.substitution.secondaryDominant"
            }
            localize(key).replace("{degree}", tonicizedDegree?.toString().orEmpty())
        }
    }

    private fun ChordConstructionDetail.toUi(
        localize: (String) -> String,
        text: (String) -> String,
    ): ChordDetailUiConstruction = when (this) {
        is ChordConstructionDetail.OmittedFromFormula -> {
            val omitted = tones.single { it.presence == ConstructionTonePresence.OMITTED }
            val formula = tones.joinToString("-") { it.degreeLabel() }
            val basisName = if (basis.tonicizedDegree == 1) {
                text(basis.definition.primaryNameKey)
            } else {
                text(basis.definition.secondaryNameKey).replace(
                    "{degree}",
                    localize("exploration.chordDetail.degree.${basis.tonicizedDegree}"),
                )
            }
            val description = localize("exploration.chordDetail.construction.omittedFromFormula")
                .replace("{basis}", basisName)
                .replace("{symbol}", basis.symbol)
                .replace("{formula}", formula)
                .replace("{root}", omitted.degreeLabel())
            ChordDetailUiConstruction(
                description = description,
                events = listOf(
                    tones.toPreviewEvent { it.presence == ConstructionTonePresence.OMITTED }
                ),
                caption = localize("exploration.chordDetail.construction.structuralCaption"),
            )
        }
        is ChordConstructionDetail.ModalScaleDegrees -> {
            val modeName = localize("exploration.chordDetail.mode.${mode.name.lowercase()}")
            val pathName = localize(
                when (path) {
                    ModalScalePath.ASCENDING -> "exploration.chordDetail.construction.modalScale.ascending"
                    ModalScalePath.DESCENDING -> "exploration.chordDetail.construction.modalScale.descending"
                }
            )
            val description = localize("exploration.chordDetail.construction.modalScale")
                .replace("{mode}", modeName)
                .replace("{path}", pathName)
                .replace(
                    "{degree}",
                    localize("exploration.chordDetail.degree.$tonicizedDegree"),
                )
            val pitches = degrees.map { it.spelling }.toAscendingPreviewPitches()
            ChordDetailUiConstruction(
                description = description,
                events = degrees.zip(pitches) { degree, pitch ->
                    ChordDetailUiConstructionEvent(
                        tones = listOf(
                            ChordDetailUiConstructionTone(
                                pitch = pitch,
                                muted = !degree.chordTone,
                            )
                        )
                    )
                },
                keySignatureFifths = keySignatureFifths,
                caption = localize("exploration.chordDetail.construction.modalScale.caption"),
            )
        }
        is ChordConstructionDetail.MinorSubdominantRelation -> {
            val sourceName = sourceTonic.displayName()
            val referenceName = localize(
                when (referenceFunction) {
                    HarmonicFunction.TONIC ->
                        "exploration.chordDetail.construction.minorSubdominant.tonicReference"
                    HarmonicFunction.DOMINANT ->
                        "exploration.chordDetail.construction.minorSubdominant.dominantReference"
                    else -> error("Unsupported minor-subdominant reference function $referenceFunction")
                }
            )
            val description = localize("exploration.chordDetail.construction.minorSubdominant")
                .replace("{source}", sourceName)
                .replace("{reference}", referenceName)
            val (referencePitches, borrowedPitches) = compactPreviewPitches(
                referenceTones.map(ChordConstructionTone::spelling),
                borrowedTones.map(ChordConstructionTone::spelling),
            )
            ChordDetailUiConstruction(
                description = description,
                events = listOf(
                    referenceTones.toPreviewEvent(referencePitches) { true },
                    borrowedTones.toPreviewEvent(borrowedPitches) { false },
                ),
                keySignatureFifths = sourceKeySignatureFifths,
                caption = localize("exploration.chordDetail.construction.minorSubdominant.caption")
                    .replace("{reference}", referenceName),
            )
        }
        is ChordConstructionDetail.AugmentedSixthDerivation -> {
            val resultFormula = augmentedSixthTones.formula()
            val alteration = text(alterationDescriptionKey)
            val description = when (val constructionOrigin = origin) {
                is AugmentedSixthConstructionOrigin.RootlessAppliedChord -> {
                    val omitted = constructionOrigin.tones.single {
                        it.presence == ConstructionTonePresence.OMITTED
                    }
                    localize("exploration.chordDetail.construction.augmentedSixth.rootlessProcess")
                        .replace("{basis}", constructionOrigin.basis.localizedName(localize, text))
                        .replace("{basisSymbol}", constructionOrigin.basis.symbol)
                        .replace("{basisFormula}", constructionOrigin.tones.formula())
                        .replace("{root}", omitted.degreeLabel())
                        .replace("{intermediateName}", text(constructionOrigin.rootlessResultNameKey))
                        .replace(
                            "{intermediateFormula}",
                            constructionOrigin.tones
                                .filter { it.presence == ConstructionTonePresence.SOUNDING }
                                .formula(),
                        )
                        .replace("{alteration}", alteration)
                        .replace("{resultSymbol}", resultSymbol)
                        .replace("{resultFormula}", resultFormula)
                }
                is AugmentedSixthConstructionOrigin.NamedChord ->
                    localize("exploration.chordDetail.construction.augmentedSixth.namedProcess")
                        .replace("{originSymbol}", constructionOrigin.symbol)
                        .replace("{originFormula}", constructionOrigin.tones.formula())
                        .replace("{alteration}", alteration)
                        .replace("{resultSymbol}", resultSymbol)
                        .replace("{resultFormula}", resultFormula)
            }
            val descendingTone = augmentedSixthTones.single {
                it.spelling == descendingEndpoint
            }
            val ascendingTone = augmentedSixthTones.single {
                it.spelling == ascendingEndpoint
            }
            val resultPreviewTones = listOf(descendingTone) +
                augmentedSixthTones
                    .filter { it !== descendingTone && it !== ascendingTone }
                    .sortedBy {
                        (it.spelling.noteName.ordinal - descendingEndpoint.noteName.ordinal).mod(7)
                    } +
                ascendingTone
            val resultPreviewSpellings = resultPreviewTones.map(ChordConstructionTone::spelling)
            val alignedSourceTones = resultPreviewSpellings.map { resultSpelling ->
                origin.tones.single { sourceTone ->
                    sourceTone.presence == ConstructionTonePresence.SOUNDING &&
                        sourceTone.spelling.noteName == resultSpelling.noteName
                }
            }
            val sourceTones = when (val constructionOrigin = origin) {
                is AugmentedSixthConstructionOrigin.RootlessAppliedChord -> listOf(
                    constructionOrigin.tones.single {
                        it.presence == ConstructionTonePresence.OMITTED
                    }
                ) + alignedSourceTones
                is AugmentedSixthConstructionOrigin.NamedChord -> alignedSourceTones
            }
            val sourceSoundingSpellings = alignedSourceTones.map(ChordConstructionTone::spelling)
            val toneGroups = buildList {
                if (
                    sourceTones.any { it.presence == ConstructionTonePresence.OMITTED } ||
                    sourceSoundingSpellings != resultPreviewSpellings
                ) {
                    add(sourceTones)
                }
                add(resultPreviewTones)
                add(listOf(resolutionTone, resolutionTone))
            }
            val resultPitches = resultPreviewSpellings.toAscendingPreviewPitches()
            val alignedSourcePitches = alignedSourceTones.zip(resultPitches) { tone, resultPitch ->
                Pitch(resultPitch.diatonicSteps, tone.spelling.chromaticOffset)
            }
            val sourcePitches = when (val constructionOrigin = origin) {
                is AugmentedSixthConstructionOrigin.RootlessAppliedChord -> listOf(
                    constructionOrigin.tones.single {
                        it.presence == ConstructionTonePresence.OMITTED
                    }.spelling.closestPitchBelow(resultPitches.first())
                ) + alignedSourcePitches
                is AugmentedSixthConstructionOrigin.NamedChord -> alignedSourcePitches
            }
            val resolutionPitches = listOf(
                Pitch(
                    resultPitches.first().diatonicSteps - 1,
                    resolutionTone.spelling.chromaticOffset,
                ),
                Pitch(
                    resultPitches.last().diatonicSteps + 1,
                    resolutionTone.spelling.chromaticOffset,
                ),
            )
            val pitchGroups = buildList {
                if (toneGroups.size == 3) add(sourcePitches)
                add(resultPitches)
                add(resolutionPitches)
            }
            ChordDetailUiConstruction(
                description = description,
                events = toneGroups.zip(pitchGroups) { tones, pitches ->
                    tones.toPreviewEvent(pitches) {
                        it.presence == ConstructionTonePresence.OMITTED
                    }
                },
                caption = localize("exploration.chordDetail.construction.augmentedSixth.caption"),
                showDescription = true,
            )
        }
    }

    private fun ChordConstructionBasisRef.localizedName(
        localize: (String) -> String,
        text: (String) -> String,
    ): String = if (tonicizedDegree == 1) {
        text(definition.primaryNameKey)
    } else {
        text(definition.secondaryNameKey).replace(
            "{degree}",
            localize("exploration.chordDetail.degree.$tonicizedDegree"),
        )
    }

    private fun List<ChordConstructionTone>.formula(): String = joinToString("-") {
        it.degreeLabel()
    }

    private fun ChordConstructionDetail.AugmentedSixthDerivation.routeTitle(
        localize: (String) -> String,
    ): String {
        val kindName = localize(
            "exploration.chordDetail.construction.augmentedSixth.${kind.name.lowercase()}"
        )
        return localize("exploration.chordDetail.construction.augmentedSixth")
            .replace("{kind}", kindName)
            .replace("{descending}", descendingEndpoint.displayName())
            .replace("{ascending}", ascendingEndpoint.displayName())
            .replace("{resolution}", resolutionTone.spelling.displayName())
    }

    private fun ChordConstructionTone.degreeLabel(): String =
        when {
            alteration < 0 -> "b".repeat(-alteration) + degree
            alteration > 0 -> "#".repeat(alteration) + degree
            else -> degree.toString()
        }

    private fun List<ChordConstructionTone>.toPreviewEvent(
        pitches: List<Pitch> = map(ChordConstructionTone::spelling).toAscendingPreviewPitches(),
        muted: (ChordConstructionTone) -> Boolean,
    ): ChordDetailUiConstructionEvent {
        require(size == pitches.size)
        return ChordDetailUiConstructionEvent(
            tones = zip(pitches) { tone, pitch ->
                ChordDetailUiConstructionTone(
                    pitch = pitch,
                    muted = muted(tone),
                )
            }
        )
    }

    private fun List<SpelledPitchClass>.previewPitchCandidates(): List<List<Pitch>> =
        (2..5).map { startOctave ->
            var octave = startOctave
            var previousMidi = Int.MIN_VALUE
            map { spelling ->
                var pitch = spelling.pitchAt(octave)
                while (pitch.midiNumber <= previousMidi) {
                    octave += 1
                    pitch = spelling.pitchAt(octave)
                }
                previousMidi = pitch.midiNumber
                pitch
            }
        }

    private fun List<SpelledPitchClass>.toAscendingPreviewPitches(): List<Pitch> =
        previewPitchCandidates().minWith(
            compareBy<List<Pitch>>(
                { it.ledgerOverflow() },
                { it.centerPenalty() },
            )
        )

    private fun SpelledPitchClass.closestPitchBelow(reference: Pitch): Pitch {
        var pitch = pitchAt(reference.octave)
        while (pitch.diatonicSteps >= reference.diatonicSteps) {
            pitch = pitchAt(pitch.octave - 1)
        }
        return pitch
    }

    private fun compactPreviewPitches(
        first: List<SpelledPitchClass>,
        second: List<SpelledPitchClass>,
    ): Pair<List<Pitch>, List<Pitch>> = compactPreviewPitches(listOf(first, second))
        .let { it[0] to it[1] }

    private fun compactPreviewPitches(
        groups: List<List<SpelledPitchClass>>,
    ): List<List<Pitch>> {
        require(groups.isNotEmpty())
        return groups
            .map { it.previewPitchCandidates() }
            .fold(listOf(emptyList<List<Pitch>>())) { combinations, candidates ->
                combinations.flatMap { combination ->
                    candidates.map { candidate -> combination + listOf(candidate) }
                }
            }
            .minWith(
                compareBy<List<List<Pitch>>>(
                    { combination -> combination.sumOf { it.ledgerOverflow() } },
                    {
                        combination -> combination.zipWithNext().sumOf { (left, right) ->
                            registerDistance(left, right)
                        }
                    },
                    { combination -> combination.sumOf { it.centerPenalty() } },
                )
            )
    }

    private fun List<Pitch>.ledgerOverflow(): Int = sumOf { pitch ->
        val below = (TREBLE_PREVIEW_LOW - pitch.diatonicSteps).coerceAtLeast(0)
        val above = (pitch.diatonicSteps - TREBLE_PREVIEW_HIGH).coerceAtLeast(0)
        below + above
    }

    private fun List<Pitch>.centerPenalty(): Int =
        sumOf { kotlin.math.abs(it.diatonicSteps - TREBLE_PREVIEW_CENTER) }

    private fun registerDistance(first: List<Pitch>, second: List<Pitch>): Int = kotlin.math.abs(
        first.sumOf(Pitch::diatonicSteps) * second.size -
            second.sumOf(Pitch::diatonicSteps) * first.size
    )

    private const val TREBLE_PREVIEW_LOW = 0
    private const val TREBLE_PREVIEW_HIGH = 11
    private const val TREBLE_PREVIEW_CENTER = 6

    private fun TheorySourceRef.toUi(localize: (String) -> String): ChordDetailUiSource =
        ChordDetailUiSource(
            label = when (claimKind) {
                TheoryClaimKind.PRIMARY_SOURCE -> localize("exploration.chordDetail.source.primary")
                TheoryClaimKind.PROJECT_INFERENCE -> localize("exploration.chordDetail.source.inference")
                TheoryClaimKind.PRODUCT_RECOMMENDATION -> localize("exploration.chordDetail.source.recommendation")
            },
            detail = buildString {
                append(sourceId)
                append(" · ")
                append(edition)
                append(" · ")
                append(chapterOrTopic)
                locator?.let {
                    append(" · ")
                    append(it)
                }
            },
        )
}
