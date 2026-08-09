package com.mecon.exploration

object ExplorationRequestRunner {
    fun run(request: CellRequest): CellOutput {
        val output = when (request) {
            is RuleExampleRequest -> RuleExampleRequestRunner.run(request)
            is ProgressionRequest -> ProgressionRequestRunner.run(request)
            is SchoenbergExerciseRequest -> SchoenbergExplorationRequestRunner.run(request)
            is ModulationExerciseCellRequest -> ModulationExplorationRequestRunner.run(request)
        }
        return output.withDiversityExhaustion(request.searchSpec())
    }

    fun fingerprint(request: CellRequest): String =
        "exploration-rules-v1:${request.hashCode()}"

    private fun CellRequest.searchSpec(): SearchSpec =
        when (this) {
            is RuleExampleRequest -> search
            is ProgressionRequest -> search
            is SchoenbergExerciseRequest -> search
            is ModulationExerciseCellRequest -> search
        }

    private fun CellOutput.withDiversityExhaustion(search: SearchSpec): CellOutput {
        if (!search.diversify || candidates.isEmpty() || candidates.size >= search.maxResults) return this
        val diagnostic = Diagnostics.diversityExhausted(candidates.size, search.maxResults)
        return copy(
            diagnostics = diagnostics + DiagnosticMessages.resolve(diagnostic),
            structuredDiagnostics = structuredDiagnostics + diagnostic,
        )
    }
}
