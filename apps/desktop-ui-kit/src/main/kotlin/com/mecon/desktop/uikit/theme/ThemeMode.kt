package com.mecon.desktop.uikit.theme

/** Which role table [MeconColors] currently resolves through. Persisted via `AppSettings.themeMode`. */
enum class ThemeMode(val code: String) {
    DARK("dark"),
    LIGHT("light");

    companion object {
        fun fromCode(code: String): ThemeMode = entries.firstOrNull { it.code == code } ?: DARK
    }
}
