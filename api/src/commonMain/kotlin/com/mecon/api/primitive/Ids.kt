package com.mecon.api.primitive

import kotlinx.serialization.Serializable
import kotlin.jvm.JvmInline

/**
 * Unique identifier for events (notes, rests, etc.)
 */
@Serializable
@JvmInline
value class EventId(val value: String) : Comparable<EventId> {
    companion object {
        fun generate(): EventId = EventId(generateId())
    }

    override fun compareTo(other: EventId): Int = value.compareTo(other.value)

    override fun toString(): String = value
}

/**
 * Unique identifier for tracks
 */
@Serializable
@JvmInline
value class TrackId(val value: String) {
    companion object {
        fun generate(): TrackId = TrackId(generateId())
    }

    override fun toString(): String = value
}

/**
 * Unique identifier for scores
 */
@Serializable
@JvmInline
value class ScoreId(val value: String) {
    companion object {
        fun generate(): ScoreId = ScoreId(generateId())
    }

    override fun toString(): String = value
}

/**
 * Unique identifier for staff groups (bracket groupings that span multiple parts/staves).
 */
@Serializable
@JvmInline
value class StaffGroupId(val value: String) {
    companion object {
        fun generate(): StaffGroupId = StaffGroupId(generateId())
    }

    override fun toString(): String = value
}

/** Unique identifier for a playable instrument (which may own multiple staves). */
@Serializable
@JvmInline
value class InstrumentId(val value: String) {
    companion object {
        fun generate(): InstrumentId = InstrumentId(generateId())
    }

    override fun toString(): String = value
}

/** Stable identifier for a persisted reduction. */
@Serializable
@JvmInline
value class ReductionId(val value: String) {
    companion object { fun generate(): ReductionId = ReductionId(generateId()) }
    override fun toString(): String = value
}

/** Stable identifier for one semantic layer inside a reduction workspace. */
@Serializable
@JvmInline
value class ReductionLayerId(val value: String) {
    companion object { fun generate(): ReductionLayerId = ReductionLayerId(generateId()) }
    override fun toString(): String = value
}

/** Stable identifier for reusable, unplaced score material. */
@Serializable
@JvmInline
value class ScoreFragmentId(val value: String) {
    companion object { fun generate(): ScoreFragmentId = ScoreFragmentId(generateId()) }
    override fun toString(): String = value
}

/** Stable identifier for an orchestration player. */
@Serializable
@JvmInline
value class PlayerId(val value: String) {
    companion object { fun generate(): PlayerId = PlayerId(generateId()) }
    override fun toString(): String = value
}
