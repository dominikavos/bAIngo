package com.example.meetingbingo.speech

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

/**
 * Manages speech recognition for the Meeting Bingo app.
 * Handles continuous listening and reports recognized words.
 */
class SpeechRecognitionManager(private val context: Context) {

    companion object {
        private const val TAG = "SpeechRecognition"
    }

    private var speechRecognizer: SpeechRecognizer? = null
    private var isListening = false
    private val mainHandler = Handler(Looper.getMainLooper())

    private val _recognizedText = MutableStateFlow("")
    val recognizedText: StateFlow<String> = _recognizedText.asStateFlow()

    private val _isAvailable = MutableStateFlow(true)
    val isAvailable: StateFlow<Boolean> = _isAvailable.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private var onWordsRecognized: ((String) -> Unit)? = null

    init {
        // Check both standard availability and if there's a service that can handle the intent
        val standardAvailable = SpeechRecognizer.isRecognitionAvailable(context)
        Log.e(TAG, "Standard speech recognition available: $standardAvailable")

        // Also check if there's an activity that can handle speech recognition
        val testIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        val activities = context.packageManager.queryIntentActivities(testIntent, 0)
        Log.e(TAG, "Speech recognition activities found: ${activities.size}")

        // We'll try to use it anyway since some devices report false but still work
        _isAvailable.value = true
    }

    /**
     * Set a callback for when words are recognized.
     */
    fun setOnWordsRecognizedListener(listener: (String) -> Unit) {
        onWordsRecognized = listener
    }

    /**
     * Start listening for speech.
     */
    fun startListening() {
        Log.e(TAG, "startListening called, available=${_isAvailable.value}, isListening=$isListening")

        if (!_isAvailable.value) {
            _error.value = "Speech recognition is not available on this device"
            return
        }

        if (isListening) {
            return
        }

        _error.value = null
        isListening = true

        mainHandler.post {
            try {
                speechRecognizer?.destroy()
                speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
                    setRecognitionListener(createRecognitionListener())
                }

                val intent = createRecognizerIntent()
                speechRecognizer?.startListening(intent)
                Log.e(TAG, "Started listening")
            } catch (e: Exception) {
                Log.e(TAG, "Error starting speech recognition", e)
                _error.value = "Error starting speech recognition: ${e.message}"
            }
        }
    }

    /**
     * Stop listening for speech.
     */
    fun stopListening() {
        Log.e(TAG, "stopListening called")
        isListening = false
        mainHandler.post {
            try {
                speechRecognizer?.stopListening()
                speechRecognizer?.destroy()
                speechRecognizer = null
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping speech recognition", e)
            }
        }
    }

    /**
     * Release resources.
     */
    fun destroy() {
        stopListening()
    }

    private fun createRecognizerIntent(): Intent {
        return Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 5000L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 3000L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 3000L)
        }
    }

    private fun createRecognitionListener(): RecognitionListener {
        return object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                Log.e(TAG, "onReadyForSpeech")
                _error.value = null
            }

            override fun onBeginningOfSpeech() {
                Log.e(TAG, "onBeginningOfSpeech")
            }

            override fun onRmsChanged(rmsdB: Float) {}

            override fun onBufferReceived(buffer: ByteArray?) {}

            override fun onEndOfSpeech() {
                Log.e(TAG, "onEndOfSpeech")
            }

            override fun onError(error: Int) {
                val errorMessage = when (error) {
                    SpeechRecognizer.ERROR_AUDIO -> "Audio recording error"
                    SpeechRecognizer.ERROR_CLIENT -> "Client side error"
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Speech recognition not available on this device. Tap cells manually to play!"
                    SpeechRecognizer.ERROR_NETWORK -> "Network error - check internet connection"
                    SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network timeout"
                    SpeechRecognizer.ERROR_NO_MATCH -> "No match"
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Recognizer busy"
                    SpeechRecognizer.ERROR_SERVER -> "Server error"
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Speech timeout"
                    else -> "Unknown error ($error)"
                }
                Log.e(TAG, "onError: $errorMessage")

                // For timeouts and no match, restart listening if still supposed to be active
                if (error == SpeechRecognizer.ERROR_NO_MATCH ||
                    error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                    if (isListening) {
                        mainHandler.postDelayed({ restartListening() }, 100)
                    }
                } else if (error == SpeechRecognizer.ERROR_CLIENT) {
                    // Client error often happens on restart, retry
                    if (isListening) {
                        mainHandler.postDelayed({ restartListening() }, 500)
                    }
                } else {
                    _error.value = errorMessage
                    // Stop trying to listen if speech recognition isn't available
                    if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                        isListening = false
                    }
                }
            }

            override fun onResults(results: Bundle?) {
                Log.e(TAG, "onResults")
                processResults(results)
                // Restart listening for continuous recognition
                if (isListening) {
                    mainHandler.postDelayed({ restartListening() }, 100)
                }
            }

            override fun onPartialResults(partialResults: Bundle?) {
                Log.e(TAG, "onPartialResults")
                processResults(partialResults)
            }

            override fun onEvent(eventType: Int, params: Bundle?) {}
        }
    }

    private fun processResults(results: Bundle?) {
        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
        Log.e(TAG, "processResults: matches=$matches")
        if (!matches.isNullOrEmpty()) {
            val text = matches.first()
            Log.e(TAG, "Recognized text: $text")
            _recognizedText.value = text
            onWordsRecognized?.invoke(text)
        }
    }

    private fun restartListening() {
        if (!isListening) return

        Log.e(TAG, "restartListening")
        mainHandler.post {
            try {
                speechRecognizer?.destroy()
                speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
                    setRecognitionListener(createRecognitionListener())
                }
                speechRecognizer?.startListening(createRecognizerIntent())
            } catch (e: Exception) {
                Log.e(TAG, "Error restarting speech recognition", e)
            }
        }
    }
}
