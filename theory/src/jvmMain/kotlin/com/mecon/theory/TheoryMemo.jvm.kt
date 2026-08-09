package com.mecon.theory

import java.util.concurrent.ConcurrentHashMap

internal actual fun <K : Any, V : Any> theoryMemoMap(): MutableMap<K, V> = ConcurrentHashMap()
