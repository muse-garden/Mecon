package com.mecon.renderer.render

import com.mecon.renderer.layout.RenderLayoutConfig
import com.mecon.renderer.render.edit.GhostClefComputer
import com.mecon.renderer.render.edit.GhostExpressionSpanComputer
import com.mecon.renderer.render.edit.GhostKeySignatureComputer
import com.mecon.renderer.render.edit.GhostNoteComputer
import com.mecon.renderer.render.edit.GhostTimeSignatureComputer
import com.mecon.renderer.render.edit.RestMovePreviewComputer
import com.mecon.renderer.render.edit.TransposePreviewComputer
import com.mecon.renderer.smufl.BravuraFont

context(BravuraFont)
internal class RenderEditPreviewFacade(config: RenderLayoutConfig) {
    val note = GhostNoteComputer(config)
    val clef = GhostClefComputer(config)
    val keySignature = GhostKeySignatureComputer(config)
    val timeSignature = GhostTimeSignatureComputer(config)
    val expressionSpan = GhostExpressionSpanComputer(config)
    val transpose = TransposePreviewComputer(config)
    val restMove = RestMovePreviewComputer(config)
}
