package com.example.meetingbingo.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.core.app.NotificationCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.example.meetingbingo.MainActivity
import com.example.meetingbingo.MeetingBingoApplication
import com.example.meetingbingo.R
import com.example.meetingbingo.network.BingoApiClient
import com.example.meetingbingo.network.PlayerState
import com.example.meetingbingo.speech.NeatAudioManager
import com.example.meetingbingo.ui.overlay.ContentOverlay
import com.example.meetingbingo.ui.overlay.FabButtons
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Overlay service that displays the bingo game over other apps.
 */
class BingoOverlayService : Service(), LifecycleOwner, SavedStateRegistryOwner {

    companion object {
        private const val TAG = "BingoOverlayService"
        private const val CHANNEL_ID = "bingo_overlay_channel"
        private const val NOTIFICATION_ID = 2

        const val ACTION_START = "com.example.meetingbingo.START_OVERLAY"
        const val ACTION_STOP = "com.example.meetingbingo.STOP_OVERLAY"
        const val EXTRA_MEETING_ID = "meeting_id"
        const val EXTRA_PLAYER_NAME = "player_name"
        const val EXTRA_SERVER_URL = "server_url"

        // Shared state for overlay
        var overlayState by mutableStateOf(OverlayState())

        fun updateMarkedCells(cells: List<List<Boolean>>) {
            overlayState = overlayState.copy(myMarkedCells = cells)
        }

        fun updateHasBingo(hasBingo: Boolean) {
            overlayState = overlayState.copy(myHasBingo = hasBingo)
        }

        fun updateWords(words: List<List<String>>) {
            overlayState = overlayState.copy(myWords = words)
        }
    }

    enum class MeetingIdDetectionStatus {
        NOT_ATTEMPTED,
        DETECTING,
        SUCCESS,
        FAILED
    }

    data class OverlayState(
        val isShowingMyBoard: Boolean = false,
        val isShowingOthers: Boolean = false,
        val myMarkedCells: List<List<Boolean>> = List(5) { List(5) { false } },
        val myHasBingo: Boolean = false,
        val otherPlayers: List<PlayerState> = emptyList(),
        val myWords: List<List<String>> = emptyList(),
        val connectionState: BingoApiClient.ConnectionState = BingoApiClient.ConnectionState.DISCONNECTED,
        val currentMeetingId: String = "1234",
        val isAccessibilityEnabled: Boolean = false,
        val meetingIdDetectionStatus: MeetingIdDetectionStatus = MeetingIdDetectionStatus.NOT_ATTEMPTED,
        val detectedMeetingId: String? = null,
        // Audio/transcription state
        val isListening: Boolean = false,
        val audioStatus: String = "",
        val lastTranscript: String = ""
    )

    private lateinit var windowManager: WindowManager
    private var overlayView: ComposeView? = null
    private var fabView: ComposeView? = null

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var apiClient: BingoApiClient? = null
    private var neatAudioManager: NeatAudioManager? = null

    private var currentMeetingId: String = "1234"
    private var currentPlayerName: String = "Player"
    private var currentServerUrl: String = "http://10.47.6.1:8080"
    private var lastMeetingIdChangeTime: Long = 0
    private var meetingIdExtractionJob: Job? = null

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateRegistryController = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry get() = savedStateRegistryController.savedStateRegistry

    override fun onCreate() {
        super.onCreate()
        savedStateRegistryController.performAttach()
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)

        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val meetingId = intent.getStringExtra(EXTRA_MEETING_ID) ?: "1234"
                val playerName = intent.getStringExtra(EXTRA_PLAYER_NAME) ?: "Player"
                val serverUrl = intent.getStringExtra(EXTRA_SERVER_URL) ?: "http://10.47.6.1:8080"

                startForeground(NOTIFICATION_ID, createNotification())
                lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
                lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)

                startOverlay(meetingId, playerName, serverUrl)
            }
            ACTION_STOP -> {
                stopOverlay()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)

        stopOverlay()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Bingo Overlay",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Shows bingo game overlay"
        }
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(channel)
    }

    private fun createNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Meeting Bingo")
            .setContentText("Bingo overlay is active")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun startOverlay(meetingId: String, playerName: String, serverUrl: String) {
        Log.d(TAG, "Starting overlay: meetingId=$meetingId, player=$playerName, server=$serverUrl")

        currentMeetingId = meetingId
        currentPlayerName = playerName
        currentServerUrl = serverUrl
        lastMeetingIdChangeTime = System.currentTimeMillis()

        // Initialize API client and connect
        apiClient = BingoApiClient(serverUrl)

        serviceScope.launch {
            // Join the game
            apiClient?.joinGame(meetingId, playerName)?.onSuccess { response ->
                Log.d(TAG, "Joined game with ${response.players.size} other players")
                overlayState = overlayState.copy(currentMeetingId = meetingId)
            }?.onFailure { e ->
                Log.e(TAG, "Failed to join game", e)
            }

            // Collect other players updates
            apiClient?.otherPlayers?.collectLatest { players ->
                overlayState = overlayState.copy(otherPlayers = players)
            }
        }

        serviceScope.launch {
            apiClient?.connectionState?.collectLatest { state ->
                overlayState = overlayState.copy(connectionState = state)
            }
        }

        // Monitor accessibility service state
        serviceScope.launch {
            BingoAccessibilityService.isServiceEnabled.collectLatest { enabled ->
                overlayState = overlayState.copy(isAccessibilityEnabled = enabled)
            }
        }

        // Set up detection callbacks
        BingoAccessibilityService.onDetectionStarted = {
            overlayState = overlayState.copy(
                meetingIdDetectionStatus = MeetingIdDetectionStatus.DETECTING
            )
        }
        BingoAccessibilityService.onDetectionSuccess = { meetingId ->
            overlayState = overlayState.copy(
                meetingIdDetectionStatus = MeetingIdDetectionStatus.SUCCESS,
                detectedMeetingId = meetingId
            )
            // Auto-switch to the detected meeting
            switchToMeeting(meetingId)
        }
        BingoAccessibilityService.onDetectionFailed = { errorMessage ->
            Log.e(TAG, "Detection failed: $errorMessage")
            overlayState = overlayState.copy(
                meetingIdDetectionStatus = MeetingIdDetectionStatus.FAILED
            )
        }

        // Monitor extracted meeting IDs from accessibility service
        meetingIdExtractionJob = serviceScope.launch {
            BingoAccessibilityService.extractedMeetingId.collectLatest { extractedId ->
                if (extractedId != null && extractedId != currentMeetingId) {
                    Log.d(TAG, "New meeting ID extracted: $extractedId")
                    switchToMeeting(extractedId)
                }
            }
        }

        // Note: Auto-detection removed in favor of manual button

        // Initialize audio manager for speech recognition
        setupAudioRecording()

        // Create FAB overlay (always visible toggle buttons)
        createFabOverlay()
    }

    /**
     * Switch to a new meeting, resetting the bingo board.
     */
    private fun switchToMeeting(newMeetingId: String) {
        val timeSinceLastChange = System.currentTimeMillis() - lastMeetingIdChangeTime
        val oneHourMs = 60 * 60 * 1000L

        // Only switch if the meeting ID is different or it's been more than an hour
        if (newMeetingId != currentMeetingId || timeSinceLastChange > oneHourMs) {
            Log.d(TAG, "Switching to meeting: $newMeetingId (time since last: ${timeSinceLastChange}ms)")

            // Leave current game
            serviceScope.launch {
                apiClient?.leaveGame()

                // Update meeting ID
                currentMeetingId = newMeetingId
                lastMeetingIdChangeTime = System.currentTimeMillis()
                overlayState = overlayState.copy(currentMeetingId = newMeetingId)

                // Join new game
                apiClient?.joinGame(newMeetingId, currentPlayerName)?.onSuccess { response ->
                    Log.d(TAG, "Joined new meeting with ${response.players.size} other players")
                }?.onFailure { e ->
                    Log.e(TAG, "Failed to join new meeting", e)
                }
            }
        }
    }

    /**
     * Trigger meeting ID extraction via accessibility service (auto-navigation method).
     */
    fun triggerMeetingIdExtraction() {
        Log.d(TAG, "triggerMeetingIdExtraction called")
        val accessibilityService = BingoAccessibilityService.instance
        Log.d(TAG, "Accessibility service instance: $accessibilityService")
        if (accessibilityService != null) {
            Log.d(TAG, "Triggering meeting ID extraction")
            accessibilityService.startMeetingIdExtraction()
        } else {
            Log.e(TAG, "Accessibility service not available - instance is null")
        }
    }

    /**
     * Open the main app activity.
     */
    fun openMainApp() {
        Log.d(TAG, "Opening main app")
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        startActivity(intent)
    }

    /**
     * Trigger manual screenshot OCR for meeting ID detection.
     * User should have Meeting info dialog visible when calling this.
     */
    fun triggerScreenshotOcr() {
        Log.d(TAG, "triggerScreenshotOcr called")
        val accessibilityService = BingoAccessibilityService.instance
        if (accessibilityService != null) {
            Log.d(TAG, "Triggering manual screenshot OCR")
            accessibilityService.extractMeetingIdFromScreenshot()
        } else {
            Log.e(TAG, "Accessibility service not available - instance is null")
            overlayState = overlayState.copy(
                meetingIdDetectionStatus = MeetingIdDetectionStatus.FAILED
            )
        }
    }

    private fun setupAudioRecording() {
        Log.d(TAG, "Setting up NeatDevKit audio recording")
        neatAudioManager = NeatAudioManager(MeetingBingoApplication.neatDevKit)

        Log.d(TAG, "Audio recording available: ${neatAudioManager?.isAvailable?.value}")

        neatAudioManager?.setOnAudioReceivedListener { micLevel, speakerLevel, samples ->
            // Update the overlay with audio level info for debugging
            val statusMsg = "Mic: %.4f | Speaker: %.4f | Samples: %d".format(micLevel, speakerLevel, samples)
            overlayState = overlayState.copy(audioStatus = statusMsg)
        }

        serviceScope.launch {
            neatAudioManager?.error?.collectLatest { error ->
                if (error != null) {
                    Log.e(TAG, "Audio error: $error")
                    overlayState = overlayState.copy(audioStatus = "Error: $error")
                }
            }
        }
    }

    /**
     * Toggle audio listening on/off.
     */
    fun toggleListening() {
        val isCurrentlyListening = overlayState.isListening
        Log.d(TAG, "toggleListening called, current state: $isCurrentlyListening")

        if (isCurrentlyListening) {
            neatAudioManager?.stopRecording()
            overlayState = overlayState.copy(
                isListening = false,
                audioStatus = "Stopped"
            )
        } else {
            val success = neatAudioManager?.startRecording() ?: false
            overlayState = overlayState.copy(
                isListening = success,
                audioStatus = if (success) "Starting..." else "Failed to start"
            )
        }
    }

    private fun stopOverlay() {
        Log.d(TAG, "Stopping overlay")

        // Stop audio recording
        neatAudioManager?.destroy()
        neatAudioManager = null

        serviceScope.launch {
            apiClient?.leaveGame()
        }

        overlayView?.let {
            try {
                windowManager.removeView(it)
            } catch (e: Exception) {
                Log.e(TAG, "Error removing overlay view", e)
            }
        }
        overlayView = null

        fabView?.let {
            try {
                windowManager.removeView(it)
            } catch (e: Exception) {
                Log.e(TAG, "Error removing FAB view", e)
            }
        }
        fabView = null

        overlayState = OverlayState()
    }

    private fun createFabOverlay() {
        // Create a small FAB-only overlay
        // Use fixed pixel size to minimize the touchable area
        // 56dp (main FAB) + 48dp (others FAB) + 48dp (detect FAB) + 48dp (reset FAB) + 48dp (mic FAB) + 12dp (indicator) + 40dp (spacing) + ~40dp (text) = ~340dp
        val density = resources.displayMetrics.density
        val width = (100 * density).toInt()  // ~100dp wide for FABs + status text
        val height = (380 * density).toInt() // ~380dp tall for stacked FABs (5 buttons + indicator + status)

        val fabParams = WindowManager.LayoutParams(
            width,
            height,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // FLAG_NOT_FOCUSABLE: Window won't receive key events (allows touches)
            // We explicitly do NOT use FLAG_NOT_TOUCHABLE so the window receives touch events
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.END
            x = 16
            y = 16
        }

        fabView = ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@BingoOverlayService)
            setViewTreeSavedStateRegistryOwner(this@BingoOverlayService)
            setContent {
                FabButtons(
                    state = overlayState,
                    onToggleMyBoard = {
                        val newShowingMyBoard = !overlayState.isShowingMyBoard
                        overlayState = overlayState.copy(
                            isShowingMyBoard = newShowingMyBoard,
                            isShowingOthers = false
                        )
                        updateContentOverlay(newShowingMyBoard || false)
                    },
                    onToggleOthers = {
                        val newShowingOthers = !overlayState.isShowingOthers
                        overlayState = overlayState.copy(
                            isShowingOthers = newShowingOthers,
                            isShowingMyBoard = false
                        )
                        updateContentOverlay(false || newShowingOthers)
                    },
                    onDetectMeeting = {
                        Log.d(TAG, "Detect meeting button clicked!")
                        triggerScreenshotOcr()
                    },
                    onReset = {
                        Log.d(TAG, "Open app button clicked!")
                        openMainApp()
                    },
                    onToggleMic = {
                        Log.d(TAG, "Mic button clicked!")
                        toggleListening()
                    }
                )
            }
        }

        windowManager.addView(fabView, fabParams)
    }

    private fun updateContentOverlay(show: Boolean) {
        if (show && overlayView == null) {
            // Create full-screen content overlay
            val contentParams = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
            }

            overlayView = ComposeView(this).apply {
                setViewTreeLifecycleOwner(this@BingoOverlayService)
                setViewTreeSavedStateRegistryOwner(this@BingoOverlayService)
                setContent {
                    ContentOverlay(
                        state = overlayState,
                        onCellClick = { row, col ->
                            serviceScope.launch {
                                apiClient?.markCell(row, col)
                            }
                        },
                        onClose = {
                            overlayState = overlayState.copy(
                                isShowingMyBoard = false,
                                isShowingOthers = false
                            )
                            updateContentOverlay(false)
                        }
                    )
                }
            }

            windowManager.addView(overlayView, contentParams)
        } else if (!show && overlayView != null) {
            // Remove content overlay
            try {
                windowManager.removeView(overlayView)
            } catch (e: Exception) {
                Log.e(TAG, "Error removing content overlay", e)
            }
            overlayView = null
        }
    }
}
