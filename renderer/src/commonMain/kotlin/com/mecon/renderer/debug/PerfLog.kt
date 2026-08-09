package com.mecon.renderer.debug

/**
 * Lightweight, opt-in performance logging for diagnosing render / edit hot paths.
 *
 * Disabled by default, so every call site costs only a boolean check — the [message] lambda is
 * inlined and never evaluated (no string built) while [enabled] is false. Turn it on at runtime by
 * setting [enabled] = true; the desktop app wires this from the `mecon.perf` system property or the
 * `MECON_PERF` env var at startup (see `App.main`). Output goes to stdout, tagged `[perf][<tag>]`.
 *
 * These probes trace the incremental edit → compute → render pipeline (see
 * docs/data_model/incremental-update.md); keep them around so the next perf investigation can just
 * flip the flag instead of re-instrumenting.
 */
object PerfLog {
    // Plain var (not @Volatile — that annotation is JVM-only and this is commonMain): the flag is set
    // once at startup before any render runs, so a benign data race on a debug toggle is acceptable.
    var enabled: Boolean = false

    inline fun log(tag: String, message: () -> String) {
        if (enabled) println("[perf][$tag] ${message()}")
    }
}
