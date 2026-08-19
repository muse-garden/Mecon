package com.mecon.desktop.service

import com.mecon.api.primitive.Fraction
import com.mecon.api.primitive.TimeCode
import com.mecon.api.runtime.pluginTrackOf
import com.mecon.api.storage.StorageScore
import com.mecon.plugins.chord.PolyphonyTonalKey
import com.mecon.plugins.chord.StorageTonalRegionEvent
import com.mecon.plugins.chord.TonalRegionEditPolicy
import com.mecon.theory.KeySignatureMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals

class ScoreSessionPluginEditingTest {
    @Test
    fun coupledTonalRegionReplacementIsOneUndoStep() = runBlocking {
        val desktopScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val session = ScoreSession(desktopScope)
            session.replaceDocument(
                StorageScore.create(StorageScore.CreationOptions(measureCount = 2)),
                file = null,
                fileName = "TonalRegions.mecon",
            )
            val cMajor = PolyphonyTonalKey(0, KeySignatureMode.MAJOR)
            val gMajor = PolyphonyTonalKey(1, KeySignatureMode.MAJOR)
            val previous = StorageTonalRegionEvent.create(
                onset = time(0),
                endOnset = time(4),
                keys = listOf(cMajor),
            )
            session.replacePluginEvents(StorageTonalRegionEvent.TRACK_TYPE, listOf(previous))
            await { session.tonalRegions().size == 1 }

            val inserted = StorageTonalRegionEvent.create(
                onset = time(1),
                endOnset = time(2),
                keys = listOf(gMajor),
            )
            val replacement = TonalRegionEditPolicy.insert(
                existing = listOf(previous),
                region = inserted,
                terminatePrevious = true,
            )
            session.replacePluginEvents(StorageTonalRegionEvent.TRACK_TYPE, replacement)
            await { session.tonalRegions().size == 2 }

            session.undo()
            await { session.tonalRegions().size == 1 }
            assertEquals(previous, session.tonalRegions().single())
            assertEquals(cMajor, session.tonalRegions().single().resolvedKey)
        } finally {
            desktopScope.cancel()
        }
    }

    private fun ScoreSession.tonalRegions(): List<StorageTonalRegionEvent> =
        runtimeScore
            ?.pluginTrackOf<StorageTonalRegionEvent>(StorageTonalRegionEvent.TRACK_TYPE)
            ?.events
            ?.map { it.storageEvent }
            .orEmpty()

    private fun time(quarter: Int): TimeCode = TimeCode.of(1, Fraction(quarter, 4))

    private suspend fun await(predicate: () -> Boolean) {
        withTimeout(5_000) {
            while (!predicate()) delay(10)
        }
    }
}
