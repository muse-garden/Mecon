package com.mecon.renderer.render.spatial

import java.util.concurrent.locks.ReentrantReadWriteLock

/**
 * JVM implementation of [ReadWriteLock] using [ReentrantReadWriteLock].
 */
actual class ReadWriteLock actual constructor() {
    private val delegate = ReentrantReadWriteLock()

    actual fun readLock(): Lock = Lock(delegate.readLock())
    actual fun writeLock(): Lock = Lock(delegate.writeLock())
}

/**
 * JVM implementation of [Lock] wrapping a [java.util.concurrent.locks.Lock].
 */
actual class Lock(private val delegate: java.util.concurrent.locks.Lock) {
    actual fun lock() = delegate.lock()
    actual fun unlock() = delegate.unlock()
}

internal actual inline fun <T> platformSynchronized(lock: Any, action: () -> T): T =
    synchronized(lock, action)
