package com.mecon.api.plugin

import com.mecon.api.primitive.TrackId

/**
 * Where an annotation staff sits relative to the notation staves.
 *
 * v1 implements only [BelowAllStaves]; the other variants are declared so plugins
 * can express their intent today, but the renderer falls back to bottom-of-score
 * placement (with a debug log) until full anchor support lands.
 */
sealed interface StaffAnchor {
    /** Place this annotation staff above every notation staff in the score. */
    data object AboveAllStaves : StaffAnchor

    /** Place this annotation staff below every notation staff in the score. */
    data object BelowAllStaves : StaffAnchor

    /** Place this annotation staff immediately above a specific notation staff. */
    data class AboveStaff(val trackId: TrackId, val staffIndex: Int) : StaffAnchor

    /** Place this annotation staff immediately below a specific notation staff. */
    data class BelowStaff(val trackId: TrackId, val staffIndex: Int) : StaffAnchor
}
