package com.mecon.features.freepractice

import com.mecon.api.runtime.RuntimeScore
import com.mecon.theory.SearchCancellation

/** CPU-only finding executor for platform workers/background dispatchers. */
object PracticeFindingExecutor {
    fun execute(
        request: PracticeFindingRequest,
        cancellation: SearchCancellation = SearchCancellation.NONE,
    ): PracticeFindingResult = PracticeFindingResult(
        requestId = request.requestId,
        baseRevision = request.baseRevision,
        fingerprint = request.fingerprint,
        items = PracticeFindingComputer.compute(
            request.document.workspace,
            RuntimeScore.fromStorage(request.score),
            PracticeFindingComputer.fallbackKey(request.document),
            cancellation,
        ),
    )
}
