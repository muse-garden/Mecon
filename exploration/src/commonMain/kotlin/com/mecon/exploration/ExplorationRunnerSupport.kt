package com.mecon.exploration

import com.mecon.api.primitive.EventId
import com.mecon.api.primitive.KeySignature
import com.mecon.theory.Key
import com.mecon.theory.KeySignatureMode
import com.mecon.theory.ModulationKey
import com.mecon.theory.RuleAnchorRole
import com.mecon.theory.RuleFinding

internal fun diagnosticOutput(
    request: CellRequest,
    diagnostics: List<SolverDiagnostic>,
): CellOutput = CellOutput(
    fingerprint = ExplorationRequestRunner.fingerprint(request),
    candidates = emptyList(),
    diagnostics = diagnostics.map(DiagnosticMessages::resolve),
    structuredDiagnostics = diagnostics,
)

internal fun KeySpec.toTheoryKey(): Key =
    when (mode) {
        KeyModeSpec.MAJOR -> Key.fromKeySignatureFifths(fifths, KeySignatureMode.MAJOR)
        KeyModeSpec.MINOR -> Key.fromKeySignatureFifths(fifths, KeySignatureMode.MINOR)
    }

internal fun KeySpec.toModulationKey(): ModulationKey =
    ModulationKey(
        fifths = fifths,
        mode = when (mode) {
            KeyModeSpec.MAJOR -> KeySignatureMode.MAJOR
            KeyModeSpec.MINOR -> KeySignatureMode.MINOR
        },
    )

internal fun KeySpec.toApiKeySignature(): KeySignature =
    when (mode) {
        KeyModeSpec.MAJOR -> KeySignature.majorByFifths(fifths)
        KeyModeSpec.MINOR -> KeySignature.minorByFifths(fifths)
    }

internal fun RuleFinding<EventId>.toStoredFinding(demonstrationRuleId: String?): StoredFinding =
    StoredFinding(
        ruleId = ruleId.value,
        severity = severity.name,
        kind = kind.name,
        messageKey = message,
        anchors = anchors,
        relatedAnchors = relatedAnchors
            .filter {
                it.role == RuleAnchorRole.RELATED ||
                    it.role == RuleAnchorRole.SOURCE ||
                    it.role == RuleAnchorRole.TARGET
            }
            .flatMap { it.anchors },
        isDemonstrationTarget =
            ruleId.value == demonstrationRuleId && kind.name == "VIOLATION",
    )
