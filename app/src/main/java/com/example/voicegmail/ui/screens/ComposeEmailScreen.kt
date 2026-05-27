package com.example.voicegmail.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AttachFile
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
import com.example.voicegmail.ui.viewmodel.ComposeEvent
import com.example.voicegmail.ui.viewmodel.ComposeViewModel
import com.example.voicegmail.ui.viewmodel.SendState

private const val MAX_ATTACHMENT_BYTES = 10 * 1024 * 1024 // 10 MB

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ComposeEmailScreen(
    onBack: () -> Unit,
    replyTo: String? = null,
    isForward: Boolean = false,
    viewModel: ComposeViewModel = hiltViewModel()
) {
    val to by viewModel.to.collectAsState()
    val subject by viewModel.subject.collectAsState()
    val body by viewModel.body.collectAsState()
    val attachments by viewModel.attachments.collectAsState()
    var activeField by remember { mutableStateOf<String?>(null) }

    val context = LocalContext.current
    val sendState by viewModel.sendState.collectAsState()
    val isListening by viewModel.isListening.collectAsState()

    // ── File picker ──────────────────────────────────────────────────────
    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        val cr = context.contentResolver
        val mimeType = cr.getType(uri) ?: "application/octet-stream"
        val filename = cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            } ?: uri.lastPathSegment?.substringAfterLast('/') ?: "attachment"
        val bytes = cr.openInputStream(uri)?.use { it.readBytes() }
        when {
            bytes == null -> viewModel.attachmentTooLarge()
            bytes.size > MAX_ATTACHMENT_BYTES -> viewModel.attachmentTooLarge()
            else -> viewModel.attachmentSelected(filename, mimeType, bytes)
        }
    }

    // Collect one-shot events from ViewModel (e.g. open file picker)
    LaunchedEffect(Unit) {
        viewModel.composeEvent.collect { event ->
            when (event) {
                is ComposeEvent.OpenFilePicker -> filePicker.launch(arrayOf("*/*"))
            }
        }
    }

    // Navigate back when voice flow triggers it (cancel or successful send)
    LaunchedEffect(Unit) {
        viewModel.navigateBack.collect { onBack() }
    }

    // Stop voice when leaving the screen
    DisposableEffect(Unit) {
        onDispose { viewModel.stopAll() }
    }

    // Pre-fill fields then start the guided flow.
    var flowStarted by remember { mutableStateOf(false) }
    val micPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) viewModel.startGuidedVoiceFlow()
    }

    LaunchedEffect(Unit) {
        if (!flowStarted) {
            flowStarted = true
            when {
                isForward -> viewModel.initForwardFromDraft()
                !replyTo.isNullOrBlank() -> viewModel.initReplyTo(replyTo)
            }
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
                    == PackageManager.PERMISSION_GRANTED) {
                viewModel.startGuidedVoiceFlow()
            } else {
                micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        }
    }

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

    val screenTitle = when {
        isForward -> "Forward"
        !replyTo.isNullOrBlank() -> "Reply"
        else -> "Compose"
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        screenTitle,
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
                    // Attach-file button for sighted caretakers
                    IconButton(
                        onClick = { filePicker.launch(arrayOf("*/*")) },
                        modifier = Modifier.semantics { contentDescription = "Attach a file" }
                    ) {
                        Icon(Icons.Default.AttachFile, contentDescription = null)
                    }
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

            // ── Attachment list (visible feedback for sighted caretakers) ──
            if (attachments.isNotEmpty()) {
                HorizontalDivider()
                Text(
                    text = "Attachments",
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.semantics {
                        contentDescription = "${attachments.size} file${if (attachments.size == 1) "" else "s"} attached"
                    }
                )
                attachments.forEachIndexed { i, att ->
                    Text(
                        text = "${i + 1}. ${att.filename}",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.semantics {
                            contentDescription = "Attachment ${i + 1}: ${att.filename}"
                        }
                    )
                }
            }

            if (sendState is SendState.Sending) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        }
    }
}
