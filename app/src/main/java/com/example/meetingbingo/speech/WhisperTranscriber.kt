package com.example.meetingbingo.speech

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit

/**
 * Transcribes audio using a local faster-whisper server.
 * Buffers audio samples and sends them periodically for transcription.
 * The server marks bingo cells only for the player who submitted the audio.
 */
class WhisperTranscriber(
    private val serverUrl: String,
    private val meetingId: String,
    private var playerId: String = ""
) {

    init {
        Log.d(TAG, "WhisperTranscriber initialized. Server: $serverUrl, Meeting: $meetingId")
    }

    /**
     * Set the player ID for this transcriber.
     * Must be called after joining a game to get the player ID.
     */
    fun setPlayerId(id: String) {
        Log.d(TAG, "Player ID set to: $id")
        playerId = id
    }

    companion object {
        private const val TAG = "WhisperTranscriber"
        private const val SAMPLE_RATE = 48000 // NeatDevKit uses 48kHz
        private const val BUFFER_DURATION_SECONDS = 5 // Send audio every 5 seconds
        private const val BUFFER_SIZE = SAMPLE_RATE * BUFFER_DURATION_SECONDS
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS) // Longer timeout for local transcription
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    private val audioBuffer = mutableListOf<Float>()
    private var onTranscript: ((String) -> Unit)? = null

    @Serializable
    data class TranscribeResponse(
        val status: String,
        val transcript: String,
        val language: String? = null
    )

    fun setOnTranscriptListener(listener: (String) -> Unit) {
        onTranscript = listener
    }

    /**
     * Add audio samples to the buffer. When buffer is full, triggers transcription.
     * Returns true if transcription was triggered.
     */
    suspend fun addSamples(samples: FloatArray): Boolean {
        synchronized(audioBuffer) {
            audioBuffer.addAll(samples.toList())
        }

        val currentSize = audioBuffer.size
        // Log buffer progress periodically (every ~1 second at 48kHz)
        if (currentSize % 48000 == 0 && currentSize > 0) {
            Log.d(TAG, "Buffer: $currentSize / $BUFFER_SIZE samples (${currentSize * 100 / BUFFER_SIZE}%)")
        }

        if (currentSize >= BUFFER_SIZE) {
            Log.d(TAG, "Buffer full ($currentSize samples)! Triggering transcription...")
            val samplesToProcess: FloatArray
            synchronized(audioBuffer) {
                samplesToProcess = audioBuffer.toFloatArray()
                audioBuffer.clear()
            }

            // Transcribe in background
            transcribe(samplesToProcess)
            return true
        }
        return false
    }

    /**
     * Force transcription of current buffer (e.g., when stopping).
     */
    suspend fun flushBuffer() {
        val samplesToProcess: FloatArray
        synchronized(audioBuffer) {
            if (audioBuffer.isEmpty()) return
            samplesToProcess = audioBuffer.toFloatArray()
            audioBuffer.clear()
        }
        transcribe(samplesToProcess)
    }

    fun clearBuffer() {
        synchronized(audioBuffer) {
            audioBuffer.clear()
        }
    }

    fun getBufferDurationMs(): Long {
        return (audioBuffer.size * 1000L) / SAMPLE_RATE
    }

    private suspend fun transcribe(samples: FloatArray) {
        if (samples.isEmpty()) return

        Log.d(TAG, "Starting transcription of ${samples.size} samples (${samples.size / SAMPLE_RATE}s of audio)")

        try {
            val wavData = samplesToWav(samples, SAMPLE_RATE)
            Log.d(TAG, "WAV data created: ${wavData.size} bytes")

            val transcript = sendToServer(wavData)
            Log.d(TAG, "Server returned: '$transcript'")

            if (transcript.isNotBlank()) {
                Log.d(TAG, "Invoking transcript listener with: $transcript")
                onTranscript?.invoke(transcript)
            } else {
                Log.d(TAG, "Transcript was blank, not invoking listener")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Transcription error: ${e.message}", e)
        }
    }

    private suspend fun sendToServer(wavData: ByteArray): String = withContext(Dispatchers.IO) {
        if (playerId.isBlank()) {
            Log.w(TAG, "Player ID not set, skipping transcription")
            return@withContext ""
        }

        val transcribeUrl = "$serverUrl/api/transcribe"
        Log.d(TAG, "Sending audio to: $transcribeUrl (player: $playerId)")

        val requestBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                "audio",
                "audio.wav",
                wavData.toRequestBody("audio/wav".toMediaType())
            )
            .addFormDataPart("meeting_id", meetingId)
            .addFormDataPart("player_id", playerId)
            .build()

        val request = Request.Builder()
            .url(transcribeUrl)
            .post(requestBody)
            .build()

        val response = client.newCall(request).execute()

        if (!response.isSuccessful) {
            val errorBody = response.body?.string() ?: "No error body"
            Log.e(TAG, "Server error: ${response.code} - $errorBody")
            throw Exception("Server error: ${response.code}")
        }

        val responseBody = response.body?.string() ?: throw Exception("Empty response")
        Log.d(TAG, "Response body: $responseBody")

        val transcribeResponse = json.decodeFromString<TranscribeResponse>(responseBody)
        transcribeResponse.transcript
    }

    /**
     * Convert float samples to WAV format.
     */
    private fun samplesToWav(samples: FloatArray, sampleRate: Int): ByteArray {
        val byteBuffer = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)

        // Convert float (-1.0 to 1.0) to 16-bit PCM
        for (sample in samples) {
            val clampedSample = sample.coerceIn(-1f, 1f)
            val pcmValue = (clampedSample * 32767).toInt().toShort()
            byteBuffer.putShort(pcmValue)
        }

        val pcmData = byteBuffer.array()
        return createWavFile(pcmData, sampleRate, 1, 16)
    }

    private fun createWavFile(pcmData: ByteArray, sampleRate: Int, channels: Int, bitsPerSample: Int): ByteArray {
        val output = ByteArrayOutputStream()

        val byteRate = sampleRate * channels * bitsPerSample / 8
        val blockAlign = channels * bitsPerSample / 8
        val dataSize = pcmData.size
        val chunkSize = 36 + dataSize

        // RIFF header
        output.write("RIFF".toByteArray())
        output.write(intToBytes(chunkSize))
        output.write("WAVE".toByteArray())

        // fmt subchunk
        output.write("fmt ".toByteArray())
        output.write(intToBytes(16)) // Subchunk1Size for PCM
        output.write(shortToBytes(1)) // AudioFormat (1 = PCM)
        output.write(shortToBytes(channels.toShort()))
        output.write(intToBytes(sampleRate))
        output.write(intToBytes(byteRate))
        output.write(shortToBytes(blockAlign.toShort()))
        output.write(shortToBytes(bitsPerSample.toShort()))

        // data subchunk
        output.write("data".toByteArray())
        output.write(intToBytes(dataSize))
        output.write(pcmData)

        return output.toByteArray()
    }

    private fun intToBytes(value: Int): ByteArray {
        return ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array()
    }

    private fun shortToBytes(value: Short): ByteArray {
        return ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(value).array()
    }
}
