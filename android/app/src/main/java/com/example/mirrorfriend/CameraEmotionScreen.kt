package com.example.mirrorfriend

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

/**
 * Returns a matching emoji for a given emotion label.
 * Falls back to 🤔 for anything the server sends that we don't recognise.
 */
// Not private — HomeScreen.kt (same package) calls this function too
fun emotionEmoji(emotion: String): String = when (emotion.trim().lowercase()) {
    "happy", "happiness", "joy" -> "😊"
    "sad", "sadness", "sorrow" -> "😢"
    "angry", "anger", "rage" -> "😠"
    "surprised", "surprise", "shock" -> "😮"
    "fearful", "fear", "scared" -> "😨"
    "disgusted", "disgust" -> "🤢"
    "contempt" -> "😒"
    "neutral" -> "😐"
    else -> "🤔"
}

/**
 * Compresses [bitmap] to a JPEG, POSTs it as multipart form-data to the
 * emotion-analysis server, and returns the detected emotion string.
 *
 * This is a suspend function intended to be called on [Dispatchers.IO]
 * because [OkHttpClient.execute] blocks the calling thread.
 */
private suspend fun postFrameAndGetEmotion(client: OkHttpClient, bitmap: Bitmap): String {
    // Step 1: compress the Bitmap to JPEG bytes (quality 80 = good balance
    //         between file size and image quality for emotion detection)
    val stream = ByteArrayOutputStream()
    bitmap.compress(Bitmap.CompressFormat.JPEG, 80, stream)
    val jpegBytes = stream.toByteArray()

    // Step 2: wrap the bytes in a multipart body with field name "file"
    val requestBody = MultipartBody.Builder()
        .setType(MultipartBody.FORM)
        .addFormDataPart(
            name = "file",
            filename = "frame.jpg",
            body = jpegBytes.toRequestBody("image/jpeg".toMediaType())
        )
        .build()

    // Step 3: build and execute the HTTP POST
    val request = Request.Builder()
        .url("http://192.168.1.3:8000/analyze-emotion")
        .post(requestBody)
        .build()

    // .use { } auto-closes the response body to avoid resource leaks
    client.newCall(request).execute().use { response ->
        val json = response.body?.string() ?: return "neutral"
        // Step 4: parse {"emotion": "..."} from the JSON response
        //         org.json is built into Android — no extra dependency needed
        return JSONObject(json).getString("emotion")
    }
}

// ---------------------------------------------------------------------------
// Composables
// ---------------------------------------------------------------------------

/**
 * Semi-transparent "pill" badge that floats at the top of the screen and
 * shows the current emotion plus a matching emoji.
 */
@Composable
private fun EmotionBadge(emotion: String) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = 52.dp), // sit just below the status bar
        contentAlignment = Alignment.TopCenter
    ) {
        Surface(
            shape = RoundedCornerShape(50), // 50 % corner radius = full pill shape
            color = Color.Black.copy(alpha = 0.55f),
            modifier = Modifier.wrapContentSize()
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = emotionEmoji(emotion),
                    fontSize = 24.sp
                )
                Text(
                    // Capitalize the first letter so "happy" displays as "Happy"
                    text = emotion.replaceFirstChar { it.uppercase() },
                    color = Color.White,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 0.5.sp
                )
            }
        }
    }
}

/**
 * Friendly message shown when the user denies camera access.
 * [onRetry] is called when the user taps the "Grant Permission" button.
 */
@Composable
private fun PermissionDeniedScreen(onRetry: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(text = "📷", fontSize = 72.sp)
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "Camera Permission Needed",
            color = Color.White,
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = "MirrorFriend uses the front camera to detect your emotions in real time. " +
                "Please grant camera access to get started.",
            color = Color.White.copy(alpha = 0.75f),
            fontSize = 15.sp,
            textAlign = TextAlign.Center,
            lineHeight = 22.sp
        )
        Spacer(modifier = Modifier.height(28.dp))
        Button(
            onClick = onRetry,
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6650A4))
        ) {
            Text(text = "Grant Permission", color = Color.White, fontSize = 16.sp)
        }
    }
}

/**
 * Shows a full-screen live preview from the front camera.
 *
 * Every 4 seconds it grabs the latest frame, converts it to JPEG, and POSTs
 * it to the emotion server.  Results are forwarded via [onEmotionDetected].
 *
 * All heavy work (conversion + network) runs on [Dispatchers.IO] so the
 * camera executor and the main/UI thread are never blocked.
 */
@Composable
private fun CameraPreview(onEmotionDetected: (String) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val coroutineScope = rememberCoroutineScope()

    // Single background thread dedicated to image analysis callbacks
    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }
    // Shut the executor down when this composable leaves the screen
    DisposableEffect(Unit) {
        onDispose { cameraExecutor.shutdown() }
    }

    // One shared OkHttp client (thread-safe; reuses connection pools)
    val httpClient = remember {
        OkHttpClient.Builder()
            .callTimeout(8, TimeUnit.SECONDS) // give up after 8 s to avoid hanging
            .build()
    }

    // The native Android View that renders the camera stream
    val previewView = remember { PreviewView(context) }

    // Run the CameraX setup once when this composable first appears
    LaunchedEffect(Unit) {
        // ProcessCameraProvider.getInstance() returns a ListenableFuture.
        // We bridge it into a coroutine with suspendCancellableCoroutine so
        // we can write sequential-looking code without blocking the main thread.
        val cameraProvider = suspendCancellableCoroutine<ProcessCameraProvider> { cont ->
            val future = ProcessCameraProvider.getInstance(context)
            future.addListener(
                { cont.resume(future.get()) },
                ContextCompat.getMainExecutor(context) // callback on main thread
            )
        }

        // Use case 1: live preview — connects the camera to previewView
        val preview = Preview.Builder().build().also {
            it.setSurfaceProvider(previewView.surfaceProvider)
        }

        // Use case 2: image analysis — gives us access to each camera frame
        val imageAnalysis = ImageAnalysis.Builder()
            // STRATEGY_KEEP_ONLY_LATEST drops frames we haven't processed yet,
            // so we always analyse the freshest image
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            // RGBA_8888 output is the easiest format to copy into a Bitmap
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .build()

        // Timestamp of the last frame we sent to the server
        var lastCaptureMs = 0L

        imageAnalysis.setAnalyzer(cameraExecutor) { imageProxy ->
            val now = System.currentTimeMillis()

            if (now - lastCaptureMs >= 4_000L) {
                // --- Time to capture a new frame ---
                lastCaptureMs = now

                // Copy pixel data from the image plane into a Bitmap.
                // planes[0] is the only plane for RGBA_8888.
                val plane = imageProxy.planes[0]
                val pixelStride = plane.pixelStride   // always 4 for RGBA_8888
                val rowStride = plane.rowStride        // may include row-end padding
                val rowWidth = imageProxy.width * pixelStride

                val bitmap = Bitmap.createBitmap(
                    imageProxy.width,
                    imageProxy.height,
                    Bitmap.Config.ARGB_8888
                )

                if (rowStride == rowWidth) {
                    // No padding — copy the whole buffer in one shot
                    bitmap.copyPixelsFromBuffer(plane.buffer)
                } else {
                    // Some devices pad rows to a memory-aligned boundary.
                    // Skip the padding bytes so the bitmap isn't corrupted.
                    val cleanPixels = ByteArray(imageProxy.width * imageProxy.height * pixelStride)
                    val srcRow = ByteArray(rowStride)
                    var dstOffset = 0
                    repeat(imageProxy.height) {
                        plane.buffer.get(srcRow)
                        System.arraycopy(srcRow, 0, cleanPixels, dstOffset, rowWidth)
                        dstOffset += rowWidth
                    }
                    bitmap.copyPixelsFromBuffer(ByteBuffer.wrap(cleanPixels))
                }

                // Always close the proxy so CameraX can reuse its buffer
                imageProxy.close()

                // Launch a coroutine on IO threads for the network call
                coroutineScope.launch(Dispatchers.IO) {
                    try {
                        val emotion = postFrameAndGetEmotion(httpClient, bitmap)
                        // Update Compose state on the main thread
                        withContext(Dispatchers.Main) { onEmotionDetected(emotion) }
                    } catch (_: Exception) {
                        // Network failure, timeout, or bad JSON — silently keep
                        // the last known emotion; the UI stays stable
                    }
                }
            } else {
                // Not time yet — discard this frame
                imageProxy.close()
            }
        }

        // Bind both use cases to the front camera + this screen's lifecycle
        try {
            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(
                lifecycleOwner,
                CameraSelector.DEFAULT_FRONT_CAMERA,
                preview,
                imageAnalysis
            )
        } catch (_: Exception) {
            // bindToLifecycle can throw if, for example, the device has no
            // front camera.  The preview just stays blank — no crash.
        }
    }

    // Embed the traditional Android View inside a Compose layout
    AndroidView(
        factory = { previewView },
        modifier = Modifier.fillMaxSize()
    )
}

// ---------------------------------------------------------------------------
// Entry point
// ---------------------------------------------------------------------------

/**
 * Top-level screen composable for MirrorFriend.
 *
 * Flow:
 *  1. On first load, request the CAMERA permission via the system dialog.
 *  2. If granted → show the live camera feed + floating emotion badge.
 *  3. If denied  → show a friendly message with a "Grant Permission" button.
 *
 * The emotion defaults to "neutral" until the first server response arrives.
 */
@Composable
fun CameraEmotionScreen() {
    val context = LocalContext.current

    // Check immediately whether permission was already granted in a previous run
    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED
        )
    }
    // Becomes true only after the user explicitly taps "Deny"
    var permissionDenied by remember { mutableStateOf(false) }

    // The emotion shown in the badge; "neutral" until we hear from the server
    var currentEmotion by remember { mutableStateOf("neutral") }

    // AppStorage lets us persist detected emotions to DataStore so the Home
    // dashboard can read them and compute a real wellness score.
    val storage = remember { AppStorage(context.applicationContext) }
    val coroutineScope = rememberCoroutineScope()

    // Registers a callback that fires once the system permission dialog closes
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasCameraPermission = granted
        if (!granted) permissionDenied = true
    }

    // Ask for the permission as soon as the screen loads (if needed)
    LaunchedEffect(Unit) {
        if (!hasCameraPermission) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    // Black background so nothing shows through during transitions
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        when {
            hasCameraPermission -> {
                // Full-screen live camera preview
                CameraPreview(onEmotionDetected = { emotion ->
                    currentEmotion = emotion
                    // Persist the emotion to DataStore so the Home dashboard's
                    // recent-emotions list and wellness score stay up to date.
                    // launch { } dispatches to a background thread automatically.
                    coroutineScope.launch { storage.addEmotion(emotion) }
                })
                // Emotion badge floats on top of the preview
                EmotionBadge(emotion = currentEmotion)
            }

            permissionDenied -> {
                // Explain why the app needs the camera and offer a retry
                PermissionDeniedScreen(
                    onRetry = {
                        permissionDenied = false
                        permissionLauncher.launch(Manifest.permission.CAMERA)
                    }
                )
            }

            // else: the system permission dialog is currently showing.
            // We just show the black background behind it — no action needed.
        }
    }
}
