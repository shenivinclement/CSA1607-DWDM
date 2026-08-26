package com.example.mirrorfriend

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

// ---------------------------------------------------------------------------
// DataStore singleton
// ---------------------------------------------------------------------------

// "by preferencesDataStore(...)" is a Kotlin property delegate. It creates
// exactly ONE DataStore tied to this Context, with the given file name.
// Calling context.dataStore from anywhere always returns the same instance —
// DataStore is NOT safe to open more than once per file name.
//
// This must be a top-level declaration (outside any class) for the delegate
// machinery to work correctly.
private val Context.dataStore: DataStore<Preferences>
        by preferencesDataStore(name = "mirrorfriend_prefs")

// ---------------------------------------------------------------------------
// Storage keys (private — callers use the typed Flow / suspend API below)
// ---------------------------------------------------------------------------

// Each key is strongly typed. The name string is what actually gets written
// to disk, so don't change it after shipping or existing installs lose data.
private object Keys {
    /** ISO date of the last session, e.g. "2026-05-30". Empty if never set. */
    val LAST_SESSION_DATE = stringPreferencesKey("last_session_date")

    /** How many consecutive days the user has used the app. */
    val CURRENT_STREAK = intPreferencesKey("current_streak")

    /** Number of sessions that have been started today. */
    val SESSIONS_TODAY = intPreferencesKey("sessions_today")

    /**
     * The date that [SESSIONS_TODAY] counts belong to.
     * Stored separately so we can detect a day roll-over and report 0
     * even before [recordSessionStarted] is called.
     */
    val SESSIONS_TODAY_DATE = stringPreferencesKey("sessions_today_date")

    /**
     * The last [AppStorage.MAX_RECENT_EMOTIONS] detected emotions joined
     * by commas, newest first. Example: "happy,neutral,sad"
     *
     * DataStore Preferences doesn't support ordered lists natively, so we
     * serialize to a plain comma-separated string.
     */
    val RECENT_EMOTIONS = stringPreferencesKey("recent_emotions")

    /** Whether the in-app voice / audio feature is enabled. */
    val VOICE_ENABLED = booleanPreferencesKey("voice_enabled")

    /** Whether the app should use dark mode. */
    val DARK_MODE_ENABLED = booleanPreferencesKey("dark_mode_enabled")

    /** The user's chosen display name, e.g. "Alex". Empty string if never set. */
    val DISPLAY_NAME = stringPreferencesKey("display_name")

    /** Optional email address stored locally — not used for login or auth. */
    val EMAIL = stringPreferencesKey("email")

    /** The emoji the user picked as their avatar, e.g. "😊". */
    val AVATAR_EMOJI = stringPreferencesKey("avatar_emoji")

    /**
     * Running lifetime total of every session ever started — never resets.
     * Incremented inside [recordSessionStarted] alongside the streak logic.
     */
    val TOTAL_SESSIONS = intPreferencesKey("total_sessions")
}

// ---------------------------------------------------------------------------
// AppStorage
// ---------------------------------------------------------------------------

/**
 * Single source-of-truth for all locally persisted data in MirrorFriend.
 *
 * ### How to use
 * Create one instance, passing [Context.applicationContext] to avoid leaks:
 * ```kotlin
 * val storage = AppStorage(context.applicationContext)
 * ```
 * Collect any Flow in a Composable with `collectAsState()`, or in a
 * ViewModel with `viewModelScope.launch { flow.collect { ... } }`.
 *
 * Call suspend functions from a coroutine:
 * ```kotlin
 * coroutineScope.launch { storage.recordSessionStarted() }
 * ```
 *
 * ### Why Preferences DataStore?
 * Unlike SharedPreferences, DataStore:
 * - Is crash-safe (writes are atomic — a power-cut can't corrupt the file).
 * - Is coroutine-native (all I/O is on a background dispatcher automatically).
 * - Exposes data as Kotlin Flows, so the UI reacts to changes automatically.
 */
class AppStorage(context: Context) {

    // Store applicationContext so we never accidentally hold an Activity ref
    // (which would leak memory after the Activity is destroyed).
    private val dataStore = context.applicationContext.dataStore

    // Maximum number of emotion strings we'll keep in the rolling list.
    // Older entries beyond this limit are silently dropped.
    private val MAX_RECENT_EMOTIONS = 10

    // -----------------------------------------------------------------------
    // Internal date helpers
    // -----------------------------------------------------------------------

    // Using java.text / java.util instead of java.time so we stay compatible
    // with minSdk 24 without needing core library desugaring.

    /** Today's date formatted as "yyyy-MM-dd". */
    private fun todayString(): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

    /** Yesterday's date formatted as "yyyy-MM-dd". */
    private fun yesterdayString(): String {
        val cal = Calendar.getInstance()
        cal.add(Calendar.DAY_OF_YEAR, -1)
        return SimpleDateFormat("yyyy-MM-dd", Locale.US).format(cal.time)
    }

    // -----------------------------------------------------------------------
    // Flows — observable, automatically updated on every write
    // -----------------------------------------------------------------------

    /**
     * The date of the user's most recent session ("yyyy-MM-dd").
     * Emits an empty string before any session has been recorded.
     */
    val lastSessionDate: Flow<String> = dataStore.data.map { prefs ->
        prefs[Keys.LAST_SESSION_DATE] ?: ""
    }

    /**
     * How many consecutive days the user has opened the app.
     * Emits 0 before the first session.
     */
    val currentStreak: Flow<Int> = dataStore.data.map { prefs ->
        prefs[Keys.CURRENT_STREAK] ?: 0
    }

    /**
     * How many sessions the user has started today.
     *
     * Automatically emits 0 when the calendar date advances past the day
     * that was recorded, so the UI always shows a fresh count without any
     * extra reset logic in the callers.
     */
    val sessionsToday: Flow<Int> = dataStore.data.map { prefs ->
        val storedDate = prefs[Keys.SESSIONS_TODAY_DATE] ?: ""
        // If the stored date isn't today, the count is stale → report 0
        if (storedDate == todayString()) prefs[Keys.SESSIONS_TODAY] ?: 0 else 0
    }

    /**
     * The rolling list of the last [MAX_RECENT_EMOTIONS] detected emotions,
     * newest first. Useful for mood chips and wellness-score calculations.
     *
     * Emits an empty list before any emotions have been recorded.
     */
    val recentEmotions: Flow<List<String>> = dataStore.data.map { prefs ->
        val csv = prefs[Keys.RECENT_EMOTIONS] ?: ""
        if (csv.isBlank()) emptyList() else csv.split(",")
    }

    /**
     * Whether the voice / audio feature is enabled.
     * Emits `true` (enabled) by default before any explicit setting is saved.
     */
    val voiceEnabled: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[Keys.VOICE_ENABLED] ?: true   // default ON
    }

    /**
     * Whether the app should use dark mode.
     * Emits `true` (dark mode on) by default before any explicit setting is saved.
     */
    val darkModeEnabled: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[Keys.DARK_MODE_ENABLED] ?: true   // default ON
    }

    /**
     * The user's display name (e.g. "Alex").
     * Emits an empty string before any name has been saved.
     */
    val displayName: Flow<String> = dataStore.data.map { prefs ->
        prefs[Keys.DISPLAY_NAME] ?: ""
    }

    /**
     * The user's optional email address (stored locally, never sent anywhere).
     * Emits an empty string before any email has been saved.
     */
    val email: Flow<String> = dataStore.data.map { prefs ->
        prefs[Keys.EMAIL] ?: ""
    }

    /**
     * The emoji the user has chosen as their avatar.
     * Defaults to "😊" before any explicit selection is saved.
     */
    val avatarEmoji: Flow<String> = dataStore.data.map { prefs ->
        prefs[Keys.AVATAR_EMOJI] ?: "😊"
    }

    /**
     * Lifetime total of all sessions ever started — never resets.
     * Emits 0 before any session has been recorded.
     */
    val totalSessions: Flow<Int> = dataStore.data.map { prefs ->
        prefs[Keys.TOTAL_SESSIONS] ?: 0
    }

    // -----------------------------------------------------------------------
    // Suspend write functions
    // -----------------------------------------------------------------------

    /**
     * Saves the voice-enabled preference.
     *
     * Example:
     * ```kotlin
     * coroutineScope.launch { storage.setVoiceEnabled(false) }
     * ```
     */
    suspend fun setVoiceEnabled(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[Keys.VOICE_ENABLED] = enabled
        }
    }

    /**
     * Saves the dark-mode preference.
     */
    suspend fun setDarkModeEnabled(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[Keys.DARK_MODE_ENABLED] = enabled
        }
    }

    /** Saves the user's display name. Pass an empty string to clear it. */
    suspend fun setDisplayName(name: String) {
        dataStore.edit { prefs -> prefs[Keys.DISPLAY_NAME] = name }
    }

    /** Saves the optional email address. Pass an empty string to clear it. */
    suspend fun setEmail(email: String) {
        dataStore.edit { prefs -> prefs[Keys.EMAIL] = email }
    }

    /** Saves the chosen avatar emoji (e.g. "🌟"). */
    suspend fun setAvatarEmoji(emoji: String) {
        dataStore.edit { prefs -> prefs[Keys.AVATAR_EMOJI] = emoji }
    }

    /**
     * Prepends [emotion] to the front of the recent-emotions list and trims
     * the list to [MAX_RECENT_EMOTIONS] entries so it never grows unbounded.
     *
     * Call this every time a new emotion is detected by the camera screen.
     *
     * @param emotion A label like "happy", "neutral", "sad". Whitespace is
     *   trimmed automatically.
     */
    suspend fun addEmotion(emotion: String) {
        dataStore.edit { prefs ->
            val csv = prefs[Keys.RECENT_EMOTIONS] ?: ""

            // Deserialize the CSV → mutable list
            val list: MutableList<String> =
                if (csv.isBlank()) mutableListOf()
                else csv.split(",").toMutableList()

            list.add(0, emotion.trim())   // newest at the front

            // Drop anything past the limit
            if (list.size > MAX_RECENT_EMOTIONS) {
                list.subList(MAX_RECENT_EMOTIONS, list.size).clear()
            }

            // Re-serialize and store
            prefs[Keys.RECENT_EMOTIONS] = list.joinToString(",")
        }
    }

    /**
     * Records that the user has started a new session.
     *
     * Updates [sessionsToday] and [currentStreak] according to three cases:
     *
     * | Last session date | Action |
     * |---|---|
     * | **Today** | Increment `sessionsToday`. Streak is already correct — leave it alone. |
     * | **Yesterday** | Increment `currentStreak`. Reset `sessionsToday` to 1. |
     * | **Older, or never set** | Reset `currentStreak` to 1. Reset `sessionsToday` to 1. |
     *
     * The entire read-then-write runs inside a single [DataStore.edit] block,
     * which DataStore guarantees to be **atomic and crash-safe**. There is no
     * race condition even if two coroutines call this function simultaneously.
     *
     * Call this once per app session, e.g. when [CameraEmotionScreen] first
     * becomes visible.
     */
    suspend fun recordSessionStarted() {
        val today     = todayString()       // e.g. "2026-05-30"
        val yesterday = yesterdayString()   // e.g. "2026-05-29"

        // dataStore.edit gives us a MutablePreferences snapshot.
        // We can read AND write inside this lambda — DataStore handles
        // the locking and disk flush automatically.
        dataStore.edit { prefs ->

            // What date did we last call recordSessionStarted()?
            val lastDate = prefs[Keys.LAST_SESSION_DATE] ?: ""

            // Safely compute how many sessions are already logged today.
            // If the stored date isn't today, treat the count as 0.
            val storedDate       = prefs[Keys.SESSIONS_TODAY_DATE] ?: ""
            val sessionsBeforeNow = if (storedDate == today) prefs[Keys.SESSIONS_TODAY] ?: 0 else 0

            // Lifetime total — always increment, regardless of streak/today logic below
            prefs[Keys.TOTAL_SESSIONS] = (prefs[Keys.TOTAL_SESSIONS] ?: 0) + 1

            when (lastDate) {

                today -> {
                    // ── Case 1: already had a session today ──────────────────
                    // Just count this new one. The streak number is already
                    // correct from earlier today, so we don't touch it.
                    prefs[Keys.SESSIONS_TODAY]      = sessionsBeforeNow + 1
                    prefs[Keys.SESSIONS_TODAY_DATE] = today
                    // LAST_SESSION_DATE is already today — no change needed
                }

                yesterday -> {
                    // ── Case 2: consecutive day ───────────────────────────────
                    // The user came back the very next day — keep the streak alive!
                    val previousStreak = prefs[Keys.CURRENT_STREAK] ?: 0
                    prefs[Keys.CURRENT_STREAK]      = previousStreak + 1
                    prefs[Keys.SESSIONS_TODAY]      = 1   // first session of today
                    prefs[Keys.SESSIONS_TODAY_DATE] = today
                    prefs[Keys.LAST_SESSION_DATE]   = today
                }

                else -> {
                    // ── Case 3: gap in sessions, or very first session ever ───
                    // lastDate is "" (never set) or two-or-more days ago.
                    // Either way, the streak is broken — start fresh at 1.
                    prefs[Keys.CURRENT_STREAK]      = 1
                    prefs[Keys.SESSIONS_TODAY]      = 1
                    prefs[Keys.SESSIONS_TODAY_DATE] = today
                    prefs[Keys.LAST_SESSION_DATE]   = today
                }
            }
        }
    }
}
