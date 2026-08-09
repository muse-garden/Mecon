package com.mecon.desktop.i18n

import com.mecon.desktop.i18n.en.BuiltinStrings as EnglishBuiltinStrings
import com.mecon.desktop.i18n.zh.BuiltinStrings as ChineseBuiltinStrings
import com.mecon.desktop.uikit.i18n.I18nRegistry
import com.mecon.desktop.uikit.i18n.Language

/**
 * Built-in translation bundle for the desktop shell. Registered with
 * [I18nRegistry] during bootstrap. Plugin modules register their own bundles
 * separately.
 */
object BuiltinStrings {

    /** Register all built-in strings with the global [I18nRegistry]. */
    fun install() {
        I18nRegistry.register(Language.CHINESE, ChineseBuiltinStrings.values)
        I18nRegistry.register(Language.ENGLISH, EnglishBuiltinStrings.values)
    }
}