package com.mecon.desktop.ui.exploration

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mecon.desktop.i18n.explorationText
import com.mecon.desktop.i18n.ruleLabel
import com.mecon.desktop.uikit.theme.MeconColors
import com.mecon.theory.RuleCatalog
import com.mecon.theory.RuleDescriptor
import com.mecon.theory.RuleId
import com.mecon.theory.RuleKind
import com.mecon.theory.schoenberg.SchoenbergCommonToneExercises
import com.mecon.theory.textbook.DOMINANT_SEVENTH_CHAPTER
import com.mecon.theory.textbook.FIRST_INVERSION_TRIAD_CHAPTER
import com.mecon.theory.textbook.ROOT_POSITION_TRIAD_CHAPTER
import com.mecon.theory.textbook.SECOND_INVERSION_TRIAD_CHAPTER
import com.mecon.theory.textbook.NON_CHORD_TONE_CHAPTER

@Composable
internal fun RuleTreePanel(
    selectedRuleId: RuleId,
    onSelectRule: (RuleId) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .heightIn(max = 560.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MeconColors.SurfaceDark.copy(alpha = 0.35f))
            .verticalScroll(rememberScrollState())
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(explorationText("rules.title"), color = MeconColors.TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        RuleTreeSyntheticRoot()
        RuleCatalog.chapter(ROOT_POSITION_TRIAD_CHAPTER).forEach { root ->
            RuleTreeNode(
                descriptor = root,
                selectedRuleId = selectedRuleId,
                onSelectRule = onSelectRule,
                depth = 1,
            )
        }
        RuleCatalog.chapter(FIRST_INVERSION_TRIAD_CHAPTER).forEach { root ->
            RuleTreeNode(
                descriptor = root,
                selectedRuleId = selectedRuleId,
                onSelectRule = onSelectRule,
                depth = 1,
            )
        }
        RuleCatalog.chapter(SECOND_INVERSION_TRIAD_CHAPTER).forEach { root ->
            RuleTreeNode(
                descriptor = root,
                selectedRuleId = selectedRuleId,
                onSelectRule = onSelectRule,
                depth = 1,
            )
        }
        RuleCatalog.chapter(DOMINANT_SEVENTH_CHAPTER).forEach { root ->
            RuleTreeNode(
                descriptor = root,
                selectedRuleId = selectedRuleId,
                onSelectRule = onSelectRule,
                depth = 1,
            )
        }
        RuleCatalog.chapter(NON_CHORD_TONE_CHAPTER).forEach { root ->
            RuleTreeNode(
                descriptor = root,
                selectedRuleId = selectedRuleId,
                onSelectRule = onSelectRule,
                depth = 1,
            )
        }
        RuleTreeSyntheticLabel(ruleLabel(SchoenbergCommonToneExercises.CHAPTER_RULE_ID))
        RuleTreeSyntheticLabel(ruleLabel(SchoenbergCommonToneExercises.MAJOR_BRANCH_RULE_ID), depth = 1)
        SchoenbergCommonToneExercises.exerciseDescriptors
            .filter { it.parentId == SchoenbergCommonToneExercises.MAJOR_BRANCH_RULE_ID }
            .forEach { descriptor ->
                RuleTreeExerciseNode(
                    ruleId = descriptor.ruleId,
                    selectedRuleId = selectedRuleId,
                    onSelectRule = onSelectRule,
                    depth = 2,
                )
            }
        RuleTreeSyntheticLabel(ruleLabel(SchoenbergCommonToneExercises.MINOR_BRANCH_RULE_ID), depth = 1)
        SchoenbergCommonToneExercises.exerciseDescriptors
            .filter { it.parentId == SchoenbergCommonToneExercises.MINOR_BRANCH_RULE_ID }
            .forEach { descriptor ->
                RuleTreeExerciseNode(
                    ruleId = descriptor.ruleId,
                    selectedRuleId = selectedRuleId,
                    onSelectRule = onSelectRule,
                    depth = 2,
                )
            }
        RuleTreeSyntheticLabel(ruleLabel(SchoenbergCommonToneExercises.GENERAL_BRANCH_RULE_ID), depth = 1)
        SchoenbergCommonToneExercises.exerciseDescriptors
            .filter { it.parentId == SchoenbergCommonToneExercises.GENERAL_BRANCH_RULE_ID }
            .forEach { descriptor ->
                RuleTreeExerciseNode(
                    ruleId = descriptor.ruleId,
                    selectedRuleId = selectedRuleId,
                    onSelectRule = onSelectRule,
                    depth = 2,
                )
            }
        RuleTreeSyntheticLabel(ruleLabel(SchoenbergCommonToneExercises.MODULATION_BRANCH_RULE_ID), depth = 1)
    }
}

@Composable
private fun RuleTreeSyntheticRoot() {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            explorationText("rules.tonalHarmony"),
            color = MeconColors.TextSecondary,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun RuleTreeSyntheticLabel(label: String, depth: Int = 0) {
    Row(
        Modifier.fillMaxWidth().padding(start = (depth * 16 + 6).dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = MeconColors.TextSecondary, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun RuleTreeExerciseNode(
    ruleId: RuleId,
    selectedRuleId: RuleId,
    onSelectRule: (RuleId) -> Unit,
    depth: Int,
) {
    val selected = ruleId == selectedRuleId
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(if (selected) MeconColors.SelectedSurface else Color.Transparent)
            .clickable { onSelectRule(ruleId) }
            .padding(start = (depth * 16).dp, end = 8.dp, top = 5.dp, bottom = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = explorationText("rules.exerciseGlyph"),
            color = MeconColors.TextMuted,
            fontSize = 11.sp,
            modifier = Modifier.width(14.dp),
        )
        Column(Modifier.weight(1f)) {
            Text(
                ruleLabel(ruleId),
                color = MeconColors.TextPrimary,
                fontSize = 12.sp,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            )
            if (selected) {
                Text(explorationText("rules.exercise"), color = MeconColors.TextMuted, fontSize = 10.sp)
            }
        }
    }
}

@Composable
private fun RuleTreeNode(
    descriptor: RuleDescriptor,
    selectedRuleId: RuleId,
    onSelectRule: (RuleId) -> Unit,
    depth: Int,
) {
    val selected = descriptor.id == selectedRuleId
    val selectable = descriptor.selectable
    val children = remember(descriptor.id) { RuleCatalog.children(descriptor.id) }
    val hasChildren = children.isNotEmpty()
    var expanded by remember(descriptor.id) { mutableStateOf(true) }
    LaunchedEffect(selectedRuleId) {
        if (selected || children.any { it.containsRule(selectedRuleId) }) expanded = true
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(6.dp))
                .background(if (selected) MeconColors.SelectedSurface else Color.Transparent)
                .clickable(enabled = selectable || hasChildren) {
                    if (selectable) onSelectRule(descriptor.id) else expanded = !expanded
                }
                .padding(start = (depth * 16).dp, end = 8.dp, top = 5.dp, bottom = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (descriptor.kind == RuleKind.GROUP) "▾" else "•",
                color = MeconColors.TextMuted,
                fontSize = 11.sp,
                modifier = Modifier.width(14.dp),
            )
            Column(Modifier.weight(1f)) {
                Text(
                    ruleLabel(descriptor.id),
                    color = if (selectable) MeconColors.TextPrimary else MeconColors.TextSecondary,
                    fontSize = if (selectable) 12.sp else 11.sp,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                )
                if (selected) {
                    Text(ruleKindLabel(descriptor), color = MeconColors.TextMuted, fontSize = 10.sp)
                }
            }
        }
        if (expanded) {
            children.forEach { child ->
                RuleTreeNode(
                    descriptor = child,
                    selectedRuleId = selectedRuleId,
                    onSelectRule = onSelectRule,
                    depth = depth + 1,
                )
            }
        }
    }
}

private fun RuleDescriptor.containsRule(ruleId: RuleId): Boolean =
    id == ruleId || RuleCatalog.children(id).any { it.containsRule(ruleId) }

@Composable
internal fun RulePathHeader(ruleId: RuleId) {
    val path = remember(ruleId) { rulePath(ruleId) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(path.joinToString(" / ") { ruleLabel(it) }, color = MeconColors.TextSecondary, fontSize = 11.sp)
        Text(ruleLabel(ruleId), color = MeconColors.TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold)
    }
}
