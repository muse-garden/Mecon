package com.mecon.desktop.service

import com.mecon.api.primitive.InstrumentId
import com.mecon.api.storage.PlayerKind

/** Immutable UI draft used when committing orchestration configuration. */
data class OrchestrationInstrumentDraft(
    val instrumentId: InstrumentId,
    val kind: PlayerKind,
    val playerCount: Int,
    val playerAssignments: List<List<Int>>,
)
