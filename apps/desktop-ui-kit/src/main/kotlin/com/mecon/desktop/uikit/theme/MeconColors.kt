package com.mecon.desktop.uikit.theme

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color

/**
 * Raw color swatches for the low-saturation blue-charcoal dark theme (the app's original,
 * default look). Nothing outside this file should reference [MeconPaletteDark] directly —
 * go through the semantic roles in [MeconColors].
 */
private object MeconPaletteDark {
    // Neutrals — blue-slate scale, darkest to lightest.
    val Slate950 = Color(0xFF080D18)
    val Slate900 = Color(0xFF101827)
    val Slate800 = Color(0xFF1A2537)
    val Slate700 = Color(0xFF26344A)
    val Slate600 = Color(0xFF3B4A61)
    val Slate500 = Color(0xFF61728A)
    val Slate400 = Color(0xFF9EADBF)
    val Slate200 = Color(0xFFE1E7EE)
    val Slate50 = Color(0xFFf8fafc)
    val White = Color(0xFFFFFFFF)

    // Brand accent — indigo.
    val Indigo600 = Color(0xFF2563B8)
    val Indigo500 = Color(0xFF3B82F6)
    val Indigo400 = Color(0xFF60A5FA)

    // Selected-state accent — a dark, cyan-tinted navy for the background and a vivid
    // cyan for the icon. Deliberately a different hue from the neutral Slate ramp above,
    // so a selected control reads clearly no matter which Slate surface it sits on.
    val CyanNavy900 = Color(0xFF4075E9)
    val Cyan400 = Color(0xFFF4F8FF)
    // Bright blue border for controls that have a selected background.
    val SelectedBorderBlue = Color(0xFF8FC5FF)

    val Emerald500 = Color(0xFF3F927F)
    val Emerald400 = Color(0xFF70B5A3)
    val Orange500 = Color(0xFFB68155)
    val Orange400 = Color(0xFFD2A477)
    val Red500 = Color(0xFFB9666D)
    val Blue600 = Color(0xFF4075E9)

    val RedSurface900 = Color(0xFF2E1B1D)
}

/**
 * Raw color swatches for the white-background light theme. Mirrors [MeconPaletteDark]'s
 * role coverage so [MeconColors.setTheme] can swap the two without touching call sites.
 */
private object MeconPaletteLight {
    // Neutrals — near-white to mid-gray.
    val Slate50 = Color(0xFFFFFFFF)
    val Slate100 = Color(0xFFF5F7FA)
    val Slate150 = Color(0xFFEEF1F5)
    val Slate200 = Color(0xFFE3E7ED)
    val Slate300 = Color(0xFFD8DEE7)
    val Slate400 = Color(0xFFC7CFDA)
    val Slate500 = Color(0xFF94A3B8)
    val Slate600 = Color(0xFF64748B)
    val Slate800 = Color(0xFF334155)
    val Slate900 = Color(0xFF1E293B)

    // Brand accent — same indigo family, shifted darker where it sits directly on white.
    val Indigo700 = Color(0xFF1D4ED8)
    val Indigo600 = Color(0xFF2563B8)
    val Indigo400 = Color(0xFF60A5FA)

    val SelectedSurfaceTint = Color(0xFFDCEAFE)
    val SelectedBorderBlue = Color(0xFF60A5FA)

    val Emerald500 = Color(0xFF3F927F)
    val Emerald400 = Color(0xFF70B5A3)
    val Orange500 = Color(0xFFB68155)
    val Orange400 = Color(0xFFD2A477)
    val Red500 = Color(0xFFB9666D)
    val Red700 = Color(0xFF9B3A42)
    val Blue600 = Color(0xFF4075E9)

    val RedSurfaceTint = Color(0xFFFBEAEB)
}

/** One theme's full set of semantic role values, applied atomically by [MeconColors.setTheme]. */
private class MeconRoleTable(
    val background: Color,
    val surface: Color,
    val surfaceLight: Color,
    val surfaceDark: Color,
    val inputBackground: Color,
    val primary: Color,
    val primaryDark: Color,
    val primaryLight: Color,
    val textPrimary: Color,
    val textMuted: Color,
    val textDark: Color,
    val border: Color,
    val borderLight: Color,
    val hoverBackground: Color,
    val hoverBackgroundLight: Color,
    val emerald: Color,
    val emeraldLight: Color,
    val orange: Color,
    val orangeLight: Color,
    val red: Color,
    val selection: Color,
    val panelHeader: Color,
    val panelContent: Color,
    val dialogBackground: Color,
    val dialogPanel: Color,
    val dialogPanelInset: Color,
    val instrumentRowBackground: Color,
    val bracketDefault: Color,
    val bracketSelected: Color,
    val bracketHandle: Color,
    val danger: Color,
    val deleteIcon: Color,
    val iconDefault: Color,
    val selectedIcon: Color,
    val selectedIconOnSurface: Color,
    val selectedBorder: Color,
    val selectedSurface: Color,
    val errorSurface: Color,
    val onErrorSurface: Color,
)

private val DarkRoles = MeconPaletteDark.let { p ->
    MeconRoleTable(
        background = p.Slate900,
        surface = p.Slate800,
        surfaceLight = p.Slate700,
        surfaceDark = p.Slate950,
        inputBackground = p.Slate950,
        primary = p.Indigo500,
        primaryDark = p.Indigo600,
        primaryLight = p.Indigo400,
        textPrimary = p.Slate200,
        textMuted = p.Slate400,
        textDark = p.Slate500,
        border = p.Slate700,
        borderLight = p.Slate600,
        hoverBackground = p.Slate700,
        hoverBackgroundLight = p.Slate600,
        emerald = p.Emerald500,
        emeraldLight = p.Emerald400,
        orange = p.Orange500,
        orangeLight = p.Orange400,
        red = p.Red500,
        selection = p.Blue600,
        panelHeader = p.Slate800,
        panelContent = p.Slate900,
        dialogBackground = p.Slate900,
        dialogPanel = p.Slate800,
        dialogPanelInset = p.Slate950,
        instrumentRowBackground = p.Slate900,
        bracketDefault = p.Slate400,
        bracketSelected = p.Indigo400,
        bracketHandle = p.SelectedBorderBlue,
        danger = p.Red500,
        deleteIcon = p.Slate400,
        iconDefault = p.Slate200,
        selectedIcon = p.Indigo400,
        selectedIconOnSurface = p.Cyan400,
        selectedBorder = p.SelectedBorderBlue,
        selectedSurface = p.CyanNavy900,
        errorSurface = p.RedSurface900,
        onErrorSurface = p.Red500,
    )
}

private val LightRoles = MeconPaletteLight.let { p ->
    MeconRoleTable(
        background = p.Slate100,
        surface = p.Slate50,
        surfaceLight = p.Slate150,
        surfaceDark = p.Slate200,
        inputBackground = p.Slate50,
        primary = p.Indigo600,
        primaryDark = p.Indigo700,
        primaryLight = p.Indigo400,
        textPrimary = p.Slate900,
        textMuted = p.Slate600,
        textDark = p.Slate500,
        border = p.Slate300,
        borderLight = p.Slate400,
        hoverBackground = p.Slate150,
        hoverBackgroundLight = p.Slate200,
        emerald = p.Emerald500,
        emeraldLight = p.Emerald400,
        orange = p.Orange500,
        orangeLight = p.Orange400,
        red = p.Red500,
        selection = p.Blue600,
        panelHeader = p.Slate150,
        panelContent = p.Slate50,
        dialogBackground = p.Slate50,
        dialogPanel = p.Slate150,
        dialogPanelInset = p.Slate200,
        instrumentRowBackground = p.Slate50,
        bracketDefault = p.Slate500,
        bracketSelected = p.Indigo400,
        bracketHandle = p.SelectedBorderBlue,
        danger = p.Red500,
        deleteIcon = p.Slate500,
        iconDefault = p.Slate800,
        selectedIcon = p.Indigo600,
        selectedIconOnSurface = p.Indigo700,
        selectedBorder = p.SelectedBorderBlue,
        selectedSurface = p.SelectedSurfaceTint,
        errorSurface = p.RedSurfaceTint,
        onErrorSurface = p.Red700,
    )
}

/**
 * Semantic color roles for the desktop UI. Grouped by purpose (surface / text / border /
 * button-content / status), not by hue, so the mapping to the active palette can change
 * per theme without renaming anything at the call site. All roles below are backed by
 * Compose [mutableStateOf] so switching [setTheme] recomposes every reader automatically;
 * roles tied to a fixed identity regardless of theme (score paper ink, voice colors, pure
 * white/transparent) stay as plain `val`s.
 */
object MeconColors {
    var themeMode: ThemeMode = ThemeMode.DARK
        private set

    // Surfaces, darkest to lightest.
    var Background by mutableStateOf(DarkRoles.background)
        private set
    var Surface by mutableStateOf(DarkRoles.surface)
        private set
    var SurfaceLight by mutableStateOf(DarkRoles.surfaceLight)
        private set
    var SurfaceDark by mutableStateOf(DarkRoles.surfaceDark)
        private set
    /** Deeper fill for text inputs and bordered controls inside panels. */
    var InputBackground by mutableStateOf(DarkRoles.inputBackground)
        private set

    // Brand accent.
    var Primary by mutableStateOf(DarkRoles.primary)
        private set
    var PrimaryDark by mutableStateOf(DarkRoles.primaryDark)
        private set
    var PrimaryLight by mutableStateOf(DarkRoles.primaryLight)
        private set

    // Body / label text.
    var TextPrimary by mutableStateOf(DarkRoles.textPrimary)
        private set
    val TextSecondary: Color get() = TextPrimary
    var TextMuted by mutableStateOf(DarkRoles.textMuted)
        private set
    var TextDark by mutableStateOf(DarkRoles.textDark)
        private set

    // Borders.
    var Border by mutableStateOf(DarkRoles.border)
        private set
    var BorderLight by mutableStateOf(DarkRoles.borderLight)
        private set

    // Hover / press feedback backgrounds (icon/text color does not change on hover).
    var HoverBackground by mutableStateOf(DarkRoles.hoverBackground)
        private set
    var HoverBackgroundLight by mutableStateOf(DarkRoles.hoverBackgroundLight)
        private set

    // Status accents.
    var Emerald by mutableStateOf(DarkRoles.emerald)
        private set
    var EmeraldLight by mutableStateOf(DarkRoles.emeraldLight)
        private set
    var Orange by mutableStateOf(DarkRoles.orange)
        private set
    var OrangeLight by mutableStateOf(DarkRoles.orangeLight)
        private set
    var Red by mutableStateOf(DarkRoles.red)
        private set

    var Selection by mutableStateOf(DarkRoles.selection)
        private set
    val Playhead: Color get() = Emerald

    /** Error/warning banner surface (e.g. a failed-then-recovered operation notice). */
    var ErrorSurface by mutableStateOf(DarkRoles.errorSurface)
        private set
    var OnErrorSurface by mutableStateOf(DarkRoles.onErrorSurface)
        private set

    /**
     * Fixed voice identities, independent of the current theme. Toolbar swatches stay bright for
     * quick identification; score swatches are darker so they remain legible on white paper.
     */
    private val VoiceToolbarColors = listOf(
        Color(0xFF2563EB), // voice 1: current blue
        Color(0xFF34D399), // voice 2: green
        Color(0xFFC084FC), // voice 3: purple
        Color(0xFFD6B36A), // voice 4: ochre
    )
    private val VoiceSelectionColors = listOf(
        Color(0xFF2563EB), // voice 1: current blue
        Color(0xFF15803D), // voice 2: darker green
        Color(0xFF7E22CE), // voice 3: darker purple
        Color(0xFFA16207), // voice 4: darker ochre
    )

    private fun voiceColor(colors: List<Color>, voiceNumber: Int): Color {
        val index = ((voiceNumber - 1) % colors.size + colors.size) % colors.size
        return colors[index]
    }

    /** Return the bright fixed toolbar color for a 1-based voice number, cycling above four. */
    fun voiceToolbarColor(voiceNumber: Int): Color = voiceColor(VoiceToolbarColors, voiceNumber)

    /** Return the darker fixed score-selection color for a 1-based voice number, cycling above four. */
    fun voiceSelectionColor(voiceNumber: Int): Color = voiceColor(VoiceSelectionColors, voiceNumber)

    var PanelHeader by mutableStateOf(DarkRoles.panelHeader)
        private set
    var PanelContent by mutableStateOf(DarkRoles.panelContent)
        private set

    // Fixed identities: the music score is always engraved as black ink on white paper,
    // independent of the surrounding app chrome's theme.
    val White = Color(0xFFFFFFFF)
    val ScoreBackground = Color(0xFFf8fafc)
    val ScoreInk = Color(0xFF080D18)
    /** Muted/ghosted note ink for construction previews, e.g. a de-emphasized chord tone. */
    val ScoreMutedInk = Color(0xFF8A9099)

    // New-score dialog roles. Keep the dialog independent of raw swatches so a
    // future light/high-contrast theme can restyle the whole workflow here.
    var DialogBackground by mutableStateOf(DarkRoles.dialogBackground)
        private set
    var DialogPanel by mutableStateOf(DarkRoles.dialogPanel)
        private set
    var DialogPanelInset by mutableStateOf(DarkRoles.dialogPanelInset)
        private set
    var InstrumentRowBackground by mutableStateOf(DarkRoles.instrumentRowBackground)
        private set
    var BracketDefault by mutableStateOf(DarkRoles.bracketDefault)
        private set
    var BracketSelected by mutableStateOf(DarkRoles.bracketSelected)
        private set
    var BracketHandle by mutableStateOf(DarkRoles.bracketHandle)
        private set
    var Danger by mutableStateOf(DarkRoles.danger)
        private set
    /** Neutral destructive affordance for dense editors; red is reserved for errors. */
    var DeleteIcon by mutableStateOf(DarkRoles.deleteIcon)
        private set
    val Transparent = Color.Transparent

    // Button / toolbar content roles.
    /** Default icon/text color for buttons & toolbar controls. */
    var IconDefault by mutableStateOf(DarkRoles.iconDefault)
        private set
    /** Restrained accent icon/text color for a button's selected (active/toggled-on) state. */
    var SelectedIcon by mutableStateOf(DarkRoles.selectedIcon)
        private set
    var SelectedIconOnSurface by mutableStateOf(DarkRoles.selectedIconOnSurface)
        private set
    var SelectedBorder by mutableStateOf(DarkRoles.selectedBorder)
        private set
    /** Background for a button's selected state — a distinct muted blue tone. */
    var SelectedSurface by mutableStateOf(DarkRoles.selectedSurface)
        private set

    /** Switch every reactive role above to [mode]'s table. Safe to call before any composition. */
    fun setTheme(mode: ThemeMode) {
        themeMode = mode
        val r = if (mode == ThemeMode.LIGHT) LightRoles else DarkRoles
        Background = r.background
        Surface = r.surface
        SurfaceLight = r.surfaceLight
        SurfaceDark = r.surfaceDark
        InputBackground = r.inputBackground
        Primary = r.primary
        PrimaryDark = r.primaryDark
        PrimaryLight = r.primaryLight
        TextPrimary = r.textPrimary
        TextMuted = r.textMuted
        TextDark = r.textDark
        Border = r.border
        BorderLight = r.borderLight
        HoverBackground = r.hoverBackground
        HoverBackgroundLight = r.hoverBackgroundLight
        Emerald = r.emerald
        EmeraldLight = r.emeraldLight
        Orange = r.orange
        OrangeLight = r.orangeLight
        Red = r.red
        Selection = r.selection
        ErrorSurface = r.errorSurface
        OnErrorSurface = r.onErrorSurface
        PanelHeader = r.panelHeader
        PanelContent = r.panelContent
        DialogBackground = r.dialogBackground
        DialogPanel = r.dialogPanel
        DialogPanelInset = r.dialogPanelInset
        InstrumentRowBackground = r.instrumentRowBackground
        BracketDefault = r.bracketDefault
        BracketSelected = r.bracketSelected
        BracketHandle = r.bracketHandle
        Danger = r.danger
        DeleteIcon = r.deleteIcon
        IconDefault = r.iconDefault
        SelectedIcon = r.selectedIcon
        SelectedIconOnSurface = r.selectedIconOnSurface
        SelectedBorder = r.selectedBorder
        SelectedSurface = r.selectedSurface
    }
}
