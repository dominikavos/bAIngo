package com.example.meetingbingo.viewmodel

import android.app.Application
import android.content.Intent
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.meetingbingo.audio.AudioCaptureManager
import com.example.meetingbingo.data.MeetingWords
import com.example.meetingbingo.model.BingoCell
import com.example.meetingbingo.model.BingoState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * ViewModel for managing the Bingo game state.
 * Uses AudioPlaybackCapture to capture meeting audio (system audio playback).
 */
class BingoViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "BingoViewModel"
    }

    private val _state = MutableStateFlow(BingoState())
    val state: StateFlow<BingoState> = _state.asStateFlow()

    private val audioCaptureManager = AudioCaptureManager(application)
    private var mediaProjectionRequester: (() -> Unit)? = null
    private var pendingStart = false

    init {
        Log.d(TAG, "ViewModel initialized")
        generateNewCard()
        setupAudioCapture()
    }

    private fun setupAudioCapture() {
        Log.d(TAG, "Setting up AudioPlaybackCapture")

        audioCaptureManager.setOnAudioDataListener { audioData ->
            // For now, just show audio level - later can integrate speech recognition
            val level = calculateRmsLevel(audioData)
            _state.update { currentState ->
                currentState.copy(
                    lastHeardWords = "System Audio | Level: %.4f | Samples: %d".format(level, audioData.size)
                )
            }
        }

        viewModelScope.launch {
            audioCaptureManager.error.collect { error ->
                if (error != null) {
                    Log.e(TAG, "Audio error: $error")
                    _state.update { it.copy(errorMessage = error) }
                }
            }
        }

        viewModelScope.launch {
            audioCaptureManager.isRecording.collect { isRecording ->
                _state.update { it.copy(isListening = isRecording) }
            }
        }
    }

    private fun calculateRmsLevel(buffer: ShortArray): Float {
        if (buffer.isEmpty()) return 0f
        var sum = 0.0
        for (sample in buffer) {
            sum += sample * sample
        }
        val rms = kotlin.math.sqrt(sum / buffer.size)
        return (rms / Short.MAX_VALUE).toFloat()
    }

    /**
     * Set the callback for requesting MediaProjection permission from Activity.
     */
    fun setMediaProjectionRequester(requester: () -> Unit) {
        mediaProjectionRequester = requester
    }

    /**
     * Called when MediaProjection is approved.
     */
    fun onMediaProjectionResult(resultCode: Int, data: Intent) {
        Log.d(TAG, "MediaProjection result received")
        audioCaptureManager.startCapture(resultCode, data)
        pendingStart = false
    }

    /**
     * Called when MediaProjection is denied.
     */
    fun onMediaProjectionDenied() {
        Log.d(TAG, "MediaProjection denied")
        pendingStart = false
        _state.update { it.copy(isListening = false, errorMessage = "Screen capture permission required") }
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
     * Start listening - requests MediaProjection permission from Activity.
     */
    fun startListening() {
        Log.d(TAG, "startListening called - requesting MediaProjection")
        pendingStart = true
        mediaProjectionRequester?.invoke()
            ?: run {
                Log.e(TAG, "MediaProjection requester not set!")
                _state.update { it.copy(errorMessage = "Cannot start capture - Activity not ready") }
            }
    }

    /**
     * Stop listening for audio.
     */
    fun stopListening() {
        Log.d(TAG, "stopListening called")
        audioCaptureManager.stopCapture()
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

    /**
     * Cleanup resources.
     */
    fun cleanup() {
        audioCaptureManager.destroy()
    }

    override fun onCleared() {
        super.onCleared()
        cleanup()
    }
}
