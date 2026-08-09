package com.mecon.theory.schoenberg

import com.mecon.theory.harmony.HarmonicRuleFamilyId
import com.mecon.theory.harmony.HarmonicTreatment
import com.mecon.theory.harmony.HarmonicTreatmentId
import com.mecon.theory.harmony.HarmonicTreatmentRegistry

object SchoenbergHarmonicTreatments {
    val LOCAL_TONICIZATION_RULES = HarmonicRuleFamilyId("chromatic.local-tonicization")
    val LOWER_TO_OMITTED_ROOT_RULES = HarmonicRuleFamilyId("chromatic.lower-to-omitted-root")
    val ALTERED_TONE_STEP_RULES = HarmonicRuleFamilyId("chromatic.altered-tone-step")
    val MINOR_SUBDOMINANT_VOICE_LEADING_RULES =
        HarmonicRuleFamilyId("chromatic.minor-subdominant-voice-leading")

    val DIATONIC = HarmonicTreatmentId("schoenberg.diatonic")
    val LEADING_TRIAD = HarmonicTreatmentId("schoenberg.leading-triad")
    val FIRST_INVERSION = HarmonicTreatmentId("schoenberg.first-inversion")
    val SECOND_INVERSION = HarmonicTreatmentId("schoenberg.second-inversion")
    val DIATONIC_DOMINANT = HarmonicTreatmentId("schoenberg.diatonic-dominant")
    val DIATONIC_PREDOMINANT = HarmonicTreatmentId("schoenberg.diatonic-predominant")
    val SECONDARY_HARMONY = HarmonicTreatmentId("schoenberg.secondary-harmony")
    val ROOTLESS_DOMINANT_NINTH = HarmonicTreatmentId("schoenberg.rootless-dominant-ninth")
    val MINOR_SUBDOMINANT = HarmonicTreatmentId("schoenberg.minor-subdominant")
    val NEAPOLITAN = HarmonicTreatmentId("schoenberg.neapolitan")
    val VAGRANT_CHORD = HarmonicTreatmentId("schoenberg.vagrant-chord")
    val AUGMENTED_SIXTH = HarmonicTreatmentId("schoenberg.augmented-sixth")

    val integratedDiatonicTreatments: Set<HarmonicTreatmentId> = setOf(
        LEADING_TRIAD,
        FIRST_INVERSION,
        SECOND_INVERSION,
        DIATONIC_DOMINANT,
    )

    val integratedSecondaryTreatments: Set<HarmonicTreatmentId> =
        integratedDiatonicTreatments + SECONDARY_HARMONY

    val integratedDiminishedTreatments: Set<HarmonicTreatmentId> =
        integratedSecondaryTreatments + ROOTLESS_DOMINANT_NINTH

    val integratedFrontierTreatments: Set<HarmonicTreatmentId> =
        integratedDiminishedTreatments + AUGMENTED_SIXTH + VAGRANT_CHORD

    val registry = HarmonicTreatmentRegistry(
        listOf(
            HarmonicTreatment(id = DIATONIC),
            HarmonicTreatment(id = LEADING_TRIAD, references = setOf(DIATONIC)),
            HarmonicTreatment(id = FIRST_INVERSION, references = setOf(DIATONIC)),
            HarmonicTreatment(id = SECOND_INVERSION, references = setOf(DIATONIC)),
            HarmonicTreatment(
                id = DIATONIC_DOMINANT,
                references = setOf(DIATONIC),
                ruleFamilies = setOf(
                    HarmonicRuleFamilyId("dominant.leading-tone-resolution"),
                    HarmonicRuleFamilyId("dominant.seventh-preparation-resolution"),
                ),
            ),
            HarmonicTreatment(
                id = DIATONIC_PREDOMINANT,
                references = setOf(DIATONIC),
            ),
            HarmonicTreatment(
                id = SECONDARY_HARMONY,
                references = setOf(DIATONIC_DOMINANT),
                ruleFamilies = setOf(
                    LOCAL_TONICIZATION_RULES,
                ),
            ),
            HarmonicTreatment(
                id = ROOTLESS_DOMINANT_NINTH,
                references = setOf(SECONDARY_HARMONY),
                substitutesFor = setOf(DIATONIC_DOMINANT),
                ruleFamilies = setOf(
                    LOWER_TO_OMITTED_ROOT_RULES,
                    ALTERED_TONE_STEP_RULES,
                ),
            ),
            HarmonicTreatment(
                id = MINOR_SUBDOMINANT,
                references = setOf(DIATONIC),
                ruleFamilies = setOf(MINOR_SUBDOMINANT_VOICE_LEADING_RULES),
            ),
            HarmonicTreatment(
                id = NEAPOLITAN,
                references = setOf(MINOR_SUBDOMINANT),
                substitutesFor = setOf(DIATONIC_PREDOMINANT),
            ),
            HarmonicTreatment(id = VAGRANT_CHORD),
            HarmonicTreatment(
                id = AUGMENTED_SIXTH,
                references = setOf(SECONDARY_HARMONY, VAGRANT_CHORD),
                substitutesFor = setOf(DIATONIC_PREDOMINANT),
                ruleFamilies = setOf(ALTERED_TONE_STEP_RULES),
            ),
        )
    )
}
