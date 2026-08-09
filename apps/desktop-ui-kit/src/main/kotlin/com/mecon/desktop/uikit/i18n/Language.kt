package com.mecon.desktop.uikit.i18n

/**
 * Supported UI languages.
 */
enum class Language(val code: String, val displayName: String) {
    CHINESE("zh", "简体中文"),
    ENGLISH("en", "English");

    companion object {
        fun fromCode(code: String): Language =
            entries.firstOrNull { it.code == code } ?: CHINESE
    }
}
