package com.example.meetingbingo.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.meetingbingo.model.BingoCell
import com.example.meetingbingo.ui.theme.CellBorder
import com.example.meetingbingo.ui.theme.CellFree
import com.example.meetingbingo.ui.theme.CellMarked
import com.example.meetingbingo.ui.theme.CellUnmarked

// Team space colors
private val CellTeam = Color(0xFFE3F2FD)  // Light blue
private val CellTeamBorder = Color(0xFF1976D2)  // Blue

/**
 * A single cell in the Bingo card.
 */
@Composable
fun BingoCellView(
    cell: BingoCell,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isTeamSpace = cell.isFreeSpace  // isFreeSpace is now used for TEAM! space

    val backgroundColor by animateColorAsState(
        targetValue = when {
            isTeamSpace && cell.isMarked -> CellMarked  // TEAM! marked = green
            isTeamSpace -> CellTeam  // TEAM! unmarked = light blue
            cell.isMarked -> CellMarked
            else -> CellUnmarked
        },
        animationSpec = tween(durationMillis = 300),
        label = "cellColor"
    )

    val textColor = when {
        cell.isMarked -> Color(0xFF2E7D32) // Dark green for marked cells
        isTeamSpace -> CellTeamBorder  // Blue for TEAM! space
        else -> Color(0xFF424242) // Dark gray for unmarked
    }

    val borderColor = when {
        isTeamSpace && cell.isMarked -> CellMarked
        isTeamSpace -> CellTeamBorder
        cell.isMarked -> CellMarked
        else -> CellBorder
    }

    Box(
        modifier = modifier
            .aspectRatio(1f)
            .padding(2.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(backgroundColor)
            .border(
                width = if (isTeamSpace) 2.dp else 1.dp,
                color = borderColor,
                shape = RoundedCornerShape(8.dp)
            )
            .clickable { onClick() },  // All cells can be clicked now
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = cell.word.uppercase(),
            style = MaterialTheme.typography.labelMedium.copy(
                fontWeight = if (cell.isMarked || isTeamSpace) FontWeight.Bold else FontWeight.Medium,
                fontSize = if (isTeamSpace) 18.sp else 14.sp
            ),
            color = textColor,
            textAlign = TextAlign.Center,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(4.dp)
        )
    }
}
