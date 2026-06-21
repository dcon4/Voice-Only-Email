package com.example.voicegmail

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
import com.example.voicegmail.voice.WakePreferences
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var voiceManager: VoiceManager
    @Inject lateinit var oAuthRedirectBus: OAuthRedirectBus
    @Inject lateinit var wakeEventBus: WakeEventBus
    @Inject lateinit var wakePreferences: WakePreferences

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
                maybeStartWakeService()
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
                voiceManager.speak(
                    "Notification permission granted. The app will show a status indicator " +
                        "while running in the background."
                )
            } else {
                val canAskAgain = shouldShowRequestPermissionRationale(
                    Manifest.permission.POST_NOTIFICATIONS
                )
                if (!canAskAgain) {
                    voiceManager.speak(
                        "Notification permission was denied and will not be asked again. " +
                            "To wake on power button, go to Android App Settings for VoiceGmail " +
                            "and enable Notifications."
                    )
                } else {
                    voiceManager.speak(
                        "Notification permission was denied. The app still works, but Android " +
                            "may stop it in the background without showing a notification. " +
                            "You can grant this later in your device settings under App Notifications."
                    )
                }
            }
            // Request mic permission next (sequentially, after notif dialog)
            if (ContextCompat.checkSelfPermission(
                    this, Manifest.permission.RECORD_AUDIO
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        }

    // Note: READ_CONTACTS is requested by ContactManager / the contacts flow
    // the first time the user resolves a recipient by name. We do not ask for
    // it at startup because the prompt is unrelated to the core "open inbox"
    // path and an early prompt would be confusing.

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

        requestPermissionsAtStartup()
        maybeStartWakeService()
        registerScreenReceiver()

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

    private var screenReceiver: BroadcastReceiver? = null

    override fun onDestroy() {
        super.onDestroy()
        unregisterScreenReceiver()
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
        val uri = intent?.data ?: return
        val scheme = uri.scheme ?: return
        if (!scheme.startsWith("com.googleusercontent.apps")) return
        DebugLogger.log("MainActivity", "OAuth redirect — scheme=$scheme")
        oAuthRedirectBus.post(uri)
    }

    /**
     * Requests dangerous permissions sequentially so each dialog is visible:
     * notification first (with a TTS rationale), then mic. This avoids the
     * notification dialog being auto-denied when the mic dialog overlaps it.
     */
    private fun requestPermissionsAtStartup() {
        val micNeeded = ContextCompat.checkSelfPermission(
            this, Manifest.permission.RECORD_AUDIO
        ) != PackageManager.PERMISSION_GRANTED

        val notifNeeded = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED

        // Request notification first, mic second — sequential, no overlap
        if (notifNeeded) {
            voiceManager.speak(
                "VoiceGmail needs notification permission to show a status indicator " +
                    "while running in the background. Please allow notifications when prompted."
            )
            Handler(Looper.getMainLooper()).postDelayed({
                notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }, 1000)
        }

        // Request mic separately (or immediately if notification was not needed)
        if (micNeeded && !notifNeeded) {
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun canStartWakeService(): Boolean {
        val micGranted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        val notifGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED

        return micGranted && notifGranted && wakePreferences.isRunInBackground()
    }

    private fun maybeStartWakeService() {
        if (canStartWakeService()) {
            VoiceWakeService.start(this)
        }
    }

    /**
     * Registers a receiver for [ACTION_SCREEN_ON] (power button press when
     * screen was off). It pauses TTS and posts a wake event so the UI asks
     * "Continue reading?".
     *
     * Screen timing out ([ACTION_SCREEN_OFF]) intentionally does nothing --
     * the user is still listening and TTS keeps running.
     *
     * Registered in [onCreate] and unregistered in [onDestroy] so it stays
     * active even when the activity is paused with the screen off.
     *
     * Debounced at 1 second to filter the event storm that Samsung One UI
     * fires when the device exits doze/idle mode (11+ rapid events).
     */
    private var lastScreenOnMs: Long = 0L

    private fun registerScreenReceiver() {
        if (screenReceiver != null) return
        screenReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == Intent.ACTION_SCREEN_ON) {
                    val now = System.currentTimeMillis()
                    if (now - lastScreenOnMs < 1_000L) {
                        DebugLogger.log("MainActivity", "Screen ON — debounced (${now - lastScreenOnMs}ms since last)")
                        return
                    }
                    lastScreenOnMs = now
                    DebugLogger.log("MainActivity", "Screen ON — pause TTS, post wake event")
                    voiceManager.stopAll()
                    wakeEventBus.postWake()
                }
            }
        }
        registerReceiver(screenReceiver, IntentFilter(Intent.ACTION_SCREEN_ON))
    }

    private fun unregisterScreenReceiver() {
        val r = screenReceiver ?: return
        screenReceiver = null
        runCatching { unregisterReceiver(r) }
    }

}
