package com.example.voicegmail.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.activity.compose.rememberLauncherForActivityResult
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

    val context = LocalContext.current
    val sendState by viewModel.sendState.collectAsState()
    val isListening by viewModel.isListening.collectAsState()

    // Tracks which field is waiting for RECORD_AUDIO permission to be granted
    var pendingPermissionField by remember { mutableStateOf<String?>(null) }

    val micPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            when (pendingPermissionField) {
                "to"      -> viewModel.startVoiceInput { result -> to = result }
                "subject" -> viewModel.startVoiceInput { result -> subject = result }
                "body"    -> viewModel.startVoiceInput { result -> body = result }
            }
        }
        pendingPermissionField = null
    }

    fun startVoiceForField(field: String, onResult: (String) -> Unit) {
        activeField = field
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED) {
            viewModel.startVoiceInput(onResult)
        } else {
            pendingPermissionField = field
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

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
                        startVoiceForField("to") { result -> to = result }
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
                        startVoiceForField("subject") { result -> subject = result }
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
                        startVoiceForField("body") { result -> body = result }
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
