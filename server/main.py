#!/usr/bin/env python3
"""
Meeting Bingo Server
Manages game state for multiplayer bingo sessions.
Each meeting has its own game room where players can join and share their progress.
"""

import asyncio
import json
import uuid
from datetime import datetime
from typing import Dict, List, Optional, Set
from dataclasses import dataclass, field, asdict
from contextlib import asynccontextmanager

from fastapi import FastAPI, WebSocket, WebSocketDisconnect, HTTPException
from fastapi.middleware.cors import CORSMiddleware
from pydantic import BaseModel


# Data models
@dataclass
class PlayerState:
    player_id: str
    player_name: str
    marked_cells: List[List[bool]] = field(default_factory=lambda: [[False]*5 for _ in range(5)])
    has_bingo: bool = False
    connected: bool = True
    last_seen: datetime = field(default_factory=datetime.now)

    def __post_init__(self):
        # Ensure center cell (FREE space) is always marked
        self.marked_cells[2][2] = True

    def to_dict(self) -> dict:
        return {
            "player_id": self.player_id,
            "player_name": self.player_name,
            "marked_cells": self.marked_cells,
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
async def join_game(request: JoinGameRequest):
    """Join or create a game room for a meeting."""
    meeting_id = request.meeting_id
    player_name = request.player_name
    player_id = str(uuid.uuid4())[:8]

    # Create room if it doesn't exist
    if meeting_id not in game_rooms:
        game_rooms[meeting_id] = GameRoom(meeting_id=meeting_id)
        print(f"[JOIN] Created new room for meeting_id={meeting_id}")

    room = game_rooms[meeting_id]

    # Clean up any existing disconnected players with the same name
    # This handles app restarts gracefully
    players_to_remove = [
        pid for pid, p in room.players.items()
        if p.player_name == player_name and not p.connected
    ]
    for pid in players_to_remove:
        print(f"[JOIN] Removing stale player '{player_name}' (id={pid})")
        del room.players[pid]
        if pid in room.websockets:
            del room.websockets[pid]

    # Create player state
    player = PlayerState(
        player_id=player_id,
        player_name=player_name
    )
    room.players[player_id] = player

    print(f"[JOIN] Player '{player_name}' (id={player_id}) joined meeting_id={meeting_id} (total players: {len(room.players)})")

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


if __name__ == "__main__":
    import uvicorn
    uvicorn.run(app, host="0.0.0.0", port=8080)
