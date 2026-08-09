package com.mecon.desktop.ui.components.toolstate

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.mecon.api.primitive.Accidental
import com.mecon.api.primitive.Duration
import com.mecon.api.primitive.DurationBase
import com.mecon.api.storage.Articulation
import com.mecon.api.storage.BeamingInfo
import com.mecon.api.storage.GraceNoteType
import com.mecon.api.storage.events.GraceTimeSource
import com.mecon.desktop.ui.components.NoteEntryKind

/** State owned exclusively by note/rest and grace-note entry. */
class NoteEntryToolState {
    var durationBase by mutableStateOf(DurationBase.QUARTER)
    var dots by mutableStateOf(0)
    var restMode by mutableStateOf(false)
    var accidental by mutableStateOf<Accidental?>(null)
    var tieMode by mutableStateOf(false)
    var entryKind by mutableStateOf(NoteEntryKind.NORMAL)
    var graceTotalDurationBase by mutableStateOf(DurationBase.EIGHTH)
    var graceTotalDurationDots by mutableStateOf(0)
    var graceTimeSource by mutableStateOf(GraceTimeSource.PRINCIPAL)
    var graceNoteType by mutableStateOf(GraceNoteType.APPOGGIATURA)
    var activeVoiceNumber by mutableStateOf(1)
    var uncommonDurationsExpanded by mutableStateOf(false)
    var articulationsExpanded by mutableStateOf(false)
    var tupletCount by mutableStateOf<Int?>(null)
    var insertionBeaming by mutableStateOf<BeamingInfo?>(null)
    var articulations by mutableStateOf<Set<Articulation>>(emptySet())
    var customTupletText by mutableStateOf("")
    var recentTupletCounts by mutableStateOf(listOf(3))

    val duration: Duration get() = Duration(durationBase, dots)
    val graceTotalDuration: Duration
        get() = Duration(graceTotalDurationBase, graceTotalDurationDots)

    fun reset() {
        durationBase = DurationBase.QUARTER
        dots = 0
        restMode = false
        accidental = null
        tieMode = false
        entryKind = NoteEntryKind.NORMAL
        graceTotalDurationBase = DurationBase.EIGHTH
        graceTotalDurationDots = 0
        graceTimeSource = GraceTimeSource.PRINCIPAL
        graceNoteType = GraceNoteType.APPOGGIATURA
        activeVoiceNumber = 1
        uncommonDurationsExpanded = false
        articulationsExpanded = false
        tupletCount = null
        insertionBeaming = null
        articulations = emptySet()
        customTupletText = ""
        recentTupletCounts = listOf(3)
    }
}
