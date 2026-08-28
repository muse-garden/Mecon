package com.mecon.theory.voiceleading

/** Which way the dissonance is discharged. */
enum class SuspensionDischarge {
    /** 延留音: the classic downward step resolution. */
    DOWNWARD,

    /** 上行延留音 (retardation): the same figure resolving upward. */
    UPWARD,
}

/**
 * A suspension inserted between two chords that are already chosen.
 *
 * This is a different construction from a pathway node, and the difference is the point. A pathway
 * derives a suspension by reordering parsimonious moves, which explains *what* a suspended sonority
 * is but not *where* it is used. A real suspension is defined by preparation: the dissonance is a
 * tone the previous chord already sounded, held into the next harmony and resolved by step. The
 * previous chord therefore need not be a parsimonious neighbour of the suspension — `IV -> Gsus4 ->
 * V` is the standard cadential 4-3 even though IV and Gsus4 are three semitones apart in one voice.
 *
 * See `docs/theory/voice-leading-pathways.md` §3.1.
 */
data class PreparedSuspension(
    val suspensionPitchClasses: List<Int>,
    /** The prepared, retained tone. Foreign to the resolution chord by construction. */
    val suspendedPitchClass: Int,
    /** Where the suspended tone goes; always a member of the resolution chord. */
    val resolutionPitchClass: Int,
    val semitones: Int,
    val readings: List<VoiceLeadingChordReading>,
    /** null when the vertical has no registered name, which is normal for 9-8 and 7-6 figures. */
    val stability: VoiceLeadingStability?,
    /**
     * Root motion of the two chords the figure decorates, measured *without* the suspension.
     *
     * A suspension decorates a progression; it does not upgrade it. Keeping the underlying motion
     * separate is what stops a strong resolution from disguising a weak `I -> V` skeleton.
     */
    val underlyingRootMotion: VoiceLeadingRootConnection,
    val dissonance: Double,
    /** Tension released at the resolution: tension(suspension) - tension(resolution). */
    val tensionDrop: Double,
) {
    val discharge: SuspensionDischarge
        get() = if (semitones < 0) SuspensionDischarge.DOWNWARD else SuspensionDischarge.UPWARD

    val nameable: Boolean get() = readings.isNotEmpty()
}

object VoiceLeadingSuspensionInsertion {

    /**
     * Every suspension that [previousPitchClasses] can prepare over [resolutionPitchClasses].
     *
     * The suspension chord is the resolution chord with one tone replaced by the retained tone, so
     * the harmony under the dissonance is already the new one — which is what makes the figure a
     * suspension rather than a chord change.
     */
    fun between(
        previousPitchClasses: Collection<Int>,
        resolutionPitchClasses: Collection<Int>,
        universe: VoiceLeadingUniverse = StandardVoiceLeadingUniverses.TERTIAN_WITH_SUSPENSIONS,
        policy: VoiceLeadingTensionPolicy = VoiceLeadingTensionPolicy.DEFAULT,
        includeUpward: Boolean = true,
    ): List<PreparedSuspension> {
        val previous = previousPitchClasses.map { it.mod(12) }.distinct().sorted()
        val resolution = resolutionPitchClasses.map { it.mod(12) }.distinct().sorted()
        require(previous.isNotEmpty() && resolution.isNotEmpty()) {
            "A prepared suspension needs both a preparation and a resolution chord"
        }
        val previousReadings = universe.recognize(previous)
        val resolutionReadings = universe.recognize(resolution)
        val underlying = if (previousReadings.isEmpty() || resolutionReadings.isEmpty()) null else {
            VoiceLeadingTransformations.mostStableRootConnection(previousReadings, resolutionReadings)
        }
        val resolutionTension = VoiceLeadingTension.tension(resolution, universe, policy)
        val moves = if (includeUpward) ALLOWED_SEMITONE_MOVES else ALLOWED_SEMITONE_MOVES.filter { it < 0 }

        return resolution.flatMap { target ->
            moves.mapNotNull { semitones ->
                // The retained tone is one step away from the tone it will become.
                val suspended = (target - semitones).mod(12)
                if (suspended !in previous) return@mapNotNull null
                if (suspended in resolution) return@mapNotNull null
                val vertical = (resolution - target + suspended).sorted()
                // A tone that lands consonantly against everything is a chord change, not a
                // suspension: `I -> iii -> V` must not be dressed up as a suspension figure.
                if (!clashes(suspended, vertical)) return@mapNotNull null
                val readings = universe.recognize(vertical)
                PreparedSuspension(
                    suspensionPitchClasses = vertical,
                    suspendedPitchClass = suspended,
                    resolutionPitchClass = target,
                    semitones = semitones,
                    readings = readings,
                    stability = readings.takeIf { it.isNotEmpty() }?.let {
                        universe.stabilityOfSet(vertical)
                    },
                    underlyingRootMotion = underlying
                        ?: VoiceLeadingTransformations.classifyRootMotion(
                            previous.first(),
                            resolution.first(),
                        ),
                    dissonance = VoiceLeadingTension.dissonance(vertical, policy),
                    tensionDrop = VoiceLeadingTension.tension(vertical, universe, policy) -
                        resolutionTension,
                )
            }
        }.distinctBy { it.suspendedPitchClass to it.resolutionPitchClass }.sortedWith(
            compareBy<PreparedSuspension> { it.discharge.ordinal }
                .thenByDescending { it.tensionDrop }
                .thenBy { it.suspendedPitchClass }
                .thenBy { it.resolutionPitchClass }
        )
    }

    /** Whether [pitchClass] forms a semitone, whole tone or tritone with any other tone. */
    private fun clashes(pitchClass: Int, vertical: List<Int>): Boolean = vertical.any { other ->
        other != pitchClass && (pitchClass - other).mod(12)
            .let { minOf(it, 12 - it) } in DISSONANT_INTERVAL_CLASSES
    }
}

private val DISSONANT_INTERVAL_CLASSES = setOf(1, 2, 6)
