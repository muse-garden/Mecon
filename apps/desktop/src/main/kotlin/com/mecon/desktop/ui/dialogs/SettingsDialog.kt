package com.mecon.desktop.ui.dialogs

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.mecon.desktop.AppSettings
import com.mecon.desktop.input.KeyStroke
import com.mecon.desktop.input.KeybindingStore
import com.mecon.desktop.input.ShortcutAction
import com.mecon.desktop.input.ShortcutCategory
import com.mecon.desktop.input.isModifierKey
import com.mecon.desktop.uikit.i18n.Language
import com.mecon.desktop.uikit.i18n.i18n
import com.mecon.desktop.uikit.theme.MeconColors
import com.mecon.desktop.uikit.theme.ThemeMode
import java.io.File
import javax.swing.JFileChooser

// Getters, not cached vals — MeconColors roles are reactive `mutableStateOf`s that change
// when the user switches skin; a plain `val` would freeze at class-init time and never update.
private val PanelBg get() = MeconColors.Surface
private val FieldBg get() = MeconColors.Background
private val RowBg get() = MeconColors.Background
private val Accent get() = MeconColors.Primary
private val Muted get() = MeconColors.TextSecondary
private val Faint get() = MeconColors.TextMuted

/**
 * Unified preferences dialog. Hosts the general options (UI scale + language,
 * relocated from the toolbar) and a fully rebindable keyboard-shortcut editor
 * backed by [KeybindingStore].
 */
@Composable
fun SettingsDialog(
    uiScale: Float,
    onUiScaleChange: (Float) -> Unit,
    currentLanguage: Language,
    onLanguageChange: (Language) -> Unit,
    currentThemeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
    autosaveDirectory: File,
    onAutosaveDirectoryChange: (File) -> Unit,
    onOpenRecoveryCenter: () -> Unit,
    onDismiss: () -> Unit,
) {
    var selectedTab by remember { mutableStateOf(0) }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier.width(560.dp).heightIn(max = 680.dp),
            shape = RoundedCornerShape(12.dp),
            color = PanelBg,
            tonalElevation = 8.dp,
        ) {
            Column(modifier = Modifier.padding(24.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = i18n("dialog.settings.title"),
                        style = MaterialTheme.typography.headlineSmall,
                        color = MeconColors.TextPrimary,
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = MeconColors.IconDefault)
                    }
                }

                Spacer(Modifier.height(16.dp))

                TabRow(
                    selectedTabIndex = selectedTab,
                    containerColor = FieldBg,
                    contentColor = MeconColors.TextPrimary,
                ) {
                    Tab(
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 },
                        text = { Text(i18n("dialog.settings.tab.general")) },
                    )
                    Tab(
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 },
                        text = { Text(i18n("dialog.settings.tab.shortcuts")) },
                    )
                    Tab(
                        selected = selectedTab == 2,
                        onClick = { selectedTab = 2 },
                        text = { Text(i18n("dialog.settings.tab.files")) },
                    )
                }

                Spacer(Modifier.height(16.dp))

                Box(modifier = Modifier.weight(1f)) {
                    when (selectedTab) {
                        0 -> GeneralTab(
                            uiScale = uiScale,
                            onUiScaleChange = onUiScaleChange,
                            currentLanguage = currentLanguage,
                            onLanguageChange = onLanguageChange,
                            currentThemeMode = currentThemeMode,
                            onThemeModeChange = onThemeModeChange,
                        )
                        1 -> ShortcutsTab()
                        2 -> FileSafetyTab(
                            autosaveDirectory = autosaveDirectory,
                            onAutosaveDirectoryChange = onAutosaveDirectoryChange,
                            onOpenRecoveryCenter = onOpenRecoveryCenter,
                        )
                    }
                }

                Spacer(Modifier.height(16.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    Button(
                        onClick = onDismiss,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Accent,
                            contentColor = Color.White,
                        ),
                    ) {
                        Text(i18n("dialog.settings.close"))
                    }
                }
            }
        }
    }
}

@Composable
private fun FileSafetyTab(
    autosaveDirectory: File,
    onAutosaveDirectoryChange: (File) -> Unit,
    onOpenRecoveryCenter: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionLabel(i18n("dialog.settings.autosaveDirectory"))
        Text(
            i18n("dialog.settings.autosaveDescription"),
            color = Muted,
            fontSize = 12.sp,
        )
        Surface(color = FieldBg, shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    autosaveDirectory.absolutePath,
                    color = MeconColors.TextPrimary,
                    fontSize = 12.sp,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = {
                    chooseAutosaveDirectory(autosaveDirectory)?.let(onAutosaveDirectoryChange)
                }) {
                    Text(i18n("dialog.settings.chooseDirectory"))
                }
            }
        }
        OutlinedButton(onClick = onOpenRecoveryCenter) {
            Text(i18n("dialog.settings.manageRecovery"))
        }
    }
}

private fun chooseAutosaveDirectory(initial: File): File? {
    val chooser = JFileChooser(initial.takeIf(File::isDirectory)).apply {
        dialogTitle = "Mecon autosave directory"
        fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
        isAcceptAllFileFilterUsed = false
    }
    return if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) chooser.selectedFile else null
}

@Composable
private fun GeneralTab(
    uiScale: Float,
    onUiScaleChange: (Float) -> Unit,
    currentLanguage: Language,
    onLanguageChange: (Language) -> Unit,
    currentThemeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
        // UI scale stepper
        SectionLabel(i18n("dialog.settings.uiScale"))
        Surface(color = FieldBg, shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StepButton("A−") { onUiScaleChange(AppSettings.stepDown(uiScale)) }
                Text(
                    "${(uiScale * 100).toInt()}%",
                    color = MeconColors.TextPrimary,
                    fontWeight = FontWeight.Medium,
                    fontSize = 15.sp,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
                StepButton("A+") { onUiScaleChange(AppSettings.stepUp(uiScale)) }
            }
        }

        // Language selector
        SectionLabel(i18n("dialog.settings.language"))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Language.entries.forEach { language ->
                val selected = language == currentLanguage
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            color = if (selected) MeconColors.SelectedSurface else FieldBg,
                            shape = RoundedCornerShape(8.dp),
                        )
                        .clickable { if (!selected) onLanguageChange(language) }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column {
                        Text(
                            language.displayName,
                            color = if (selected) MeconColors.SelectedIcon else MeconColors.IconDefault,
                            fontSize = 14.sp
                        )
                        Text(language.code, color = Faint, fontSize = 11.sp)
                    }
                    if (selected) {
                        Icon(Icons.Default.Check, contentDescription = null, tint = MeconColors.SelectedIcon)
                    }
                }
            }
        }

        // Theme / skin selector
        SectionLabel(i18n("dialog.settings.theme"))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ThemeMode.entries.forEach { mode ->
                val selected = mode == currentThemeMode
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            color = if (selected) MeconColors.SelectedSurface else FieldBg,
                            shape = RoundedCornerShape(8.dp),
                        )
                        .clickable { if (!selected) onThemeModeChange(mode) }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        i18n(if (mode == ThemeMode.LIGHT) "dialog.settings.theme.light" else "dialog.settings.theme.dark"),
                        color = if (selected) MeconColors.SelectedIcon else MeconColors.IconDefault,
                        fontSize = 14.sp
                    )
                    if (selected) {
                        Icon(Icons.Default.Check, contentDescription = null, tint = MeconColors.SelectedIcon)
                    }
                }
            }
        }
    }
}

@Composable
private fun ShortcutsTab() {
    // The action currently awaiting a key press, or null when not capturing.
    var capturing by remember { mutableStateOf<ShortcutAction?>(null) }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(
                    onClick = { KeybindingStore.resetAll() },
                    colors = ButtonDefaults.textButtonColors(contentColor = MeconColors.IconDefault),
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(i18n("dialog.settings.resetAll"), fontSize = 13.sp)
                }
            }

            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                ShortcutCategory.entries.forEach { category ->
                    item(key = category.name) {
                        Text(
                            text = i18n(category.labelKey),
                            color = Faint,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
                        )
                    }
                    items(
                        ShortcutAction.entries.filter { it.category == category },
                        key = { it.name },
                    ) { action ->
                        ShortcutRow(
                            action = action,
                            onRebind = { capturing = action },
                        )
                    }
                }
            }
        }

        capturing?.let { action ->
            KeyCaptureOverlay(
                action = action,
                onCaptured = { stroke ->
                    KeybindingStore.setBinding(action, stroke)
                    capturing = null
                },
                onCancel = { capturing = null },
            )
        }
    }
}

@Composable
private fun ShortcutRow(action: ShortcutAction, onRebind: () -> Unit) {
    val stroke = KeybindingStore.binding(action)
    Surface(color = RowBg, shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = i18n(action.labelKey),
                color = MeconColors.TextPrimary,
                fontSize = 14.sp,
                modifier = Modifier.weight(1f),
            )
            // Current chord chip
            Box(
                modifier = Modifier
                    .background(FieldBg, RoundedCornerShape(6.dp))
                    .border(1.dp, MeconColors.SurfaceLight, RoundedCornerShape(6.dp))
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            ) {
                Text(
                    text = stroke?.displayLabel() ?: i18n("dialog.settings.unbound"),
                    color = if (stroke != null) MeconColors.TextPrimary else Faint,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                )
            }
            Spacer(Modifier.width(8.dp))
            TextButton(
                onClick = onRebind,
                colors = ButtonDefaults.textButtonColors(contentColor = MeconColors.IconDefault),
            ) {
                Text(i18n("dialog.settings.rebind"), fontSize = 13.sp)
            }
            IconButton(onClick = { KeybindingStore.resetToDefault(action) }, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Default.Refresh, contentDescription = "Reset", tint = MeconColors.IconDefault, modifier = Modifier.size(16.dp))
            }
        }
    }
}

/**
 * Modal capture surface: grabs focus and turns the next non-modifier key press
 * into a [KeyStroke]. Escape cancels. Bare modifier presses are ignored so the
 * user can hold Ctrl/Shift while reaching for the real key.
 */
@Composable
private fun KeyCaptureOverlay(
    action: ShortcutAction,
    onCaptured: (KeyStroke) -> Unit,
    onCancel: () -> Unit,
) {
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(action) { focusRequester.requestFocus() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MeconColors.Background.copy(alpha = 0.8f))
            .focusRequester(focusRequester)
            .focusable()
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when {
                    event.key == Key.Escape -> { onCancel(); true }
                    event.key.isModifierKey() -> false
                    else -> { onCaptured(KeyStroke.fromEvent(event)); true }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            color = PanelBg,
            shape = RoundedCornerShape(12.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, Accent),
        ) {
            Column(
                modifier = Modifier.padding(28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(i18n(action.labelKey), color = Accent, fontWeight = FontWeight.Medium, fontSize = 15.sp)
                Spacer(Modifier.height(10.dp))
                Text(i18n("dialog.settings.capturing"), color = Muted, fontSize = 13.sp)
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, color = Muted, fontWeight = FontWeight.Medium, fontSize = 14.sp)
}

@Composable
private fun StepButton(label: String, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.clickable(onClick = onClick),
        color = MeconColors.SurfaceLight,
        shape = RoundedCornerShape(6.dp),
    ) {
        Text(
            label,
            color = MeconColors.TextPrimary,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
        )
    }
}
