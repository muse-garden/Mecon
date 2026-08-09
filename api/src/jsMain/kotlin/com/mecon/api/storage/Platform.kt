package com.mecon.api.storage

internal actual fun currentTimeMillis(): Long = kotlin.js.Date.now().toLong()
