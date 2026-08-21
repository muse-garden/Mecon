package com.mecon.desktop

/** Process seams that must sit outside the Compose window tree. */
object DesktopApplicationLifecycle {
    @Volatile
    private var closeHandler: ((() -> Unit) -> Unit)? = null
    @Volatile
    private var emergencyRecovery: (() -> Unit)? = null

    fun install(close: ((() -> Unit) -> Unit), recover: () -> Unit) {
        closeHandler = close
        emergencyRecovery = recover
    }

    fun clear() {
        closeHandler = null
        emergencyRecovery = null
    }

    fun requestClose(exitApplication: () -> Unit) {
        closeHandler?.invoke(exitApplication) ?: exitApplication()
    }

    fun attemptEmergencyRecovery() {
        emergencyRecovery?.invoke()
    }
}
