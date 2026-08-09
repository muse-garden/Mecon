package com.mecon.desktop.ui.exploration

import androidx.compose.ui.graphics.Color
import com.mecon.api.interaction.EventSection
import com.mecon.api.interaction.StyleOverride
import com.mecon.api.interaction.VoiceEventSection
import com.mecon.api.interaction.VoiceNoteSection
import com.mecon.api.primitive.EventId
import com.mecon.api.primitive.TimeCode
import com.mecon.api.render.RenderColor
import com.mecon.api.storage.StorageScore
import com.mecon.api.storage.tracks.StoragePluginTrack
import com.mecon.desktop.i18n.explorationText
import com.mecon.desktop.uikit.theme.MeconColors
import com.mecon.exploration.CapabilityManifest
import com.mecon.exploration.EnumerationRequest
import com.mecon.exploration.FormSpec
import com.mecon.exploration.KeyModeSpec
import com.mecon.exploration.KeySpec
import com.mecon.exploration.SolverEngine
import com.mecon.exploration.StoredFinding
import com.mecon.exploration.SymbolicProgression
import com.mecon.plugins.chord.StorageChordEvent
import com.mecon.plugins.chord.ChordToneStyleProvider
import com.mecon.theory.ChordQuality
import com.mecon.theory.ChordRecognizer
import com.mecon.theory.RuleCatalog
import com.mecon.theory.RuleDegreePair
import com.mecon.theory.RuleDescriptor
import com.mecon.theory.RuleExampleInputSpec
import com.mecon.theory.RuleId
import com.mecon.theory.RuleKeyModeConstraint
import com.mecon.theory.RuleKind
import com.mecon.theory.SelectionContext
import com.mecon.theory.schoenberg.SchoenbergCommonToneExercises
import com.mecon.theory.schoenberg.SchoenbergSymbolicChord
import com.mecon.theory.textbook.TextbookSeventhPosition
import com.mecon.theory.textbook.TextbookTriadPosition
import com.mecon.theory.textbook.NonChordToneRules

internal enum class ExplorationMode {
    RULE_EXAMPLE,
    PROGRESSION,
    SCHOENBERG_EXERCISE,
    MODULATION,
}

internal val ExplorationMode.requestType: String
    get() = when (this) {
        ExplorationMode.RULE_EXAMPLE -> "rule-example"
        ExplorationMode.PROGRESSION -> "progression"
        ExplorationMode.SCHOENBERG_EXERCISE -> "schoenberg-exercise"
        ExplorationMode.MODULATION -> "modulation-exercise"
    }

internal fun CapabilityManifest.formFor(requestType: String): FormSpec =
    forms.firstOrNull { it.requestType == requestType } ?: FormSpec(requestType, emptyList())

internal data class RuleExampleRequestParts(
    val selectedRules: List<RuleId>,
    val demonstrationRuleId: RuleId?,
) {
    val validationRules: List<RuleId>
        get() = selectedRules + listOfNotNull(demonstrationRuleId)
}

internal val RuleExampleInputSpec.isDemonstrableAsViolation: Boolean
    get() = RuleCatalog.descriptor(ruleId)?.demonstrableAsViolation == true

internal fun RuleExampleInputSpec.compile(companionRuleId: RuleId, demonstrateRuleId: RuleId?): RuleExampleRequestParts {
    val effectiveCompanion = companionRuleOptions.firstOrNull { it == companionRuleId }
        ?: defaultCompanionRuleId
    val demonstration = demonstrateRuleId?.takeIf { it == ruleId && isDemonstrableAsViolation }
    val selected = when {
        demonstration == ruleId -> emptyList()
        companionRuleOptions.isNotEmpty() && effectiveCompanion != null ->
            listOf(effectiveCompanion, ruleId).distinct()
        else -> listOf(ruleId)
    }
    return RuleExampleRequestParts(selectedRules = selected, demonstrationRuleId = demonstration)
}

internal fun RuleKeyModeConstraint.toKeyModeSpec(): KeyModeSpec =
    when (this) {
        RuleKeyModeConstraint.MAJOR -> KeyModeSpec.MAJOR
        RuleKeyModeConstraint.MINOR -> KeyModeSpec.MINOR
    }

internal fun degreePairLabel(
    pair: RuleDegreePair,
    mode: KeyModeSpec,
    degreeQualities: Map<Int, ChordQuality>,
): String =
    "${romanDegree(pair.fromDegree, mode, degreeQualities[pair.fromDegree])} -> " +
        romanDegree(pair.toDegree, mode, degreeQualities[pair.toDegree])

internal fun romanDegree(degree: Int, mode: KeyModeSpec, qualityOverride: ChordQuality? = null): String {
    val qualityLabel = when (qualityOverride) {
        ChordQuality.MAJOR -> mapOf(
            1 to "I",
            2 to "II",
            3 to "III",
            4 to "IV",
            5 to "V",
            6 to "VI",
            7 to "VII",
        )
        ChordQuality.MINOR -> mapOf(
            1 to "i",
            2 to "ii",
            3 to "iii",
            4 to "iv",
            5 to "v",
            6 to "vi",
            7 to "vii",
        )
        ChordQuality.DIMINISHED -> mapOf(
            1 to "i°",
            2 to "ii°",
            3 to "iii°",
            4 to "iv°",
            5 to "v°",
            6 to "vi°",
            7 to "vii°",
        )
        ChordQuality.AUGMENTED -> mapOf(
            1 to "I+",
            2 to "II+",
            3 to "III+",
            4 to "IV+",
            5 to "V+",
            6 to "VI+",
            7 to "VII+",
        )
        ChordQuality.DOMINANT7 -> mapOf(
            1 to "I7",
            2 to "II7",
            3 to "III7",
            4 to "IV7",
            5 to "V7",
            6 to "VI7",
            7 to "VII7",
        )
        else -> null
    }
    if (qualityLabel != null) return qualityLabel.getValue(degree)

    val major = mapOf(
        1 to "I",
        2 to "ii",
        3 to "iii",
        4 to "IV",
        5 to "V",
        6 to "vi",
        7 to "vii°",
    )
    val minor = mapOf(
        1 to "i",
        2 to "ii°",
        3 to "III",
        4 to "iv",
        5 to "V",
        6 to "VI",
        7 to "vii°",
    )
    return (if (mode == KeyModeSpec.MAJOR) major else minor).getValue(degree)
}

internal fun chordQualityLabel(quality: ChordQuality): String {
    val key = "chordQuality.${quality.name}"
    val label = explorationText(key)
    return if (label == "exploration.$key") quality.name.lowercase() else label
}

internal fun ruleInputSummary(spec: RuleExampleInputSpec): String =
    when {
        spec.defaultDemonstrationRuleId != null -> explorationText("summary.demonstration")
        spec.keyMode != null -> explorationText("summary.keyMode")
        spec.companionRuleOptions.isNotEmpty() -> explorationText("summary.companion")
        else -> explorationText("summary.default")
    }

internal const val DIATONIC_DEGREES = 7

/** 给定前一和弦音级，返回所有让全部规则仍适用的后一和弦音级（scene 驱动的 applicability）。 */
internal fun feasibleTargets(rules: List<RuleId>, fromDegree: Int): List<Int> =
    (1..DIATONIC_DEGREES).filter { to ->
        rules.all { RuleCatalog.applicability(it, SelectionContext(fromDegree, to)).applies }
    }

/** 只保留至少存在一个合法后位的前一和弦音级。 */
internal fun feasibleSources(rules: List<RuleId>): List<Int> =
    (1..DIATONIC_DEGREES).filter { from -> feasibleTargets(rules, from).isNotEmpty() }

/** 三个和弦进行的可选项：首/次和弦音级（映射为 from/to 请求）+ 三和弦罗马数字标签。 */
internal data class SceneProgressionOption(
    val fromDegree: Int,
    val toDegree: Int,
    val label: String,
)

/** 所选规则声明的最大场景窗口（四六 = 3）；无场景默认按两和弦连接规则处理。 */
internal fun maxSceneWindow(rules: List<RuleId>): Int =
    rules.flatMap { RuleCatalog.scenes(it) }.maxOfOrNull { it.window.last } ?: 2

/**
 * 经 [SolverEngine.enumerate] 枚举 window≥3 场景在给定调性上的适用三和弦进行（含 BassMotion 约束），
 * 去重到首/次和弦音级对。取代对四六用松散的 window-2 applicability 投影，改由 solver-api 统一取数。
 */
internal fun sceneProgressions(
    rules: List<RuleId>,
    keyFifths: Int,
    keyMode: KeyModeSpec,
): List<SceneProgressionOption> =
    SolverEngine.enumerate(
        EnumerationRequest(
            key = KeySpec(fifths = keyFifths, mode = keyMode),
            ruleIds = rules.map { it.value },
            policyId = SECOND_INVERSION_TRIADS_POLICY,
        )
    ).progressions
        .filter { it.slots.size >= 3 }
        .mapNotNull { it.toProgressionOption(keyMode) }
        .distinctBy { it.fromDegree to it.toDegree }

internal fun schoenbergExerciseProgressions(
    exerciseId: String,
    keyFifths: Int,
    continuationChordCount: Int,
    chordFilters: List<com.mecon.exploration.SchoenbergChordFilterSpec> = emptyList(),
    selections: Map<String, List<String>> = emptyMap(),
    keyMode: KeyModeSpec = KeyModeSpec.MAJOR,
    includeDeceptiveCadence: Boolean = false,
    includeCadentialSixFour: Boolean = false,
    maxResults: Int? = null,
    maxVisitedNodes: Int? = null,
    shouldContinue: () -> Boolean = { true },
): List<SymbolicProgression> =
    SolverEngine.enumerate(
        EnumerationRequest(
            key = KeySpec(fifths = keyFifths, mode = keyMode),
            ruleIds = listOf(SchoenbergCommonToneExercises.ruleIdForExercise(exerciseId).value),
            windowLimit = SchoenbergCommonToneExercises
                .descriptorForExercise(exerciseId)
                .enumerationWindowLimit
                ?: continuationChordCount + 1,
            maxResults = maxResults,
            maxVisitedNodes = maxVisitedNodes,
            chordFilters = chordFilters,
            selections = selections,
            includeDeceptiveCadence = includeDeceptiveCadence,
            includeCadentialSixFour = includeCadentialSixFour,
        ),
        shouldContinue = shouldContinue,
    ).progressions

internal fun SymbolicProgression.schoenbergLabel(mode: KeyModeSpec = KeyModeSpec.MAJOR): String =
    slots.joinToString(" - ") { slot ->
        symbolicChordLabel(slot, mode)
    }

internal fun SchoenbergSymbolicChord.schoenbergLabel(mode: KeyModeSpec = KeyModeSpec.MAJOR): String =
    symbolicChordLabel(
        com.mecon.exploration.SymbolicChordSpecView(
            degree = degree,
            quality = quality.name,
            position = seventhPosition?.name ?: position.name,
            arity = arity.name,
            rootAlteration = rootAlteration,
            appliedToDegree = appliedToDegree,
            secondaryFamily = secondaryFamily?.name,
            modalOrigins = modalOrigins.map { it.name },
            rootlessDominantNinthChordId = rootlessDominantNinthChordId,
            rootlessDominantNinthUsageId = rootlessDominantNinthUsageId,
            omittedRootDegree = omittedRootDegree,
            omittedRootAlteration = omittedRootAlteration,
        ),
        mode,
    )

private fun SymbolicProgression.toProgressionOption(mode: KeyModeSpec): SceneProgressionOption? {
    val from = slots.getOrNull(0)?.degree ?: return null
    val to = slots.getOrNull(1)?.degree ?: return null
    val label = slots.joinToString(" - ") { slot ->
        symbolicChordLabel(slot, mode)
    }
    return SceneProgressionOption(from, to, label)
}

private fun symbolicChordLabel(
    slot: com.mecon.exploration.SymbolicChordSpecView,
    mode: KeyModeSpec,
): String {
    val accidental = when {
        slot.rootAlteration > 0 -> "♯".repeat(slot.rootAlteration)
        slot.rootAlteration < 0 -> "♭".repeat(-slot.rootAlteration)
        else -> ""
    }
    val base = accidental +
        romanDegree(slot.degree, mode, ChordQuality.valueOf(slot.quality)) +
        inversionFigure(slot)
    val target = slot.appliedToDegree ?: return base
    return "$base/${romanDegree(target, mode)}"
}

private fun inversionFigure(slot: com.mecon.exploration.SymbolicChordSpecView): String =
    if (slot.arity == "SEVENTH") {
        when (TextbookSeventhPosition.valueOf(slot.position)) {
            TextbookSeventhPosition.ROOT_POSITION -> "⁷"
            TextbookSeventhPosition.FIRST_INVERSION -> "⁶₅"
            TextbookSeventhPosition.SECOND_INVERSION -> "⁴₃"
            TextbookSeventhPosition.THIRD_INVERSION -> "⁴₂"
        }
    } else {
        when (TextbookTriadPosition.valueOf(slot.position)) {
            TextbookTriadPosition.ROOT_POSITION -> ""
            TextbookTriadPosition.FIRST_INVERSION -> "⁶"
            TextbookTriadPosition.SECOND_INVERSION -> "⁶₄"
        }
    }

internal fun parseDegrees(text: String): List<Int> =
    text.split(',', ' ', '-', '>')
        .mapNotNull { it.trim().toIntOrNull() }
        .filter { it in 1..7 }
        .ifEmpty { listOf(1, 5, 1) }

internal data class ProgressionPolicy(
    val id: String,
    val labelKey: String,
)

internal val progressionPolicies = listOf(
    ProgressionPolicy(INTRODUCTORY_TRIADS_POLICY, "policy.introductoryTriads"),
    ProgressionPolicy(FREE_TRIADS_POLICY, "policy.freeTriads"),
    ProgressionPolicy(FIRST_INVERSION_TRIADS_POLICY, "policy.firstInversionTriads"),
    ProgressionPolicy(SECOND_INVERSION_TRIADS_POLICY, "policy.secondInversionTriads"),
)

internal fun progressionDegrees(text: String, policyId: String): List<Int> {
    val degrees = parseDegrees(text)
    return if (policyId == SECOND_INVERSION_TRIADS_POLICY) {
        when (degrees.size) {
            0 -> listOf(1, 5, 1)
            1 -> listOf(degrees.first(), 5, degrees.first())
            2 -> listOf(degrees.first(), degrees.last(), degrees.first())
            else -> degrees
        }
    } else {
        degrees
    }
}

internal fun minProgressionSlots(policyId: String): Int =
    if (policyId == SECOND_INVERSION_TRIADS_POLICY) 3 else 1

internal fun progressionPolicyHint(policyId: String): String =
    if (policyId == SECOND_INVERSION_TRIADS_POLICY) {
        explorationText("policyHint.secondInversionTriads")
    } else {
        explorationText("policyHint.default")
    }

internal fun List<Int>.updated(index: Int, value: Int): List<Int> =
    mapIndexed { itemIndex, item -> if (itemIndex == index) value else item }

internal fun List<Int>.removeAtIndex(index: Int): List<Int> =
    filterIndexed { itemIndex, _ -> itemIndex != index }

internal fun Int?.orDefaultDegree(): Int = this ?: 1

internal const val INTRODUCTORY_TRIADS_POLICY = "introductory-triads"
internal const val FREE_TRIADS_POLICY = "free-triads"
internal const val FIRST_INVERSION_TRIADS_POLICY = "first-inversion-triads"
internal const val SECOND_INVERSION_TRIADS_POLICY = "second-inversion-triads"

internal fun Double.formatScore(): String =
    "%.1f".format(this)

internal fun EventSection.eventId(): EventId? =
    when (this) {
        is VoiceNoteSection -> event.id
        is VoiceEventSection -> event.id
        else -> null
    }

internal fun EventSection.timeCode(): TimeCode? =
    when (this) {
        is VoiceNoteSection -> event.onset
        is VoiceEventSection -> event.onset
        else -> null
    }

internal fun StoredFinding.contains(eventId: EventId): Boolean =
    eventId in anchors || eventId in relatedAnchors

internal val StoredFinding.localId: String
    get() = "$ruleId:${anchors.joinToString("_") { it.value }}:${relatedAnchors.joinToString("_") { it.value }}"

internal fun buildFindingStyles(
    findings: List<StoredFinding>,
    activeFinding: StoredFinding?,
    selectedEventId: EventId?,
): Map<EventId, StyleOverride> {
    val styles = linkedMapOf<EventId, StyleOverride>()
    findings.forEach { finding ->
        val color = finding.renderColor()
        finding.anchors.forEach { eventId ->
            styles.mergeStyle(eventId, StyleOverride(fillColor = color.withAlpha(210)))
        }
    }

    val active = activeFinding ?: selectedEventId?.let { eventId ->
        findings.firstOrNull { it.contains(eventId) }
    }
    active?.let { finding ->
        val color = finding.renderColor()
        finding.anchors.forEach { eventId ->
            styles.mergeStyle(
                eventId,
                StyleOverride(
                    fillColor = color,
                    backgroundColor = color.withAlpha(86),
                ),
            )
        }
        finding.relatedAnchors.forEach { eventId ->
            styles.mergeStyle(
                eventId,
                StyleOverride(
                    fillColor = RELATED_RENDER_COLOR,
                    backgroundColor = RELATED_RENDER_COLOR.withAlpha(54),
                ),
            )
        }
    }

    selectedEventId?.let { eventId ->
        styles.mergeStyle(
            eventId,
            StyleOverride(
                fillColor = SELECTED_RENDER_COLOR,
                backgroundColor = SELECTED_RENDER_COLOR.withAlpha(74),
            ),
        )
    }

    return styles
}

private fun MutableMap<EventId, StyleOverride>.mergeStyle(eventId: EventId, override: StyleOverride) {
    this[eventId] = this[eventId]?.mergeOver(override) ?: override
}

private fun StoredFinding.renderColor(): RenderColor =
    when {
        NonChordToneRules.typeFor(RuleId(ruleId)) != null ->
            ChordToneStyleProvider.typeColors.getValue(NonChordToneRules.typeFor(RuleId(ruleId))!!)
        kind == "INDICATION" -> RenderColor.rgb(22, 163, 74)
        kind == "HINT" -> RenderColor.rgb(37, 99, 235)
        severity == "HARD" -> RenderColor.rgb(220, 38, 38)
        severity == "SOFT" -> RenderColor.rgb(234, 88, 12)
        else -> RenderColor.rgb(100, 116, 139)
    }

internal fun StoredFinding.textColor(): Color =
    when {
        kind == "INDICATION" -> MeconColors.EmeraldLight
        severity == "HARD" -> MeconColors.Red
        severity == "SOFT" -> MeconColors.OrangeLight
        else -> MeconColors.PrimaryLight
    }

private fun RenderColor.withAlpha(alpha: Int): RenderColor =
    copy(alpha = alpha)

private val SELECTED_RENDER_COLOR = RenderColor.rgb(37, 99, 235)
private val RELATED_RENDER_COLOR = RenderColor.rgb(147, 51, 234)
private val EXPLORATION_CHORD_TRACK_ID = com.mecon.api.primitive.TrackId("exploration-chord-analysis")

internal fun StorageScore.withRecognizedChordTrack(): StorageScore {
    // 教材外音示例和用户标注谱已经给出权威和弦；自动识别不能覆盖分析上下文。
    if (pluginTracks.values.any { it.type == StorageChordEvent.TRACK_TYPE }) return this
    val chordEvents = pitchTracks.values
        .flatMap { it.events }
        .groupBy { it.onset }
        .toSortedMap()
        .mapNotNull { (onset, events) ->
            val pitches = events.flatMap { it.pitches }
            val chord = ChordRecognizer.recognize(pitches).firstOrNull()?.chord ?: return@mapNotNull null
            StorageChordEvent(
                id = EventId("exploration-chord-${onset.eventIdSuffix()}"),
                onset = onset,
                root = chord.root.value,
                quality = chord.quality,
                bass = chord.bass?.value,
            )
        }

    if (chordEvents.isEmpty()) return this
    val chordTrack = StoragePluginTrack(
        id = EXPLORATION_CHORD_TRACK_ID,
        name = "Exploration Chord Symbols",
        type = StorageChordEvent.TRACK_TYPE,
        events = chordEvents,
    )
    return copy(
        pluginTracks = pluginTracks
            .filterValues { it.type != StorageChordEvent.TRACK_TYPE } +
            (chordTrack.id to chordTrack),
    )
}

private fun TimeCode.eventIdSuffix(): String =
    components.joinToString("-") { "${it.numerator}_${it.denominator}" }

internal fun rulePath(ruleId: RuleId): List<RuleId> {
    SchoenbergCommonToneExercises.exerciseDescriptors.firstOrNull { it.ruleId == ruleId }?.let { descriptor ->
        return listOf(SchoenbergCommonToneExercises.CHAPTER_RULE_ID, descriptor.parentId, ruleId)
    }
    if (
        ruleId == SchoenbergCommonToneExercises.MAJOR_BRANCH_RULE_ID ||
        ruleId == SchoenbergCommonToneExercises.MINOR_BRANCH_RULE_ID ||
        ruleId == SchoenbergCommonToneExercises.GENERAL_BRANCH_RULE_ID ||
        ruleId == SchoenbergCommonToneExercises.MODULATION_BRANCH_RULE_ID
    ) {
        return listOf(SchoenbergCommonToneExercises.CHAPTER_RULE_ID, ruleId)
    }
    val descriptor = RuleCatalog.descriptor(ruleId) ?: return listOf(ruleId)
    return descriptor.parent?.let { rulePath(it) }.orEmpty() + ruleId
}

internal fun ruleKindLabel(rule: RuleDescriptor): String =
    when (rule.kind) {
        RuleKind.GROUP -> explorationText("ruleKind.group")
        RuleKind.PATTERN -> explorationText("ruleKind.pattern")
        RuleKind.CONSTRAINT -> if (rule.demonstrableAsViolation) {
            explorationText("ruleKind.constraint.demonstrable")
        } else {
            explorationText("ruleKind.constraint")
        }
        RuleKind.TENDENCY -> explorationText("ruleKind.tendency")
    }
