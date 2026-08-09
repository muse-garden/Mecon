package com.mecon.api.interaction

import com.mecon.api.primitive.TimeCode

/**
 * A track for recording style overrides to [EventSection] elements.
 * Changes do not take effect until [submit] is called.
 */
interface StyleTrack {
    /** The priority of this track. Higher priority overrides take precedence. */
    val priority: Int

    /** Apply an override to a specific section for its entire duration. */
    fun setStyle(section: EventSection, override: StyleOverride)

    /** Apply an override to a specific section starting at a specific timecode. */
    fun setStyle(section: EventSection, timeCode: TimeCode, override: StyleOverride)

    /** Remove the override for a specific section that was set for its entire duration. */
    fun removeStyle(section: EventSection)

    /** Remove the override for a specific section that was set at a specific timecode. */
    fun removeStyle(section: EventSection, timeCode: TimeCode)

    /** Remove all overrides in this track. */
    fun clear()

    /** Submit the changes to the central style manager, triggering re-render. */
    fun submit()
}
