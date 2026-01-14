package com.example.meetingbingo.ui.overlay

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.meetingbingo.network.BingoApiClient
import com.example.meetingbingo.network.PlayerState
import com.example.meetingbingo.service.BingoOverlayService
import com.example.meetingbingo.ui.theme.BingoGreen
import com.example.meetingbingo.ui.theme.BingoOrange
import com.example.meetingbingo.ui.theme.BingoRed
import com.example.meetingbingo.ui.theme.CellFree
import com.example.meetingbingo.ui.theme.CellMarked

/**
 * FAB buttons overlay - small footprint, doesn't block touches elsewhere.
 */
@Composable
fun FabButtons(
    state: BingoOverlayService.OverlayState,
    onToggleMyBoard: () -> Unit,
    onToggleOthers: () -> Unit,
    onDetectMeeting: (() -> Unit)? = null,
    onReset: (() -> Unit)? = null,
    onToggleMic: (() -> Unit)? = null
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Audio status text (shows mic level when listening)
        if (state.audioStatus.isNotEmpty()) {
            Text(
                text = state.audioStatus,
                color = Color.White,
                fontSize = 8.sp,
                maxLines = 2,
                modifier = Modifier
                    .background(Color.Black.copy(alpha = 0.7f), RoundedCornerShape(4.dp))
                    .padding(4.dp)
            )
        }

        // Connection indicator
        ConnectionIndicator(state.connectionState)

        // Microphone button - toggle listening
        FloatingActionButton(
            onClick = { onToggleMic?.invoke() },
            containerColor = if (state.isListening) BingoOrange else Color.Gray,
            modifier = Modifier.size(48.dp)
        ) {
            Icon(
                imageVector = if (state.isListening) Icons.Default.Mic else Icons.Default.MicOff,
                contentDescription = if (state.isListening) "Stop listening" else "Start listening",
                tint = Color.White
            )
        }

        // Reset button
        FloatingActionButton(
            onClick = { onReset?.invoke() },
            containerColor = Color.Gray,
            modifier = Modifier.size(48.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Refresh,
                contentDescription = "Reset Game",
                tint = Color.White
            )
        }

        // Detect Meeting ID button - color based on status
        // Stay green if we have a detected meeting ID, even after restart
        val hasDetectedMeetingId = state.detectedMeetingId != null
        val detectButtonColor = when {
            hasDetectedMeetingId -> BingoGreen
            state.meetingIdDetectionStatus == BingoOverlayService.MeetingIdDetectionStatus.DETECTING -> BingoOrange
            state.meetingIdDetectionStatus == BingoOverlayService.MeetingIdDetectionStatus.FAILED -> BingoRed
            else -> Color.Gray
        }
        FloatingActionButton(
            onClick = { onDetectMeeting?.invoke() },
            containerColor = detectButtonColor,
            modifier = Modifier.size(48.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Search,
                contentDescription = "Detect Meeting ID",
                tint = Color.White
            )
        }

        // Others button
        FloatingActionButton(
            onClick = onToggleOthers,
            containerColor = if (state.isShowingOthers) BingoOrange else BingoGreen,
            modifier = Modifier.size(48.dp)
        ) {
            Icon(
                imageVector = Icons.Default.People,
                contentDescription = "Show others",
                tint = Color.White
            )
        }

        // My board button
        FloatingActionButton(
            onClick = onToggleMyBoard,
            containerColor = if (state.isShowingMyBoard) BingoOrange else BingoGreen,
            modifier = Modifier.size(56.dp)
        ) {
            Icon(
                imageVector = Icons.Default.GridOn,
                contentDescription = "My board",
                tint = Color.White
            )
        }
    }
}

/**
 * Content overlay - full screen with scrim and board panels.
 */
@Composable
fun ContentOverlay(
    state: BingoOverlayService.OverlayState,
    onCellClick: (Int, Int) -> Unit,
    onClose: () -> Unit
) {
    Box(
        modifier = Modifier.fillMaxSize()
    ) {
        // Background scrim
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.5f))
                .clickable { onClose() }
        )

        // My Board overlay
        if (state.isShowingMyBoard) {
            Box(modifier = Modifier.align(Alignment.Center)) {
                MyBoardOverlay(
                    markedCells = state.myMarkedCells,
                    words = state.myWords,
                    hasBingo = state.myHasBingo,
                    onCellClick = onCellClick,
                    onClose = onClose
                )
            }
        }

        // Others' boards overlay
        if (state.isShowingOthers) {
            Box(modifier = Modifier.align(Alignment.Center)) {
                OtherPlayersOverlay(
                    players = state.otherPlayers,
                    onClose = onClose
                )
            }
        }
    }
}

@Composable
private fun ConnectionIndicator(state: BingoApiClient.ConnectionState) {
    val color = when (state) {
        BingoApiClient.ConnectionState.CONNECTED -> BingoGreen
        BingoApiClient.ConnectionState.CONNECTING -> BingoOrange
        BingoApiClient.ConnectionState.DISCONNECTED -> Color.Gray
        BingoApiClient.ConnectionState.ERROR -> BingoRed
    }

    Box(
        modifier = Modifier
            .size(12.dp)
            .clip(CircleShape)
            .background(color)
    )
}

@Composable
fun MyBoardOverlay(
    markedCells: List<List<Boolean>>,
    words: List<List<String>>,
    hasBingo: Boolean,
    onCellClick: (Int, Int) -> Unit,
    onClose: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth(0.9f)
            .padding(16.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "My Board",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = if (hasBingo) BingoGreen else Color.Black
                )
                IconButton(onClick = onClose) {
                    Icon(Icons.Default.Close, contentDescription = "Close")
                }
            }

            if (hasBingo) {
                Text(
                    text = "BINGO!",
                    color = BingoOrange,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 24.sp
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Board grid
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color.LightGray.copy(alpha = 0.3f))
                    .padding(4.dp)
            ) {
                for (row in 0 until 5) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        for (col in 0 until 5) {
                            val isMarked = markedCells.getOrNull(row)?.getOrNull(col) ?: false
                            val isFreeSpace = row == 2 && col == 2
                            val word = words.getOrNull(row)?.getOrNull(col) ?: if (isFreeSpace) "FREE" else ""

                            OverlayCell(
                                word = word,
                                isMarked = isMarked || isFreeSpace,
                                isFreeSpace = isFreeSpace,
                                showWord = true,
                                onClick = { onCellClick(row, col) },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun OtherPlayersOverlay(
    players: List<PlayerState>,
    onClose: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth(0.9f)
            .fillMaxSize(0.8f)
            .padding(16.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Other Players (${players.size})",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                IconButton(onClick = onClose) {
                    Icon(Icons.Default.Close, contentDescription = "Close")
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            if (players.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "No other players yet",
                        color = Color.Gray
                    )
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    contentPadding = PaddingValues(vertical = 8.dp)
                ) {
                    items(players) { player ->
                        PlayerBoardCard(player = player)
                    }
                }
            }
        }
    }
}

@Composable
fun PlayerBoardCard(player: PlayerState) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (player.has_bingo) CellMarked.copy(alpha = 0.3f) else Color.LightGray.copy(alpha = 0.2f)
        )
    ) {
        Column(
            modifier = Modifier.padding(12.dp)
        ) {
            // Player header
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Connection indicator
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(if (player.connected) BingoGreen else Color.Gray)
                )

                Text(
                    text = player.player_name,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )

                if (player.has_bingo) {
                    Text(
                        text = "BINGO!",
                        color = BingoOrange,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Mini board (no words, just marked/unmarked)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(4.dp))
                    .padding(2.dp)
            ) {
                for (row in 0 until 5) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(1.dp)
                    ) {
                        for (col in 0 until 5) {
                            val isMarked = player.marked_cells.getOrNull(row)?.getOrNull(col) ?: false
                            val isFreeSpace = row == 2 && col == 2

                            OverlayCell(
                                word = "",
                                isMarked = isMarked || isFreeSpace,
                                isFreeSpace = isFreeSpace,
                                showWord = false,
                                onClick = {},
                                modifier = Modifier.weight(1f),
                                mini = true
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun OverlayCell(
    word: String,
    isMarked: Boolean,
    isFreeSpace: Boolean,
    showWord: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    mini: Boolean = false
) {
    val backgroundColor = when {
        isFreeSpace -> CellFree
        isMarked -> CellMarked
        else -> Color.White
    }

    val aspectRatio = if (mini) 1f else 1f
    val shape = RoundedCornerShape(if (mini) 2.dp else 6.dp)

    Box(
        modifier = modifier
            .aspectRatio(aspectRatio)
            .padding(if (mini) 0.5.dp else 2.dp)
            .clip(shape)
            .background(backgroundColor)
            .border(
                width = if (mini) 0.5.dp else 1.dp,
                color = if (isMarked) CellMarked else Color.LightGray,
                shape = shape
            )
            .clickable(enabled = showWord && !isFreeSpace) { onClick() },
        contentAlignment = Alignment.Center
    ) {
        if (showWord && word.isNotEmpty()) {
            Text(
                text = word.uppercase(),
                fontSize = if (isFreeSpace) 14.sp else 12.sp,
                fontWeight = if (isMarked) FontWeight.Bold else FontWeight.Normal,
                textAlign = TextAlign.Center,
                color = if (isMarked) Color(0xFF2E7D32) else Color.DarkGray,
                maxLines = 3,
                modifier = Modifier.padding(2.dp)
            )
        }
    }
}
