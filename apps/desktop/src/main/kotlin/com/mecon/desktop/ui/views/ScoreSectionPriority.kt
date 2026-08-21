package com.mecon.desktop.ui.views

import com.mecon.api.interaction.*

/**
 * Select the most specific (highest priority) section from a list of hit sections.
 * Finer-grained sections like individual noteheads take precedence over
 * coarser sections like whole voice events.
 *
 * Shared by every family that turns a pointer hit into a stable selection target: tap selection,
 * marquee collection and the drag arbitration in [com.mecon.desktop.ui.views.drag].
 */
internal fun List<EventSection>.selectByPriority(): EventSection? {
    return this.minByOrNull { section ->
        when (section) {
            is VoiceNoteSection -> 0
            is VoiceArticulationSection -> 1
            is VoiceStemSection -> 2
            is VoiceFlagSection -> 3
            is VoiceBeamSection -> 4
            is VoiceEventSection -> 5
            is VoiceTupletSection -> 6
            is VoiceTieSection -> 7
            is VoiceSlurSection -> 7
            is LayoutBreakSection -> 8
            is VoltaEndingSection -> 8
            is NavigationMarkSection -> 8
            is BarlineSection -> 9
            is ClefSection -> 10
            is KeySignatureSection -> 11
            is TimeSignatureSection -> 12
            is StaffAttachmentSection -> 1
            is MeasureStaffSection -> 13
            is HiddenStaffSection -> 14
        }
    }
}
