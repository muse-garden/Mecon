package com.mecon.theory.schoenberg

/** Free-writing F1 does not ship forbidden-transition data. Never silently substitute an empty set. */
internal actual fun loadSchoenbergForbiddenTransitionResource(
    minor: Boolean,
    profile: SchoenbergForbiddenTransitionProfile,
): String? = error(
    "Forbidden-transition data is not available in the free-writing JS build " +
        "(minor=$minor, profile=$profile)"
)
