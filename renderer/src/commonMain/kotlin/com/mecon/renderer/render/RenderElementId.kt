package com.mecon.renderer.render

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlin.jvm.JvmInline

/**
 * Compact render-geometry identifier.
 *
 * Layout: 16-bit system (`0` means global), 24-bit generation, 24-bit generation-local ordinal.
 */
@JvmInline
@Serializable(with = RenderElementIdSerializer::class)
value class RenderElementId(val value: Long) {
    val systemIndex: Int?
        get() = ((value ushr (GENERATION_BITS + LOCAL_BITS)).toInt() - 1).takeIf { it >= 0 }

    val generation: Int
        get() = ((value ushr LOCAL_BITS) and GENERATION_MASK).toInt()

    val localOrdinal: Int
        get() = (value and LOCAL_MASK).toInt()

    /** String form is deliberately a debug/snapshot concern, not the runtime representation. */
    fun debugString(): String = "elem_$value"

    override fun toString(): String = debugString()

    companion object {
        private const val LOCAL_BITS = 24
        private const val GENERATION_BITS = 24
        private const val LOCAL_MASK = (1L shl LOCAL_BITS) - 1L
        private const val GENERATION_MASK = (1L shl GENERATION_BITS) - 1L
        private const val GLOBAL_MASK = (1L shl (GENERATION_BITS + LOCAL_BITS)) - 1L
        private const val MAX_SYSTEM_INDEX = (1 shl (64 - GENERATION_BITS - LOCAL_BITS)) - 2

        fun system(systemIndex: Int, localOrdinal: Int, generation: Int = 0): RenderElementId {
            require(systemIndex in 0..MAX_SYSTEM_INDEX) { "systemIndex out of range: $systemIndex" }
            require(localOrdinal in 0..LOCAL_MASK.toInt()) { "localOrdinal out of range: $localOrdinal" }
            require(generation in 0..GENERATION_MASK.toInt()) { "generation out of range: $generation" }
            return RenderElementId(
                ((systemIndex + 1L) shl (GENERATION_BITS + LOCAL_BITS)) or
                    (generation.toLong() shl LOCAL_BITS) or localOrdinal.toLong()
            )
        }

        fun global(localOrdinal: Long): RenderElementId {
            require(localOrdinal in 0..GLOBAL_MASK) { "global ordinal out of range: $localOrdinal" }
            return RenderElementId(localOrdinal)
        }
    }
}

/** Debug/snapshot wire form; production indexes retain the unboxed [Long]. */
object RenderElementIdSerializer : KSerializer<RenderElementId> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("RenderElementId", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: RenderElementId) {
        encoder.encodeString(value.debugString())
    }

    override fun deserialize(decoder: Decoder): RenderElementId {
        val text = decoder.decodeString()
        return RenderElementId(text.removePrefix("elem_").toLong())
    }
}

/** Assigns dense local ordinals independently for every system and for global elements. */
internal class RenderElementIdAllocator(private val generation: Int = 0) {
    private val nextBySystem = HashMap<Int, Int>()
    private var nextGlobal = 0L

    fun next(systemIndex: Int?): RenderElementId = if (systemIndex == null) {
        RenderElementId.global(nextGlobal++)
    } else {
        val ordinal = nextBySystem[systemIndex] ?: 0
        nextBySystem[systemIndex] = ordinal + 1
        RenderElementId.system(systemIndex, ordinal, generation)
    }
}
