package com.mecon.theory

import com.mecon.api.primitive.Pitch

fun Pitch.Companion.parse(name: String): Pitch = fromName(name)

fun Pitch.degree(key: Key): Int {
    val idx = key.scale.pitchClasses.indexOf(pitchClass)
    return if (idx >= 0) idx + 1 else -1
}

fun Pitch.Companion.fromDegree(degree: Int, key: Key): Pitch {
    val pc = key.scale.pitchClasses.getOrNull((degree - 1).mod(key.scale.pitchClasses.size)) ?: key.root
    return Pitch.fromMidi(60 + pc.value)
}
