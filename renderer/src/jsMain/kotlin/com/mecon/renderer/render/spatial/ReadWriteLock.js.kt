package com.mecon.renderer.render.spatial

/**
 * JavaScript executes one document engine serially (normally in a Web Worker), so lock
 * operations are intentionally no-ops while preserving the common engine contract.
 */
actual class ReadWriteLock actual constructor() {
    private val lock = Lock()

    actual fun readLock(): Lock = lock
    actual fun writeLock(): Lock = lock
}

actual class Lock {
    actual fun lock() = Unit
    actual fun unlock() = Unit
}

internal actual inline fun <T> platformSynchronized(lock: Any, action: () -> T): T = action()
