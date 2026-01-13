package com.example.meetingbingo.viewmodel

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.meetingbingo.data.MeetingWords
import com.example.meetingbingo.model.BingoCell
import com.example.meetingbingo.model.BingoState
import com.example.meetingbingo.speech.SpeechRecognitionManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * ViewModel for managing the Bingo game state.
 * Handles card generation, word detection, and win checking.
 */
class BingoViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "BingoViewModel"
    }

    private val _state = MutableStateFlow(BingoState())
    val state: StateFlow<BingoState> = _state.asStateFlow()

    private val speechManager = SpeechRecognitionManager(application)

    init {
        Log.e(TAG, "ViewModel initialized")
        generateNewCard()
        setupSpeechRecognition()
    }

    private fun setupSpeechRecognition() {
        Log.e(TAG, "Setting up speech recognition listener")
        speechManager.setOnWordsRecognizedListener { text ->
            Log.e(TAG, "Words recognized callback: $text")
            processRecognizedText(text)
        }

        viewModelScope.launch {
            speechManager.error.collect { error ->
                Log.e(TAG, "Speech error: $error")
                _state.update { it.copy(errorMessage = error) }
            }
        }
    }

    /**
     * Generate a new Bingo card with random meeting words.
     */
    fun generateNewCard() {
        val words = MeetingWords.getRandomWords(24)
        val cells = mutableListOf<BingoCell>()

        var wordIndex = 0
        for (row in 0 until 5) {
            for (col in 0 until 5) {
                val cell = if (row == 2 && col == 2) {
                    // Center cell is the FREE space
                    BingoCell(
                        word = "FREE",
                        isMarked = true,
                        isFreeSpace = true,
                        row = row,
                        column = col
                    )
                } else {
                    BingoCell(
                        word = words[wordIndex++],
                        isMarked = false,
                        isFreeSpace = false,
                        row = row,
                        column = col
                    )
                }
                cells.add(cell)
            }
        }

        _state.update {
            BingoState(
                cells = cells,
                isListening = false,
                hasBingo = false,
                lastHeardWords = "",
                errorMessage = null
            )
        }
    }

    /**
     * Start listening for speech.
     */
    fun startListening() {
        Log.e(TAG, "startListening called")
        speechManager.startListening()
        _state.update { it.copy(isListening = true, errorMessage = null) }
    }

    /**
     * Stop listening for speech.
     */
    fun stopListening() {
        Log.e(TAG, "stopListening called")
        speechManager.stopListening()
        _state.update { it.copy(isListening = false) }
    }

    /**
     * Toggle listening state.
     */
    fun toggleListening() {
        Log.e(TAG, "toggleListening called, current state: ${_state.value.isListening}")
        if (_state.value.isListening) {
            stopListening()
        } else {
            startListening()
        }
    }

    /**
     * Manually mark a cell (for testing or manual play).
     */
    fun markCell(row: Int, column: Int) {
        _state.update { currentState ->
            val updatedCells = currentState.cells.map { cell ->
                if (cell.row == row && cell.column == column && !cell.isFreeSpace) {
                    cell.copy(isMarked = !cell.isMarked)
                } else {
                    cell
                }
            }
            val newState = currentState.copy(cells = updatedCells)
            newState.copy(hasBingo = checkForBingo(newState))
        }
    }

    /**
     * Process recognized text and mark matching cells.
     */
    private fun processRecognizedText(text: String) {
        Log.d(TAG, "processRecognizedText: $text")
        val lowerText = text.lowercase()

        _state.update { currentState ->
            var hasNewMatch = false
            val updatedCells = currentState.cells.map { cell ->
                if (!cell.isMarked && !cell.isFreeSpace) {
                    // Check if the recognized text contains this cell's word
                    val cellWord = cell.word.lowercase()
                    if (lowerText.contains(cellWord) ||
                        cellWord.split(" ").all { word -> lowerText.contains(word) }) {
                        hasNewMatch = true
                        cell.copy(isMarked = true)
                    } else {
                        cell
                    }
                } else {
                    cell
                }
            }

            val newState = currentState.copy(
                cells = updatedCells,
                lastHeardWords = text
            )
            newState.copy(hasBingo = checkForBingo(newState))
        }
    }

    /**
     * Check if the current state represents a Bingo (5 in a row, column, or diagonal).
     */
    private fun checkForBingo(state: BingoState): Boolean {
        val grid = state.getGrid()

        // Check rows
        for (row in 0 until 5) {
            if (grid[row].all { it.isMarked }) {
                return true
            }
        }

        // Check columns
        for (col in 0 until 5) {
            if ((0 until 5).all { row -> grid[row][col].isMarked }) {
                return true
            }
        }

        // Check diagonal (top-left to bottom-right)
        if ((0 until 5).all { i -> grid[i][i].isMarked }) {
            return true
        }

        // Check diagonal (top-right to bottom-left)
        if ((0 until 5).all { i -> grid[i][4 - i].isMarked }) {
            return true
        }

        return false
    }

    /**
     * Clear any error message.
     */
    fun clearError() {
        _state.update { it.copy(errorMessage = null) }
    }

    override fun onCleared() {
        super.onCleared()
        speechManager.destroy()
    }
}
