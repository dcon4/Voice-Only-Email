package com.example.voicegmail

import android.Manifest
import android.content.Context
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

    private var wakeLock: PowerManager.WakeLock? = null

    // Request the RECORD_AUDIO permission at startup. The app cannot function
    // without it — speech recognition will silently fail or crash if it is missing.
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

        // Ask for microphone permission if we don't already have it.
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
                                onCompose = { replyTo ->
                                    if (!replyTo.isNullOrBlank()) {
                                        navController.navigate(
                                            "compose?replyTo=${Uri.encode(replyTo)}"
                                        )
                                    } else {
                                        navController.navigate("compose")
                                    }
                                }
                            )
                        }
                        composable(
                            route = "compose?replyTo={replyTo}",
                            arguments = listOf(
                                navArgument("replyTo") {
                                    type = NavType.StringType
                                    nullable = true
                                    defaultValue = null
                                }
                            )
                        ) { backStackEntry ->
                            val replyTo = backStackEntry.arguments?.getString("replyTo")
                            ComposeEmailScreen(
                                onBack = { navController.popBackStack() },
                                replyTo = replyTo
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        DebugLogger.log("MainActivity", "onResume")
        // Keep the screen and CPU on while the app is active so the microphone
        // is never cut off during voice dictation.
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
