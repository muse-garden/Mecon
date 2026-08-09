package com.mecon.core.container

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * One pluggable module payload stored at `modules/<id>.json` inside a `.mecon` container.
 *
 * The container carries modules **opaquely**: [payload] is an arbitrary [JsonElement], so each
 * module family (exploration, analysis, improvisation, and whatever comes next) owns and evolves
 * its own payload schema without ever touching the container code. A reader that doesn't recognise
 * a [type] can still round-trip the entry untouched, so unknown/future modules survive a
 * load-then-save cycle instead of being dropped.
 *
 * @property id            Stable id; matches [MeconModuleRef.id] and the entry file stem.
 * @property type          Module family (e.g. `exploration`); selects who interprets [payload].
 * @property schemaVersion Version of *this module family's* payload schema (module-owned).
 * @property scoreId       Optional link to the score this module concerns; null = workspace-scoped.
 * @property payload       Module-specific content; interpreted only by the owning module family.
 */
@Serializable
data class MeconModuleEntry(
    val id: String,
    val type: String,
    val schemaVersion: Int = 1,
    val scoreId: String? = null,
    val payload: JsonElement = JsonObject(emptyMap()),
)
