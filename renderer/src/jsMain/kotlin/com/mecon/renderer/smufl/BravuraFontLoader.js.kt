package com.mecon.renderer.smufl

/**
 * Resource loading belongs to the npm host. The exported web facade accepts the two SMuFL
 * JSON resources explicitly, so the shared loader has no implicit browser-global lookup.
 */
actual class BravuraFontLoader {
    actual suspend fun load(): BravuraFont? = null
}
