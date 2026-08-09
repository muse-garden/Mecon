package com.mecon.audio

/** The library used as the first choice for ordinary pitched-instrument playback. */
enum class DefaultTimbreLibrary {
    RHODY,
    MS_BASIC;

    companion object {
        const val SYSTEM_PROPERTY = "mecon.audio.defaultLibrary"
        const val ENVIRONMENT_VARIABLE = "MECON_AUDIO_DEFAULT_LIBRARY"

        /**
         * Resolve the headless v1 setting. Rhody is the default when no setting is supplied;
         * [JvmAudioEngine] transparently falls back to MS Basic when its optional library is absent.
         */
        fun fromSystem(
            property: String? = System.getProperty(SYSTEM_PROPERTY),
            environment: String? = System.getenv(ENVIRONMENT_VARIABLE),
        ): DefaultTimbreLibrary = parse(property ?: environment) ?: RHODY

        fun parse(value: String?): DefaultTimbreLibrary? = when (
            value?.trim()?.lowercase()?.replace('_', '-')?.replace(' ', '-')
        ) {
            "rhody" -> RHODY
            "ms-basic", "msbasic" -> MS_BASIC
            else -> null
        }
    }
}
