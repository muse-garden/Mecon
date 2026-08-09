package com.mecon.desktop.ui.components

import com.mecon.api.storage.BeamingInfo
import com.mecon.features.scoreediting.ScoreNoteInputTransition

/**
 * Applies the note pen's shared one-shot lifecycle and returns the success callback.
 *
 * Accidental and start/end beam choices are consumed when an insertion is dispatched. Tuplet entry
 * applies the shared session's derived continuation only after the host confirms the commit.
 */
internal fun NoteToolState.prepareInsertionCommit(): (ScoreNoteInputTransition) -> Unit {
    accidental = null
    if (insertionBeaming == BeamingInfo.start() || insertionBeaming == BeamingInfo.end()) {
        insertionBeaming = null
    }
    return { transition ->
        tupletCount = transition.tupletCount
        durationBase = transition.duration.base
        dots = transition.duration.dots
    }
}
