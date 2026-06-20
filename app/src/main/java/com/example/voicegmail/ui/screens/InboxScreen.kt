package com.example.voicegmail.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.example.voicegmail.gmail.EmailItem
import com.example.voicegmail.ui.viewmodel.InboxNavEvent
import com.example.voicegmail.ui.viewmodel.InboxUiState
import com.example.voicegmail.ui.viewmodel.InboxViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InboxScreen(
    onCompose: (replyTo: String?, isForward: Boolean) -> Unit,
    viewModel: InboxViewModel = hiltViewModel()
) {
    val uiState          by viewModel.uiState.collectAsState()
    val isSignedIn       by viewModel.isSignedIn.collectAsState()
    val settingsVisible  by viewModel.settingsPanelVisible.collectAsState()
    val context          = LocalContext.current
    var showDebugDialog  by remember { mutableStateOf(false) }

    // One-shot navigation events from voice commands
    LaunchedEffect(Unit) {
        viewModel.navigationEvent.collect { event ->
            when (event) {
                is InboxNavEvent.NavigateToCompose -> onCompose(event.replyTo, event.isForward)
            }
        }
    }

    // Sign-in requested by voice command
    LaunchedEffect(Unit) {
        viewModel.signInRequested.collect { intent ->
            context.startActivity(intent)
        }
    }

    DisposableEffect(Unit) { onDispose { viewModel.stopVoice() } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "VoiceGmail",
                        modifier = Modifier.semantics { heading() }
                    )
                },
                actions = {
                    // Settings — opens VoiceSettingsPanel for sighted helpers
                    if (isSignedIn) {
                        IconButton(
                            onClick = { viewModel.openSettingsPanel() },
                            modifier = Modifier.semantics {
                                contentDescription = "Open voice settings"
                            }
                        ) {
                            Icon(
                                Icons.Default.Settings,
                                contentDescription = null
                            )
                        }
                    }
                    // Debug log share
                    IconButton(
                        onClick = { showDebugDialog = true },
                        modifier = Modifier.semantics {
                            contentDescription = "Share debug log"
                        }
                    ) {
                        Icon(Icons.Default.BugReport, contentDescription = null)
                    }
                    // Refresh
                    IconButton(
                        onClick = { viewModel.loadInbox() },
                        modifier = Modifier.semantics {
                            contentDescription = "Refresh inbox"
                        }
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = null)
                    }
                }
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            when (val state = uiState) {
                is InboxUiState.Loading -> {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .semantics { contentDescription = "Loading inbox" }
                    )
                }

                is InboxUiState.SignedOut -> {
                    Column(
                        modifier = Modifier.align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Text(
                            "Sign in to access your Gmail inbox",
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.semantics { heading() }
                        )
                        Button(
                            onClick = { context.startActivity(viewModel.getSignInIntent()) },
                            modifier = Modifier.semantics {
                                contentDescription = "Sign in with Google"
                            }
                        ) {
                            Text("Sign in with Google")
                        }
                    }
                }

                is InboxUiState.Success -> {
                    EmailList(
                        emails       = state.emails,
                        onEmailClick = { email -> viewModel.readEmailAloud(email) }
                    )
                }

                is InboxUiState.Error -> {
                    Column(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text  = state.message,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.semantics {
                                contentDescription = "Error: ${state.message}"
                            }
                        )
                        Spacer(Modifier.height(12.dp))
                        if (state.isAuthError) {
                            Button(
                                onClick = { context.startActivity(viewModel.getSignInIntent()) },
                                modifier = Modifier.semantics {
                                    contentDescription = "Sign in with Google"
                                }
                            ) {
                                Text("Sign in with Google")
                            }
                        } else {
                            Button(
                                onClick = { viewModel.loadInbox() },
                                modifier = Modifier.semantics {
                                    contentDescription = "Retry loading inbox"
                                }
                            ) {
                                Text("Retry")
                            }
                        }
                    }
                }
            }

            // Compose FAB inside the content Box (below panel overlays)
            // so the AppLauncherPanel renders on top of it.
            val launcherOpen by viewModel.launcherPanelVisible.collectAsState()
            if (isSignedIn && !launcherOpen) {
                FloatingActionButton(
                    onClick = { onCompose(null, false) },
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(16.dp)
                        .semantics { contentDescription = "Compose new email" }
                ) {
                    Icon(Icons.Default.Add, contentDescription = null)
                }
            }
        }

        // Voice settings panel — shown on top of inbox content
        if (settingsVisible) {
            VoiceSettingsPanel(viewModel = viewModel)
        }

        // App launcher panel — shown on top of inbox content
        val launcherPanelVisible by viewModel.launcherPanelVisible.collectAsState()
        if (launcherPanelVisible) {
            AppLauncherPanel(viewModel = viewModel)
        }

        // Bible options sub-page — shown on top of inbox content
        val bibleSettingsVisible by viewModel.bibleSettingsVisible.collectAsState()
        if (bibleSettingsVisible) {
            BibleSettingsScreen(viewModel = viewModel)
        }

        // Debug log share/email dialog
        if (showDebugDialog) {
            AlertDialog(
                onDismissRequest = { showDebugDialog = false },
                title = { Text("Debug Log") },
                text = { Text("Share the debug log file with another app, or open an email with the subject and file pre-filled.") },
                confirmButton = {
                    TextButton(onClick = {
                        viewModel.getShareLogIntent()?.let { intent ->
                            context.startActivity(
                                android.content.Intent.createChooser(intent, "Share debug log")
                            )
                        }
                        showDebugDialog = false
                    }) { Text("Share") }
                },
                dismissButton = {
                    TextButton(onClick = {
                        viewModel.getEmailLogIntent()?.let { intent ->
                            context.startActivity(
                                android.content.Intent.createChooser(intent, "Email debug log")
                            )
                        }
                        showDebugDialog = false
                    }) { Text("Email") }
                }
            )
        }
    }
}

@Composable
private fun EmailList(
    emails: List<EmailItem>,
    onEmailClick: (EmailItem) -> Unit
) {
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(emails) { email ->
            EmailRow(email = email, onClick = { onEmailClick(email) })
            HorizontalDivider()
        }
    }
}

@Composable
private fun EmailRow(email: EmailItem, onClick: () -> Unit) {
    val unreadIndicator = if (email.isUnread) "Unread. " else ""
    val rowDescription  = "${unreadIndicator}Email from ${email.from}. Subject: ${email.subject}. ${email.snippet}"
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick, onClickLabel = "Read email aloud")
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .semantics(mergeDescendants = true) {
                contentDescription = rowDescription
            }
    ) {
        Text(
            text  = email.from,
            style = if (email.isUnread) MaterialTheme.typography.titleSmall
                    else MaterialTheme.typography.titleSmall.copy(
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text  = email.subject,
            style = if (email.isUnread) MaterialTheme.typography.bodyMedium
                    else MaterialTheme.typography.bodyMedium.copy(
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text     = email.snippet,
            style    = MaterialTheme.typography.bodySmall,
            color    = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2
        )
    }
}
