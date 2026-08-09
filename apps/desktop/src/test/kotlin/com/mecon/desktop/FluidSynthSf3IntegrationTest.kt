package com.mecon.desktop

import com.mecon.audio.JvmAudioEngine
import com.mecon.audio.engine.AudioResult
import com.mecon.audio.engine.NoteAudition
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertTrue

class FluidSynthSf3IntegrationTest {
    @Test
    fun bundledSf3LoadsThroughFluidSynthOnWindows() = runBlocking {
        if (!System.getProperty("os.name").contains("Windows", ignoreCase = true)) return@runBlocking
        val engine = JvmAudioEngine(this)
        try {
            val initialized = engine.initialize()
            assertTrue(
                initialized is AudioResult.Success,
                (initialized as? AudioResult.Failure)?.error?.message
            )
            val default = engine.soundFontManager.defaultSoundFont.value
            assertTrue(default != null && default.id in engine.soundFontManager.loadedSoundFonts.value)
            // Exercise the real native preset switch path (including pin + get_program validation).
            for (program in listOf(73, 19, 73)) {
                engine.audition(
                    NoteAudition(
                        midiNumbers = listOf(60),
                        midiProgram = program,
                        velocity = 1,
                        durationMillis = 10,
                    )
                )
                delay(50)
            }
        } finally {
            engine.shutdown()
        }
    }
}
