package com.example.meetingbingo.ui.overlay

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
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
 * Now with single button to show combined board view.
 */
@Composable
fun FabButtons(
    state: BingoOverlayService.OverlayState,
    onToggleBoards: () -> Unit,
    onDetectMeeting: (() -> Unit)? = null,
    onReset: (() -> Unit)? = null
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Transcript display (shows last transcribed text)
        if (state.lastTranscript.isNotEmpty()) {
            Text(
                text = state.lastTranscript.takeLast(100),
                color = Color.White,
                fontSize = 9.sp,
                maxLines = 3,
                modifier = Modifier
                    .width(90.dp)
                    .background(BingoGreen.copy(alpha = 0.9f), RoundedCornerShape(4.dp))
                    .padding(4.dp)
            )
        }

        // Audio status text (shows speaker level when listening)
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

        // Reset button (opens main app)
        FloatingActionButton(
            onClick = { onReset?.invoke() },
            containerColor = Color.Gray,
            modifier = Modifier.size(48.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Refresh,
                contentDescription = "Open App",
                tint = Color.White
            )
        }

        // Detect Meeting ID button
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

        // Combined boards button (shows my board + others)
        FloatingActionButton(
            onClick = onToggleBoards,
            containerColor = if (state.isShowingMyBoard) BingoOrange else BingoGreen,
            modifier = Modifier.size(56.dp)
        ) {
            Icon(
                imageVector = Icons.Default.GridOn,
                contentDescription = "Show Boards",
                tint = Color.White
            )
        }
    }
}

/**
 * Content overlay - shows combined view of my board and others' boards.
 * Adapts layout based on orientation:
 * - Landscape: My board on left, others scrollable on right
 * - Portrait: My board on top, others scrollable horizontally on bottom
 */
@Composable
fun ContentOverlay(
    state: BingoOverlayService.OverlayState,
    onClose: () -> Unit
) {
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    Box(
        modifier = Modifier.fillMaxSize()
    ) {
        // Background scrim
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.6f))
                .clickable { onClose() }
        )

        // Combined board view
        if (state.isShowingMyBoard) {
            Card(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(8.dp),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.95f)),
                elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
            ) {
                if (isLandscape) {
                    LandscapeBoardLayout(
                        state = state,
                        onClose = onClose
                    )
                } else {
                    PortraitBoardLayout(
                        state = state,
                        onClose = onClose
                    )
                }
            }
        }
    }
}

@Composable
private fun LandscapeBoardLayout(
    state: BingoOverlayService.OverlayState,
    onClose: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxSize()
            .padding(8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // My board on the left (takes up more space)
        Column(
            modifier = Modifier
                .weight(0.6f)
                .fillMaxHeight(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Header with close button
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (state.myHasBingo) "MY BOARD - BINGO!" else "My Board",
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    color = if (state.myHasBingo) BingoOrange else Color.Black
                )
                IconButton(onClick = onClose, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.Close, contentDescription = "Close", modifier = Modifier.size(20.dp))
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            // My board grid
            Box(
                modifier = Modifier
                    .weight(1f)
                    .aspectRatio(1f)
            ) {
                BingoGrid(
                    markedCells = state.myMarkedCells,
                    words = state.myWords,
                    showWords = true
                )
            }
        }

        // Others on the right (scrollable column)
        Column(
            modifier = Modifier
                .weight(0.4f)
                .fillMaxHeight()
        ) {
            Text(
                text = "Others (${state.otherPlayers.size})",
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
                modifier = Modifier.padding(bottom = 4.dp)
            )

            if (state.otherPlayers.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text("No other players", color = Color.Gray, fontSize = 12.sp)
                }
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(2.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    state.otherPlayers.forEach { player ->
                        MiniPlayerBoard(
                            player = player,
                            modifier = Modifier.widthIn(max = 200.dp)  // Max width for landscape
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PortraitBoardLayout(
    state: BingoOverlayService.OverlayState,
    onClose: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(8.dp)
    ) {
        // Header with close button
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = if (state.myHasBingo) "MY BOARD - BINGO!" else "My Board",
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp,
                color = if (state.myHasBingo) BingoOrange else Color.Black
            )
            IconButton(onClick = onClose, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Default.Close, contentDescription = "Close", modifier = Modifier.size(20.dp))
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        // My board grid (main focus)
        Box(
            modifier = Modifier
                .weight(0.7f)
                .fillMaxWidth()
                .aspectRatio(1f),
            contentAlignment = Alignment.Center
        ) {
            BingoGrid(
                markedCells = state.myMarkedCells,
                words = state.myWords,
                showWords = true
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Others section (horizontal scroll)
        Text(
            text = "Others (${state.otherPlayers.size})",
            fontWeight = FontWeight.Bold,
            fontSize = 14.sp
        )

        Spacer(modifier = Modifier.height(4.dp))

        if (state.otherPlayers.isEmpty()) {
            Box(
                modifier = Modifier
                    .weight(0.3f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Text("No other players yet", color = Color.Gray, fontSize = 12.sp)
            }
        } else {
            Row(
                modifier = Modifier
                    .weight(0.3f)
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                state.otherPlayers.forEach { player ->
                    MiniPlayerBoard(
                        player = player,
                        modifier = Modifier.width(180.dp)  // Larger boards in portrait
                    )
                }
            }
        }
    }
}

@Composable
private fun BingoGrid(
    markedCells: List<List<Boolean>>,
    words: List<List<String>>,
    showWords: Boolean,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Color.LightGray.copy(alpha = 0.3f))
            .padding(4.dp)
    ) {
        for (row in 0 until 5) {
            Row(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                for (col in 0 until 5) {
                    val isMarked = markedCells.getOrNull(row)?.getOrNull(col) ?: false
                    val isTeamSpace = row == 2 && col == 2
                    val word = words.getOrNull(row)?.getOrNull(col) ?: if (isTeamSpace) "TEAM!" else ""

                    OverlayCell(
                        word = word,
                        isMarked = isMarked,
                        isTeamSpace = isTeamSpace,
                        showWord = showWords,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

@Composable
private fun MiniPlayerBoard(
    player: PlayerState,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (player.has_bingo) CellMarked.copy(alpha = 0.3f) else Color.LightGray.copy(alpha = 0.2f)
        )
    ) {
        Column(
            modifier = Modifier.padding(6.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Player name with status
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(if (player.connected) BingoGreen else Color.Gray)
                )
                Text(
                    text = player.player_name,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (player.has_bingo) {
                    Text(
                        text = "!",
                        color = BingoOrange,
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            // Mini grid
            Column(
                modifier = Modifier
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(4.dp))
            ) {
                for (row in 0 until 5) {
                    Row(
                        modifier = Modifier.weight(1f),
                        horizontalArrangement = Arrangement.spacedBy(1.dp)
                    ) {
                        for (col in 0 until 5) {
                            val isMarked = player.marked_cells.getOrNull(row)?.getOrNull(col) ?: false
                            val isTeamSpace = row == 2 && col == 2

                            OverlayCell(
                                word = "",
                                isMarked = isMarked,
                                isTeamSpace = isTeamSpace,
                                showWord = false,
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

// Team space color - a nice blue/purple to stand out
private val CellTeam = Color(0xFFE3F2FD)  // Light blue
private val CellTeamBorder = Color(0xFF1976D2)  // Blue

@Composable
private fun OverlayCell(
    word: String,
    isMarked: Boolean,
    isTeamSpace: Boolean = false,
    showWord: Boolean,
    modifier: Modifier = Modifier,
    mini: Boolean = false
) {
    val backgroundColor = when {
        isTeamSpace && isMarked -> CellMarked  // When TEAM! is marked, show green
        isTeamSpace -> CellTeam  // TEAM! space - light blue
        isMarked -> CellMarked
        else -> Color.White
    }

    val borderColor = when {
        isTeamSpace && isMarked -> CellMarked
        isTeamSpace -> CellTeamBorder
        isMarked -> CellMarked
        else -> Color.LightGray
    }

    val shape = RoundedCornerShape(if (mini) 2.dp else 6.dp)

    Box(
        modifier = modifier
            .aspectRatio(1f)
            .padding(if (mini) 0.5.dp else 2.dp)
            .clip(shape)
            .background(backgroundColor)
            .border(
                width = if (mini) 0.5.dp else if (isTeamSpace) 2.dp else 1.dp,
                color = borderColor,
                shape = shape
            ),
        contentAlignment = Alignment.Center
    ) {
        if (showWord && word.isNotEmpty()) {
            Text(
                text = word.uppercase(),
                fontSize = if (isTeamSpace) 12.sp else 10.sp,
                fontWeight = if (isMarked || isTeamSpace) FontWeight.Bold else FontWeight.Normal,
                textAlign = TextAlign.Center,
                color = when {
                    isMarked -> Color(0xFF2E7D32)  // Green when marked
                    isTeamSpace -> CellTeamBorder  // Blue for TEAM!
                    else -> Color.DarkGray
                },
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(1.dp)
            )
        }
    }
}
