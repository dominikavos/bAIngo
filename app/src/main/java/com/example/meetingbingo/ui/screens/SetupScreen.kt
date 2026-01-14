package com.example.meetingbingo.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.LaunchedEffect
import com.example.meetingbingo.service.BingoAccessibilityService
import com.example.meetingbingo.service.BingoOverlayService
import com.example.meetingbingo.ui.components.BingoCard
import com.example.meetingbingo.ui.theme.BingoGreen
import com.example.meetingbingo.ui.theme.BingoOrange
import com.example.meetingbingo.ui.theme.BingoRed
import com.example.meetingbingo.viewmodel.BingoViewModel

@Composable
fun SetupScreen(
    viewModel: BingoViewModel,
    onStartOverlay: (meetingId: String, playerName: String, serverUrl: String) -> Unit,
    onStopOverlay: () -> Unit,
    hasOverlayPermission: Boolean,
    onRequestOverlayPermission: () -> Unit,
    hasAccessibilityPermission: Boolean = false,
    onRequestAccessibilityPermission: () -> Unit = {}
) {
    val state by viewModel.state.collectAsState()

    // Observe detected meeting ID from overlay service
    val detectedMeetingId by BingoAccessibilityService.extractedMeetingId.collectAsState()

    var meetingId by remember { mutableStateOf("") }
    var playerName by remember { mutableStateOf("Player") }
    var serverUrl by remember { mutableStateOf("http://10.47.6.1:8080") }
    var isOverlayRunning by remember { mutableStateOf(false) }

    // Update meeting ID field when a new one is detected
    LaunchedEffect(detectedMeetingId) {
        detectedMeetingId?.let {
            meetingId = it
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f),
                        MaterialTheme.colorScheme.background
                    )
                )
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Meeting Bingo",
                    style = MaterialTheme.typography.headlineMedium.copy(
                        fontWeight = FontWeight.Bold
                    ),
                    color = MaterialTheme.colorScheme.primary
                )

                IconButton(onClick = { viewModel.generateNewCard() }) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = "New Card",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Setup Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "Game Setup",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )

                    OutlinedTextField(
                        value = meetingId,
                        onValueChange = { meetingId = it },
                        label = { Text(if (meetingId.isEmpty()) "Meeting ID (detect via overlay)" else "Meeting ID") },
                        placeholder = { Text("Use detect button in overlay") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )

                    OutlinedTextField(
                        value = playerName,
                        onValueChange = { playerName = it },
                        label = { Text("Your Name") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )

                    OutlinedTextField(
                        value = serverUrl,
                        onValueChange = { serverUrl = it },
                        label = { Text("Server URL") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )

                    // Overlay permission status
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = if (hasOverlayPermission) Icons.Default.CheckCircle else Icons.Default.Warning,
                            contentDescription = null,
                            tint = if (hasOverlayPermission) BingoGreen else BingoOrange,
                            modifier = Modifier.size(20.dp)
                        )
                        Text(
                            text = if (hasOverlayPermission) "Overlay permission granted" else "Overlay permission required",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (hasOverlayPermission) BingoGreen else BingoOrange
                        )
                        if (!hasOverlayPermission) {
                            Button(
                                onClick = onRequestOverlayPermission,
                                colors = ButtonDefaults.buttonColors(containerColor = BingoOrange),
                                modifier = Modifier.height(32.dp)
                            ) {
                                Text("Grant", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }

                    // Accessibility permission status (for auto-detecting meeting ID)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = if (hasAccessibilityPermission) Icons.Default.CheckCircle else Icons.Default.Warning,
                            contentDescription = null,
                            tint = if (hasAccessibilityPermission) BingoGreen else Color.Gray,
                            modifier = Modifier.size(20.dp)
                        )
                        Text(
                            text = if (hasAccessibilityPermission) "Auto-detect meeting ID enabled" else "Auto-detect meeting ID (optional)",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (hasAccessibilityPermission) BingoGreen else Color.Gray
                        )
                        if (!hasAccessibilityPermission) {
                            Button(
                                onClick = onRequestAccessibilityPermission,
                                colors = ButtonDefaults.buttonColors(containerColor = Color.Gray),
                                modifier = Modifier.height(32.dp)
                            ) {
                                Text("Enable", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // Start/Stop buttons
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Button(
                            onClick = {
                                // Use "pending" as placeholder if no meeting ID yet
                                val effectiveMeetingId = meetingId.ifBlank { "pending" }
                                onStartOverlay(effectiveMeetingId, playerName, serverUrl)
                                isOverlayRunning = true
                            },
                            enabled = hasOverlayPermission && !isOverlayRunning && playerName.isNotBlank(),
                            colors = ButtonDefaults.buttonColors(containerColor = BingoGreen),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.PlayArrow, contentDescription = null)
                            Spacer(modifier = Modifier.size(4.dp))
                            Text("Start")
                        }

                        Button(
                            onClick = {
                                onStopOverlay()
                                isOverlayRunning = false
                            },
                            enabled = isOverlayRunning,
                            colors = ButtonDefaults.buttonColors(containerColor = BingoRed),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.Stop, contentDescription = null)
                            Spacer(modifier = Modifier.size(4.dp))
                            Text("Stop")
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Preview of Bingo Card
            Text(
                text = "Your Card Preview",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.align(Alignment.Start)
            )

            Spacer(modifier = Modifier.height(8.dp))

            BingoCard(
                cells = state.getGrid(),
                onCellClick = { row, col -> viewModel.markCell(row, col) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp)
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Status
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)
                ),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .size(12.dp)
                                .clip(CircleShape)
                                .background(
                                    color = if (isOverlayRunning) BingoGreen else Color.Gray
                                )
                        )
                        Text(
                            text = if (isOverlayRunning) "  Overlay Active" else "  Overlay Inactive",
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (isOverlayRunning) BingoGreen else Color.Gray
                        )
                    }

                    if (state.hasBingo) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "BINGO!",
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.ExtraBold,
                            color = BingoOrange
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(80.dp))
        }
    }
}
