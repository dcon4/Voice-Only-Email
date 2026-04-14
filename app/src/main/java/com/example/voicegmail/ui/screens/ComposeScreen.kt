package com.example.voicegmail.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.example.voicegmail.R
import com.example.voicegmail.voice.VoiceManager
import com.example.voicegmail.viewmodel.ComposeViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ComposeScreen(
    prefillTo: String = "",
    onBack: () -> Unit,
    onSent: () -> Unit,
    voiceManager: VoiceManager,
    viewModel: ComposeViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()
    val toField by viewModel.to.collectAsState()
    val subjectField by viewModel.subject.collectAsState()
    val bodyField by viewModel.body.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    // Pre-fill "To" if replying
    LaunchedEffect(prefillTo) {
        if (prefillTo.isNotBlank()) viewModel.setField("to", prefillTo)
    }

    LaunchedEffect(Unit) {
        voiceManager.speakNow(
            "Compose email. Say 'send to address', 'subject your subject', " +
                    "'body your message', then 'send'."
        )
    }

    LaunchedEffect(state) {
        when (val s = state) {
            is ComposeViewModel.ComposeState.Sent -> {
                voiceManager.speakNow("Email sent successfully.")
                snackbarHostState.showSnackbar("Email sent!")
                onSent()
            }
            is ComposeViewModel.ComposeState.Error -> {
                voiceManager.speakNow("Error: ${s.message}")
                snackbarHostState.showSnackbar("Error: ${s.message}")
                viewModel.resetState()
            }
            else -> Unit
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.compose_title)) },
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.semantics { contentDescription = "Go back" }
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
                actions = {
                    if (state is ComposeViewModel.ComposeState.Sending) {
                        CircularProgressIndicator(modifier = Modifier.padding(12.dp))
                    } else {
                        IconButton(
                            onClick = { viewModel.sendEmail() },
                            enabled = toField.isNotBlank(),
                            modifier = Modifier.semantics { contentDescription = "Send email" }
                        ) {
                            Icon(Icons.Default.Send, contentDescription = null)
                        }
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
        ) {
            Spacer(Modifier.height(8.dp))

            OutlinedTextField(
                value = toField,
                onValueChange = { viewModel.setField("to", it) },
                label = { Text(stringResource(R.string.to_field)) },
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = "To field. Enter recipient email address." },
                singleLine = true
            )

            Spacer(Modifier.height(8.dp))

            OutlinedTextField(
                value = subjectField,
                onValueChange = { viewModel.setField("subject", it) },
                label = { Text(stringResource(R.string.subject_field)) },
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = "Subject field" },
                singleLine = true
            )

            Spacer(Modifier.height(8.dp))

            OutlinedTextField(
                value = bodyField,
                onValueChange = { viewModel.setField("body", it) },
                label = { Text(stringResource(R.string.body_field)) },
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .semantics { contentDescription = "Message body field" },
                minLines = 8
            )

            Spacer(Modifier.height(16.dp))

            Button(
                onClick = { viewModel.sendEmail() },
                enabled = toField.isNotBlank() &&
                        state !is ComposeViewModel.ComposeState.Sending,
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = "Send email button" }
            ) {
                Text(stringResource(R.string.send_email))
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}
