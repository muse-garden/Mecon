package com.mecon.theory.schoenberg

private const val MAJOR_RESOURCE_PATH = "/schoenberg/forbidden-transitions.txt"
private const val MINOR_RESOURCE_PATH = "/schoenberg/forbidden-transitions-minor.txt"
private const val FREE_MAJOR_RESOURCE_PATH = "/schoenberg/forbidden-transitions-free.txt"
private const val FREE_MINOR_RESOURCE_PATH = "/schoenberg/forbidden-transitions-free-minor.txt"

internal actual fun loadSchoenbergForbiddenTransitionResource(
    minor: Boolean,
    profile: SchoenbergForbiddenTransitionProfile,
): String? =
    object {}.javaClass.getResourceAsStream(
        when (profile) {
            SchoenbergForbiddenTransitionProfile.STRICT ->
                if (minor) MINOR_RESOURCE_PATH else MAJOR_RESOURCE_PATH
            SchoenbergForbiddenTransitionProfile.FREE ->
                if (minor) FREE_MINOR_RESOURCE_PATH else FREE_MAJOR_RESOURCE_PATH
        }
    )
        ?.bufferedReader(Charsets.UTF_8)
        ?.use { it.readText() }
