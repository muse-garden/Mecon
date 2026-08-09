package com.mecon.theory

import kotlin.test.Test
import kotlin.test.assertTrue

class RuleSceneDeclarationTest {
    @Test
    fun everySelectableOrDemonstrableRuleDeclaresAScene() {
        RuleCatalog.allDescriptors()
            .filter { it.selectable || it.demonstrableAsViolation }
            .forEach { descriptor ->
                assertTrue(
                    RuleCatalog.scenes(descriptor.id).isNotEmpty(),
                    "rule ${descriptor.id.value} must declare at least one scene",
                )
            }
    }

    @Test
    fun sceneFacetSlotIndicesStayWithinWindow() {
        allScenes().forEach { scene ->
            scene.facets.forEach { facet ->
                when (facet) {
                    is ChordPattern ->
                        assertTrue(
                            facet.slots.size <= scene.window.last,
                            "${scene.ruleId.value}: pattern length ${facet.slots.size} exceeds window ${scene.window}",
                        )
                    is RootMotion ->
                        assertTrue(
                            scene.window.last >= facet.fromSlot + 2,
                            "${scene.ruleId.value}: RootMotion needs two slots within window ${scene.window}",
                        )
                    is VoiceMotion ->
                        assertTrue(
                            scene.window.last >= facet.fromSlot + 2,
                            "${scene.ruleId.value}: VoiceMotion needs two slots within window ${scene.window}",
                        )
                    is BassMotion ->
                        assertTrue(
                            scene.window.last >= facet.fromSlot + 1,
                            "${scene.ruleId.value}: BassMotion fromSlot outside window ${scene.window}",
                        )
                    is DoublingExpectation ->
                        assertTrue(
                            facet.slot < scene.window.last,
                            "${scene.ruleId.value}: DoublingExpectation slot outside window ${scene.window}",
                        )
                    is KeyContext -> Unit
                }
            }
        }
    }

    @Test
    fun sameChordReferencesAreBackwardOnly() {
        // ChordPattern 的 init 已强制 sameChordAsSlot 无自引用/前向引用；此处对所有已声明场景再走一遍确认不抛异常。
        allScenes().flatMap { it.facets }.filterIsInstance<ChordPattern>().forEach { pattern ->
            pattern.slots.forEachIndexed { index, slot ->
                slot.sameChordAsSlot?.let {
                    assertTrue(it in 0 until index, "sameChordAsSlot must reference an earlier slot")
                }
            }
        }
    }

    private fun allScenes(): List<RuleScene> =
        RuleCatalog.allDescriptors().flatMap { RuleCatalog.scenes(it.id) }
}
