package com.mecon.desktop

import com.mecon.desktop.uikit.i18n.Language
import com.mecon.desktop.uikit.theme.ThemeMode
import com.mecon.desktop.input.NoteInputEntryMode
import com.mecon.input.ComputerNoteKey
import com.mecon.input.InputPitchMode
import java.io.File
import java.util.prefs.Preferences

/**
 * Persistent app-level settings backed by the platform preference store.
 * All writes flush immediately — no explicit save step required.
 */
object AppSettings {

    private val prefs = Preferences.userRoot().node("com/mecon/desktop")

    // ─── UI Scale ────────────────────────────────────────────────────────────

    /** Ordered scale steps the toolbar +/− buttons step through. */
    val uiScaleSteps = listOf(0.75f, 0.875f, 1.0f, 1.125f, 1.25f, 1.5f, 1.75f, 2.0f)

    val uiScaleMin get() = uiScaleSteps.first()
    val uiScaleMax get() = uiScaleSteps.last()

    var uiScale: Float
        get() = prefs.getFloat("uiScale", 1.0f).coerceIn(uiScaleMin, uiScaleMax)
        set(value) { prefs.putFloat("uiScale", value.coerceIn(uiScaleMin, uiScaleMax)) }

    fun stepDown(current: Float): Float {
        val idx = uiScaleSteps.indexOfFirst { it >= current - 0.01f }
        return if (idx > 0) uiScaleSteps[idx - 1] else uiScaleSteps.first()
    }

    fun stepUp(current: Float): Float {
        val idx = uiScaleSteps.indexOfLast { it <= current + 0.01f }
        return if (idx < uiScaleSteps.lastIndex) uiScaleSteps[idx + 1] else uiScaleSteps.last()
    }

    // ─── Language ────────────────────────────────────────────────────────────

    /** Persisted UI language; restored at startup and written when the user switches. */
    var language: Language
        get() = Language.fromCode(prefs.get("language", Language.CHINESE.code))
        set(value) { prefs.put("language", value.code) }

    // ─── Theme ───────────────────────────────────────────────────────────────

    /** Persisted UI color skin; restored at startup and written when the user switches. */
    var themeMode: ThemeMode
        get() = ThemeMode.fromCode(prefs.get("themeMode", ThemeMode.DARK.code))
        set(value) { prefs.put("themeMode", value.code) }

    // ─── File safety ────────────────────────────────────────────────────────

    /** Directory containing recoverable autosave payloads and their small metadata sidecars. */
    var autosaveDirectory: File
        get() = prefs.get("files.autosaveDirectory", "")
            .takeIf(String::isNotBlank)
            ?.let(::File)
            ?: File(System.getProperty("user.home"), ".mecon/autosave")
        set(value) { prefs.put("files.autosaveDirectory", value.absoluteFile.normalize().path) }

    // ─── Note input ──────────────────────────────────────────────────────────

    var noteInputEntryMode: NoteInputEntryMode
        get() = prefs.get("noteInput.entryMode", NoteInputEntryMode.STEP.name)
            .let { runCatching { NoteInputEntryMode.valueOf(it) }.getOrDefault(NoteInputEntryMode.STEP) }
        set(value) { prefs.put("noteInput.entryMode", value.name) }

    var noteInputPitchMode: InputPitchMode
        get() = prefs.get("noteInput.pitchMode", InputPitchMode.RELATIVE_TO_KEY.name)
            .let { runCatching { InputPitchMode.valueOf(it) }.getOrDefault(InputPitchMode.RELATIVE_TO_KEY) }
        set(value) { prefs.put("noteInput.pitchMode", value.name) }

    var noteInputCenterOctave: Int
        get() = prefs.getInt("noteInput.centerOctave", 4).coerceIn(-1, 9)
        set(value) { prefs.putInt("noteInput.centerOctave", value.coerceIn(-1, 9)) }

    var noteInputAnchorKey: ComputerNoteKey
        get() = prefs.get("noteInput.anchorKey", ComputerNoteKey.G.name)
            .let { runCatching { ComputerNoteKey.valueOf(it) }.getOrDefault(ComputerNoteKey.G) }
            .takeIf { runCatching { ComputerKeyboardAnchorValidator.validate(it) }.isSuccess }
            ?: ComputerNoteKey.G
        set(value) {
            ComputerKeyboardAnchorValidator.validate(value)
            prefs.put("noteInput.anchorKey", value.name)
        }

    var noteInputChordWindowMs: Long
        get() = prefs.getLong("noteInput.chordWindowMs", 60L).coerceIn(20L, 150L)
        set(value) { prefs.putLong("noteInput.chordWindowMs", value.coerceIn(20L, 150L)) }

    /** Smallest ordinary quantization grid; stored as a whole-note denominator. */
    var noteInputQuantizeDenominator: Int
        get() = prefs.getInt("noteInput.quantizeDenominator", 16)
            .takeIf { it in setOf(4, 8, 16, 32, 64) } ?: 16
        set(value) {
            require(value in setOf(4, 8, 16, 32, 64))
            prefs.putInt("noteInput.quantizeDenominator", value)
        }

    var noteInputAllowedTuplets: Set<Int>
        get() = prefs.get("noteInput.allowedTuplets", "2,3,6")
            .split(',')
            .mapNotNull(String::toIntOrNull)
            .filterTo(linkedSetOf()) { it in setOf(2, 3, 6) }
        set(value) {
            prefs.put("noteInput.allowedTuplets", value.filter { it in setOf(2, 3, 6) }.sorted().joinToString(","))
        }

    /** Device input latency compensation. Positive values move captured events earlier. */
    var noteInputLatencyMs: Int
        get() = prefs.getInt("noteInput.latencyMs", 0).coerceIn(-500, 500)
        set(value) { prefs.putInt("noteInput.latencyMs", value.coerceIn(-500, 500)) }

    var midiInputDeviceId: String?
        get() = prefs.get("noteInput.midiDeviceId", "").takeIf { it.isNotBlank() }
        set(value) {
            if (value == null) prefs.remove("noteInput.midiDeviceId")
            else prefs.put("noteInput.midiDeviceId", value)
        }

    /** Null accepts every channel; otherwise zero-based MIDI channel 0..15. */
    var midiInputChannel: Int?
        get() = prefs.getInt("noteInput.midiChannel", -1).takeIf { it in 0..15 }
        set(value) { prefs.putInt("noteInput.midiChannel", value?.coerceIn(0, 15) ?: -1) }

    var midiInputVelocityThreshold: Int
        get() = prefs.getInt("noteInput.midiVelocityThreshold", 1).coerceIn(1, 127)
        set(value) { prefs.putInt("noteInput.midiVelocityThreshold", value.coerceIn(1, 127)) }

    var midiInputThru: Boolean
        get() = prefs.getBoolean("noteInput.midiThru", true)
        set(value) { prefs.putBoolean("noteInput.midiThru", value) }

    var midiInputCenterNote: Int
        get() = prefs.getInt("noteInput.midiCenterNote", 60).coerceIn(0, 127)
        set(value) { prefs.putInt("noteInput.midiCenterNote", value.coerceIn(0, 127)) }

    /** True for the normal case where a MIDI keyboard sends sounding pitch. */
    var midiInputSendsSoundingPitch: Boolean
        get() = prefs.getBoolean("noteInput.midiSendsSoundingPitch", true)
        set(value) { prefs.putBoolean("noteInput.midiSendsSoundingPitch", value) }

    var noteInputOverdub: Boolean
        get() = prefs.getBoolean("noteInput.overdub", false)
        set(value) { prefs.putBoolean("noteInput.overdub", value) }

    private object ComputerKeyboardAnchorValidator {
        fun validate(key: ComputerNoteKey) {
            com.mecon.input.ComputerKeyboardLayout(key)
        }
    }
}
