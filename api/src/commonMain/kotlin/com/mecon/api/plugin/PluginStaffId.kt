package com.mecon.api.plugin

import kotlinx.serialization.Serializable
import kotlin.jvm.JvmInline

/**
 * Stable identifier for an [AnnotationStaff][AnnotationStaffProvider] contributed by a plugin.
 *
 * Plugins should use a namespaced string (e.g. `"mecon.chord_analysis"`) so identifiers
 * are unique across installed plugins.
 */
@Serializable
@JvmInline
value class PluginStaffId(val value: String) {
    override fun toString(): String = value
}
