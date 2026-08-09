package com.mecon.renderer.render.spatial

/**
 * Platform-agnostic read-write lock for concurrent read access
 * with exclusive write access.
 *
 * JVM implementation delegates to [java.util.concurrent.locks.ReentrantReadWriteLock].
 */
expect class ReadWriteLock() {
    fun readLock(): Lock
    fun writeLock(): Lock
}

/**
 * Platform-agnostic lock.
 */
expect class Lock {
    fun lock()
    fun unlock()
}

internal expect inline fun <T> platformSynchronized(lock: Any, action: () -> T): T

/**
 * Execute [action] while holding the lock.
 */
inline fun <T> Lock.withLock(action: () -> T): T {
    lock()
    try {
        return action()
    } finally {
        unlock()
    }
}
