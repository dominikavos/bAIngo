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
 * Transcribes audio using OpenAI's Whisper API.
 * Buffers audio samples and sends them periodically for transcription.
 */
class WhisperTranscriber(private val apiKey: String) {

    companion object {
        private const val TAG = "WhisperTranscriber"
        private const val WHISPER_API_URL = "https://api.openai.com/v1/audio/transcriptions"
        private const val SAMPLE_RATE = 48000 // NeatDevKit uses 48kHz
        private const val BUFFER_DURATION_SECONDS = 5 // Send audio every 5 seconds
        private const val BUFFER_SIZE = SAMPLE_RATE * BUFFER_DURATION_SECONDS
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    private val audioBuffer = mutableListOf<Float>()
    private var onTranscript: ((String) -> Unit)? = null

    @Serializable
    data class WhisperResponse(val text: String)

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

        if (audioBuffer.size >= BUFFER_SIZE) {
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

        try {
            val wavData = samplesToWav(samples, SAMPLE_RATE)
            val transcript = sendToWhisper(wavData)

            if (transcript.isNotBlank()) {
                Log.d(TAG, "Transcript: $transcript")
                onTranscript?.invoke(transcript)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Transcription error", e)
        }
    }

    private suspend fun sendToWhisper(wavData: ByteArray): String = withContext(Dispatchers.IO) {
        val requestBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                "file",
                "audio.wav",
                wavData.toRequestBody("audio/wav".toMediaType())
            )
            .addFormDataPart("model", "whisper-1")
            .addFormDataPart("language", "en")
            .addFormDataPart("response_format", "json")
            .build()

        val request = Request.Builder()
            .url(WHISPER_API_URL)
            .header("Authorization", "Bearer $apiKey")
            .post(requestBody)
            .build()

        val response = client.newCall(request).execute()

        if (!response.isSuccessful) {
            val errorBody = response.body?.string() ?: "No error body"
            Log.e(TAG, "Whisper API error: ${response.code} - $errorBody")
            throw Exception("Whisper API error: ${response.code}")
        }

        val responseBody = response.body?.string() ?: throw Exception("Empty response")
        val whisperResponse = json.decodeFromString<WhisperResponse>(responseBody)
        whisperResponse.text
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
