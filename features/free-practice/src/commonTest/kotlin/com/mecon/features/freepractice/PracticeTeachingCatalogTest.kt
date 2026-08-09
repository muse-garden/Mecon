package com.mecon.features.freepractice

import com.mecon.theory.KeySignatureMode
import com.mecon.theory.ModulationKey
import com.mecon.theory.freepractice.WorkspaceKeyMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PracticeTeachingCatalogTest {
    @Test
    fun offKeyFocusDoesNotPolluteTheDefaultTeachingVariants() {
        val workspace = FreePracticePreset.workspace(
            voiceCount = 4,
            initialKey = ModulationKey(0, KeySignatureMode.MAJOR),
        )
        val focus = requireNotNull(workspace.slots.single().chordChoice)
        fun request(includeOffKey: Boolean) = PracticeTeachingCatalogRequest(
            requestId = if (includeOffKey) 2 else 1,
            baseRevision = 0,
            fingerprint = includeOffKey.toString(),
            initialKey = PracticeKeyView(0, WorkspaceKeyMode.MAJOR),
            activeKeys = listOf(PracticeKeyView(0, WorkspaceKeyMode.MAJOR)),
            catalogKey = PracticeKeyView(0, WorkspaceKeyMode.MAJOR),
            focus = focus,
            includeOffKey = includeOffKey,
        )

        val local = PracticeTeachingCatalogExecutor.execute(request(false))
        val offKey = PracticeTeachingCatalogExecutor.execute(request(true))
        fun PracticeTeachingCatalogResult.defaultMembers() = definitions.flatMap { definition ->
            definition.variants.filter { it.availableByDefault }.map { definition.id to it.id }
        }.toSet()

        assertEquals(local.defaultMembers(), offKey.defaultMembers())
        assertTrue(offKey.definitions.any { definition ->
            definition.variants.any { it.relatedToFocus } &&
                definition.variants.any { !it.relatedToFocus }
        })
        offKey.definitions.forEach { definition ->
            assertEquals(
                definition.variants.any { it.relatedToFocus },
                definition.relatedToFocus,
            )
            assertEquals(
                definition.variants.any { it.availableByDefault },
                definition.availableByDefault,
            )
        }
    }
}
