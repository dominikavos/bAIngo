package com.example.meetingbingo.network

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit

/**
 * Data models for API communication
 */
@Serializable
data class PlayerState(
    val player_id: String,
    val player_name: String,
    val marked_cells: List<List<Boolean>>,
    val has_bingo: Boolean,
    val connected: Boolean
)

@Serializable
data class JoinGameResponse(
    val player_id: String,
    val meeting_id: String,
    val players: List<PlayerState>
)

@Serializable
data class MarkedCellInfo(
    val player_id: String,
    val player_name: String,
    val row: Int,
    val col: Int,
    val word: String
)

@Serializable
data class WebSocketMessage(
    val type: String,
    val player: PlayerState? = null,
    val player_id: String? = null,
    val player_name: String? = null,
    val players: List<PlayerState>? = null,
    val message: String? = null,
    val text: String? = null,  // for transcript messages
    val marked_cells: List<MarkedCellInfo>? = null  // cells marked by transcription
)

/**
 * Client for communicating with the Bingo server.
 */
class BingoApiClient(
    private val serverUrl: String = "http://10.0.2.2:8080"  // Default for emulator
) {
    companion object {
        private const val TAG = "BingoApiClient"
    }

    private val json = Json { ignoreUnknownKeys = true }
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private var webSocket: WebSocket? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _otherPlayers = MutableStateFlow<List<PlayerState>>(emptyList())
    val otherPlayers: StateFlow<List<PlayerState>> = _otherPlayers.asStateFlow()

    private val _events = MutableSharedFlow<GameEvent>()
    val events: SharedFlow<GameEvent> = _events.asSharedFlow()

    private var currentPlayerId: String? = null
    private var currentMeetingId: String? = null

    enum class ConnectionState {
        DISCONNECTED, CONNECTING, CONNECTED, ERROR
    }

    sealed class GameEvent {
        data class PlayerJoined(val player: PlayerState) : GameEvent()
        data class PlayerLeft(val playerId: String, val playerName: String) : GameEvent()
        data class PlayerUpdated(val player: PlayerState) : GameEvent()
        data class Bingo(val playerId: String, val playerName: String) : GameEvent()
        data class Transcript(val text: String, val markedCells: List<MarkedCellInfo>) : GameEvent()
        data class Error(val message: String) : GameEvent()
    }

    /**
     * Join a game room for a meeting.
     */
    suspend fun joinGame(meetingId: String, playerName: String): Result<JoinGameResponse> = withContext(Dispatchers.IO) {
        try {
            _connectionState.value = ConnectionState.CONNECTING

            val requestBody = """{"meeting_id": "$meetingId", "player_name": "$playerName"}"""
            val mediaType = "application/json".toMediaType()
            val request = Request.Builder()
                .url("$serverUrl/api/join")
                .post(requestBody.toRequestBody(mediaType))
                .build()

            val response = client.newCall(request).execute()

            if (!response.isSuccessful) {
                _connectionState.value = ConnectionState.ERROR
                return@withContext Result.failure(Exception("Failed to join: ${response.code}"))
            }

            val body = response.body?.string() ?: ""
            val joinResponse = json.decodeFromString<JoinGameResponse>(body)

            currentPlayerId = joinResponse.player_id
            currentMeetingId = joinResponse.meeting_id
            _otherPlayers.value = joinResponse.players

            // Connect WebSocket for real-time updates
            connectWebSocket(meetingId, joinResponse.player_id)

            Log.d(TAG, "Joined game: meetingId=$meetingId, playerId=${joinResponse.player_id}")
            Result.success(joinResponse)
        } catch (e: Exception) {
            Log.e(TAG, "Error joining game", e)
            _connectionState.value = ConnectionState.ERROR
            Result.failure(e)
        }
    }

    /**
     * Mark a cell and notify the server.
     */
    suspend fun markCell(row: Int, col: Int): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            // Send via WebSocket if connected
            webSocket?.send("""{"type": "mark_cell", "row": $row, "col": $col}""")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Error marking cell", e)
            Result.failure(e)
        }
    }

    /**
     * Set the bingo words for the current player.
     * This allows the server to track which words each player has on their card.
     */
    suspend fun setWords(words: List<List<String>>): Result<Unit> = withContext(Dispatchers.IO) {
        val playerId = currentPlayerId ?: return@withContext Result.failure(Exception("Not joined"))
        val meetingId = currentMeetingId ?: return@withContext Result.failure(Exception("Not joined"))

        try {
            // Build JSON array manually for simplicity
            val wordsJson = buildString {
                append("[")
                words.forEachIndexed { rowIndex, row ->
                    if (rowIndex > 0) append(",")
                    append("[")
                    row.forEachIndexed { colIndex, word ->
                        if (colIndex > 0) append(",")
                        append("\"")
                        append(word.replace("\"", "\\\""))
                        append("\"")
                    }
                    append("]")
                }
                append("]")
            }
            val mediaType = "application/json".toMediaType()
            val request = Request.Builder()
                .url("$serverUrl/api/room/$meetingId/player/$playerId/words")
                .post(wordsJson.toRequestBody(mediaType))
                .build()

            val response = client.newCall(request).execute()

            if (!response.isSuccessful) {
                Log.e(TAG, "Failed to set words: ${response.code}")
                return@withContext Result.failure(Exception("Failed to set words: ${response.code}"))
            }

            Log.d(TAG, "Successfully set words for player $playerId")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Error setting words", e)
            Result.failure(e)
        }
    }

    /**
     * Leave the current game.
     */
    suspend fun leaveGame() = withContext(Dispatchers.IO) {
        val playerId = currentPlayerId ?: return@withContext
        val meetingId = currentMeetingId ?: return@withContext

        try {
            webSocket?.close(1000, "Leaving game")
            webSocket = null

            val request = Request.Builder()
                .url("$serverUrl/api/room/$meetingId/player/$playerId")
                .delete()
                .build()

            client.newCall(request).execute()
        } catch (e: Exception) {
            Log.e(TAG, "Error leaving game", e)
        } finally {
            currentPlayerId = null
            currentMeetingId = null
            _connectionState.value = ConnectionState.DISCONNECTED
            _otherPlayers.value = emptyList()
        }
    }

    private fun connectWebSocket(meetingId: String, playerId: String) {
        val wsUrl = serverUrl.replace("http://", "ws://").replace("https://", "wss://")
        val request = Request.Builder()
            .url("$wsUrl/ws/$meetingId/$playerId")
            .build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(TAG, "WebSocket connected")
                _connectionState.value = ConnectionState.CONNECTED
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                Log.d(TAG, "WebSocket message: $text")
                try {
                    val message = json.decodeFromString<WebSocketMessage>(text)
                    handleWebSocketMessage(message)
                } catch (e: Exception) {
                    Log.e(TAG, "Error parsing WebSocket message", e)
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WebSocket closing: $code - $reason")
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WebSocket closed: $code - $reason")
                _connectionState.value = ConnectionState.DISCONNECTED
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WebSocket failure", t)
                _connectionState.value = ConnectionState.ERROR
            }
        })
    }

    private fun handleWebSocketMessage(message: WebSocketMessage) {
        when (message.type) {
            "sync" -> {
                message.players?.let { players ->
                    _otherPlayers.value = players
                }
            }
            "player_joined" -> {
                message.player?.let { player ->
                    _otherPlayers.value = _otherPlayers.value + player
                    scope.launch {
                        _events.emit(GameEvent.PlayerJoined(player))
                    }
                }
            }
            "player_left", "player_disconnected" -> {
                val playerId = message.player_id ?: return
                val playerName = message.player_name ?: "Unknown"
                _otherPlayers.value = _otherPlayers.value.map {
                    if (it.player_id == playerId) it.copy(connected = false) else it
                }
                scope.launch {
                    _events.emit(GameEvent.PlayerLeft(playerId, playerName))
                }
            }
            "player_reconnected" -> {
                val playerId = message.player_id ?: return
                _otherPlayers.value = _otherPlayers.value.map {
                    if (it.player_id == playerId) it.copy(connected = true) else it
                }
            }
            "player_updated" -> {
                message.player?.let { player ->
                    _otherPlayers.value = _otherPlayers.value.map {
                        if (it.player_id == player.player_id) player else it
                    }
                    scope.launch {
                        _events.emit(GameEvent.PlayerUpdated(player))
                    }
                }
            }
            "bingo" -> {
                val playerId = message.player_id ?: return
                val playerName = message.player_name ?: "Unknown"
                scope.launch {
                    _events.emit(GameEvent.Bingo(playerId, playerName))
                }
            }
            "transcript" -> {
                val text = message.text ?: ""
                val markedCells = message.marked_cells ?: emptyList()
                scope.launch {
                    _events.emit(GameEvent.Transcript(text, markedCells))
                }
            }
            "error" -> {
                val errorMessage = message.message ?: "Unknown error"
                scope.launch {
                    _events.emit(GameEvent.Error(errorMessage))
                }
            }
        }
    }
}
