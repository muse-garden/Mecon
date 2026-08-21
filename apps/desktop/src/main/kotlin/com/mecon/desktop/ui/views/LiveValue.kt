package com.mecon.desktop.ui.views

import kotlin.reflect.KProperty

/**
 * Property delegates that read (and write) through to their owner on every access.
 *
 * Long-lived pointer/gesture lambdas must not capture a Compose `State` value once: the captured
 * snapshot goes stale as soon as the next frame changes pan, zoom or selection. Delegating to a
 * getter keeps `val x by LiveValue { ... }` reading the current value without re-creating the
 * lambda (which would restart the gesture handler and, on large scores, re-engrave every element).
 */
internal class LiveValue<T>(private val read: () -> T) {
    operator fun getValue(thisRef: Any?, property: KProperty<*>): T = read()
}

internal class MutableLiveValue<T>(
    private val read: () -> T,
    private val write: (T) -> Unit,
) {
    operator fun getValue(thisRef: Any?, property: KProperty<*>): T = read()
    operator fun setValue(thisRef: Any?, property: KProperty<*>, value: T) = write(value)
}
