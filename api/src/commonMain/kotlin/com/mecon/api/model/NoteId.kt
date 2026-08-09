package com.mecon.api.model

import kotlinx.serialization.Serializable
import kotlin.jvm.JvmInline

@JvmInline
@Serializable
value class NoteId(val value: String) {
    companion object {
        fun generate(): NoteId = NoteId(
            (1..9).map { "0123456789abcdefghijklmnopqrstuvwxyz".random() }.joinToString("")
        )
    }
}
