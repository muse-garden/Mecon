package com.mecon.features.freepractice

import kotlinx.serialization.Serializable

/**
 * Stable, platform-neutral structure of the two free-practice toolbars.
 *
 * Platforms own widgets and OS integration, but they must replay these groups and controls in
 * order. Runtime state (enabled/selected/expanded/visible) is carried separately so snapshots can
 * compare product state without coupling commonMain to Compose or React.
 */
@Serializable
data class FreePracticeToolbarDescriptor(
    val version: Int,
    val top: FreePracticeToolbarLayer,
    val score: FreePracticeToolbarLayer,
    val tokens: FreePracticeToolbarTokens,
)

@Serializable
data class FreePracticeToolbarLayer(
    val id: String,
    val groups: List<FreePracticeToolbarGroup>,
)

@Serializable
data class FreePracticeToolbarGroup(
    val id: String,
    val controls: List<String>,
    val trailing: Boolean = false,
    val expandable: Boolean = false,
)

@Serializable
data class FreePracticeToolbarTokens(
    val topHeight: Float = 64f,
    val paletteButtonSize: Float = 28f,
    val horizontalPadding: Float = 16f,
    val paletteHorizontalPadding: Float = 6f,
    val paletteVerticalPadding: Float = 4f,
    val groupGap: Float = 8f,
    val rowGap: Float = 4f,
    val dividerWidth: Float = 1f,
)

@Serializable
data class FreePracticeToolbarControlState(
    val id: String,
    val enabled: Boolean = true,
    val selected: Boolean = false,
    val expanded: Boolean = false,
    val visible: Boolean = true,
)

@Serializable
data class FreePracticeToolbarStateSnapshot(
    val descriptorVersion: Int = FreePracticeToolbarSpec.DESCRIPTOR_VERSION,
    val controls: List<FreePracticeToolbarControlState>,
)

object FreePracticeToolbarSpec {
    const val DESCRIPTOR_VERSION: Int = 6

    val descriptor: FreePracticeToolbarDescriptor = FreePracticeToolbarDescriptor(
        version = DESCRIPTOR_VERSION,
        top = FreePracticeToolbarLayer(
            id = "free-practice.top",
            groups = listOf(
                group("file", "file.new", "file.open", "file.save"),
                group("history", "history.undo", "history.redo"),
                group(
                    "writing",
                    "writing.rewrite", "writing.alternate", "writing.cancel", "writing.auto",
                    "writing.backtrack-count", "writing.replay-count", "writing.tempo",
                    "writing.voice-count", "writing.upper-voice-count", "writing.grid-unit",
                    "writing.default-chord-beats", "writing.initial-key",
                ),
                group("structure", "structure.time-signature", "structure.insert-measures"),
                group(
                    "playback",
                    "playback.from-start", "playback.play-pause", "playback.from-selection",
                    "playback.speed", "playback.audio-settings",
                ),
            ),
        ),
        score = FreePracticeToolbarLayer(
            id = "free-practice.score",
            groups = listOf(
                group("tool", "tool.select", "tool.marquee", "tool.palette-toggle"),
                group("voice", "voice.1", "voice.2", "voice.3", "voice.4"),
                group(
                    "duration",
                    "duration.whole", "duration.half", "duration.quarter", "duration.eighth",
                    "duration.16th", "duration.32nd", "duration.rest", "duration.dot.1",
                    "duration.dot.2", "duration.uncommon-toggle",
                    "duration.breve", "duration.64th", "duration.longa", "duration.maxima",
                    "duration.128th",
                ),
                group(
                    "accidental",
                    "accidental.sharp", "accidental.flat", "accidental.natural",
                    "accidental.double-sharp", "accidental.double-flat",
                ),
                group("curve", "curve.tie", "curve.slur"),
                group("grace", "grace.appoggiatura", "grace.acciaccatura", "grace.small-note"),
                group(
                    "tuplet",
                    "tuplet.suggested.2", "tuplet.suggested.3", "tuplet.suggested.4",
                    "tuplet.suggested.5", "tuplet.suggested.6", "tuplet.suggested.7",
                    "tuplet.suggested.8", "tuplet.suggested.9", "tuplet.custom",
                    "tuplet.confirm",
                ),
                group(
                    "beam",
                    "beam.independent", "beam.both", "beam.right", "beam.left", "beam.group",
                ),
                group(
                    "articulation",
                    "articulation.toggle", "articulation.staccato", "articulation.tenuto",
                    "articulation.accent", "articulation.marcato", "articulation.staccatissimo",
                    expandable = true,
                ),
            ),
        ),
        tokens = FreePracticeToolbarTokens(),
    )

    val topControlIds: List<String> = descriptor.top.groups.flatMap(FreePracticeToolbarGroup::controls)
    val scoreControlIds: List<String> = descriptor.score.groups.flatMap(FreePracticeToolbarGroup::controls)

    private fun group(
        id: String,
        vararg controls: String,
        trailing: Boolean = false,
        expandable: Boolean = false,
    ): FreePracticeToolbarGroup = FreePracticeToolbarGroup(
        id = id,
        controls = controls.toList(),
        trailing = trailing,
        expandable = expandable,
    )
}
