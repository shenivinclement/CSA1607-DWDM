package com.example.mirrorfriend

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import java.util.Calendar

// ---------------------------------------------------------------------------
// Dark-theme color palette (scoped to this file)
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

// ---------------------------------------------------------------------------
// Private helper functions
// ---------------------------------------------------------------------------

/**
 * Returns a greeting string appropriate to the time of day:
 *   0 – 11  → "Good morning"
 *   12 – 16 → "Good afternoon"
 *   17 – 23 → "Good evening"
 */
private fun timeOfDayGreeting(): String {
    val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)  // 0–23
    return when {
        hour < 12 -> "Good morning"
        hour < 17 -> "Good afternoon"
        else      -> "Good evening"
    }
}

/**
 * Converts a list of emotion strings into a 0–100 wellness score by mapping
 * each emotion to a positivity value and averaging the results.
 *
 * Returns -1 when [emotions] is empty so the caller can show a "no data" state.
 *
 * Mapping rationale (explained so it's defensible, not arbitrary):
 *   - Positive emotions (happy, surprised) score high — they correlate with
 *     reported well-being in the affective computing literature.
 *   - Neutral is the midpoint.
 *   - Negative emotions (angry, sad, fearful, disgusted) score lower —
 *     not zero, because even detecting them is useful data.
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
            else                              ->  50  // unknown → treat as neutral-ish
        }
    }.average()

    return average.toInt().coerceIn(0, 100)
}

// ---------------------------------------------------------------------------
// Reusable small composables (private to this file)
// ---------------------------------------------------------------------------

/**
 * A rounded card with the dark background and a 1 dp border.
 * Use it to group any dashboard section — it wraps [content] in a Column.
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
        Column(
            modifier = Modifier.padding(16.dp),
            content = content
        )
    }
}

/**
 * A small pill chip that shows one emotion as an emoji + capitalised label.
 */
@Composable
private fun EmotionChip(emotion: String) {
    Surface(
        shape = RoundedCornerShape(50),   // fully rounded pill
        color = ChipBg,
        border = BorderStroke(1.dp, ChipBorderCol)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            // emotionEmoji() is defined in CameraEmotionScreen.kt (same package, not private)
            Text(text = emotionEmoji(emotion), fontSize = 14.sp)
            Text(
                text = emotion.replaceFirstChar { it.uppercase() },
                color = Color(0xFFCCCCCC),
                fontSize = 13.sp
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Home screen
// ---------------------------------------------------------------------------

/**
 * Main landing screen shown when the app opens.
 *
 * Reads live data from [AppStorage] (DataStore) so every value updates
 * automatically whenever the underlying data changes — no manual refresh.
 *
 * @param onNavigateToMirror Invoked when the user taps "Start Mirror Session".
 *   [MainActivity] supplies the actual nav action.
 */
@Composable
fun HomeScreen(onNavigateToMirror: () -> Unit) {
    val context        = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    // AppStorage wraps all DataStore reads/writes.
    // remember { } ensures we create only one instance per composition.
    // applicationContext avoids accidental Activity memory leaks.
    val storage = remember { AppStorage(context.applicationContext) }

    // Collect each Flow as Compose State. Every time DataStore emits a new
    // value, Compose automatically recomposes the relevant part of the UI.
    val recentEmotions by storage.recentEmotions.collectAsState(initial = emptyList())
    val currentStreak  by storage.currentStreak.collectAsState(initial = 0)
    val sessionsToday  by storage.sessionsToday.collectAsState(initial = 0)
    // Used to personalise the greeting — blank until the user sets a name in Profile
    val displayName    by storage.displayName.collectAsState(initial = "")

    // Derived values — recomputed whenever recentEmotions changes
    val latestEmotion = recentEmotions.firstOrNull() ?: ""
    val wellnessScore = computeWellnessScore(recentEmotions)  // -1 means "no data yet"
    val hasData       = wellnessScore >= 0

    // Outer scrollable column so the dashboard works on small screens too
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BgColor)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
    ) {

        Spacer(modifier = Modifier.height(24.dp))

        // ── 1. Greeting header ─────────────────────────────────────────────
        Text(
            // Show "Welcome, Name" when a name is saved; fall back to "Welcome"
            text = if (displayName.isBlank()) "Welcome" else "Welcome, $displayName",
            color = TextPrimary,
            fontSize = 32.sp,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = timeOfDayGreeting(),  // changes automatically with the hour
            color = TextSecondary,
            fontSize = 16.sp
        )

        Spacer(modifier = Modifier.height(20.dp))

        // ── 2. Current mood + wellness score card ──────────────────────────
        DarkCard(modifier = Modifier.fillMaxWidth()) {

            // Top row: emoji · emotion label · "just now" badge
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (hasData) emotionEmoji(latestEmotion) else "🪞",
                    fontSize = 38.sp
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Current Mood",
                        color = TextSecondary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = if (hasData)
                            latestEmotion.replaceFirstChar { it.uppercase() }
                        else
                            "No data yet",
                        color = TextPrimary,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                // Only show "just now" when we actually have a reading
                if (hasData) {
                    Text(text = "just now", color = TextSecondary, fontSize = 12.sp)
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Score label + percentage on one row
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

            // Progress bar — clips to rounded ends so it matches the card's style
            LinearProgressIndicator(
                // Lambda form avoids triggering a recompose on every animation frame
                progress = { if (hasData) wellnessScore / 100f else 0f },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(RoundedCornerShape(4.dp)),
                color = AccentColor,
                trackColor = ProgressTrack
            )

            // Hint shown before the first mirror session
            if (!hasData) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Start a session to begin tracking your mood",
                    color = Color(0xFF666666),
                    fontSize = 12.sp
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // ── 3. Stat cards — sessions today and day streak, side by side ───
        Row(modifier = Modifier.fillMaxWidth()) {

            DarkCard(modifier = Modifier.weight(1f)) {
                Text(text = "Sessions Today", color = TextSecondary, fontSize = 12.sp)
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = sessionsToday.toString(),
                    color = TextPrimary,
                    fontSize = 40.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            DarkCard(modifier = Modifier.weight(1f)) {
                Text(text = "Day Streak", color = TextSecondary, fontSize = 12.sp)
                Spacer(modifier = Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = currentStreak.toString(),
                        color = TextPrimary,
                        fontSize = 40.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(text = "🔥", fontSize = 26.sp)
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // ── 4. Start Mirror Session button ────────────────────────────────
        Button(
            onClick = {
                // recordSessionStarted() is a suspend function, so we launch it
                // in a coroutine. Navigation happens immediately in parallel —
                // the DataStore write happens in the background and the Flows
                // update the UI automatically once the write completes.
                coroutineScope.launch {
                    storage.recordSessionStarted()
                }
                onNavigateToMirror()
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(containerColor = AccentColor)
        ) {
            Text(
                text = "▶  Start Mirror Session",
                color = Color.White,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold
            )
        }

        Spacer(modifier = Modifier.height(20.dp))

        // ── 5. Recent emotions chip row ───────────────────────────────────
        Text(
            text = "Recent Emotions",
            color = TextSecondary,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium
        )
        Spacer(modifier = Modifier.height(8.dp))

        if (recentEmotions.isEmpty()) {
            Text(
                text = "No emotions detected yet — tap Start to begin",
                color = Color(0xFF555555),
                fontSize = 13.sp
            )
        } else {
            // Scrollable row so many chips don't overflow the screen width
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                recentEmotions.forEach { emotion ->
                    EmotionChip(emotion = emotion)
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))
    }
}
