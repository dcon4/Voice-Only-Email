package com.example.voicegmail

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.speech.RecognizerIntent
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.example.voicegmail.ui.screens.ComposeScreen
import com.example.voicegmail.ui.screens.InboxScreen
import com.example.voicegmail.ui.screens.MessageDetailScreen
import com.example.voicegmail.ui.screens.SettingsScreen
import com.example.voicegmail.ui.screens.SignInScreen
import com.example.voicegmail.ui.theme.VoiceGmailTheme
import com.example.voicegmail.viewmodel.AuthViewModel
import com.example.voicegmail.voice.VoiceController
import com.example.voicegmail.voice.VoiceManager
import dagger.hilt.android.AndroidEntryPoint
import java.util.Locale
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var voiceManager: VoiceManager
    @Inject lateinit var voiceController: VoiceController

    private val authViewModel: AuthViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            VoiceGmailTheme {
                VoiceGmailContent(
                    activity = this,
                    authViewModel = authViewModel,
                    voiceManager = voiceManager,
                    voiceController = voiceController
                )
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        voiceManager.shutdown()
    }
}

@Composable
private fun VoiceGmailContent(
    activity: Activity,
    authViewModel: AuthViewModel,
    voiceManager: VoiceManager,
    voiceController: VoiceController
) {
    val navController = rememberNavController()
    val authState by authViewModel.state.collectAsState()

    // Determine the start destination based on persisted token
    var startDestination by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        authViewModel.checkExistingToken()
    }

    LaunchedEffect(authState) {
        when (authState) {
            is AuthViewModel.AuthState.Authenticated -> {
                if (startDestination == null) {
                    startDestination = Screen.INBOX
                }
            }
            is AuthViewModel.AuthState.Idle,
            is AuthViewModel.AuthState.Error -> {
                if (startDestination == null) {
                    startDestination = Screen.SIGN_IN
                }
            }
            else -> Unit
        }
    }

    // OAuth launcher – opens browser for Google sign-in
    val authLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val intent = result.data
        authViewModel.handleAuthResult(activity, intent)
    }

    // Speech recognition launcher
    val speechLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val matches = result.data
                ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            val text = matches?.firstOrNull() ?: return@rememberLauncherForActivityResult
            voiceController.handle(
                rawText = text,
                navigate = { route -> navController.navigate(route) },
                onSignOut = {
                    authViewModel.signOut()
                    navController.navigate(Screen.SIGN_IN) {
                        popUpTo(0) { inclusive = true }
                    }
                },
                onBack = { navController.popBackStack() }
            )
        }
    }

    // Don't render NavHost until start destination is resolved
    val destination = startDestination ?: return

    AppNavHost(
        navController = navController,
        startDestination = destination,
        authViewModel = authViewModel,
        voiceManager = voiceManager,
        voiceController = voiceController,
        onSignInClick = {
            val intent = authViewModel.buildAuthIntent(activity)
            authLauncher.launch(intent)
        },
        onSpeechRequest = {
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(
                    RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                )
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
                putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak a command")
            }
            speechLauncher.launch(intent)
        }
    )
}

@Composable
private fun AppNavHost(
    navController: NavHostController,
    startDestination: String,
    authViewModel: AuthViewModel,
    voiceManager: VoiceManager,
    voiceController: VoiceController,
    onSignInClick: () -> Unit,
    onSpeechRequest: () -> Unit
) {
    NavHost(
        navController = navController,
        startDestination = startDestination
    ) {
        composable(Screen.SIGN_IN) {
            SignInScreen(
                onAuthenticated = {
                    navController.navigate(Screen.INBOX) {
                        popUpTo(Screen.SIGN_IN) { inclusive = true }
                    }
                },
                onSignInClick = onSignInClick,
                viewModel = androidx.hilt.navigation.compose.hiltViewModel()
            )
        }

        composable(Screen.INBOX) {
            InboxScreen(
                onMessageClick = { messageId ->
                    navController.navigate(Screen.messageDetail(messageId))
                },
                onComposeClick = { navController.navigate(Screen.COMPOSE) },
                onSettingsClick = { navController.navigate(Screen.SETTINGS) },
                voiceManager = voiceManager,
                viewModel = androidx.hilt.navigation.compose.hiltViewModel()
            )
        }

        composable(
            route = Screen.MESSAGE_DETAIL,
            arguments = listOf(navArgument("messageId") { type = NavType.StringType })
        ) { backStackEntry ->
            val messageId = backStackEntry.arguments?.getString("messageId") ?: ""
            MessageDetailScreen(
                messageId = messageId,
                onBack = { navController.popBackStack() },
                onReply = { fromAddress ->
                    navController.navigate(Screen.COMPOSE + "?to=${Uri.encode(fromAddress)}")
                },
                voiceManager = voiceManager,
                viewModel = androidx.hilt.navigation.compose.hiltViewModel()
            )
        }

        composable(
            route = "${Screen.COMPOSE}?to={to}",
            arguments = listOf(
                navArgument("to") {
                    type = NavType.StringType
                    defaultValue = ""
                    nullable = true
                }
            )
        ) { backStackEntry ->
            val prefillTo = backStackEntry.arguments?.getString("to") ?: ""
            ComposeScreen(
                prefillTo = prefillTo,
                onBack = { navController.popBackStack() },
                onSent = { navController.popBackStack() },
                voiceManager = voiceManager,
                viewModel = androidx.hilt.navigation.compose.hiltViewModel()
            )
        }

        composable(Screen.SETTINGS) {
            SettingsScreen(
                onBack = { navController.popBackStack() },
                onSignedOut = {
                    navController.navigate(Screen.SIGN_IN) {
                        popUpTo(0) { inclusive = true }
                    }
                },
                viewModel = androidx.hilt.navigation.compose.hiltViewModel()
            )
        }
    }
}
