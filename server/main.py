#!/usr/bin/env python3
"""
Meeting Bingo Server
Manages game state for multiplayer bingo sessions.
Each meeting has its own game room where players can join and share their progress.
Includes local Whisper transcription for speech-to-text.
"""

import asyncio
import io
import json
import os
import re
import tempfile
import uuid
from datetime import datetime
from typing import Dict, List, Optional, Set
from dataclasses import dataclass, field, asdict
from contextlib import asynccontextmanager

from fastapi import FastAPI, WebSocket, WebSocketDisconnect, HTTPException, UploadFile, File, Form, Request
from fastapi.middleware.cors import CORSMiddleware
from pydantic import BaseModel
from nltk.stem import PorterStemmer

# Initialize stemmer for word matching
stemmer = PorterStemmer()

# Whisper model - loaded at startup
from faster_whisper import WhisperModel

model_size = os.environ.get("WHISPER_MODEL", "base")
print(f"[WHISPER] Loading faster-whisper model ({model_size})...")
whisper_model = WhisperModel(model_size, device="cpu", compute_type="int8")
print("[WHISPER] Model loaded successfully")


# Data models
@dataclass
class PlayerState:
    player_id: str
    player_name: str
    client_ip: str = ""  # Track client IP to deduplicate players
    marked_cells: List[List[bool]] = field(default_factory=lambda: [[False]*5 for _ in range(5)])
    words: List[List[str]] = field(default_factory=lambda: [[""]*5 for _ in range(5)])
    has_bingo: bool = False
    connected: bool = True
    last_seen: datetime = field(default_factory=datetime.now)

    def __post_init__(self):
        # Center cell is TEAM! - not pre-marked, but can be marked when someone says "TEAM!"
        pass

    def to_dict(self) -> dict:
        return {
            "player_id": self.player_id,
            "player_name": self.player_name,
            "marked_cells": self.marked_cells,
            "words": self.words,
            "has_bingo": self.has_bingo,
            "connected": self.connected
        }


@dataclass
class GameRoom:
    meeting_id: str
    players: Dict[str, PlayerState] = field(default_factory=dict)
    websockets: Dict[str, WebSocket] = field(default_factory=dict)
    created_at: datetime = field(default_factory=datetime.now)

    def get_all_player_states(self, exclude_player_id: Optional[str] = None) -> List[dict]:
        """Get all player states, optionally excluding one player."""
        return [
            p.to_dict() for p in self.players.values()
            if p.player_id != exclude_player_id
        ]


# Global game state
game_rooms: Dict[str, GameRoom] = {}


async def cleanup_old_rooms():
    """Background task to clean up rooms older than 1 hour."""
    while True:
        await asyncio.sleep(300)  # Check every 5 minutes
        now = datetime.now()
        rooms_to_delete = []

        for meeting_id, room in game_rooms.items():
            age_seconds = (now - room.created_at).total_seconds()
            if age_seconds > 3600:  # 1 hour
                rooms_to_delete.append(meeting_id)
                print(f"[CLEANUP] Room {meeting_id} expired (age: {age_seconds/60:.1f} min)")

        for meeting_id in rooms_to_delete:
            room = game_rooms[meeting_id]
            # Close all websockets
            for ws in list(room.websockets.values()):
                try:
                    await ws.send_json({"type": "room_expired", "message": "Game session expired"})
                    await ws.close()
                except:
                    pass
            del game_rooms[meeting_id]

        if rooms_to_delete:
            print(f"[CLEANUP] Removed {len(rooms_to_delete)} expired rooms, {len(game_rooms)} rooms remaining")


@asynccontextmanager
async def lifespan(app: FastAPI):
    # Startup
    print("Meeting Bingo Server starting...")
    cleanup_task = asyncio.create_task(cleanup_old_rooms())
    yield
    # Shutdown
    print("Meeting Bingo Server shutting down...")
    cleanup_task.cancel()
    # Close all websocket connections
    for room in game_rooms.values():
        for ws in room.websockets.values():
            try:
                await ws.close()
            except:
                pass


app = FastAPI(
    title="Meeting Bingo Server",
    description="Multiplayer bingo game server for meetings",
    version="1.0.0",
    lifespan=lifespan
)

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)


# Pydantic models for REST API
class JoinGameRequest(BaseModel):
    meeting_id: str
    player_name: str


class JoinGameResponse(BaseModel):
    player_id: str
    meeting_id: str
    players: List[dict]


class MarkCellRequest(BaseModel):
    meeting_id: str
    player_id: str
    row: int
    col: int


class PlayerStateResponse(BaseModel):
    player_id: str
    player_name: str
    marked_cells: List[List[bool]]
    has_bingo: bool
    connected: bool


# Helper functions
def check_bingo(marked_cells: List[List[bool]]) -> bool:
    """Check if the player has achieved bingo."""
    # Check rows
    for row in marked_cells:
        if all(row):
            return True

    # Check columns
    for col in range(5):
        if all(marked_cells[row][col] for row in range(5)):
            return True

    # Check diagonals
    if all(marked_cells[i][i] for i in range(5)):
        return True
    if all(marked_cells[i][4-i] for i in range(5)):
        return True

    return False


async def broadcast_to_room(room: GameRoom, message: dict, exclude_player_id: Optional[str] = None):
    """Broadcast a message to all connected players in a room."""
    disconnected = []
    # Create a copy of items to avoid RuntimeError during iteration
    websocket_items = list(room.websockets.items())
    for player_id, ws in websocket_items:
        if player_id == exclude_player_id:
            continue
        try:
            await ws.send_json(message)
        except:
            disconnected.append(player_id)

    # Clean up disconnected websockets
    for player_id in disconnected:
        if player_id in room.websockets:
            del room.websockets[player_id]
        if player_id in room.players:
            room.players[player_id].connected = False


# REST API endpoints
@app.get("/")
async def root():
    return {"status": "ok", "service": "Meeting Bingo Server"}


@app.get("/health")
async def health():
    return {"status": "healthy", "rooms": len(game_rooms)}


@app.post("/api/join", response_model=JoinGameResponse)
async def join_game(join_request: JoinGameRequest, request: Request):
    """Join or create a game room for a meeting."""
    meeting_id = join_request.meeting_id
    player_name = join_request.player_name
    client_ip = request.client.host if request.client else "unknown"
    player_id = str(uuid.uuid4())[:8]

    # Create room if it doesn't exist
    if meeting_id not in game_rooms:
        game_rooms[meeting_id] = GameRoom(meeting_id=meeting_id)
        print(f"[JOIN] Created new room for meeting_id={meeting_id}")

    room = game_rooms[meeting_id]

    # Remove any existing players from the same IP (handles reconnects/app restarts)
    # This ensures one device = one player
    players_to_remove = [
        pid for pid, p in room.players.items()
        if p.client_ip == client_ip
    ]
    for pid in players_to_remove:
        old_player = room.players[pid]
        print(f"[JOIN] Removing duplicate player '{old_player.player_name}' (id={pid}) from same IP {client_ip}")
        # Close old websocket if exists
        if pid in room.websockets:
            try:
                await room.websockets[pid].close()
            except:
                pass
            del room.websockets[pid]
        del room.players[pid]
        # Notify others about the removal
        await broadcast_to_room(room, {
            "type": "player_left",
            "player_id": pid,
            "player_name": old_player.player_name
        })

    # Create player state
    player = PlayerState(
        player_id=player_id,
        player_name=player_name,
        client_ip=client_ip
    )
    room.players[player_id] = player

    print(f"[JOIN] Player '{player_name}' (id={player_id}, ip={client_ip}) joined meeting_id={meeting_id} (total players: {len(room.players)})")

    # Broadcast player joined to others
    await broadcast_to_room(room, {
        "type": "player_joined",
        "player": player.to_dict()
    }, exclude_player_id=player_id)

    return JoinGameResponse(
        player_id=player_id,
        meeting_id=meeting_id,
        players=room.get_all_player_states(exclude_player_id=player_id)
    )


@app.post("/api/mark")
async def mark_cell(request: MarkCellRequest):
    """Mark a cell on the player's board."""
    meeting_id = request.meeting_id
    player_id = request.player_id
    row = request.row
    col = request.col

    if meeting_id not in game_rooms:
        raise HTTPException(status_code=404, detail="Room not found")

    room = game_rooms[meeting_id]

    if player_id not in room.players:
        raise HTTPException(status_code=404, detail="Player not found")

    player = room.players[player_id]

    # Mark the cell
    if 0 <= row < 5 and 0 <= col < 5:
        player.marked_cells[row][col] = True
        player.has_bingo = check_bingo(player.marked_cells)
        player.last_seen = datetime.now()

    # Broadcast update to all players
    await broadcast_to_room(room, {
        "type": "player_updated",
        "player": player.to_dict()
    })

    # If player got bingo, send special notification
    if player.has_bingo:
        await broadcast_to_room(room, {
            "type": "bingo",
            "player_id": player_id,
            "player_name": player.player_name
        })

    return {"status": "ok", "has_bingo": player.has_bingo}


@app.get("/api/room/{meeting_id}")
async def get_room_state(meeting_id: str):
    """Get the current state of a game room."""
    if meeting_id not in game_rooms:
        raise HTTPException(status_code=404, detail="Room not found")

    room = game_rooms[meeting_id]
    return {
        "meeting_id": meeting_id,
        "players": room.get_all_player_states(),
        "player_count": len(room.players)
    }


@app.delete("/api/room/{meeting_id}/player/{player_id}")
async def leave_game(meeting_id: str, player_id: str):
    """Leave a game room."""
    if meeting_id not in game_rooms:
        raise HTTPException(status_code=404, detail="Room not found")

    room = game_rooms[meeting_id]

    if player_id in room.players:
        player = room.players[player_id]
        player.connected = False

        # Notify others
        await broadcast_to_room(room, {
            "type": "player_left",
            "player_id": player_id,
            "player_name": player.player_name
        })

        # Remove from websockets
        if player_id in room.websockets:
            del room.websockets[player_id]

    return {"status": "ok"}


@app.post("/api/room/{meeting_id}/reset")
async def reset_room(meeting_id: str):
    """Reset a game room - removes all players and resets state. For testing."""
    if meeting_id not in game_rooms:
        return {"status": "ok", "message": "Room did not exist"}

    room = game_rooms[meeting_id]

    # Notify all players
    await broadcast_to_room(room, {
        "type": "room_reset",
        "message": "Game has been reset"
    })

    # Close all websockets
    for ws in list(room.websockets.values()):
        try:
            await ws.close()
        except:
            pass

    # Delete the room
    del game_rooms[meeting_id]
    print(f"[RESET] Room {meeting_id} has been reset")

    return {"status": "ok", "message": f"Room {meeting_id} has been reset"}


@app.post("/api/reset-all")
async def reset_all_rooms():
    """Reset all game rooms. For testing/development."""
    room_count = len(game_rooms)

    # Notify and close all
    for meeting_id, room in list(game_rooms.items()):
        await broadcast_to_room(room, {
            "type": "room_reset",
            "message": "Server reset - all games cleared"
        })
        for ws in list(room.websockets.values()):
            try:
                await ws.close()
            except:
                pass

    game_rooms.clear()
    print(f"[RESET] All {room_count} rooms have been reset")

    return {"status": "ok", "message": f"Reset {room_count} rooms"}


# WebSocket endpoint for real-time updates
@app.websocket("/ws/{meeting_id}/{player_id}")
async def websocket_endpoint(websocket: WebSocket, meeting_id: str, player_id: str):
    """WebSocket connection for real-time game updates."""
    await websocket.accept()

    # Validate room and player
    if meeting_id not in game_rooms:
        await websocket.send_json({"type": "error", "message": "Room not found"})
        await websocket.close()
        return

    room = game_rooms[meeting_id]

    if player_id not in room.players:
        await websocket.send_json({"type": "error", "message": "Player not found"})
        await websocket.close()
        return

    # Register websocket
    room.websockets[player_id] = websocket
    room.players[player_id].connected = True

    # Send current state to newly connected player
    await websocket.send_json({
        "type": "sync",
        "players": room.get_all_player_states(exclude_player_id=player_id)
    })

    # Notify others of reconnection
    await broadcast_to_room(room, {
        "type": "player_reconnected",
        "player_id": player_id,
        "player_name": room.players[player_id].player_name
    }, exclude_player_id=player_id)

    try:
        while True:
            # Receive messages from client
            data = await websocket.receive_json()

            if data.get("type") == "mark_cell":
                row = data.get("row", -1)
                col = data.get("col", -1)

                if 0 <= row < 5 and 0 <= col < 5:
                    player = room.players[player_id]
                    player.marked_cells[row][col] = True
                    player.has_bingo = check_bingo(player.marked_cells)
                    player.last_seen = datetime.now()

                    # Broadcast update
                    await broadcast_to_room(room, {
                        "type": "player_updated",
                        "player": player.to_dict()
                    })

                    if player.has_bingo:
                        await broadcast_to_room(room, {
                            "type": "bingo",
                            "player_id": player_id,
                            "player_name": player.player_name
                        })

            elif data.get("type") == "ping":
                await websocket.send_json({"type": "pong"})
                room.players[player_id].last_seen = datetime.now()

    except WebSocketDisconnect:
        # Handle disconnect
        if player_id in room.websockets:
            del room.websockets[player_id]
        if player_id in room.players:
            room.players[player_id].connected = False

            await broadcast_to_room(room, {
                "type": "player_disconnected",
                "player_id": player_id,
                "player_name": room.players[player_id].player_name
            })


# ============== TRANSCRIPTION ENDPOINTS ==============

def normalize_text(text: str) -> str:
    """Normalize text for matching - lowercase, remove punctuation."""
    return re.sub(r'[^\w\s]', '', text.lower())


def split_hyphenated(text: str) -> List[str]:
    """Split hyphenated words into separate words, lowercase."""
    # Replace hyphens with spaces, then normalize
    return re.sub(r'[^\w\s]', ' ', text.lower()).split()


def stem_words(words: List[str]) -> List[str]:
    """Apply Porter stemming to a list of words."""
    return [stemmer.stem(w) for w in words]


def phrase_matches_transcript(phrase_stems: List[str], transcript_stems: List[str], max_gap: int = 3) -> bool:
    """
    Check if all stemmed words in the phrase appear in the transcript in order,
    allowing other words in between (up to max_gap words between consecutive matches).

    Example: phrase "think outside box" matches transcript "thinking outside the box"
             because stemmed forms match: "think" matches "think", etc.

    The max_gap parameter (default 3) prevents matching words that are too far apart
    (e.g., "win" in two different sentences). This allows for natural insertions like:
    - "take it offline" (1 word gap)
    - "take this thing offline" (2 word gap)
    - "it's a win win" (0 word gap)
    """
    if not phrase_stems:
        return False

    phrase_idx = 0
    last_match_pos = -1

    for i, transcript_stem in enumerate(transcript_stems):
        if transcript_stem == phrase_stems[phrase_idx]:
            # Check gap from previous match (skip for first match)
            if phrase_idx > 0 and (i - last_match_pos - 1) > max_gap:
                # Gap too large, reset and try again from this position
                phrase_idx = 0
                if transcript_stem == phrase_stems[0]:
                    phrase_idx = 1
                    last_match_pos = i
                continue

            last_match_pos = i
            phrase_idx += 1
            if phrase_idx == len(phrase_stems):
                return True

    return False


def find_matching_words(transcript: str, words: List[List[str]]) -> List[tuple]:
    """
    Find which bingo words appear in the transcript.
    Returns list of (row, col) tuples for matching cells.

    Uses flexible matching:
    - Stemming to handle verb conjugations (thinking -> think, synergies -> synergi)
    - Phrase words must appear in order but other words can appear in between
    - Handles "Take Offline" matching "take it offline"
    - Handles "win-win" matching "win win" (hyphenated phrases split into words)
    - Uses max gap of 3 words to prevent matching across unrelated sentences
    """
    normalized_transcript = normalize_text(transcript)
    transcript_words = normalized_transcript.split()
    transcript_stems = stem_words(transcript_words)
    matches = []

    for row in range(5):
        for col in range(5):
            word = words[row][col]
            if not word or word.upper() == "FREE":
                continue

            # Split hyphenated words (e.g., "win-win" -> ["win", "win"])
            phrase_words = split_hyphenated(word)

            if not phrase_words:
                continue

            # Stem the phrase words
            phrase_stems = stem_words(phrase_words)

            # For single words, use simple stemmed word matching
            if len(phrase_stems) == 1:
                if phrase_stems[0] in transcript_stems:
                    matches.append((row, col))
            else:
                # For multi-word phrases, check if stemmed words appear in order (with gap limit)
                if phrase_matches_transcript(phrase_stems, transcript_stems):
                    matches.append((row, col))

    return matches


@app.post("/api/transcribe")
async def transcribe_audio(
    audio: UploadFile = File(...),
    meeting_id: str = Form(...),
    player_id: str = Form(...)
):
    """
    Transcribe audio and auto-mark matching bingo cells for the submitting player ONLY.
    The player who submits audio is hearing others speak, so we only mark their cells.
    This prevents players from marking their own words by speaking them.
    """
    print(f"[TRANSCRIBE] Received audio for meeting_id={meeting_id}, player_id={player_id}, size={audio.size if hasattr(audio, 'size') else 'unknown'}")

    # Read audio data
    audio_data = await audio.read()
    print(f"[TRANSCRIBE] Audio data size: {len(audio_data)} bytes")

    # Save to temp file for Whisper
    with tempfile.NamedTemporaryFile(suffix=".wav", delete=False) as tmp_file:
        tmp_file.write(audio_data)
        tmp_path = tmp_file.name

    try:
        # Analyze WAV file for debugging
        import wave
        import numpy as np
        try:
            with wave.open(tmp_path, 'rb') as wav:
                channels = wav.getnchannels()
                sample_width = wav.getsampwidth()
                framerate = wav.getframerate()
                n_frames = wav.getnframes()
                duration = n_frames / framerate

                # Read and analyze samples
                frames = wav.readframes(n_frames)
                samples = np.frombuffer(frames, dtype=np.int16)

                # Calculate audio stats
                max_val = np.max(np.abs(samples))
                rms = np.sqrt(np.mean(samples.astype(np.float64)**2))

                print(f"[TRANSCRIBE] WAV: {channels}ch, {sample_width*8}bit, {framerate}Hz, {duration:.2f}s")
                print(f"[TRANSCRIBE] Audio stats: max={max_val}, rms={rms:.1f}, max_possible=32767")
        except Exception as wav_err:
            print(f"[TRANSCRIBE] WAV analysis error: {wav_err}")

        # Transcribe with Whisper
        segments, info = whisper_model.transcribe(tmp_path, language="en")

        # Combine all segments
        transcript = " ".join(segment.text for segment in segments).strip()
        print(f"[TRANSCRIBE] Result: '{transcript}'")

        # If room exists, check for matching words and mark cells for the submitting player ONLY
        marked_cells_info = []
        if meeting_id in game_rooms:
            room = game_rooms[meeting_id]

            # Only mark cells for the player who submitted the audio
            if player_id in room.players:
                player = room.players[player_id]
                matches = find_matching_words(transcript, player.words)

                for row, col in matches:
                    if not player.marked_cells[row][col]:
                        player.marked_cells[row][col] = True
                        word = player.words[row][col]
                        marked_cells_info.append({
                            "player_id": player_id,
                            "player_name": player.player_name,
                            "row": row,
                            "col": col,
                            "word": word
                        })
                        print(f"[TRANSCRIBE] Marked '{word}' for {player.player_name} at ({row},{col})")

                # Check for bingo
                if matches:
                    player.has_bingo = check_bingo(player.marked_cells)

                # Broadcast transcript to all players (so they can see what was heard)
                await broadcast_to_room(room, {
                    "type": "transcript",
                    "text": transcript,
                    "marked_cells": marked_cells_info
                })

                # Broadcast updated state for the player who got cells marked
                await broadcast_to_room(room, {
                    "type": "player_updated",
                    "player": player.to_dict()
                })

                if player.has_bingo:
                    await broadcast_to_room(room, {
                        "type": "bingo",
                        "player_id": player_id,
                        "player_name": player.player_name
                    })
            else:
                print(f"[TRANSCRIBE] Player {player_id} not found in room {meeting_id}")

        return {
            "status": "ok",
            "transcript": transcript,
            "language": info.language,
            "marked_cells": marked_cells_info
        }

    except Exception as e:
        print(f"[TRANSCRIBE] Error: {e}")
        raise HTTPException(status_code=500, detail=str(e))
    finally:
        # Clean up temp file
        try:
            os.unlink(tmp_path)
        except:
            pass


@app.post("/api/room/{meeting_id}/player/{player_id}/words")
async def set_player_words(meeting_id: str, player_id: str, words: List[List[str]]):
    """Set the bingo words for a player's card."""
    if meeting_id not in game_rooms:
        raise HTTPException(status_code=404, detail="Room not found")

    room = game_rooms[meeting_id]

    if player_id not in room.players:
        raise HTTPException(status_code=404, detail="Player not found")

    player = room.players[player_id]
    player.words = words

    print(f"[WORDS] Set words for {player.player_name}: {sum(len(row) for row in words)} words")

    return {"status": "ok"}


if __name__ == "__main__":
    import uvicorn
    uvicorn.run(app, host="0.0.0.0", port=8080)
