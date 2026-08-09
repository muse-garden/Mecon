package com.mecon.renderer.frozen

import kotlinx.serialization.json.Json

/**
 * JSON codec for [FrozenScoreBundle].
 *
 * Kept beside the geometry types (the renderer owns [com.mecon.renderer.render.RenderCommand]'s
 * polymorphic hierarchy) so the container layer in `core` need not depend on the renderer: the
 * desktop packager encodes here and hands the resulting JSON bytes to the zip writer as an opaque
 * `geometry/<scoreId>.json` entry; a web viewer decodes here without ever touching the layout
 * engine.
 *
 * [ignoreUnknownKeys] lets an older viewer read a newer bundle; [encodeDefaults] is off to keep
 * the payload compact (defaulted fields such as empty metadata maps are dropped).
 */
object FrozenScoreCodec {

    private val json = Json {
        prettyPrint = false
        ignoreUnknownKeys = true
        encodeDefaults = false
    }

    fun encode(bundle: FrozenScoreBundle): String =
        json.encodeToString(FrozenScoreBundle.serializer(), bundle)

    fun decode(text: String): FrozenScoreBundle =
        json.decodeFromString(FrozenScoreBundle.serializer(), text)
}
