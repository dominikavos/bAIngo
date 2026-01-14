package com.example.meetingbingo

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.example.meetingbingo.service.BingoAccessibilityService
import com.example.meetingbingo.service.BingoOverlayService
import com.example.meetingbingo.ui.screens.SetupScreen
import com.example.meetingbingo.ui.theme.MeetingBingoTheme
import com.example.meetingbingo.viewmodel.BingoViewModel

class MainActivity : ComponentActivity() {

    private val viewModel: BingoViewModel by viewModels()
    private var isAccessibilityEnabled = false

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            Toast.makeText(this, "Audio permission granted", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(
                this,
                "Audio permission is required for meeting audio capture",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        checkAndRequestPermission()

        setContent {
            MeetingBingoTheme {
                val accessibilityEnabled by BingoAccessibilityService.isServiceEnabled.collectAsState()

                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    SetupScreen(
                        viewModel = viewModel,
                        onStartOverlay = { meetingId, playerName, serverUrl ->
                            startOverlayService(meetingId, playerName, serverUrl)
                        },
                        onStopOverlay = {
                            stopOverlayService()
                        },
                        hasOverlayPermission = Settings.canDrawOverlays(this),
                        onRequestOverlayPermission = {
                            requestOverlayPermission()
                        },
                        hasAccessibilityPermission = accessibilityEnabled,
                        onRequestAccessibilityPermission = {
                            requestAccessibilityPermission()
                        }
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Update UI if overlay permission changed
        viewModel.updateOverlayPermission(Settings.canDrawOverlays(this))
    }

    private fun checkAndRequestPermission() {
        when {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED -> {
                // Permission already granted
            }
            shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO) -> {
                Toast.makeText(
                    this,
                    "Audio permission is needed to capture meeting audio",
                    Toast.LENGTH_LONG
                ).show()
                requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
            else -> {
                requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        }
    }

    private fun requestOverlayPermission() {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName")
        )
        startActivity(intent)
    }

    private fun requestAccessibilityPermission() {
        Toast.makeText(
            this,
            "Please enable Meeting Bingo in Accessibility Settings",
            Toast.LENGTH_LONG
        ).show()
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        startActivity(intent)
    }

    private fun startOverlayService(meetingId: String, playerName: String, serverUrl: String) {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Overlay permission required", Toast.LENGTH_SHORT).show()
            requestOverlayPermission()
            return
        }

        // Update ViewModel with game data
        viewModel.setGameInfo(meetingId, playerName, serverUrl)

        val intent = Intent(this, BingoOverlayService::class.java).apply {
            action = BingoOverlayService.ACTION_START
            putExtra(BingoOverlayService.EXTRA_MEETING_ID, meetingId)
            putExtra(BingoOverlayService.EXTRA_PLAYER_NAME, playerName)
            putExtra(BingoOverlayService.EXTRA_SERVER_URL, serverUrl)
        }
        startForegroundService(intent)

        // Sync the words to the overlay
        viewModel.syncWordsToOverlay()

        Toast.makeText(this, "Bingo overlay started", Toast.LENGTH_SHORT).show()

        // Minimize the app to show the overlay
        moveTaskToBack(true)
    }

    private fun stopOverlayService() {
        val intent = Intent(this, BingoOverlayService::class.java).apply {
            action = BingoOverlayService.ACTION_STOP
        }
        startService(intent)
        Toast.makeText(this, "Bingo overlay stopped", Toast.LENGTH_SHORT).show()
    }
}
