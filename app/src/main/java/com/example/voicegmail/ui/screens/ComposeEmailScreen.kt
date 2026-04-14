package com.example.voicegmail.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.example.voicegmail.ui.viewmodel.ComposeViewModel
import com.example.voicegmail.ui.viewmodel.SendState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ComposeEmailScreen(
    onBack: () -> Unit,
    viewModel: ComposeViewModel = hiltViewModel()
) {
    var to by remember { mutableStateOf("") }
    var subject by remember { mutableStateOf("") }
    var body by remember { mutableStateOf("") }
    var activeField by remember { mutableStateOf<String?>(null) }

    val sendState by viewModel.sendState.collectAsState()
    val isListening by viewModel.isListening.collectAsState()

    LaunchedEffect(sendState) {
        if (sendState is SendState.Success) {
            onBack()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Compose") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(
                        onClick = { viewModel.sendEmail(to, subject, body) },
                        enabled = sendState !is SendState.Sending && to.isNotBlank()
                    ) {
                        Icon(Icons.Default.Send, contentDescription = "Send")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (sendState is SendState.Error) {
                Text(
                    text = "Error: ${(sendState as SendState.Error).message}",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall
                )
            }

            OutlinedTextField(
                value = to,
                onValueChange = { to = it },
                label = { Text("To") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                trailingIcon = {
                    IconButton(onClick = {
                        activeField = "to"
                        viewModel.startVoiceInput { result -> to = result }
                    }) {
                        Icon(
                            Icons.Default.Mic,
                            contentDescription = "Voice input for To",
                            tint = if (isListening && activeField == "to")
                                MaterialTheme.colorScheme.primary
                            else
                                MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            )

            OutlinedTextField(
                value = subject,
                onValueChange = { subject = it },
                label = { Text("Subject") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                trailingIcon = {
                    IconButton(onClick = {
                        activeField = "subject"
                        viewModel.startVoiceInput { result -> subject = result }
                    }) {
                        Icon(
                            Icons.Default.Mic,
                            contentDescription = "Voice input for Subject",
                            tint = if (isListening && activeField == "subject")
                                MaterialTheme.colorScheme.primary
                            else
                                MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            )

            OutlinedTextField(
                value = body,
                onValueChange = { body = it },
                label = { Text("Message") },
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                trailingIcon = {
                    IconButton(onClick = {
                        activeField = "body"
                        viewModel.startVoiceInput { result -> body = result }
                    }) {
                        Icon(
                            Icons.Default.Mic,
                            contentDescription = "Voice input for Message",
                            tint = if (isListening && activeField == "body")
                                MaterialTheme.colorScheme.primary
                            else
                                MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            )

            if (sendState is SendState.Sending) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        }
    }
}
