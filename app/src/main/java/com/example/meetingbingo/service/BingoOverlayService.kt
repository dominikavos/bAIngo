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
import com.example.meetingbingo.R
import com.example.meetingbingo.network.BingoApiClient
import com.example.meetingbingo.network.PlayerState
import com.example.meetingbingo.ui.overlay.OverlayContent
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

    data class OverlayState(
        val isShowingMyBoard: Boolean = false,
        val isShowingOthers: Boolean = false,
        val myMarkedCells: List<List<Boolean>> = List(5) { List(5) { false } },
        val myHasBingo: Boolean = false,
        val otherPlayers: List<PlayerState> = emptyList(),
        val myWords: List<List<String>> = emptyList(),
        val connectionState: BingoApiClient.ConnectionState = BingoApiClient.ConnectionState.DISCONNECTED
    )

    private lateinit var windowManager: WindowManager
    private var overlayView: ComposeView? = null
    private var fabView: ComposeView? = null

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var apiClient: BingoApiClient? = null

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

        // Initialize API client and connect
        apiClient = BingoApiClient(serverUrl)

        serviceScope.launch {
            // Join the game
            apiClient?.joinGame(meetingId, playerName)?.onSuccess { response ->
                Log.d(TAG, "Joined game with ${response.players.size} other players")
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

        // Create FAB overlay (always visible toggle buttons)
        createFabOverlay()
    }

    private fun stopOverlay() {
        Log.d(TAG, "Stopping overlay")

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
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }

        fabView = ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@BingoOverlayService)
            setViewTreeSavedStateRegistryOwner(this@BingoOverlayService)
            setContent {
                OverlayContent(
                    state = overlayState,
                    onToggleMyBoard = {
                        overlayState = overlayState.copy(
                            isShowingMyBoard = !overlayState.isShowingMyBoard,
                            isShowingOthers = false
                        )
                        updateOverlayTouchability()
                    },
                    onToggleOthers = {
                        overlayState = overlayState.copy(
                            isShowingOthers = !overlayState.isShowingOthers,
                            isShowingMyBoard = false
                        )
                        updateOverlayTouchability()
                    },
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
                        updateOverlayTouchability()
                    }
                )
            }
        }

        windowManager.addView(fabView, params)
    }

    private fun updateOverlayTouchability() {
        val isShowingContent = overlayState.isShowingMyBoard || overlayState.isShowingOthers

        fabView?.let { view ->
            val params = view.layoutParams as WindowManager.LayoutParams
            params.flags = if (isShowingContent) {
                // When showing content, allow touches everywhere
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
            } else {
                // When not showing content, only capture touches on FABs
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
            }
            try {
                windowManager.updateViewLayout(view, params)
            } catch (e: Exception) {
                Log.e(TAG, "Error updating overlay layout", e)
            }
        }
    }
}
