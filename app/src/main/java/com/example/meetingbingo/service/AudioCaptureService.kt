package com.example.meetingbingo.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.meetingbingo.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import android.graphics.Bitmap
import kotlin.math.sqrt

class AudioCaptureService : Service() {

    companion object {
        private const val TAG = "AudioCaptureService"
        private const val CHANNEL_ID = "AudioCaptureChannel"
        private const val NOTIFICATION_ID = 1
        private const val SAMPLE_RATE = 16000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT

        // Singleton instance for accessing MediaProjection from other components
        var instance: AudioCaptureService? = null
            private set
    }

    private val binder = LocalBinder()
    private var mediaProjection: MediaProjection? = null
    private var screenCapture: MediaProjectionScreenCapture? = null
    private var audioRecord: AudioRecord? = null
    private var captureThread: Thread? = null
    private var isCapturing = false

    private val _audioLevel = MutableStateFlow(0f)
    val audioLevel: StateFlow<Float> = _audioLevel.asStateFlow()

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _transcribedText = MutableStateFlow("")
    val transcribedText: StateFlow<String> = _transcribedText.asStateFlow()

    private var onAudioDataReceived: ((ShortArray) -> Unit)? = null

    inner class LocalBinder : Binder() {
        fun getService(): AudioCaptureService = this@AudioCaptureService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        instance = this
        Log.d(TAG, "Service created")
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand")
        return START_NOT_STICKY
    }

    fun setOnAudioDataListener(listener: (ShortArray) -> Unit) {
        onAudioDataReceived = listener
    }

    fun startCapture(resultCode: Int, data: Intent) {
        Log.d(TAG, "startCapture called")

        val notification = createNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        val projectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = projectionManager.getMediaProjection(resultCode, data)

        if (mediaProjection == null) {
            Log.e(TAG, "Failed to get MediaProjection")
            _error.value = "Failed to get MediaProjection"
            stopSelf()
            return
        }

        mediaProjection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                Log.d(TAG, "MediaProjection stopped")
                stopCapturing()
            }
        }, null)

        startAudioCapture()
    }

    private fun startAudioCapture() {
        try {
            val config = AudioPlaybackCaptureConfiguration.Builder(mediaProjection!!)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()

            val bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
            Log.d(TAG, "Buffer size: $bufferSize")

            audioRecord = AudioRecord.Builder()
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AUDIO_FORMAT)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(CHANNEL_CONFIG)
                        .build()
                )
                .setBufferSizeInBytes(bufferSize * 2)
                .setAudioPlaybackCaptureConfig(config)
                .build()

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord failed to initialize")
                _error.value = "AudioRecord failed to initialize"
                return
            }

            audioRecord?.startRecording()
            isCapturing = true
            _isRecording.value = true

            captureThread = Thread {
                Log.d(TAG, "Capture thread started")
                val buffer = ShortArray(bufferSize / 2)

                while (isCapturing) {
                    val read = audioRecord?.read(buffer, 0, buffer.size) ?: -1
                    if (read > 0) {
                        val level = calculateRmsLevel(buffer, read)
                        _audioLevel.value = level

                        // Send audio data to listener
                        val audioData = buffer.copyOf(read)
                        onAudioDataReceived?.invoke(audioData)

                        // Update status text with audio level
                        _transcribedText.value = "Audio level: %.4f | Samples: %d".format(level, read)
                    }
                }
                Log.d(TAG, "Capture thread ended")
            }
            captureThread?.start()

            Log.d(TAG, "Audio capture started successfully")

        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException: ${e.message}", e)
            _error.value = "Permission denied: ${e.message}"
        } catch (e: Exception) {
            Log.e(TAG, "Error starting audio capture", e)
            _error.value = "Error: ${e.message}"
        }
    }

    private fun calculateRmsLevel(buffer: ShortArray, readSize: Int): Float {
        var sum = 0.0
        for (i in 0 until readSize) {
            sum += buffer[i] * buffer[i]
        }
        val rms = sqrt(sum / readSize)
        return (rms / Short.MAX_VALUE).toFloat()
    }

    fun stopCapturing() {
        Log.d(TAG, "stopCapturing called")
        isCapturing = false
        _isRecording.value = false

        captureThread?.interrupt()
        captureThread = null

        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping AudioRecord", e)
        }
        audioRecord = null

        stopScreenCapture()

        mediaProjection?.stop()
        mediaProjection = null

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        Log.d(TAG, "Service destroyed")
        stopCapturing()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Audio Capture",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Captures meeting audio for bingo"
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Meeting Bingo")
            .setContentText("Listening to meeting audio...")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    // ==================== MediaProjection Screenshot API ====================

    /**
     * Check if MediaProjection is available for screenshot capture.
     */
    fun isMediaProjectionAvailable(): Boolean = mediaProjection != null

    /**
     * Take a single screenshot using MediaProjection.
     * This is an alternative to AccessibilityService.takeScreenshot() when
     * accessibility service is not available.
     *
     * @param onResult Callback with the captured bitmap, or null if capture failed
     */
    fun takeScreenshot(onResult: (Bitmap?) -> Unit) {
        val projection = mediaProjection
        if (projection == null) {
            Log.e(TAG, "takeScreenshot: MediaProjection not available")
            onResult(null)
            return
        }

        Log.d(TAG, "Taking screenshot via MediaProjection")

        // Create screen capture instance if needed
        if (screenCapture == null) {
            screenCapture = MediaProjectionScreenCapture(applicationContext)
        }

        screenCapture?.takeSingleScreenshot(projection, onResult)
    }

    /**
     * Clean up screen capture resources.
     * Called automatically when service is destroyed.
     */
    private fun stopScreenCapture() {
        screenCapture?.stop()
        screenCapture = null
    }
}
