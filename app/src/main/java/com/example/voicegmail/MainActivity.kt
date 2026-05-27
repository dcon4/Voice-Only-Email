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

    private var wakeLock: PowerManager.WakeLock? = null

    // ---- Permission launchers ------------------------------------------------

    private val micPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                DebugLogger.log("MainActivity", "RECORD_AUDIO granted")
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
            // Even if denied the foreground service runs — the notification just stays hidden.
        }

    // ---- Lifecycle -----------------------------------------------------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DebugLogger.log("MainActivity", "onCreate")

        // Show this activity over the lock screen and turn the screen on when
        // the app is brought to the foreground from VoiceWakeService.
        enableLockScreenMode()

        // Handle OAuth redirect on a fresh process start.
        handleOAuthRedirectIntent(intent)

        // Mic permission — required for voice recognition.
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }

        // Notification permission — Android 13+ (API 33+).
        // The foreground service notification for VoiceWakeService requires this on API 33+.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        // Start the background wake service so the app can be activated by the
        // power button even when the screen is locked.
        VoiceWakeService.start(this)

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
        // Wake intent from VoiceWakeService: the WakeEventBus has already been
        // posted by the service before startActivity() was called, so InboxViewModel
        // will receive the wake event via its coroutine collector — no extra work here.
    }

    override fun onResume() {
        super.onResume()
        DebugLogger.log("MainActivity", "onResume")
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        @Suppress("DEPRECATION")
        wakeLock = pm.newWakeLock(
            PowerManager.SCREEN_DIM_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
            "VoiceGmail:ListeningWakeLock"
        ).also { it.acquire(10 * 60 * 1000L) }
    }

    override fun onPause() {
        super.onPause()
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    override fun onDestroy() {
        super.onDestroy()
        voiceManager.shutdown()
    }

    // ---- Helpers -------------------------------------------------------------

    /**
     * Allow the activity to appear over the lock screen and turn the screen on.
     * API 27+ uses Activity methods; API 26 falls back to the deprecated window flags.
     */
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
