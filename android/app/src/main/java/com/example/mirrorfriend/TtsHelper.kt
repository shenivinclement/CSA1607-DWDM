package com.example.mirrorfriend

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale

/**
 * Lightweight wrapper around Android's built-in [TextToSpeech] engine.
 *
 * Android's TTS is entirely on-device — it works offline with no API key or
 * internet permission required.  The engine is initialised once and reused for
 * every [speak] call, which is far cheaper than creating a new instance each time.
 *
 * ### Typical usage inside a Composable
 * ```kotlin
 * val ttsHelper = remember { TtsHelper(context) }
 * DisposableEffect(Unit) { onDispose { ttsHelper.shutdown() } }
 *
 * // Speak a reply:
 * ttsHelper.speak("Hello, how are you feeling today?")
 *
 * // Stop speech (e.g. when the user sends a new message):
 * ttsHelper.stop()
 * ```
 *
 * @param context Pass [Context.applicationContext] to avoid leaking an Activity.
 */
class TtsHelper(context: Context) {

    // The TTS engine itself.  Nullable because it's initialised asynchronously —
    // it might not be ready the instant this class is constructed.
    private var tts: TextToSpeech? = null

    // Becomes true once the engine reports SUCCESS and a language is loaded.
    // All public functions check this before doing anything so callers never
    // need to worry about early calls crashing.
    private var ready = false

    init {
        // Initialization is asynchronous: the lambda fires on the main thread
        // once the engine is ready (or fails).  We store the result in [ready]
        // so subsequent speak() calls know whether they can proceed.
        tts = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                // Request US English for a calm, neutral voice.
                // setLanguage() returns an error code if the language pack is
                // missing — in that case we leave [ready] false and stay silent
                // rather than crashing or falling back to a wrong language.
                val result = tts?.setLanguage(Locale.US)
                ready = result != TextToSpeech.LANG_MISSING_DATA &&
                        result != TextToSpeech.LANG_NOT_SUPPORTED
            }
            // If status != SUCCESS the engine failed silently — we just won't speak.
        }
    }

    /**
     * Speaks [text] aloud.
     *
     * If the engine is already speaking, [TextToSpeech.QUEUE_FLUSH] cancels the
     * current utterance before starting the new one — replies never stack up.
     *
     * Does nothing (silently) if the engine is not yet initialised or is
     * unavailable on this device.
     */
    fun speak(text: String) {
        if (!ready) return
        // QUEUE_FLUSH: interrupt whatever is currently playing, then say this.
        // The fourth argument is an utterance ID (unused here, so null is fine).
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, null)
    }

    /**
     * Stops playback immediately without releasing the engine.
     *
     * Safe to call even if nothing is playing.  Use this when the user sends
     * a new message so the previous reply doesn't keep talking over the new one.
     */
    fun stop() {
        tts?.stop()
    }

    /**
     * Stops playback and permanently releases the TTS engine.
     *
     * **Always call this from a `DisposableEffect`'s `onDispose` block** so the
     * engine is cleaned up when the composable leaves the screen.  After calling
     * [shutdown], this object should not be used again.
     */
    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        ready = false
    }
}
