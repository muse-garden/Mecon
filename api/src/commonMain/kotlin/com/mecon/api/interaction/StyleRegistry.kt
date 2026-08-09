package com.mecon.api.interaction

/**
 * A central registry for managing and layering style modification tracks.
 */
interface StyleRegistry {
    /** 
     * Creates a new style track with the given priority. 
     * If a track with the same priority already exists, behavior depends on the implementation. 
     */
    fun createTrack(priority: Int): StyleTrack

    /** 
     * Removes the given style track and immediately refreshes the rendered view. 
     */
    fun removeTrack(track: StyleTrack)
}
