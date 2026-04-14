package com.example.voicegmail.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Create
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.example.voicegmail.R
import com.example.voicegmail.data.EmailMessage
import com.example.voicegmail.voice.VoiceManager
import com.example.voicegmail.viewmodel.InboxViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InboxScreen(
    onMessageClick: (String) -> Unit,
    onComposeClick: () -> Unit,
    onSettingsClick: () -> Unit,
    voiceManager: VoiceManager,
    viewModel: InboxViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()

    // Announce inbox to TTS when screen opens
    LaunchedEffect(Unit) {
        voiceManager.speakNow("Inbox. Say 'compose' to write an email, or tap an email to read it.")
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.inbox_title)) },
                actions = {
                    IconButton(
                        onClick = { viewModel.loadInbox() },
                        modifier = Modifier.semantics { contentDescription = "Refresh inbox" }
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = null)
                    }
                    IconButton(
                        onClick = onSettingsClick,
                        modifier = Modifier.semantics { contentDescription = "Open settings" }
                    ) {
                        Icon(Icons.Default.Settings, contentDescription = null)
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = onComposeClick,
                modifier = Modifier.semantics { contentDescription = "Compose new email" }
            ) {
                Icon(Icons.Default.Create, contentDescription = null)
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            when (val s = state) {
                is InboxViewModel.InboxState.Loading -> {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .semantics { contentDescription = "Loading inbox" }
                    )
                }

                is InboxViewModel.InboxState.Error -> {
                    Text(
                        text = stringResource(R.string.error_prefix) + s.message,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(16.dp)
                    )
                    LaunchedEffect(s.message) {
                        voiceManager.speakNow("Error: ${s.message}")
                    }
                }

                is InboxViewModel.InboxState.Success -> {
                    if (s.messages.isEmpty()) {
                        Text(
                            text = stringResource(R.string.no_emails),
                            modifier = Modifier.align(Alignment.Center)
                        )
                        LaunchedEffect(Unit) {
                            voiceManager.speakNow("Your inbox is empty.")
                        }
                    } else {
                        LaunchedEffect(s.messages.size) {
                            voiceManager.speakNow(
                                "You have ${s.messages.size} emails. " +
                                        "First email from ${s.messages.first().from}."
                            )
                        }
                        LazyColumn(modifier = Modifier.fillMaxSize()) {
                            items(s.messages) { msg ->
                                EmailListItem(
                                    message = msg,
                                    onClick = { onMessageClick(msg.id) }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EmailListItem(
    message: EmailMessage,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .clickable(
                onClickLabel = "Read email from ${message.from}, subject ${message.subject}"
            ) { onClick() }
            .semantics {
                contentDescription =
                    "Email from ${message.from}. Subject: ${message.subject}. " +
                            "${message.snippet.take(80)}"
            },
        elevation = CardDefaults.cardElevation(defaultElevation = if (message.isUnread) 4.dp else 1.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (message.isUnread)
                MaterialTheme.colorScheme.primaryContainer
            else
                MaterialTheme.colorScheme.surface
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = message.from,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = if (message.isUnread) FontWeight.Bold else FontWeight.Normal,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = message.date.take(16),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = message.subject,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (message.isUnread) FontWeight.SemiBold else FontWeight.Normal
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = message.snippet,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2
            )
        }
    }
}
