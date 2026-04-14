package com.example.voicegmail.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.example.voicegmail.voice.VoiceManager
import com.example.voicegmail.viewmodel.InboxViewModel

private const val TTS_BODY_PREVIEW_LENGTH = 500
private const val UI_BODY_PREVIEW_LENGTH = 300

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MessageDetailScreen(
    messageId: String,
    onBack: () -> Unit,
    onReply: (String) -> Unit,
    voiceManager: VoiceManager,
    viewModel: InboxViewModel = hiltViewModel()
) {
    val message by viewModel.selectedMessage.collectAsState()

    LaunchedEffect(messageId) {
        viewModel.loadMessage(messageId)
    }

    LaunchedEffect(message) {
        message?.let { msg ->
            voiceManager.speakNow(
                "Email from ${msg.from}. Subject: ${msg.subject}. ${msg.bodyText.take(TTS_BODY_PREVIEW_LENGTH)}"
            )
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(message?.subject ?: "Email") },
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.semantics { contentDescription = "Go back to inbox" }
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { message?.from?.let { onReply(it) } },
                modifier = Modifier.semantics { contentDescription = "Reply to this email" }
            ) {
                Icon(Icons.Default.Edit, contentDescription = null)
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (message == null) {
                CircularProgressIndicator(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .semantics { contentDescription = "Loading email" }
                )
            } else {
                val msg = message!!
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp)
                ) {
                    Text(
                        text = msg.subject,
                        style = MaterialTheme.typography.titleLarge
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "From: ${msg.from}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = msg.date,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(16.dp))
                    Text(
                        text = msg.bodyText.ifBlank { msg.snippet },
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.semantics {
                            contentDescription = "Email body: ${msg.bodyText.take(UI_BODY_PREVIEW_LENGTH)}"
                        }
                    )
                }
            }
        }
    }
}
