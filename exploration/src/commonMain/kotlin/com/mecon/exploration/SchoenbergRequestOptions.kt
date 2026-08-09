package com.mecon.exploration

import com.mecon.theory.schoenberg.SchoenbergExerciseOption
internal fun SchoenbergExerciseRequest.enabledSchoenbergOptions(): Set<SchoenbergExerciseOption> =
    buildSet {
        if (includeDeceptiveCadence) add(SchoenbergExerciseOption.DECEPTIVE_CADENCE)
        if (includeCadentialSixFour) add(SchoenbergExerciseOption.CADENTIAL_SIX_FOUR)
    }

internal fun EnumerationRequest.enabledSchoenbergOptions(): Set<SchoenbergExerciseOption> =
    buildSet {
        if (includeDeceptiveCadence) add(SchoenbergExerciseOption.DECEPTIVE_CADENCE)
        if (includeCadentialSixFour) add(SchoenbergExerciseOption.CADENTIAL_SIX_FOUR)
    }
