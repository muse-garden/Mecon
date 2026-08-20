@file:OptIn(ExperimentalJsExport::class)

package com.mecon.web

import com.mecon.core.serializer.ScoreSerializer
import com.mecon.features.scoreediting.ScoreEditCodec
import com.mecon.features.scoreediting.ScoreEditingSession
import com.mecon.features.scoreediting.ScoreInteractionCatalog

/** Worker-friendly string-only facade around the shared score-editing state machine. */
@JsExport
class MeconScoreEditor(scoreJson: String) {
    private var session: ScoreEditingSession? = ScoreEditingSession.open(
        ScoreSerializer.fromJson(scoreJson),
    )

    fun initialUpdateJson(): String = ScoreEditCodec.encodeUpdate(
        requireSession().initialUpdate(),
    )

    /** Stable UI-neutral interaction families; Web must not duplicate this classification. */
    fun interactionCatalogJson(): String = ScoreInteractionCatalog.encodeSpecs()

    fun dispatchJson(intentJson: String): String {
        val active = requireSession()
        return ScoreEditCodec.encodeUpdate(
            active.dispatch(ScoreEditCodec.decodeIntent(intentJson)).toWireUpdate(),
        )
    }

    fun close() {
        session = null
    }

    private fun requireSession(): ScoreEditingSession =
        checkNotNull(session) { "MeconScoreEditor is closed" }
}
