package com.example.mirrorfriend

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

// ---------------------------------------------------------------------------
// Color palette — same values as HomeScreen so both screens feel identical.
// Declared private here so they don't accidentally pollute other files.
// ---------------------------------------------------------------------------

private val BgColor       = Color(0xFF0D0D0D)  // near-black page background
private val CardColor     = Color(0xFF1A1A1A)  // elevated card surface
private val BorderColor   = Color(0xFF2A2A2A)  // subtle 1 dp card border
private val AccentColor   = Color(0xFF6650A4)  // purple — matches the rest of the app
private val TextPrimary   = Color.White
private val TextSecondary = Color(0xFF9E9E9E)  // muted label text
private val ProgressTrack = Color(0xFF2A2A2A)  // empty section of the wellness bar
private val ChipBg        = Color(0xFF252525)  // emotion chip background
private val ChipBorderCol = Color(0xFF383838)  // emotion chip border
private val AvatarBg      = Color(0xFF2A2A2A)  // inner background of the avatar circle

// The preset emojis the user can pick as their avatar.
// Displayed in a 4×2 grid inside the avatar card.
private val PRESET_AVATARS = listOf("😊", "🧠", "🌟", "🦋", "🌙", "🔮", "🎯", "🌺")

// ---------------------------------------------------------------------------
// Private helper: wellness score (same logic as HomeScreen.kt)
// Keeping it private here avoids a cross-file dependency for one pure function.
// ---------------------------------------------------------------------------

/**
 * Converts a list of detected emotion strings into a 0–100 wellness score.
 * Returns -1 when [emotions] is empty so callers can show a "no data" state.
 */
private fun computeWellnessScore(emotions: List<String>): Int {
    if (emotions.isEmpty()) return -1
    val average = emotions.map { emotion ->
        when (emotion.trim().lowercase()) {
            "happy", "happiness", "joy"       -> 100
            "surprised", "surprise", "shock"  ->  85
            "neutral"                         ->  60
            "contempt"                        ->  40
            "fearful", "fear", "scared"       ->  25
            "disgusted", "disgust"            ->  20
            "sad", "sadness", "sorrow"        ->  20
            "angry", "anger", "rage"          ->  15
            else                              ->  50   // unknown → treat as neutral-ish
        }
    }.average()
    return average.toInt().coerceIn(0, 100)
}

// ---------------------------------------------------------------------------
// Small reusable composables (private to this file)
// ---------------------------------------------------------------------------

/**
 * Dark-themed rounded card that wraps [content] in a Column.
 * Matches the DarkCard in HomeScreen so the two screens feel consistent.
 */
@Composable
private fun DarkCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        color = CardColor,
        border = BorderStroke(1.dp, BorderColor)
    ) {
        Column(modifier = Modifier.padding(16.dp), content = content)
    }
}

/**
 * Pill chip showing one emotion as an emoji + capitalised label.
 * Shares visual style with the chips on the Home screen.
 */
@Composable
private fun EmotionChip(emotion: String) {
    Surface(
        shape = RoundedCornerShape(50),
        color = ChipBg,
        border = BorderStroke(1.dp, ChipBorderCol)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            // emotionEmoji() is defined (non-private) in CameraEmotionScreen.kt
            Text(text = emotionEmoji(emotion), fontSize = 14.sp)
            Text(
                text = emotion.replaceFirstChar { it.uppercase() },
                color = Color(0xFFCCCCCC),
                fontSize = 13.sp
            )
        }
    }
}

/**
 * A single stat row: emoji on the left, a label that fills the remaining
 * space, and a bold value on the right.
 */
@Composable
private fun StatRow(emoji: String, label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(text = emoji, fontSize = 20.sp)
        Text(
            text = label,
            color = TextSecondary,
            fontSize = 14.sp,
            modifier = Modifier.weight(1f)  // push the value to the far right
        )
        Text(
            text = value,
            color = TextPrimary,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

/**
 * A settings row with a description on the left and a Material3 [Switch] on
 * the right.  Reads/writes via [onCheckedChange] so callers manage the state.
 */
@Composable
private fun SettingsToggleRow(
    emoji: String,
    label: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(text = emoji, fontSize = 22.sp)

        Column(modifier = Modifier.weight(1f)) {
            Text(text = label, color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Text(text = description, color = TextSecondary, fontSize = 12.sp)
        }

        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor   = Color.White,
                checkedTrackColor   = AccentColor,
                uncheckedThumbColor = Color(0xFF888888),
                uncheckedTrackColor = Color(0xFF333333)
            )
        )
    }
}

// ---------------------------------------------------------------------------
// Profile screen
// ---------------------------------------------------------------------------

/**
 * Full-featured profile screen.
 *
 * Sections (top to bottom):
 *  1. Avatar — circular placeholder with an 8-emoji picker
 *  2. Profile fields — editable display name + email, with a Save button
 *  3. Your Stats — streak, sessions today, lifetime total (all from DataStore)
 *  4. Your Mood Lately — wellness score bar + recent emotion chips
 *  5. Settings — voice on/off and dark mode toggles
 *
 * All data is stored on-device in DataStore via [AppStorage].  Nothing is
 * sent to a server; this is purely local personal info.
 */
@Composable
fun ProfileScreen() {
    val context        = LocalContext.current
    val storage        = remember { AppStorage(context) }
    val coroutineScope = rememberCoroutineScope()

    // ── Live stats from DataStore (update automatically on any write) ─────
    val currentStreak   by storage.currentStreak.collectAsState(initial = 0)
    val sessionsToday   by storage.sessionsToday.collectAsState(initial = 0)
    val totalSessions   by storage.totalSessions.collectAsState(initial = 0)
    val recentEmotions  by storage.recentEmotions.collectAsState(initial = emptyList())
    val voiceEnabled    by storage.voiceEnabled.collectAsState(initial = true)
    val darkModeEnabled by storage.darkModeEnabled.collectAsState(initial = true)

    val wellnessScore = computeWellnessScore(recentEmotions)
    val hasData       = wellnessScore >= 0

    // ── Local edit buffers for profile fields ─────────────────────────────
    // Start empty; pre-populated from DataStore in the LaunchedEffect below.
    var nameInput      by remember { mutableStateOf("") }
    var emailInput     by remember { mutableStateOf("") }
    var avatarEmoji    by remember { mutableStateOf("😊") }
    var showPicker     by remember { mutableStateOf(false) }   // avatar picker open?
    var savedConfirmed by remember { mutableStateOf(false) }   // "Saved ✓" visible?

    // Load saved profile values from DataStore when the screen first opens.
    // flow.first() is a one-shot collector: it waits for the first emission
    // from DataStore (the persisted value), then stops — it will NOT keep
    // overwriting edits the user makes after the screen is open.
    LaunchedEffect(Unit) {
        nameInput   = storage.displayName.first()
        emailInput  = storage.email.first()
        avatarEmoji = storage.avatarEmoji.first()
    }

    // Hide the "Saved ✓" confirmation automatically after 2 seconds.
    LaunchedEffect(savedConfirmed) {
        if (savedConfirmed) {
            delay(2_000L)
            savedConfirmed = false
        }
    }

    // ── Shared OutlinedTextField colors (dark-theme friendly) ────────────
    // Defined here once and reused for both text fields below.
    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedTextColor        = TextPrimary,
        unfocusedTextColor      = TextPrimary,
        focusedContainerColor   = Color(0xFF232323),
        unfocusedContainerColor = Color(0xFF1E1E1E),
        cursorColor             = AccentColor,
        focusedBorderColor      = AccentColor,
        unfocusedBorderColor    = BorderColor,
        focusedLabelColor       = AccentColor,
        unfocusedLabelColor     = TextSecondary
    )

    // ── Screen layout ─────────────────────────────────────────────────────
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BgColor)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
    ) {

        Spacer(modifier = Modifier.height(24.dp))

        // ── 1. Avatar card ────────────────────────────────────────────────
        DarkCard(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Circular avatar — tapping toggles the emoji picker
                Box(
                    modifier = Modifier
                        .size(80.dp)
                        .clip(CircleShape)
                        .background(AvatarBg)
                        .clickable { showPicker = !showPicker },
                    contentAlignment = Alignment.Center
                ) {
                    Text(text = avatarEmoji, fontSize = 42.sp)
                }

                Spacer(modifier = Modifier.height(6.dp))

                Text(
                    text = if (showPicker) "Choose your avatar" else "Tap to change avatar",
                    color = TextSecondary,
                    fontSize = 12.sp
                )

                // Emoji picker — 8 options laid out as two rows of four
                if (showPicker) {
                    Spacer(modifier = Modifier.height(14.dp))

                    // chunked(4) splits the 8-item list into [[row1], [row2]]
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        PRESET_AVATARS.chunked(4).forEach { rowEmojis ->
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                rowEmojis.forEach { emoji ->
                                    Box(
                                        modifier = Modifier
                                            .size(44.dp)
                                            .clip(CircleShape)
                                            // Highlight the currently selected emoji
                                            .background(
                                                if (emoji == avatarEmoji) AccentColor else ChipBg
                                            )
                                            .clickable {
                                                avatarEmoji = emoji
                                                showPicker = false
                                                // Persist the new avatar to DataStore immediately
                                                coroutineScope.launch {
                                                    storage.setAvatarEmoji(emoji)
                                                }
                                            },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(text = emoji, fontSize = 22.sp)
                                    }
                                }
                            }
                        }
                    }
                }

                // Show the saved display name under the avatar when it has been set
                if (nameInput.isNotBlank()) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = nameInput,
                        color = TextPrimary,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // ── 2. Editable profile fields card ──────────────────────────────
        DarkCard(modifier = Modifier.fillMaxWidth()) {

            Text(
                text = "YOUR PROFILE",
                color = TextSecondary,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Display name — used in the Home screen greeting
            OutlinedTextField(
                value = nameInput,
                onValueChange = { nameInput = it },
                label = { Text("Display name") },
                placeholder = { Text("e.g. Alex", color = Color(0xFF555555)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                shape = RoundedCornerShape(10.dp),
                colors = fieldColors
            )

            Spacer(modifier = Modifier.height(10.dp))

            // Email — optional, stored locally only, never sent to any server
            OutlinedTextField(
                value = emailInput,
                onValueChange = { emailInput = it },
                label = { Text("Email (optional)") },
                placeholder = { Text("Stored on device only", color = Color(0xFF555555)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                // Show the email keyboard so autocomplete works on most devices
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                shape = RoundedCornerShape(10.dp),
                colors = fieldColors
            )

            Spacer(modifier = Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Button(
                    onClick = {
                        // Suspend writes run in a coroutine; the UI updates
                        // automatically through the DataStore Flows.
                        coroutineScope.launch {
                            storage.setDisplayName(nameInput.trim())
                            storage.setEmail(emailInput.trim())
                        }
                        savedConfirmed = true   // triggers the 2-second flash
                    },
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = AccentColor)
                ) {
                    Text("Save", color = Color.White, fontWeight = FontWeight.SemiBold)
                }

                // "Saved ✓" appears for 2 seconds after each successful save
                if (savedConfirmed) {
                    Text(
                        text = "Saved ✓",
                        color = Color(0xFF66BB6A),   // Material green
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // ── 3. Stats card ─────────────────────────────────────────────────
        DarkCard(modifier = Modifier.fillMaxWidth()) {

            Text(
                text = "YOUR STATS",
                color = TextSecondary,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium
            )

            Spacer(modifier = Modifier.height(12.dp))

            StatRow(
                emoji = "🔥",
                label = "Day Streak",
                // Grammatically correct: "1 day" vs "3 days"
                value = "$currentStreak ${if (currentStreak == 1) "day" else "days"}"
            )

            Spacer(modifier = Modifier.height(10.dp))
            HorizontalDivider(color = BorderColor, thickness = 1.dp)
            Spacer(modifier = Modifier.height(10.dp))

            StatRow(
                emoji = "📅",
                label = "Sessions Today",
                value = sessionsToday.toString()
            )

            Spacer(modifier = Modifier.height(10.dp))
            HorizontalDivider(color = BorderColor, thickness = 1.dp)
            Spacer(modifier = Modifier.height(10.dp))

            StatRow(
                emoji = "🎯",
                label = "Lifetime Sessions",
                value = totalSessions.toString()
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        // ── 4. Mood card ──────────────────────────────────────────────────
        DarkCard(modifier = Modifier.fillMaxWidth()) {

            Text(
                text = "YOUR MOOD LATELY",
                color = TextSecondary,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Wellness score label + value on one row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(text = "Wellness Score", color = TextSecondary, fontSize = 13.sp)
                Text(
                    text = if (hasData) "$wellnessScore%" else "—",
                    color = if (hasData) AccentColor else TextSecondary,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Progress bar — clipped to rounded ends so it matches the card style
            LinearProgressIndicator(
                progress = { if (hasData) wellnessScore / 100f else 0f },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(RoundedCornerShape(4.dp)),
                color = AccentColor,
                trackColor = ProgressTrack
            )

            if (recentEmotions.isNotEmpty()) {
                Spacer(modifier = Modifier.height(12.dp))
                // Scrollable chip row — same pattern as the Home screen
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    recentEmotions.forEach { emotion -> EmotionChip(emotion) }
                }
            } else {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "No emotions detected yet — start a Mirror session",
                    color = Color(0xFF555555),
                    fontSize = 13.sp
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // ── 5. Settings card ──────────────────────────────────────────────
        DarkCard(modifier = Modifier.fillMaxWidth()) {

            Text(
                text = "SETTINGS",
                color = TextSecondary,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium
            )

            // Voice replies toggle — also controlled from the chat screen's top bar
            SettingsToggleRow(
                emoji = "🔊",
                label = "Voice replies",
                description = "Mirror Friend speaks chat replies aloud",
                checked = voiceEnabled,
                onCheckedChange = { enabled ->
                    coroutineScope.launch { storage.setVoiceEnabled(enabled) }
                }
            )

            HorizontalDivider(color = BorderColor, thickness = 1.dp)

            // Dark mode toggle — saves the preference; full theme wiring is a future step
            SettingsToggleRow(
                emoji = "🌙",
                label = "Dark mode",
                description = "Preference saved — full theme wiring coming soon",
                checked = darkModeEnabled,
                onCheckedChange = { enabled ->
                    coroutineScope.launch { storage.setDarkModeEnabled(enabled) }
                }
            )
        }

        Spacer(modifier = Modifier.height(32.dp))
    }
}
