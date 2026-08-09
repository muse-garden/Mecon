package com.mecon.desktop.input

import com.mecon.desktop.AppSettings
import com.mecon.input.PerformanceInputEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.sound.midi.MidiDevice
import javax.sound.midi.MidiMessage
import javax.sound.midi.MidiSystem
import javax.sound.midi.Receiver
import javax.sound.midi.ShortMessage
import javax.sound.midi.Transmitter

data class MidiInputDeviceInfo(
    val id: String,
    val name: String,
    val vendor: String,
    val description: String,
)

/**
 * JVM MIDI-device adapter with polling hot-plug support. Native receiver callbacks do no score or
 * Compose work: they only normalize the message and hand it to [listener].
 */
class JvmMidiInputService(
    private val scope: CoroutineScope,
) {
    private val _devices = MutableStateFlow<List<MidiInputDeviceInfo>>(emptyList())
    val devices: StateFlow<List<MidiInputDeviceInfo>> = _devices.asStateFlow()

    private val _selectedDeviceId = MutableStateFlow<String?>(null)
    val selectedDeviceId: StateFlow<String?> = _selectedDeviceId.asStateFlow()

    @Volatile
    private var listener: ((PerformanceInputEvent) -> Unit)? = null
    private var device: MidiDevice? = null
    private var transmitter: Transmitter? = null
    private var pollJob: Job? = null

    fun setListener(value: ((PerformanceInputEvent) -> Unit)?) {
        listener = value
    }

    fun start() {
        if (pollJob != null) return
        pollJob = scope.launch {
            refreshAndReconnect()
            while (isActive) {
                delay(2_000)
                refreshAndReconnect()
            }
        }
    }

    fun select(deviceId: String?) {
        AppSettings.midiInputDeviceId = deviceId
        scope.launch { connect(deviceId) }
    }

    fun cycleDevice() {
        val all = _devices.value
        if (all.isEmpty()) {
            select(null)
            return
        }
        val current = all.indexOfFirst { it.id == _selectedDeviceId.value }
        select(all[(current + 1).mod(all.size)].id)
    }

    fun close() {
        pollJob?.cancel()
        pollJob = null
        closeDevice()
        listener = null
    }

    private suspend fun refreshAndReconnect() {
        val discovered = withContext(Dispatchers.IO) { discover() }
        _devices.value = discovered.map { it.second }
        val selected = _selectedDeviceId.value
        if (selected != null && discovered.none { it.second.id == selected }) {
            closeDevice()
            _selectedDeviceId.value = null
            listener?.invoke(PerformanceInputEvent.SourceDisconnected(selected, System.nanoTime()))
        }
        if (device == null) {
            val preferred = AppSettings.midiInputDeviceId
            val target = discovered.firstOrNull { it.second.id == preferred }
                ?: discovered.firstOrNull()
            target?.second?.id?.let { connect(it, discovered) }
        }
    }

    private suspend fun connect(
        deviceId: String?,
        known: List<Pair<MidiDevice.Info, MidiInputDeviceInfo>>? = null,
    ) {
        if (deviceId == _selectedDeviceId.value && device?.isOpen == true) return
        val previous = _selectedDeviceId.value
        closeDevice()
        if (previous != null && previous != deviceId) {
            listener?.invoke(PerformanceInputEvent.SourceDisconnected(previous, System.nanoTime()))
        }
        if (deviceId == null) {
            _selectedDeviceId.value = null
            return
        }
        val entries = known ?: withContext(Dispatchers.IO) { discover() }
        val nativeInfo = entries.firstOrNull { it.second.id == deviceId }?.first ?: return
        withContext(Dispatchers.IO) {
            runCatching {
                val opened = MidiSystem.getMidiDevice(nativeInfo)
                opened.open()
                val tx = opened.transmitter
                tx.receiver = NormalizingReceiver(deviceId)
                device = opened
                transmitter = tx
                _selectedDeviceId.value = deviceId
                AppSettings.midiInputDeviceId = deviceId
            }
        }
    }

    private fun discover(): List<Pair<MidiDevice.Info, MidiInputDeviceInfo>> =
        MidiSystem.getMidiDeviceInfo().mapNotNull { info ->
            val candidate = runCatching { MidiSystem.getMidiDevice(info) }.getOrNull()
                ?: return@mapNotNull null
            if (candidate.maxTransmitters == 0) return@mapNotNull null
            val id = listOf(info.vendor, info.name, info.version, info.description).joinToString("|")
            info to MidiInputDeviceInfo(id, info.name, info.vendor, info.description)
        }

    private fun closeDevice() {
        transmitter?.close()
        transmitter = null
        device?.close()
        device = null
    }

    private inner class NormalizingReceiver(
        private val sourceId: String,
    ) : Receiver {
        override fun send(message: MidiMessage, timeStamp: Long) {
            normalizeMidiMessage(
                sourceId = sourceId,
                message = message,
                atNanos = System.nanoTime(),
                acceptedChannel = AppSettings.midiInputChannel,
                velocityThreshold = AppSettings.midiInputVelocityThreshold,
            )?.let { listener?.invoke(it) }
        }

        override fun close() = Unit
    }
}

internal fun normalizeMidiMessage(
    sourceId: String,
    message: MidiMessage,
    atNanos: Long,
    acceptedChannel: Int?,
    velocityThreshold: Int,
): PerformanceInputEvent? {
    val short = message as? ShortMessage ?: return null
    if (acceptedChannel != null && short.channel != acceptedChannel) return null
    return when (short.command) {
        ShortMessage.NOTE_ON -> when {
            short.data2 == 0 -> PerformanceInputEvent.NoteOff(sourceId, atNanos, short.data1)
            short.data2 >= velocityThreshold ->
                PerformanceInputEvent.NoteOn(sourceId, atNanos, short.data1, short.data2)
            else -> null
        }
        ShortMessage.NOTE_OFF ->
            PerformanceInputEvent.NoteOff(sourceId, atNanos, short.data1)
        ShortMessage.CONTROL_CHANGE ->
            PerformanceInputEvent.ControlChange(sourceId, atNanos, short.data1, short.data2)
        else -> null
    }
}
