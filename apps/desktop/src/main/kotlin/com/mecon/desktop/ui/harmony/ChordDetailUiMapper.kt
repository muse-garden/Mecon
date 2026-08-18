package com.mecon.desktop.ui.harmony

import com.mecon.desktop.uikit.components.ChordDetailSeverity
import com.mecon.desktop.uikit.components.ChordDetailUiConstruction
import com.mecon.desktop.uikit.components.ChordDetailUiConstructionEvent
import com.mecon.desktop.uikit.components.ChordDetailUiConstructionTone
import com.mecon.desktop.uikit.components.ChordDetailUiModel
import com.mecon.desktop.uikit.components.ChordDetailUiExplanation
import com.mecon.desktop.uikit.components.ChordDetailUiRoute
import com.mecon.desktop.uikit.components.ChordDetailUiSection
import com.mecon.desktop.uikit.components.ChordDetailUiSource

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
}
