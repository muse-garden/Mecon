package com.mecon.theory

/**
 * Backing map for process-wide memos of pure theory derivations.
 *
 * Desktop builds teaching catalogs on background dispatchers and may run two derivations at once,
 * so the JVM actual must tolerate concurrent access. JS is single-threaded and uses a plain map.
 * A racing [MutableMap.getOrPut] may compute the same value twice; that is harmless because every
 * memoized derivation is a pure function of its key.
 */
internal expect fun <K : Any, V : Any> theoryMemoMap(): MutableMap<K, V>
