package com.example.voicegmail.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
    onCompose: () -> Unit,
    viewModel: InboxViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val isSignedIn by viewModel.isSignedIn.collectAsState()

    val signInLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        viewModel.handleSignInResult(result)
    }

    // Observe one-shot navigation events from voice commands (e.g. "compose")
    LaunchedEffect(Unit) {
        viewModel.navigationEvent.collect { event ->
            when (event) {
                is InboxNavEvent.NavigateToCompose -> onCompose()
            }
        }
    }

    // Stop voice when leaving the screen
    DisposableEffect(Unit) {
        onDispose { viewModel.stopVoice() }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "Voice Gmail",
                        modifier = Modifier.semantics { heading() }
                    )
                },
                actions = {
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
        },
        floatingActionButton = {
            if (isSignedIn) {
                FloatingActionButton(
                    onClick = onCompose,
                    modifier = Modifier.semantics {
                        contentDescription = "Compose new email"
                    }
                ) {
                    Icon(Icons.Default.Add, contentDescription = null)
                }
            }
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
                            onClick = { signInLauncher.launch(viewModel.getSignInIntent()) },
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
                        emails = state.emails,
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
                            text = "Error: ${state.message}",
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.semantics {
                                contentDescription = "Error: ${state.message}"
                            }
                        )
                        Spacer(Modifier.height(8.dp))
                        Button(
                            onClick = { viewModel.loadInbox() },
                            modifier = Modifier.semantics { contentDescription = "Retry loading inbox" }
                        ) {
                            Text("Retry")
                        }
                    }
                }
            }
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
    val rowDescription = "Email from ${email.from}. Subject: ${email.subject}. ${email.snippet}"
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                onClick = onClick,
                onClickLabel = "Read email aloud"
            )
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .semantics(mergeDescendants = true) {
                contentDescription = rowDescription
            }
    ) {
        Text(
            text = email.from,
            style = MaterialTheme.typography.titleSmall
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = email.subject,
            style = MaterialTheme.typography.bodyMedium
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = email.snippet,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2
        )
    }
}
