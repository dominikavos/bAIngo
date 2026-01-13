package com.example.meetingbingo.model

/**
 * Represents a single cell in the Bingo card.
 *
 * @param word The word/phrase displayed in this cell
 * @param isMarked Whether this cell has been marked (word was detected)
 * @param isFreeSpace Whether this is the center free space
 * @param row The row position (0-4)
 * @param column The column position (0-4)
 */
data class BingoCell(
    val word: String,
    val isMarked: Boolean = false,
    val isFreeSpace: Boolean = false,
    val row: Int,
    val column: Int
)
