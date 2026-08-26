"""
SQLite storage for Mirror Friend.

The backend itself stays simple: a single local SQLite file (mirrorfriend.db)
sitting next to this module records two things so we have a real history:

  * emotions     — every detected facial emotion, with a timestamp
  * chat_messages — every chat turn (the emotion context, the user's message,
                    and Mirror Friend's reply), with a timestamp

SQLite ships with Python (the built-in `sqlite3` module), so there is nothing
extra to install. We open a fresh connection per call — that keeps things
thread-safe under FastAPI without any connection-pool bookkeeping, and this
app's write volume is tiny.
"""

import os
import sqlite3
from datetime import datetime, timezone

# Put the database file right next to this file, so it works no matter what
# directory uvicorn happens to be started from.
DB_PATH = os.path.join(os.path.dirname(os.path.abspath(__file__)), "mirrorfriend.db")


def _connect() -> sqlite3.Connection:
    """Open a connection with row access by column name."""
    conn = sqlite3.connect(DB_PATH)
    conn.row_factory = sqlite3.Row
    return conn


def init_db() -> None:
    """Create the tables if they don't exist yet. Safe to call on every startup."""
    with _connect() as conn:
        conn.execute(
            """
            CREATE TABLE IF NOT EXISTS emotions (
                id         INTEGER PRIMARY KEY AUTOINCREMENT,
                emotion    TEXT NOT NULL,
                created_at TEXT NOT NULL
            )
            """
        )
        conn.execute(
            """
            CREATE TABLE IF NOT EXISTS chat_messages (
                id           INTEGER PRIMARY KEY AUTOINCREMENT,
                emotion      TEXT NOT NULL,
                user_message TEXT NOT NULL,
                reply        TEXT NOT NULL,
                created_at   TEXT NOT NULL
            )
            """
        )


def _now() -> str:
    """Current UTC time as an ISO-8601 string (sortable and timezone-clear)."""
    return datetime.now(timezone.utc).isoformat()


def save_emotion(emotion: str) -> None:
    """Record one detected emotion."""
    with _connect() as conn:
        conn.execute(
            "INSERT INTO emotions (emotion, created_at) VALUES (?, ?)",
            (emotion, _now()),
        )


def save_chat(emotion: str, user_message: str, reply: str) -> None:
    """Record one chat turn (context emotion + user message + our reply)."""
    with _connect() as conn:
        conn.execute(
            "INSERT INTO chat_messages (emotion, user_message, reply, created_at) "
            "VALUES (?, ?, ?, ?)",
            (emotion, user_message, reply, _now()),
        )


def recent_emotions(limit: int = 50) -> list[dict]:
    """Return the most recent detected emotions, newest first."""
    with _connect() as conn:
        rows = conn.execute(
            "SELECT id, emotion, created_at FROM emotions "
            "ORDER BY id DESC LIMIT ?",
            (limit,),
        ).fetchall()
    return [dict(row) for row in rows]


def recent_chats(limit: int = 50) -> list[dict]:
    """Return the most recent chat turns, newest first."""
    with _connect() as conn:
        rows = conn.execute(
            "SELECT id, emotion, user_message, reply, created_at FROM chat_messages "
            "ORDER BY id DESC LIMIT ?",
            (limit,),
        ).fetchall()
    return [dict(row) for row in rows]
