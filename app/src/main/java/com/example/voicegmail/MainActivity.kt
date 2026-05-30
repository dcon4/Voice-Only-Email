package com.example.voicegmail

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.example.voicegmail.auth.OAuthRedirectBus
import com.example.voicegmail.debug.DebugLogger
import com.example.voicegmail.ui.screens.ComposeEmailScreen
import com.example.voicegmail.ui.screens.InboxScreen
import com.example.voicegmail.ui.theme.VoiceGmailTheme
import com.example.voicegmail.voice.VoiceManager
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var voiceManager: VoiceManager
    @Inject lateinit var oAuthRedirectBus: OAuthRedirectBus
    @Inject lateinit var wakePreferences: com.example.voicegmail.voice.WakePreferences

    /**
     * PARTIAL_WAKE_LOCK keeps the CPU awake for the entire Activity lifetime so
     * that the TTS->mic->command loop continues even after the screen turns off.
     *
     * VoiceWakeService independently holds its own PARTIAL_WAKE_LOCK for when
     * the Activity is not in the foreground at all. Together they guarantee that
     * at least one component is keeping the CPU alive at all times while the
     * user is actively using the app.
     */
    private var wakeLock: PowerManager.WakeLock? = null

    // ---- Permission launchers ------------------------------------------------

    private val micPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                DebugLogger.log("MainActivity", "RECORD_AUDIO granted")
                // On Android 14+, FOREGROUND_SERVICE_TYPE_MICROPHONE requires
                // RECORD_AUDIO to be granted before the service can start.
                // Start here (deferred from onCreate) now that it is granted.
                if (wakePreferences.isRunInBackground()) {
                    VoiceWakeService.start(this)
                }
            } else {
                DebugLogger.log("MainActivity", "RECORD_AUDIO DENIED")
                voiceManager.speak(
                    "Microphone permission was denied. Voice commands will not work. " +
                        "Please grant microphone permission in your device settings."
                )
            }
        }

    private val notifPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            DebugLogger.log("MainActivity", "POST_NOTIFICATIONS granted=$granted")
            if (granted) {
                voiceManager.speak("Notification permission granted. The app will show a status indicator while running in the background.")
            } else {
                voiceManager.speak(
                    "Notification permission was denied. The app still works, but Android " +
                        "may stop it in the background without showing a notification. " +
                        "You can grant this later in your device settings under App Notifications."
                )
            }
        }

    // ---- Lifecycle -----------------------------------------------------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DebugLogger.log("MainActivity", "onCreate")

        enableLockScreenMode()
        handleOAuthRedirectIntent(intent)

        // PARTIAL_WAKE_LOCK: screen may dim/turn off, but CPU stays awake so
        // TTS and SpeechRecognizer keep working in the background.
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "VoiceGmail:ActivityWakeLock"
        ).also { it.acquire() } // indefinite; released in onDestroy

        val micGranted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        if (!micGranted) {
            // Request the permission; VoiceWakeService is started in the grant
            // callback once RECORD_AUDIO is confirmed.  On Android 14+, starting
            // a FOREGROUND_SERVICE_TYPE_MICROPHONE service without this permission
            // throws SecurityException and crashes the process.
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            // Speak rationale before showing the system permission dialog
            voiceManager.speak(
                "VoiceGmail needs notification permission to show a status indicator " +
                    "while running in the background. Please allow notifications when prompted."
            )
            // Delay the request slightly so TTS can deliver the rationale
            android.os.Handler(mainLooper).postDelayed({
                notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }, 4500)
        }

        // Start the background wake service only when RECORD_AUDIO is already
        // granted (typical after the first install).  If not yet granted the
        // micPermissionLauncher callback above will start it on approval.
        if (micGranted && wakePreferences.isRunInBackground()) {
            VoiceWakeService.start(this)
        }

        setContent {
            VoiceGmailTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val navController = rememberNavController()
                    NavHost(navController = navController, startDestination = "inbox") {
                        composable("inbox") {
                            InboxScreen(
                                onCompose = { replyTo, isForward ->
                                    val params = buildList {
                                        if (!replyTo.isNullOrBlank()) add("replyTo=${Uri.encode(replyTo)}")
                                        if (isForward) add("isForward=true")
                                    }
                                    val route = if (params.isEmpty()) "compose"
                                               else "compose?${params.joinToString("&")}"
                                    navController.navigate(route)
                                }
                            )
                        }
                        composable(
                            route = "compose?replyTo={replyTo}&isForward={isForward}",
                            arguments = listOf(
                                navArgument("replyTo") {
                                    type = NavType.StringType; nullable = true; defaultValue = null
                                },
                                navArgument("isForward") {
                                    type = NavType.BoolType; defaultValue = false
                                }
                            )
                        ) { backStack ->
                            ComposeEmailScreen(
                                onBack = { navController.popBackStack() },
                                replyTo = backStack.arguments?.getString("replyTo"),
                                isForward = backStack.arguments?.getBoolean("isForward") ?: false
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        DebugLogger.log("MainActivity", "onNewIntent — data=${intent.data} action=${intent.action}")
        handleOAuthRedirectIntent(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        voiceManager.shutdown()
    }

    // ---- Helpers -------------------------------------------------------------

    private fun enableLockScreenMode() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
    }

    private fun handleOAuthRedirectIntent(intent: Intent?) {
        val uri    = intent?.data ?: return
        val scheme = uri.scheme ?: return
        if (!scheme.startsWith("com.googleusercontent.apps")) return
        DebugLogger.log("MainActivity", "OAuth redirect — scheme=$scheme")
        oAuthRedirectBus.post(uri)
    }
}
