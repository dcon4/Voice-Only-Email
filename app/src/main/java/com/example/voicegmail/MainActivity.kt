package com.example.voicegmail

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
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

    /**
     * Hilt-injected singleton bus shared with InboxViewModel.
     * MainActivity posts OAuth redirect URIs here; InboxViewModel collects and exchanges
     * the auth code for tokens. Using a StateFlow ensures no URI is lost even when the
     * ViewModel starts collecting after the redirect has been posted (process-death scenario).
     */
    @Inject lateinit var oAuthRedirectBus: OAuthRedirectBus

    private var wakeLock: PowerManager.WakeLock? = null

    private val micPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                DebugLogger.log("MainActivity", "RECORD_AUDIO permission granted")
            } else {
                DebugLogger.log("MainActivity", "RECORD_AUDIO permission DENIED — voice input will not work")
                voiceManager.speak(
                    "Microphone permission was denied. Voice commands will not work. " +
                        "Please grant the microphone permission in your device settings."
                )
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DebugLogger.log("MainActivity", "onCreate")

        // Handle OAuth redirect delivered on a fresh process start.
        // When the app process was killed while Chrome was open, Android restarts
        // MainActivity directly from the redirect intent (singleTask + intent-filter).
        handleOAuthRedirectIntent(intent)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }

        setContent {
            VoiceGmailTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val navController = rememberNavController()
                    NavHost(
                        navController = navController,
                        startDestination = "inbox"
                    ) {
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
                                    type = NavType.StringType
                                    nullable = true
                                    defaultValue = null
                                },
                                navArgument("isForward") {
                                    type = NavType.BoolType
                                    defaultValue = false
                                }
                            )
                        ) { backStackEntry ->
                            val replyTo = backStackEntry.arguments?.getString("replyTo")
                            val isForward = backStackEntry.arguments?.getBoolean("isForward") ?: false
                            ComposeEmailScreen(
                                onBack = { navController.popBackStack() },
                                replyTo = replyTo,
                                isForward = isForward
                            )
                        }
                    }
                }
            }
        }
    }

    /**
     * Called when the app is already running and Chrome delivers the OAuth redirect.
     * singleTask launch mode ensures Android routes the redirect here and clears
     * AuthorizationManagementActivity from the back stack automatically.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        DebugLogger.log("MainActivity", "onNewIntent — data=${intent.data}")
        handleOAuthRedirectIntent(intent)
    }

    private fun handleOAuthRedirectIntent(intent: Intent?) {
        val uri = intent?.data ?: return
        // Only handle our OAuth redirect scheme; ignore all other implicit intents.
        val scheme = uri.scheme ?: return
        if (!scheme.startsWith("com.googleusercontent.apps")) return
        DebugLogger.log("MainActivity", "OAuth redirect detected — scheme=$scheme")
        oAuthRedirectBus.post(uri)
    }

    override fun onResume() {
        super.onResume()
        DebugLogger.log("MainActivity", "onResume")
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        @Suppress("DEPRECATION")
        wakeLock = pm.newWakeLock(
            PowerManager.SCREEN_DIM_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
            "VoiceGmail:ListeningWakeLock"
        ).also { it.acquire(10 * 60 * 1000L /* 10 min max */) }
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
}
