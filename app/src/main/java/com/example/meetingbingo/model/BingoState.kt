package com.example.meetingbingo.model

/**
 * Represents the complete state of the Bingo game.
 *
 * @param cells The 5x5 grid of bingo cells (stored as a flat list)
 * @param isListening Whether speech recognition is currently active
 * @param hasBingo Whether the player has achieved a bingo
 * @param lastHeardWords Recent words that were detected from speech
 * @param errorMessage Any error message to display to the user
 */
data class BingoState(
    val cells: List<BingoCell> = emptyList(),
    val isListening: Boolean = false,
    val hasBingo: Boolean = false,
    val lastHeardWords: String = "",
    val errorMessage: String? = null
) {
    /**
     * Get a cell at a specific row and column.
     */
    fun getCell(row: Int, column: Int): BingoCell? {
        return cells.find { it.row == row && it.column == column }
    }

    /**
     * Get cells organized as a 2D grid for display.
     */
    fun getGrid(): List<List<BingoCell>> {
        return (0 until 5).map { row ->
            (0 until 5).map { col ->
                getCell(row, col) ?: BingoCell("", row = row, column = col)
            }
        }
    }
}
