package com.example.meetingbingo.audio

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import com.example.meetingbingo.service.AudioCaptureService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Manager class to handle AudioPlaybackCapture service binding and communication.
 */
class AudioCaptureManager(private val context: Context) {

    companion object {
        private const val TAG = "AudioCaptureManager"
    }

    private var audioCaptureService: AudioCaptureService? = null
    private var isBound = false

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    private val _audioLevel = MutableStateFlow(0f)
    val audioLevel: StateFlow<Float> = _audioLevel.asStateFlow()

    private val _statusText = MutableStateFlow("")
    val statusText: StateFlow<String> = _statusText.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private var onAudioDataReceived: ((ShortArray) -> Unit)? = null

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            Log.d(TAG, "Service connected")
            val binder = service as AudioCaptureService.LocalBinder
            audioCaptureService = binder.getService()
            isBound = true

            // Set up audio data listener
            audioCaptureService?.setOnAudioDataListener { audioData ->
                onAudioDataReceived?.invoke(audioData)
            }

            // Forward service state flows
            // Note: In a real app, you'd collect these in a coroutine
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            Log.d(TAG, "Service disconnected")
            audioCaptureService = null
            isBound = false
            _isRecording.value = false
        }
    }

    fun setOnAudioDataListener(listener: (ShortArray) -> Unit) {
        onAudioDataReceived = listener
        audioCaptureService?.setOnAudioDataListener(listener)
    }

    fun bindService() {
        Log.d(TAG, "Binding service")
        val intent = Intent(context, AudioCaptureService::class.java)
        context.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    fun unbindService() {
        Log.d(TAG, "Unbinding service")
        if (isBound) {
            context.unbindService(serviceConnection)
            isBound = false
        }
    }

    fun startCapture(resultCode: Int, data: Intent) {
        Log.d(TAG, "Starting capture")

        // Start the service first
        val serviceIntent = Intent(context, AudioCaptureService::class.java)
        context.startService(serviceIntent)

        // Bind if not already bound
        if (!isBound) {
            val intent = Intent(context, AudioCaptureService::class.java)
            context.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        }

        // Wait a bit for binding, then start capture
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            audioCaptureService?.startCapture(resultCode, data)
            _isRecording.value = true
        }, 100)
    }

    fun stopCapture() {
        Log.d(TAG, "Stopping capture")
        audioCaptureService?.stopCapturing()
        _isRecording.value = false
    }

    fun getService(): AudioCaptureService? = audioCaptureService

    fun destroy() {
        stopCapture()
        unbindService()
    }
}
