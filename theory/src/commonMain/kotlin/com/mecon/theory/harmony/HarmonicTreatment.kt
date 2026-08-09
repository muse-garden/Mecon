package com.mecon.theory.harmony

import kotlin.jvm.JvmInline

@JvmInline
value class HarmonicRuleFamilyId(val value: String) {
    init {
        require(value.isNotBlank())
    }
}

/**
 * Declarative composition for phrases such as "use dominant progressions as reference",
 * "substitute for V7", and "add smooth altered-tone motion".
 */
data class HarmonicTreatment(
    val id: HarmonicTreatmentId,
    val references: Set<HarmonicTreatmentId> = emptySet(),
    val substitutesFor: Set<HarmonicTreatmentId> = emptySet(),
    val ruleFamilies: Set<HarmonicRuleFamilyId> = emptySet(),
)

data class ResolvedHarmonicTreatment(
    val requestedIds: Set<HarmonicTreatmentId>,
    val includedTreatmentIds: Set<HarmonicTreatmentId>,
    val substitutionTargets: Set<HarmonicTreatmentId>,
    val ruleFamilies: Set<HarmonicRuleFamilyId>,
)

class HarmonicTreatmentRegistry(
    treatments: Iterable<HarmonicTreatment>,
) {
    private val byId = treatments.associateBy(HarmonicTreatment::id)

    init {
        require(byId.size == treatments.count()) { "Harmonic treatment ids must be unique" }
        byId.values.forEach { treatment ->
            val dependencies = treatment.references + treatment.substitutesFor
            require(dependencies.all(byId::containsKey)) {
                "Treatment ${treatment.id.value} refers to an unregistered treatment"
            }
        }
    }

    fun resolve(ids: Set<HarmonicTreatmentId>): ResolvedHarmonicTreatment {
        require(ids.all(byId::containsKey)) { "Unknown harmonic treatment in $ids" }
        val included = linkedSetOf<HarmonicTreatmentId>()
        val substitutions = linkedSetOf<HarmonicTreatmentId>()
        val visiting = linkedSetOf<HarmonicTreatmentId>()

        fun visit(id: HarmonicTreatmentId) {
            if (id in included) return
            check(visiting.add(id)) { "Harmonic treatment cycle at ${id.value}" }
            val treatment = byId.getValue(id)
            treatment.references.forEach(::visit)
            treatment.substitutesFor.forEach { substituted ->
                substitutions += substituted
                visit(substituted)
            }
            visiting -= id
            included += id
        }

        ids.forEach(::visit)
        return ResolvedHarmonicTreatment(
            requestedIds = ids,
            includedTreatmentIds = included,
            substitutionTargets = substitutions,
            ruleFamilies = included.flatMapTo(linkedSetOf()) { byId.getValue(it).ruleFamilies },
        )
    }
}
