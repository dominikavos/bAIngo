package com.example.meetingbingo.speech

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import no.neat.devkit.Audio
import no.neat.devkit.NeatDevKit
import kotlin.math.abs

/**
 * Manages audio recording using NeatDevKit's Audio API.
 * This is used to verify that we're receiving audio samples from the device.
 */
@Suppress("DEPRECATION")
class NeatAudioManager(private val neatDevKit: NeatDevKit?) {

    companion object {
        private const val TAG = "NeatAudioManager"
    }

    private var isRecording = false
    private var sampleCount = 0L
    private var callbackCount = 0

    private val _isAvailable = MutableStateFlow(false)
    val isAvailable: StateFlow<Boolean> = _isAvailable.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _audioStatus = MutableStateFlow("")
    val audioStatus: StateFlow<String> = _audioStatus.asStateFlow()

    private val _audioLevel = MutableStateFlow(0f)
    val audioLevel: StateFlow<Float> = _audioLevel.asStateFlow()

    private var onAudioReceived: ((micLevel: Float, speakerLevel: Float, samples: Int) -> Unit)? = null

    init {
        val audio = neatDevKit?.audio()
        val supported = audio?.audioRecordingIsSupported() ?: false
        _isAvailable.value = supported
        Log.e(TAG, "NeatDevKit audio recording supported: $supported")

        if (neatDevKit == null) {
            Log.e(TAG, "NeatDevKit is null!")
            _error.value = "NeatDevKit not initialized"
        }
    }

    fun setOnAudioReceivedListener(listener: (micLevel: Float, speakerLevel: Float, samples: Int) -> Unit) {
        onAudioReceived = listener
    }

    fun startRecording(): Boolean {
        if (isRecording) {
            Log.e(TAG, "Already recording")
            return true
        }

        val audio = neatDevKit?.audio()
        if (audio == null) {
            Log.e(TAG, "NeatDevKit audio is null")
            _error.value = "NeatDevKit audio not available"
            return false
        }

        if (!audio.audioRecordingIsSupported()) {
            Log.e(TAG, "Audio recording not supported")
            _error.value = "Audio recording not supported on this device"
            return false
        }

        Log.e(TAG, "Starting audio recording...")
        _error.value = null
        sampleCount = 0
        callbackCount = 0

        Log.e(TAG, "Calling audio.startAudioRecording with callback...")
        val success = audio.startAudioRecording { microphone, loudspeaker, numSamples ->
            Log.e(TAG, "CALLBACK RECEIVED! numSamples=$numSamples, mic=${microphone?.size}, speaker=${loudspeaker?.size}")
            callbackCount++
            sampleCount += numSamples

            // Calculate RMS levels for mic and speaker
            val micLevel = calculateRmsLevel(microphone)
            val speakerLevel = calculateRmsLevel(loudspeaker)

            _audioLevel.value = micLevel

            // Update status every 10 callbacks (~100ms at 48kHz with 480 samples)
            if (callbackCount % 10 == 0) {
                val statusMsg = "Callbacks: $callbackCount, Samples: $sampleCount, Mic: %.4f, Speaker: %.4f".format(micLevel, speakerLevel)
                Log.e(TAG, statusMsg)
                _audioStatus.value = statusMsg
            }

            onAudioReceived?.invoke(micLevel, speakerLevel, numSamples)
        }

        if (success) {
            isRecording = true
            Log.e(TAG, "Audio recording started successfully")
            _audioStatus.value = "Recording started..."
        } else {
            Log.e(TAG, "Failed to start audio recording")
            _error.value = "Failed to start audio recording"
        }

        return success
    }

    fun stopRecording(): Boolean {
        if (!isRecording) {
            Log.e(TAG, "Not recording")
            return true
        }

        val audio = neatDevKit?.audio()
        if (audio == null) {
            Log.e(TAG, "NeatDevKit audio is null")
            isRecording = false
            return false
        }

        Log.e(TAG, "Stopping audio recording...")
        val success = audio.stopAudioRecording()

        if (success) {
            isRecording = false
            val finalStatus = "Recording stopped. Total callbacks: $callbackCount, Total samples: $sampleCount"
            Log.e(TAG, finalStatus)
            _audioStatus.value = finalStatus
        } else {
            Log.e(TAG, "Failed to stop audio recording")
            _error.value = "Failed to stop audio recording"
        }

        return success
    }

    fun isRecording(): Boolean = isRecording

    fun destroy() {
        if (isRecording) {
            stopRecording()
        }
    }

    private fun calculateRmsLevel(samples: FloatArray?): Float {
        if (samples == null || samples.isEmpty()) return 0f

        var sum = 0.0
        for (sample in samples) {
            sum += sample * sample
        }
        return kotlin.math.sqrt(sum / samples.size).toFloat()
    }
}
