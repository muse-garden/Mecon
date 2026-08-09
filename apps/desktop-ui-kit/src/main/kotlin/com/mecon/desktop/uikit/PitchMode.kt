package com.mecon.desktop.uikit

/**
 * Whether notes display their absolute pitch (C, D, E…) or relative scale degree.
 * Lives in the kit so plugin panels can read the current mode without depending
 * back on the desktop app module.
 */
enum class PitchMode {
    ABSOLUTE,
    RELATIVE
}
