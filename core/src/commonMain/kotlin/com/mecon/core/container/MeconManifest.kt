package com.mecon.core.container

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * The `.mecon` container's top-level index — `manifest.json`.
 *
 * A `.mecon` file is a **zip** of named JSON entries (see
 * `docs/data_model/mecon-container.md`). The manifest is the single source of truth for what the
 * archive holds: the ordered list of scores, the pluggable analysis / exploration / improvisation
 * modules, and workspace-level preferences. Everything else is found through the paths recorded
 * here, so a reader parses this one entry first and then loads exactly what it references.
 *
 * Kept independent of the renderer: geometry lives in separate `geometry/<id>.json` entries and is
 * referenced only by [MeconScoreRef.geometryPath], so a viewer can read geometry without the
 * document model and the document model needs no renderer types.
 *
 * @property formatVersion  Container format version; bumped on incompatible layout changes.
 * @property engineVersion  Build that wrote the file (diagnostics / migration hints).
 * @property createdAt / modifiedAt  Epoch millis; `0` when the writer left them unset.
 * @property scores   Ordered score entries (display / tab order).
 * @property modules  Pluggable module entries (exploration, analysis, improvisation, …).
 * @property activeScoreId  Score to focus on open; null = first score.
 * @property workspace  Workspace / UI preferences that outlive any single score.
 */
@Serializable
data class MeconManifest(
    val formatVersion: Int = MeconFormat.FORMAT_VERSION,
    val engineVersion: String = MeconFormat.ENGINE_VERSION,
    val createdAt: Long = 0L,
    val modifiedAt: Long = 0L,
    val scores: List<MeconScoreRef> = emptyList(),
    val modules: List<MeconModuleRef> = emptyList(),
    val activeScoreId: String? = null,
    val workspace: WorkspacePreferences = WorkspacePreferences(),
)

/**
 * Manifest entry pointing at one score and its optional frozen geometry.
 *
 * @property id    The score's [com.mecon.api.primitive.ScoreId] value (stable key across entries).
 * @property title Display title, duplicated here so a browser/index needn't open the score entry.
 * @property path  Zip path of the score JSON, e.g. `scores/<id>.json`.
 * @property geometryPath Zip path of the frozen-geometry JSON (`geometry/<id>.json`), or null when
 *   the writer stored no geometry for this score.
 */
@Serializable
data class MeconScoreRef(
    val id: String,
    val title: String = "",
    val path: String,
    val geometryPath: String? = null,
)

/**
 * Manifest entry pointing at one pluggable module payload.
 *
 * The container is deliberately agnostic about module *content*: [type] names the module family
 * and its payload schema is owned by that family (see [MeconModuleEntry]). Adding a new module
 * kind — or changing an existing one — needs no change to the container, only a new [type] and its
 * own payload shape.
 *
 * @property id      Stable module id (also the entry file stem).
 * @property type    Module family, e.g. `exploration`, `analysis`, `improvisation`.
 * @property path    Zip path of the module JSON (`modules/<id>.json`).
 * @property scoreId Optional link to the score this module is about; null = workspace-scoped.
 */
@Serializable
data class MeconModuleRef(
    val id: String,
    val type: String,
    val path: String,
    val scoreId: String? = null,
)

/**
 * Workspace-level preferences that belong to the file rather than to any one score (which panel is
 * active, remembered layout choices, …). Deliberately open-ended: [extras] is a free-form JSON bag
 * so UI state can be persisted and evolved without a format change.
 */
@Serializable
data class WorkspacePreferences(
    val activeModuleId: String? = null,
    val extras: JsonObject = JsonObject(emptyMap()),
)
