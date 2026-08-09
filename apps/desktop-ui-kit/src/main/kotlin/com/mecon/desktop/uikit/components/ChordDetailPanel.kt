package com.mecon.desktop.uikit.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mecon.api.primitive.Pitch
import com.mecon.desktop.uikit.theme.MeconColors

enum class ChordDetailMode {
    VIEW,
    PRE_COMMIT,
}

enum class ChordDetailSeverity {
    INFO,
    RECOMMENDATION,
    REQUIREMENT,
}

data class ChordDetailUiSource(
    val label: String,
    val detail: String,
)

data class ChordDetailUiSection(
    val title: String,
    val lines: List<String>,
    val severity: ChordDetailSeverity = ChordDetailSeverity.INFO,
)

data class ChordDetailUiConstructionTone(
    val pitch: Pitch,
    val muted: Boolean,
)

data class ChordDetailUiConstructionEvent(
    val tones: List<ChordDetailUiConstructionTone>,
)

data class ChordDetailUiConstruction(
    val description: String,
    val events: List<ChordDetailUiConstructionEvent>,
    val keySignatureFifths: Int? = null,
    val caption: String,
    val showDescription: Boolean = false,
)

data class ChordDetailUiRoute(
    val id: String,
    val title: String,
    val subtitle: String? = null,
    val badge: String? = null,
    val sections: List<ChordDetailUiSection> = emptyList(),
    val construction: ChordDetailUiConstruction? = null,
    val sources: List<ChordDetailUiSource> = emptyList(),
)

data class ChordDetailUiExplanation(
    val id: String,
    val title: String,
    val subtitle: String? = null,
    val badges: List<String> = emptyList(),
    val commonSections: List<ChordDetailUiSection> = emptyList(),
    val routes: List<ChordDetailUiRoute> = emptyList(),
    val sources: List<ChordDetailUiSource> = emptyList(),
)

data class ChordDetailUiModel(
    val title: String,
    val subtitle: String? = null,
    val badges: List<String> = emptyList(),
    val commonSections: List<ChordDetailUiSection> = emptyList(),
    val routes: List<ChordDetailUiRoute> = emptyList(),
    val sources: List<ChordDetailUiSource> = emptyList(),
    val missingKnowledgeMessage: String? = null,
    val explanations: List<ChordDetailUiExplanation> = emptyList(),
)

data class ChordDetailPanelStrings(
    val routes: String,
    val routeHint: String,
    val sources: String,
    val applyInterpretation: String,
    val chooseRoute: String,
    val restoreFreeInterpretation: String = "Restore free interpretation",
)

/**
 * Localized, domain-neutral chord detail. Selecting a route only updates the caller-owned
 * preview state; [onConfirmRoute] is the sole commit callback.
 */
@Composable
fun ChordDetailPanel(
    model: ChordDetailUiModel,
    mode: ChordDetailMode,
    strings: ChordDetailPanelStrings,
    selectedRouteId: String?,
    onSelectRoute: (String) -> Unit,
    onConfirmRoute: (String) -> Unit,
    pinned: Boolean = false,
    onRestoreFreeInterpretation: () -> Unit = {},
    constructionPreview: @Composable (ChordDetailUiConstruction) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val allRoutes = model.routes + model.explanations.flatMap(ChordDetailUiExplanation::routes)
    val confirmedRoute = allRoutes.singleOrNull()?.id ?: selectedRouteId
    val focusedRoute = selectedRouteId ?: allRoutes.firstOrNull()?.id
    Column(
        modifier = modifier
            .heightIn(max = 620.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Text(
            model.title,
            color = MeconColors.TextPrimary,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
        )
        model.subtitle?.let {
            Text(it, color = MeconColors.TextSecondary, fontSize = 12.sp)
        }
        if (model.badges.isNotEmpty()) {
            Text(model.badges.joinToString(" · "), color = MeconColors.PrimaryLight, fontSize = 11.sp)
        }
        model.missingKnowledgeMessage?.let {
            DetailSection(
                ChordDetailUiSection(
                    title = "",
                    lines = listOf(it),
                    severity = ChordDetailSeverity.RECOMMENDATION,
                )
            )
        }
        model.commonSections.forEach { DetailSection(it) }
        if (model.explanations.isNotEmpty()) {
            model.explanations.forEachIndexed { index, explanation ->
                if (index > 0) {
                    HorizontalDivider(color = MeconColors.Border)
                }
                ExplanationCard(
                    explanation = explanation,
                    showTitle = model.explanations.size > 1 || explanation.title != model.title,
                    focusedRoute = focusedRoute,
                    selectable = mode == ChordDetailMode.PRE_COMMIT,
                    routesTitle = strings.routes,
                    routeHint = strings.routeHint,
                    sourcesTitle = strings.sources,
                    confirmedRoute = confirmedRoute,
                    onSelectRoute = onSelectRoute,
                    constructionPreview = constructionPreview,
                )
            }
        } else if (model.routes.isNotEmpty()) {
            Text(strings.routes, color = MeconColors.TextSecondary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            model.routes.forEach { route ->
                RouteCard(
                    route = route,
                    selected = route.id == confirmedRoute,
                    expanded = route.id == focusedRoute,
                    selectable = mode == ChordDetailMode.PRE_COMMIT,
                    onSelect = { onSelectRoute(route.id) },
                    constructionPreview = constructionPreview,
                )
            }
        }
        SourceList(strings.sources, model.sources)
        if (mode == ChordDetailMode.PRE_COMMIT) {
            Button(
                enabled = confirmedRoute != null,
                onClick = { confirmedRoute?.let(onConfirmRoute) },
                colors = ButtonDefaults.buttonColors(
                    containerColor = MeconColors.Primary,
                    contentColor = MeconColors.White,
                    disabledContainerColor = MeconColors.SurfaceLight,
                    disabledContentColor = MeconColors.TextDark,
                ),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    if (confirmedRoute == null) strings.chooseRoute else strings.applyInterpretation,
                    fontSize = 12.sp,
                )
            }
            if (pinned) {
                OutlinedButton(
                    onClick = onRestoreFreeInterpretation,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MeconColors.TextPrimary),
                    border = BorderStroke(1.dp, MeconColors.Border),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(strings.restoreFreeInterpretation, fontSize = 11.sp)
                }
            }
        }
    }
}

@Composable
private fun ExplanationCard(
    explanation: ChordDetailUiExplanation,
    showTitle: Boolean,
    focusedRoute: String?,
    selectable: Boolean,
    routesTitle: String,
    routeHint: String,
    sourcesTitle: String,
    confirmedRoute: String?,
    onSelectRoute: (String) -> Unit,
    constructionPreview: @Composable (ChordDetailUiConstruction) -> Unit,
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (showTitle) {
            Text(explanation.title, color = MeconColors.TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }
        explanation.subtitle?.let { Text(it, color = MeconColors.TextSecondary, fontSize = 11.sp) }
        if (explanation.badges.isNotEmpty()) {
            Text(explanation.badges.joinToString(" · "), color = MeconColors.PrimaryLight, fontSize = 10.sp)
        }
        ExplanationSummary(explanation.commonSections)
        if (explanation.routes.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text(routesTitle, color = MeconColors.TextPrimary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Text(routeHint, color = MeconColors.TextDark, fontSize = 10.sp)
            }
            explanation.routes.forEach { route ->
                RouteCard(
                    route = route,
                    selected = route.id == confirmedRoute,
                    expanded = route.id == focusedRoute,
                    selectable = selectable,
                    onSelect = { onSelectRoute(route.id) },
                    constructionPreview = constructionPreview,
                )
            }
        }
        val routeSources = explanation.routes.flatMap(ChordDetailUiRoute::sources).toSet()
        SourceList(sourcesTitle, explanation.sources.filterNot(routeSources::contains))
    }
}

@Composable
private fun RouteCard(
    route: ChordDetailUiRoute,
    selected: Boolean,
    expanded: Boolean,
    selectable: Boolean,
    onSelect: () -> Unit,
    constructionPreview: @Composable (ChordDetailUiConstruction) -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = if (selected) MeconColors.Primary.copy(alpha = 0.13f) else MeconColors.SurfaceDark,
        shape = RoundedCornerShape(9.dp),
        border = BorderStroke(1.dp, if (selected) MeconColors.PrimaryLight else MeconColors.Border),
    ) {
        Column(
            modifier = Modifier
                .then(
                    if (selectable) {
                        Modifier.selectable(
                            selected = selected,
                            onClick = onSelect,
                            role = Role.RadioButton,
                        )
                    } else Modifier
                )
                .padding(11.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (selectable) {
                    RadioButton(
                        selected = selected,
                        onClick = null,
                        colors = RadioButtonDefaults.colors(
                            selectedColor = MeconColors.PrimaryLight,
                            unselectedColor = MeconColors.Border,
                        ),
                    )
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        route.title,
                        color = if (selected) MeconColors.PrimaryLight else MeconColors.TextPrimary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    route.subtitle?.let {
                        Text(it, color = MeconColors.TextMuted, fontSize = 10.sp)
                    }
                }
                route.badge?.let { badge ->
                    Text(
                        badge,
                        color = MeconColors.PrimaryLight,
                        fontSize = 10.sp,
                        modifier = Modifier
                            .border(1.dp, MeconColors.PrimaryLight, RoundedCornerShape(6.dp))
                            .padding(horizontal = 7.dp, vertical = 3.dp),
                    )
                }
            }
            if (expanded) {
                HorizontalDivider(color = MeconColors.Border)
                route.sections.forEach { DetailSection(it) }
                route.construction?.let { construction ->
                    if (construction.showDescription) {
                        Text(
                            construction.description,
                            color = MeconColors.TextSecondary,
                            fontSize = 11.sp,
                        )
                    }
                    constructionPreview(construction)
                    Text(construction.caption, color = MeconColors.TextDark, fontSize = 10.sp)
                }
                SourceList(null, route.sources)
            }
        }
    }
}

@Composable
private fun ExplanationSummary(sections: List<ChordDetailUiSection>) {
    if (sections.isEmpty()) return
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        sections.forEachIndexed { index, section ->
            if (index > 0) {
                VerticalDivider(
                    modifier = Modifier.heightIn(min = 48.dp),
                    color = MeconColors.Border,
                )
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(section.title, color = MeconColors.TextDark, fontSize = 10.sp)
                section.lines.forEach { line ->
                    Text(line, color = MeconColors.TextSecondary, fontSize = 11.sp)
                }
            }
        }
    }
}

@Composable
private fun DetailSection(section: ChordDetailUiSection) {
    val color = when (section.severity) {
        ChordDetailSeverity.INFO -> MeconColors.TextMuted
        ChordDetailSeverity.RECOMMENDATION -> MeconColors.OrangeLight
        ChordDetailSeverity.REQUIREMENT -> MeconColors.Red
    }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        if (section.title.isNotBlank()) {
            Text(section.title, color = MeconColors.TextSecondary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        }
        section.lines.forEach { line ->
            Text(line, color = color, fontSize = 11.sp)
        }
    }
}

@Composable
private fun SourceList(
    title: String?,
    sources: List<ChordDetailUiSource>,
) {
    if (sources.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        title?.let {
            Text(it, color = MeconColors.TextSecondary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        }
        sources.forEach { source ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(source.label, color = MeconColors.TextMuted, fontSize = 10.sp)
                Text(source.detail, color = MeconColors.TextDark, fontSize = 10.sp, modifier = Modifier.weight(1f))
            }
        }
    }
}
