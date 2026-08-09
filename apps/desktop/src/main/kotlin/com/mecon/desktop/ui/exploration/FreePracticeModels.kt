package com.mecon.desktop.ui.exploration

import com.mecon.api.primitive.EventId
import com.mecon.api.primitive.Fraction
import com.mecon.api.primitive.Pitch
import com.mecon.api.primitive.TrackId
import com.mecon.theory.freepractice.HarmonyWorkspaceState
import com.mecon.theory.freepractice.WorkspaceHarmonySlot
import com.mecon.theory.freepractice.WorkspaceChordChoice
import com.mecon.theory.freepractice.WorkspacePatternChoice
import com.mecon.theory.freepractice.WorkspaceKeyMode
import com.mecon.theory.freepractice.WorkspaceSlotId
import com.mecon.theory.freepractice.WorkspaceTonalLayout
import com.mecon.theory.freepractice.WorkspaceTonalLayoutId
import com.mecon.theory.freepractice.WorkspaceVoiceBoundary
import com.mecon.theory.freepractice.WorkspaceVoiceSpec
import com.mecon.theory.CommonChordPivotStep
import com.mecon.theory.KeySignatureMode
import com.mecon.theory.ModulationCommonChordCatalog
import com.mecon.theory.ModulationChordId
import com.mecon.theory.ModulationChordVocabularyId
import com.mecon.theory.ModulationKey
import com.mecon.theory.RuleFindingKind
import com.mecon.theory.ChordQuality
import com.mecon.theory.TonalEndingIntent
import com.mecon.theory.TonalRoutePlan
import com.mecon.theory.VoicePlan
import com.mecon.theory.harmony.ChordSelectionCatalog
import com.mecon.theory.harmony.ChordSelectionChoice
import com.mecon.theory.harmony.ChordInterpretationRef

internal enum class PracticeInputMode { CHORD_TONE, FREE_DRAW }

/** Resolve display metadata without ever replacing the persisted audible pitch-class set. */
internal fun Iterable<ChordSelectionChoice>.matchingChoice(
    slot: WorkspaceHarmonySlot,
    interpretationRef: ChordInterpretationRef? = null,
): ChordSelectionChoice? = matchingChoice(
    committed = slot.chordChoice?.let { committed ->
        interpretationRef?.let { committed.copy(pinnedInterpretationRef = it) } ?: committed
    },
    legacyInterpretationRef = slot.chordInterpretationRef,
    legacySymbol = slot.chordIdentity,
)

internal fun Iterable<ChordSelectionChoice>.matchingChoice(
    committed: WorkspaceChordChoice?,
    legacyInterpretationRef: ChordInterpretationRef?,
    legacySymbol: String?,
): ChordSelectionChoice? {
    val choices = this.toList()
    committed?.let {
        val soundingMatches = choices.filter { it.pitchClasses == committed.pitchClasses.toSet() }
        return soundingMatches.firstOrNull {
            committed.pinnedInterpretationRef != null &&
                committed.pinnedInterpretationRef in it.interpretationRefs
        } ?: soundingMatches.firstOrNull()
    }
    return choices.firstOrNull { choice ->
        legacyInterpretationRef?.let { it in choice.interpretationRefs }
            ?: (choice.identity == legacySymbol)
    }
}

internal data class PracticeFinding(
    val title: String,
    val detail: String,
    val severity: PracticeFindingSeverity,
    val ruleId: String? = null,
    val kind: RuleFindingKind? = null,
    val anchors: List<EventId> = emptyList(),
)

internal enum class PracticeFindingSeverity { INFO, WARNING, ERROR }

internal fun localizedPracticeFinding(
    finding: com.mecon.features.freepractice.PracticeFindingView,
): PracticeFinding = PracticeFinding(
    title = finding.message ?: when (finding.messageKey) {
        "freePractice.finding.incompleteHarmony" -> "和声尚未填写完整"
        "freePractice.finding.voiceRange" -> "音符超出声部范围"
        "freePractice.finding.polyphonyLimit" -> "同时发声音符超过上限"
        "freePractice.finding.voiceSeparation" -> "分析声部分离未完成"
        else -> finding.ruleId?.let { "规则 $it" } ?: finding.messageKey
    },
    detail = when (finding.messageKey) {
        "freePractice.finding.incompleteHarmony" -> "空槽保持未判定，不会被误报为违规。"
        "freePractice.finding.voiceRange" -> "${finding.arguments["pitch"] ?: "?"} 不在当前声部预设音域内。"
        "freePractice.finding.polyphonyLimit" ->
            "当前峰值为 ${finding.arguments["peak"] ?: "?"} 个音符，上限为 ${finding.arguments["limit"] ?: "?"}。"
        "freePractice.finding.voiceSeparation" -> "记谱保持不变；请调整交叠音符后再次分析。"
        else -> (finding.ruleId ?: finding.arguments["ruleId"])?.let { "规则：$it" }.orEmpty()
    },
    severity = when (finding.severity) {
        com.mecon.features.freepractice.PracticeFindingSeverity.INFO -> PracticeFindingSeverity.INFO
        com.mecon.features.freepractice.PracticeFindingSeverity.WARNING -> PracticeFindingSeverity.WARNING
        com.mecon.features.freepractice.PracticeFindingSeverity.ERROR -> PracticeFindingSeverity.ERROR
    },
    ruleId = finding.ruleId,
    anchors = finding.anchors,
)

internal fun initialTonalRoute(
    source: ModulationKey = ModulationKey(0, KeySignatureMode.MAJOR),
): TonalRoutePlan {
    val targetFifths = if (source.fifths == 7) 6 else source.fifths + 1
    val target = ModulationKey(targetFifths, source.mode)
    val pivot = ModulationCommonChordCatalog.commonChords(source, target).first().id
    return TonalRoutePlan(
        source = source,
        steps = listOf(CommonChordPivotStep(target, pivot)),
        endingIntent = TonalEndingIntent.ESTABLISHED,
    )
}

internal fun initialWorkspace(
    voiceCount: Int,
    initialKey: ModulationKey = ModulationKey(0, KeySignatureMode.MAJOR),
): HarmonyWorkspaceState = com.mecon.features.freepractice.FreePracticePreset.workspace(voiceCount, initialKey)
