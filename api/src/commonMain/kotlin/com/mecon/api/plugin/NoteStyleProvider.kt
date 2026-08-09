package com.mecon.api.plugin

import com.mecon.api.computed.ComputedScore
import com.mecon.api.interaction.StyleOverride
import com.mecon.api.primitive.EventId

data class NoteStylePatch(
    val upserts: Map<Pair<EventId, Int>, StyleOverride>,
    val removes: Set<Pair<EventId, Int>>,
)

/**
 * SPI for plugins that want to color individual noteheads.
 *
 * [computeStyles] is called by [RenderEngine] after every render pass.
 * The returned map keys are (EventId, pitchIndex) pairs identifying each
 * notehead within a chord; values are the desired [StyleOverride].
 *
 * Implementations live in plugin core modules (no UI dependency).
 * Register via [PluginInstallContext.registerNoteStyleProvider].
 */
interface NoteStyleProvider {
    /**
     * Track types this provider cares about. Return an empty set for full-score
     * computed styles that do not depend on storage plugin tracks.
     */
    val pluginTrackTypes: Set<String>

    /**
     * Compute per-notehead style overrides from the fully-computed score.
     * Return an empty map when there is nothing to color.
     */
    fun computeStyles(computedScore: ComputedScore): Map<Pair<EventId, Int>, StyleOverride>

    /**
     * 相对该 provider 上次输出的差量。null 表示不支持或本次需要回退全量；计算发生在渲染后台
     * dispatcher，patch 应用与 Compose snapshot 发布由引擎串行完成。
     */
    fun computeStylePatch(computedScore: ComputedScore): NoteStylePatch? = null
}
