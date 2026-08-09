package com.mecon.core.serializer

import com.mecon.api.storage.*

import com.charleskorn.kaml.PolymorphismStyle
import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlConfiguration
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.EmptySerializersModule
import kotlinx.serialization.modules.SerializersModule

/**
 * Score serialization utilities.
 * Supports both YAML and JSON formats.
 *
 * Plugins extend [StoragePluginEvent][com.mecon.api.storage.events.StoragePluginEvent]
 * and register their serializers via a [SerializersModule] at app startup
 * by calling [installSerializersModule]. Must run before any encode/decode.
 */
object ScoreSerializer {

    private var serializersModule: SerializersModule = EmptySerializersModule()

    private var yaml = buildYaml()
    private var json = buildJson(pretty = false)
    private var prettyJson = buildJson(pretty = true)

    /**
     * Install (replace) the [SerializersModule] used for polymorphic plugin events.
     * Call once during bootstrap, after all plugins have registered their event serializers.
     */
    fun installSerializersModule(module: SerializersModule) {
        serializersModule = module
        yaml = buildYaml()
        json = buildJson(pretty = false)
        prettyJson = buildJson(pretty = true)
    }

    private fun buildYaml(): Yaml = Yaml(
        serializersModule = serializersModule,
        configuration = YamlConfiguration(
            encodeDefaults = false,
            strictMode = false,
            polymorphismStyle = PolymorphismStyle.Property
        )
    )

    private fun buildJson(pretty: Boolean): Json = Json {
        prettyPrint = pretty
        ignoreUnknownKeys = true
        encodeDefaults = false
        serializersModule = this@ScoreSerializer.serializersModule
    }

    // ========== YAML ==========

    /**
     * Serialize score to YAML string.
     */
    fun toYaml(score: StorageScore): String =
        yaml.encodeToString(StorageScore.serializer(), score.migrateReductionWorkspaces())

    /**
     * Deserialize score from YAML string.
     */
    fun fromYaml(yamlString: String): StorageScore =
        yaml.decodeFromString(StorageScore.serializer(), yamlString).migrateReductionWorkspaces()

    // ========== JSON ==========

    /**
     * Serialize score to JSON string.
     */
    fun toJson(score: StorageScore, pretty: Boolean = false): String =
        if (pretty) {
            prettyJson.encodeToString(StorageScore.serializer(), score.migrateReductionWorkspaces())
        } else {
            json.encodeToString(StorageScore.serializer(), score.migrateReductionWorkspaces())
        }

    /**
     * Deserialize score from JSON string.
     */
    fun fromJson(jsonString: String): StorageScore =
        json.decodeFromString(StorageScore.serializer(), jsonString).migrateReductionWorkspaces()

    // ========== File extension helpers ==========

    enum class Format {
        YAML, JSON
    }

    /**
     * Detect format from file extension.
     */
    fun detectFormat(filename: String): Format =
        when {
            filename.endsWith(".yaml", ignoreCase = true) ||
            filename.endsWith(".yml", ignoreCase = true) -> Format.YAML
            filename.endsWith(".json", ignoreCase = true) -> Format.JSON
            else -> Format.YAML  // Default to YAML
        }

    /**
     * Serialize score to string based on format.
     */
    fun serialize(score: StorageScore, format: Format, pretty: Boolean = true): String =
        when (format) {
            Format.YAML -> toYaml(score)
            Format.JSON -> toJson(score, pretty)
        }

    /**
     * Deserialize score from string based on format.
     */
    fun deserialize(content: String, format: Format): StorageScore =
        when (format) {
            Format.YAML -> fromYaml(content)
            Format.JSON -> fromJson(content)
        }
}
