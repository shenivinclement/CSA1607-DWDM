package com.example.mirrorfriend

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

// ---------------------------------------------------------------------------
// Data model
// ---------------------------------------------------------------------------

/**
 * A single message in the conversation.
 * [role] is "user" for the human's messages, "assistant" for Mirror Friend's replies.
 * [content] is the plain text of the message.
 */
data class ChatMessage(val role: String, val content: String)

// ---------------------------------------------------------------------------
// Network helper
// ---------------------------------------------------------------------------

/**
 * Sends a chat request to the backend server and returns the assistant's reply text.
 *
 * This is a suspend function, so it must run on a background thread via
 * [Dispatchers.IO] — never call it from the main thread.
 *
 * @param client  Shared OkHttp client (thread-safe, reuse it).
 * @param emotion The current detected emotion (e.g. "neutral", "happy").
 * @param message The new message the user just typed.
 * @param history All messages sent before this one, so the server has context.
 */
private suspend fun postChatMessage(
    client: OkHttpClient,
    emotion: String,
    message: String,
    history: List<ChatMessage>
): String {
    // Build the history as a JSON array: [{"role": "user", "content": "..."}, ...]
    val historyArray = JSONArray()
    for (msg in history) {
        historyArray.put(
            JSONObject().apply {
                put("role", msg.role)
                put("content", msg.content)
            }
        )
    }

    // Assemble the full request body JSON
    val bodyJson = JSONObject().apply {
        put("emotion", emotion)   // detected emotion context
        put("message", message)   // the new user message
        put("history", historyArray) // prior conversation for context
    }.toString()

    val request = Request.Builder()
        .url("http://192.168.1.3:8000/chat")
        .post(bodyJson.toRequestBody("application/json".toMediaType()))
        .build()

    // .use { } closes the response body when done, preventing resource leaks
    client.newCall(request).execute().use { response ->
        val responseText = response.body?.string()
            ?: return "I'm having trouble responding right now."
        // Parse {"reply": "..."} from the server's JSON response
        return JSONObject(responseText).getString("reply")
    }
}

// ---------------------------------------------------------------------------
// Small UI pieces
// ---------------------------------------------------------------------------

/**
 * Renders one message bubble.
 * User messages → right side, purple background.
 * Assistant messages → left side, light grey background.
 */
@Composable
private fun MessageBubble(message: ChatMessage) {
    val isUser = message.role == "user"

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        // Push user bubbles to the right, assistant bubbles to the left
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        Surface(
            // The "tail" of each bubble points toward the speaker's side
            shape = RoundedCornerShape(
                topStart = 16.dp,
                topEnd = 16.dp,
                bottomStart = if (isUser) 16.dp else 4.dp,
                bottomEnd   = if (isUser) 4.dp  else 16.dp
            ),
            color = if (isUser) Color(0xFF6650A4) else Color(0xFFE8E8E8),
            // Cap width at 280 dp so long messages don't stretch edge-to-edge
            modifier = Modifier.widthIn(max = 280.dp)
        ) {
            Text(
                text = message.content,
                color = if (isUser) Color.White else Color.Black,
                fontSize = 15.sp,
                lineHeight = 21.sp,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
            )
        }
    }
}

/**
 * A small animated label shown while Mirror Friend is composing a reply.
 */
@Composable
private fun TypingIndicator() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.Start
    ) {
        Surface(
            shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomEnd = 16.dp, bottomStart = 4.dp),
            color = Color(0xFFE8E8E8)
        ) {
            Text(
                text = "Mirror Friend is typing…",
                color = Color.Gray,
                fontSize = 14.sp,
                fontStyle = FontStyle.Italic,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Main screen composable
// ---------------------------------------------------------------------------

/**
 * Full chat screen where the user converses with Mirror Friend.
 *
 * @param currentEmotion The emotion detected by the camera. Used to give the
 *   AI extra context so it can respond in a supportive, emotion-aware way.
 *
 *   TODO: Wire this to the real live emotion from CameraEmotionScreen.
 *         For now the caller passes a hardcoded "neutral" string. Once a
 *         shared ViewModel (or hoisted state) is added in the next step, this
 *         parameter will receive the actual detected emotion.
 */
@Composable
fun ChatScreen(currentEmotion: String) {
    // The full list of messages in this conversation
    var messages by remember { mutableStateOf(listOf<ChatMessage>()) }

    // What the user is currently typing
    var inputText by remember { mutableStateOf("") }

    // True while we are waiting for the server to reply
    var isTyping by remember { mutableStateOf(false) }

    // Remembers scroll position so we can animate to the latest message
    val listState = rememberLazyListState()

    // Coroutine scope tied to this composable's lifecycle
    val coroutineScope = rememberCoroutineScope()

    // One shared OkHttp client — thread-safe, reuses connection pools
    val httpClient = remember {
        OkHttpClient.Builder()
            .callTimeout(15, TimeUnit.SECONDS) // give up after 15 s
            .build()
    }

    // ---- Text-to-Speech setup ----

    // We need a Context to create AppStorage and TtsHelper.
    // LocalContext.current gives us the Activity context; we pass
    // applicationContext inside each class to avoid leaking the Activity.
    val context = LocalContext.current

    // AppStorage lets us read and write the voiceEnabled preference in DataStore.
    val storage = remember { AppStorage(context) }

    // Collect the voiceEnabled Flow as Compose State so the UI reacts instantly
    // whenever the user taps the speaker button.  Default true = voice on until
    // DataStore has a chance to load its persisted value.
    val voiceEnabled by storage.voiceEnabled.collectAsState(initial = true)

    // TtsHelper wraps Android's on-device TTS engine.  remembered so we
    // don't create a new engine on every recomposition.
    val ttsHelper = remember { TtsHelper(context) }

    // Release the TTS engine when ChatScreen is removed from the composition
    // (e.g. the user taps another tab).  Without this, the engine keeps running
    // in the background and leaks memory.
    DisposableEffect(Unit) {
        onDispose { ttsHelper.shutdown() }
    }

    // Scroll to the bottom whenever a message is added or typing starts
    LaunchedEffect(messages.size, isTyping) {
        // Index of the last visible item (typing indicator counts as +1)
        val lastIndex = if (isTyping) messages.size else (messages.size - 1).coerceAtLeast(0)
        if (messages.isNotEmpty() || isTyping) {
            listState.animateScrollToItem(lastIndex)
        }
    }

    // Speak the latest message aloud whenever messages.size increases.
    // We only speak assistant replies — never the user's own text.
    LaunchedEffect(messages.size) {
        val lastMsg = messages.lastOrNull()
        if (lastMsg != null && lastMsg.role == "assistant" && voiceEnabled) {
            ttsHelper.speak(lastMsg.content)
        }
    }

    // Handles the "Send" action — called from both the button and the keyboard
    fun sendMessage() {
        val text = inputText.trim()
        if (text.isEmpty() || isTyping) return // ignore if empty or already waiting

        // Stop any ongoing speech so it doesn't talk over the new exchange
        ttsHelper.stop()

        // Snapshot the history BEFORE adding the new user message so the
        // server receives prior context but not the message being sent now
        val historySnapshot = messages.toList()

        // Show the user's message immediately in the UI
        messages = messages + ChatMessage(role = "user", content = text)
        inputText = ""   // clear the input field
        isTyping = true  // show the typing indicator

        // Run the network call on a background thread
        coroutineScope.launch(Dispatchers.IO) {
            val reply = try {
                postChatMessage(
                    client  = httpClient,
                    emotion = currentEmotion,
                    message = text,
                    history = historySnapshot
                )
            } catch (e: Exception) {
                // Friendly fallback — the app never crashes due to network issues
                "Sorry, I couldn't connect right now. Please try again in a moment. 💙"
            }

            // Back on the main thread, hide the typing indicator and add the reply
            withContext(Dispatchers.Main) {
                isTyping = false
                messages = messages + ChatMessage(role = "assistant", content = reply)
            }
        }
    }

    // ---- Screen layout ----
    // Outer column: [emotion label] [message list] [input row]
    androidx.compose.foundation.layout.Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFFF5F5F5))
    ) {

        // -- Emotion label banner at the top (also holds the voice toggle) --
        Surface(
            color = Color(0xFF6650A4),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    // Extra right padding kept small so the icon button has room
                    .padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Mood text — weight(1f) pushes the icon to the far right
                Text(
                    // TODO: Replace "neutral" with the real emotion once wired up
                    text = "Current mood: ${currentEmotion.replaceFirstChar { it.uppercase() }}",
                    color = Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f)
                )

                // Speaker toggle button — tapping flips voiceEnabled in DataStore
                IconButton(
                    onClick = {
                        // If voice is currently ON, stop mid-sentence right now
                        // before the DataStore write (which is async) takes effect
                        if (voiceEnabled) ttsHelper.stop()
                        coroutineScope.launch { storage.setVoiceEnabled(!voiceEnabled) }
                    }
                ) {
                    // 🔊 filled speaker = voice on  |  🔇 muted speaker = voice off
                    Text(
                        text = if (voiceEnabled) "🔊" else "🔇",
                        fontSize = 20.sp
                    )
                }
            }
        }

        // -- Scrollable message list --
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)        // fill all space between the banner and input
                .fillMaxWidth(),
            contentPadding = PaddingValues(vertical = 8.dp)
        ) {
            // Placeholder shown before the first message
            if (messages.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "Say hello to Mirror Friend! 👋",
                            color = Color.Gray,
                            fontSize = 15.sp
                        )
                    }
                }
            }

            // One bubble per message
            items(messages) { message ->
                MessageBubble(message = message)
            }

            // Typing indicator sits below the last message while waiting
            if (isTyping) {
                item { TypingIndicator() }
            }
        }

        // -- Text input row at the bottom --
        Surface(
            color = Color.White,
            shadowElevation = 8.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // The text field grows up to 4 lines before scrolling internally
                OutlinedTextField(
                    value = inputText,
                    onValueChange = { inputText = it },
                    // Explicit dark text so it's always visible on the white background
                    textStyle = TextStyle(color = Color(0xFF1C1B1F), fontSize = 15.sp),
                    placeholder = {
                        // Lighter gray so the placeholder is clearly distinct from typed text
                        Text("Type a message…", color = Color(0xFFAAAAAA), fontSize = 15.sp)
                    },
                    modifier = Modifier.weight(1f),
                    maxLines = 4,
                    // "Send" action on the software keyboard triggers sendMessage()
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { sendMessage() }),
                    shape = RoundedCornerShape(24.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        // Text color whether or not the field is focused
                        focusedTextColor   = Color(0xFF1C1B1F),
                        unfocusedTextColor = Color(0xFF1C1B1F),
                        // White pill background in both states
                        focusedContainerColor   = Color.White,
                        unfocusedContainerColor = Color.White,
                        // Purple cursor matches the app accent color
                        cursorColor = Color(0xFF6650A4),
                        // Purple border when focused, light gray when idle
                        focusedBorderColor   = Color(0xFF6650A4),
                        unfocusedBorderColor = Color(0xFFCCCCCC),
                    )
                )

                Spacer(modifier = Modifier.padding(horizontal = 4.dp))

                Button(
                    onClick = { sendMessage() },
                    // Disabled while waiting for a reply or when the field is blank
                    enabled = inputText.isNotBlank() && !isTyping,
                    shape = RoundedCornerShape(50),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6650A4)),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)
                ) {
                    Text("Send", color = Color.White, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}
