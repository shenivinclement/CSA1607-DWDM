"""Quick manual test for the /chat endpoint, including conversation memory.

Make sure the server is running first:
    venv\\Scripts\\uvicorn main:app

Then run:
    venv\\Scripts\\python test_chat.py
"""

import requests

URL = "http://127.0.0.1:8000/chat"


def send(emotion, message, history):
    response = requests.post(
        URL,
        json={"emotion": emotion, "message": message, "history": history},
    )
    response.raise_for_status()
    return response.json()["reply"]


# --- Turn 1: introduce a specific, memorable detail ---
turn1_message = "I had a really rough day at work. My dog Biscuit is the only thing that cheered me up."
reply1 = send("sad", turn1_message, history=[])
print("USER  (turn 1):", turn1_message)
print("MIRROR(turn 1):", reply1)
print()

# --- Turn 2: refer back without restating the detail ---
# Build history from turn 1 so the AI has context.
history = [
    {"role": "user", "content": turn1_message},
    {"role": "assistant", "content": reply1},
]
turn2_message = "What was the name of my dog again?"
reply2 = send("sad", turn2_message, history=history)
print("USER  (turn 2):", turn2_message)
print("MIRROR(turn 2):", reply2)
print()

# Confirm the model remembered the detail from turn 1.
if "biscuit" in reply2.lower():
    print("PASS: the AI remembered 'Biscuit' from the first message.")
else:
    print("NOTE: 'Biscuit' not found in the reply — memory may not have carried over.")
