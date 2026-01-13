package com.example.meetingbingo.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
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

/**
 * A single cell in the Bingo card.
 */
@Composable
fun BingoCellView(
    cell: BingoCell,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val backgroundColor by animateColorAsState(
        targetValue = when {
            cell.isFreeSpace -> CellFree
            cell.isMarked -> CellMarked
            else -> CellUnmarked
        },
        animationSpec = tween(durationMillis = 300),
        label = "cellColor"
    )

    val textColor = if (cell.isMarked || cell.isFreeSpace) {
        Color(0xFF2E7D32) // Dark green for marked cells
    } else {
        Color(0xFF424242) // Dark gray for unmarked
    }

    Box(
        modifier = modifier
            .aspectRatio(1f)
            .padding(2.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(backgroundColor)
            .border(
                width = 1.dp,
                color = if (cell.isMarked) CellMarked else CellBorder,
                shape = RoundedCornerShape(8.dp)
            )
            .clickable(enabled = !cell.isFreeSpace) { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = cell.word.uppercase(),
            style = MaterialTheme.typography.labelMedium.copy(
                fontWeight = if (cell.isMarked) FontWeight.Bold else FontWeight.Medium,
                fontSize = if (cell.isFreeSpace) 14.sp else 10.sp
            ),
            color = textColor,
            textAlign = TextAlign.Center,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .fillMaxSize()
                .padding(4.dp)
                .wrapContentHeight(Alignment.CenterVertically)
        )
    }
}

@Composable
private fun Modifier.wrapContentHeight(alignment: Alignment.Vertical): Modifier {
    return this
}
