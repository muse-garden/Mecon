package com.mecon.desktop.service

import com.mecon.api.runtime.RuntimeScore
import com.mecon.api.storage.PageArrangement
import com.mecon.api.storage.PageLayoutConfig
import com.mecon.api.storage.ScoreMetadata
import com.mecon.api.storage.StorageScore
import com.mecon.api.runtime.toStorage
import java.io.File

/** The current document with the latest rendered geometry folded in, ready to serialize. */
val ScoreSession.storageScoreForSave: StorageScore?
    get() {
        val runtime = runtimeScore ?: return null
        return runtime.copy(geometry = lastRenderedGeometry ?: runtime.geometry).toStorage()
    }

/** Flip the page arrangement (vertical ↔ horizontal); tracked as a view-preference edit. */
fun ScoreSession.toggleArrangement() {
    manager?.updateViewPreferences { prefs ->
        prefs.copy(
            pageArrangement =
                if (prefs.pageArrangement == PageArrangement.VERTICAL) PageArrangement.HORIZONTAL
                else PageArrangement.VERTICAL
        )
    }
}

/** Toggle the final-pass measure numbers without entering the undo history. */
fun ScoreSession.toggleMeasureNumbers() {
    manager?.updateViewPreferences { prefs ->
        prefs.copy(showMeasureNumbers = !prefs.showMeasureNumbers)
    }
}

/** Apply an edited page layout (drives pagination); recomputes all layers. */
fun ScoreSession.applyPageConfig(config: PageLayoutConfig) {
    val base = manager?.currentState?.runtimeScore ?: return
    applyRuntimeEdit(base.copy(pageLayout = config))
}

/** Apply edited score metadata and refresh the suggested file name for unsaved documents. */
fun ScoreSession.applyMetadata(metadata: ScoreMetadata) {
    val base = manager?.currentState?.runtimeScore ?: return
    if (currentFile == null) {
        currentFileName = "${metadata.title.ifBlank { "Untitled" }}.mecon"
    }
    applyRuntimeEdit(base.copy(metadata = metadata))
}
