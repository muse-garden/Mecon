package com.mecon.audio.engine

/**
 * Low-latency metronome output independent of transport playback. Scheduling stays in the
 * performance-input controller; the sink only emits one short percussion click.
 */
interface MetronomeSink {
    fun metronomeTick(accent: Boolean)
}
