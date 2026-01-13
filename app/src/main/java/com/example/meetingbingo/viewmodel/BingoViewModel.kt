package com.example.meetingbingo.viewmodel

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.meetingbingo.MeetingBingoApplication
import com.example.meetingbingo.data.MeetingWords
import com.example.meetingbingo.model.BingoCell
import com.example.meetingbingo.model.BingoState
import com.example.meetingbingo.speech.NeatAudioManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * ViewModel for managing the Bingo game state.
 * Uses NeatDevKit Audio API to capture meeting audio.
 */
class BingoViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "BingoViewModel"
    }

    private val _state = MutableStateFlow(BingoState())
    val state: StateFlow<BingoState> = _state.asStateFlow()

    private val neatAudioManager = NeatAudioManager(MeetingBingoApplication.neatDevKit)

    init {
        Log.d(TAG, "ViewModel initialized")
        generateNewCard()
        setupAudioRecording()
    }

    private fun setupAudioRecording() {
        Log.d(TAG, "Setting up NeatDevKit audio recording")
        Log.d(TAG, "Audio recording available: ${neatAudioManager.isAvailable.value}")

        neatAudioManager.setOnAudioReceivedListener { micLevel, speakerLevel, samples ->
            // Update the UI with audio level info
            _state.update { currentState ->
                currentState.copy(
                    lastHeardWords = "Mic: %.4f | Speaker: %.4f | Samples: %d".format(micLevel, speakerLevel, samples)
                )
            }
        }

        viewModelScope.launch {
            neatAudioManager.error.collect { error ->
                if (error != null) {
                    Log.e(TAG, "Audio error: $error")
                    _state.update { it.copy(errorMessage = error) }
                }
            }
        }

        viewModelScope.launch {
            neatAudioManager.audioStatus.collect { status ->
                if (status.isNotEmpty()) {
                    Log.d(TAG, "Audio status: $status")
                }
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
     * Start listening for audio.
     */
    fun startListening() {
        Log.d(TAG, "startListening called - using NeatDevKit audio")
        val success = neatAudioManager.startRecording()
        _state.update { it.copy(isListening = success, errorMessage = if (!success) "Failed to start recording" else null) }
    }

    /**
     * Stop listening for audio.
     */
    fun stopListening() {
        Log.d(TAG, "stopListening called")
        neatAudioManager.stopRecording()
        _state.update { it.copy(isListening = false) }
    }

    /**
     * Toggle listening state.
     */
    fun toggleListening() {
        Log.d(TAG, "toggleListening called, current state: ${_state.value.isListening}")
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
            val updatedCells = currentState.cells.map { cell ->
                if (!cell.isMarked && !cell.isFreeSpace) {
                    val cellWord = cell.word.lowercase()
                    if (lowerText.contains(cellWord) ||
                        cellWord.split(" ").all { word -> lowerText.contains(word) }) {
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
     * Check if the current state represents a Bingo.
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
        neatAudioManager.destroy()
    }
}
