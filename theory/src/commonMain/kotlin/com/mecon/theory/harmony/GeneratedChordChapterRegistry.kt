package com.mecon.theory.harmony

import com.mecon.theory.schoenberg.SchoenbergAugmentedSixthChapter
import com.mecon.theory.schoenberg.SchoenbergDiminishedSeventhChapter
import com.mecon.theory.schoenberg.SchoenbergMinorSubdominantChapter
import com.mecon.theory.schoenberg.SchoenbergRootPositionConnections
import com.mecon.theory.schoenberg.SchoenbergSecondaryDominantChapter
import com.mecon.theory.schoenberg.SchoenbergSeventhChordChapter

/**
 * Compile-time chapter registry shared by JVM and JS.
 *
 * Provider objects are declared once here instead of being rediscovered with platform reflection.
 * Plugin environments may still inject their own provider list at catalog construction boundaries.
 */
internal object GeneratedChordChapterRegistry {
    val catalogProviders: List<ChordCatalogChapterProvider> = listOf(
        SchoenbergAugmentedSixthChapter,
        SchoenbergDiminishedSeventhChapter,
        SchoenbergMinorSubdominantChapter,
        SchoenbergRootPositionConnections,
        SchoenbergSecondaryDominantChapter,
        SchoenbergSeventhChordChapter,
    )

    val knowledgeProviders: List<ChordKnowledgeChapterProvider> = listOf(
        SchoenbergAugmentedSixthChapter,
        SchoenbergDiminishedSeventhChapter,
        SchoenbergMinorSubdominantChapter,
        SchoenbergRootPositionConnections,
        SchoenbergSecondaryDominantChapter,
    )
}
