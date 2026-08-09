package com.mecon.renderer.smufl

import com.mecon.renderer.geometry.StaffSpace
import kotlinx.serialization.Serializable

/**
 * Engraving defaults from SMuFL font metadata.
 * All values are in staff spaces.
 */
@Serializable
data class EngravingDefaults(
    val arrowShaftThickness: StaffSpace = StaffSpace(0.16f),
    val barlineSeparation: StaffSpace = StaffSpace(0.4f),
    val beamSpacing: StaffSpace = StaffSpace(0.25f),
    val beamThickness: StaffSpace = StaffSpace(0.5f),
    val bracketThickness: StaffSpace = StaffSpace(0.5f),
    val dashedBarlineDashLength: StaffSpace = StaffSpace(0.5f),
    val dashedBarlineGapLength: StaffSpace = StaffSpace(0.25f),
    val dashedBarlineThickness: StaffSpace = StaffSpace(0.16f),
    val hBarThickness: StaffSpace = StaffSpace(1.0f),
    val hairpinThickness: StaffSpace = StaffSpace(0.16f),
    val legerLineExtension: StaffSpace = StaffSpace(0.4f),
    val legerLineThickness: StaffSpace = StaffSpace(0.16f),
    val lyricLineThickness: StaffSpace = StaffSpace(0.16f),
    val octaveLineThickness: StaffSpace = StaffSpace(0.16f),
    val pedalLineThickness: StaffSpace = StaffSpace(0.16f),
    val repeatBarlineDotSeparation: StaffSpace = StaffSpace(0.16f),
    val repeatEndingLineThickness: StaffSpace = StaffSpace(0.16f),
    val slurEndpointThickness: StaffSpace = StaffSpace(0.1f),
    val slurMidpointThickness: StaffSpace = StaffSpace(0.22f),
    val staffLineThickness: StaffSpace = StaffSpace(0.13f),
    val stemThickness: StaffSpace = StaffSpace(0.12f),
    val subBracketThickness: StaffSpace = StaffSpace(0.16f),
    val textEnclosureThickness: StaffSpace = StaffSpace(0.16f),
    val thickBarlineThickness: StaffSpace = StaffSpace(0.5f),
    val thinBarlineThickness: StaffSpace = StaffSpace(0.16f),
    val tieEndpointThickness: StaffSpace = StaffSpace(0.1f),
    val tieMidpointThickness: StaffSpace = StaffSpace(0.22f),
    val tupletBracketThickness: StaffSpace = StaffSpace(0.16f)
) {
    companion object {
        /**
         * Default Bravura engraving defaults.
         */
        val BRAVURA = EngravingDefaults()
    }
}
