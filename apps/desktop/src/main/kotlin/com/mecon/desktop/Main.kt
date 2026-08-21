package com.mecon.desktop

import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.mecon.desktop.bootstrap.bootstrapPlugins
import com.mecon.desktop.i18n.BuiltinStrings
import com.mecon.desktop.i18n.ExplorationStrings
import com.mecon.desktop.uikit.i18n.I18nRegistry
import com.mecon.desktop.input.GlobalShortcutDispatcher
import com.mecon.desktop.uikit.components.MeconTextInputFocus

fun main() {
    val previousUncaughtHandler = Thread.getDefaultUncaughtExceptionHandler()
    Thread.setDefaultUncaughtExceptionHandler { thread, error ->
        runCatching { DesktopApplicationLifecycle.attemptEmergencyRecovery() }
            .onFailure(Throwable::printStackTrace)
        if (previousUncaughtHandler != null) previousUncaughtHandler.uncaughtException(thread, error)
        else error.printStackTrace()
    }
    // Opt-in perf tracing of the edit → compute → render pipeline. Off by default; enable with
    // -Dmecon.perf=true or MECON_PERF=1. Logs to stdout, tagged [perf][...] (see PerfLog).
    com.mecon.renderer.debug.PerfLog.enabled =
        System.getProperty("mecon.perf")?.lowercase() in setOf("true", "1", "on") ||
        System.getenv("MECON_PERF")?.lowercase() in setOf("true", "1", "on")
    BuiltinStrings.install()
    ExplorationStrings.install()
    I18nRegistry.setLanguage(AppSettings.language)
    bootstrapPlugins()
    application {
        Window(
            onCloseRequest = { DesktopApplicationLifecycle.requestClose(::exitApplication) },
            title = "Mecon - Music Analysis",
            state = rememberWindowState(width = 1400.dp, height = 900.dp),
            onPreviewKeyEvent = { event ->
                if (MeconTextInputFocus.hasFocus) false else GlobalShortcutDispatcher.dispatch(event)
            },
        ) {
            App()
        }
    }
}
