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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
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
    // Bind UI text to ViewModel state so both voice and manual entry stay in sync
    val to by viewModel.to.collectAsState()
    val subject by viewModel.subject.collectAsState()
    val body by viewModel.body.collectAsState()
    var activeField by remember { mutableStateOf<String?>(null) }

    val context = LocalContext.current
    val sendState by viewModel.sendState.collectAsState()
    val isListening by viewModel.isListening.collectAsState()

    // Navigate back when voice flow triggers it (cancel or successful send)
    LaunchedEffect(Unit) {
        viewModel.navigateBack.collect { onBack() }
    }

    // Stop voice when leaving the screen
    DisposableEffect(Unit) {
        onDispose { viewModel.stopAll() }
    }

    // Start the guided voice flow as soon as the screen is entered (permission check first)
    var permissionChecked by remember { mutableStateOf(false) }
    val micPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) viewModel.startGuidedVoiceFlow()
    }

    LaunchedEffect(Unit) {
        if (!permissionChecked) {
            permissionChecked = true
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
                    == PackageManager.PERMISSION_GRANTED) {
                viewModel.startGuidedVoiceFlow()
            } else {
                micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        }
    }

    // Tracks which field is waiting for RECORD_AUDIO permission for manual mic buttons
    var pendingPermissionField by remember { mutableStateOf<String?>(null) }

    val manualMicPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            when (pendingPermissionField) {
                "to"      -> viewModel.startVoiceInput { result -> viewModel.updateTo(result) }
                "subject" -> viewModel.startVoiceInput { result -> viewModel.updateSubject(result) }
                "body"    -> viewModel.startVoiceInput { result -> viewModel.updateBody(result) }
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
            manualMicPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "Compose",
                        modifier = Modifier.semantics { heading() }
                    )
                },
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.semantics { contentDescription = "Go back to inbox" }
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
                actions = {
                    IconButton(
                        onClick = { viewModel.sendEmail(to, subject, body) },
                        enabled = sendState !is SendState.Sending && to.isNotBlank(),
                        modifier = Modifier.semantics { contentDescription = "Send email" }
                    ) {
                        Icon(Icons.Default.Send, contentDescription = null)
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
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.semantics {
                        contentDescription = "Send error: ${(sendState as SendState.Error).message}"
                    }
                )
            }

            OutlinedTextField(
                value = to,
                onValueChange = { viewModel.updateTo(it) },
                label = { Text("To") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                trailingIcon = {
                    IconButton(
                        onClick = { startVoiceForField("to") { result -> viewModel.updateTo(result) } },
                        modifier = Modifier.semantics { contentDescription = "Voice input for recipient" }
                    ) {
                        Icon(
                            Icons.Default.Mic,
                            contentDescription = null,
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
                onValueChange = { viewModel.updateSubject(it) },
                label = { Text("Subject") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                trailingIcon = {
                    IconButton(
                        onClick = { startVoiceForField("subject") { result -> viewModel.updateSubject(result) } },
                        modifier = Modifier.semantics { contentDescription = "Voice input for subject" }
                    ) {
                        Icon(
                            Icons.Default.Mic,
                            contentDescription = null,
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
                onValueChange = { viewModel.updateBody(it) },
                label = { Text("Message") },
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                trailingIcon = {
                    IconButton(
                        onClick = { startVoiceForField("body") { result -> viewModel.updateBody(result) } },
                        modifier = Modifier.semantics { contentDescription = "Voice input for message body" }
                    ) {
                        Icon(
                            Icons.Default.Mic,
                            contentDescription = null,
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
