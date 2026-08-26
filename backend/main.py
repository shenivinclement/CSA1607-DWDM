import io
import os

import google.generativeai as genai
from dotenv import load_dotenv
from fastapi import FastAPI, File, UploadFile
from PIL import Image
from pydantic import BaseModel

import database

load_dotenv()

GEMINI_API_KEY = os.getenv("GEMINI_API_KEY")

genai.configure(api_key=GEMINI_API_KEY)
model = genai.GenerativeModel("gemini-2.5-flash-lite")

VALID_EMOTIONS = {"happy", "sad", "angry", "fear", "surprise", "neutral", "disgust"}

EMOTION_PROMPT = (
    "Identify the single dominant facial emotion of the person in this image. "
    "Choose exactly one from this list: happy, sad, angry, fear, surprise, "
    "neutral, disgust. If no face is detected or the image is unclear, respond "
    "with 'neutral'. Respond with only the emotion word, nothing else."
)

CHAT_SYSTEM_INSTRUCTION = (
    "You are Mirror Friend, a warm and compassionate AI companion. The user "
    "currently appears to be feeling {emotion}. Listen carefully, validate their "
    "feelings, and gently help them feel better. Keep your responses caring, "
    "conversational, and fairly short — two or three sentences. Never give "
    "medical advice; if someone seems to be in serious distress, gently suggest "
    "they reach out to someone they trust."
)

CHAT_FALLBACK_REPLY = "I'm here with you. Tell me more about what's on your mind."


class Message(BaseModel):
    role: str  # "user" or "assistant"
    content: str


class ChatRequest(BaseModel):
    emotion: str
    message: str
    history: list[Message] = []


app = FastAPI()


@app.on_event("startup")
async def on_startup():
    # Create the SQLite tables if this is the first run.
    database.init_db()


@app.get("/")
async def health_check():
    return {"status": "ok"}


@app.post("/analyze-emotion")
async def analyze_emotion(file: UploadFile = File(...)):
    try:
        contents = await file.read()
        image = Image.open(io.BytesIO(contents))
    except Exception:
        # Unreadable or invalid image -> safe fallback
        return {"emotion": "neutral"}

    try:
        response = model.generate_content([EMOTION_PROMPT, image])
        emotion = (response.text or "").strip().lower()
    except Exception:
        # Gemini API failure -> safe fallback
        return {"emotion": "neutral"}

    if emotion not in VALID_EMOTIONS:
        emotion = "neutral"

    # Persist the detection so we have a mood history.
    database.save_emotion(emotion)

    return {"emotion": emotion}


@app.post("/chat")
async def chat(request: ChatRequest):
    system_instruction = CHAT_SYSTEM_INSTRUCTION.format(emotion=request.emotion)

    # Convert prior turns into Gemini's chat history format. Gemini uses the
    # role "model" for the assistant, so map "assistant" -> "model".
    gemini_history = [
        {
            "role": "model" if msg.role == "assistant" else "user",
            "parts": [msg.content],
        }
        for msg in request.history
    ]

    try:
        chat_model = genai.GenerativeModel(
            "gemini-2.5-flash-lite",
            system_instruction=system_instruction,
        )
        conversation = chat_model.start_chat(history=gemini_history)
        response = conversation.send_message(request.message)
        reply = (response.text or "").strip()
    except Exception:
        # Gemini API failure (429 / etc.) -> gentle generic fallback
        return {"reply": CHAT_FALLBACK_REPLY}

    if not reply:
        reply = CHAT_FALLBACK_REPLY

    # Persist the chat turn (emotion context + user message + our reply).
    database.save_chat(request.emotion, request.message, reply)

    return {"reply": reply}


@app.get("/history/emotions")
async def history_emotions(limit: int = 50):
    """Most recent detected emotions, newest first."""
    return {"emotions": database.recent_emotions(limit)}


@app.get("/history/chat")
async def history_chat(limit: int = 50):
    """Most recent chat turns, newest first."""
    return {"chats": database.recent_chats(limit)}
