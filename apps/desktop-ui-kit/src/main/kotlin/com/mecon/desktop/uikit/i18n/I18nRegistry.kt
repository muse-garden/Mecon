package com.mecon.desktop.uikit.i18n

/**
 * Plugin-extensible translation registry. The desktop app registers its built-in
 * bundles at startup; plugins register their own. [i18n] consults the registry
 * for the current [Language] and returns the key unchanged when no translation
 * exists.
 */
object I18nRegistry {

    private val bundles: MutableMap<Language, MutableMap<String, String>> = mutableMapOf()
    private var currentLanguage: Language = Language.CHINESE

    fun setLanguage(language: Language) {
        currentLanguage = language
    }

    fun getCurrentLanguage(): Language = currentLanguage

    /** Register or merge a translation bundle for [language]. Later wins on key conflicts. */
    fun register(language: Language, strings: Map<String, String>) {
        bundles.getOrPut(language) { mutableMapOf() }.putAll(strings)
    }

    fun get(key: String): String =
        bundles[currentLanguage]?.get(key) ?: key
}

/** Convenience accessor — equivalent to [I18nRegistry.get]. */
fun i18n(key: String): String = I18nRegistry.get(key)
