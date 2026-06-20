package com.example.voicegmail.ui.screens

import android.content.Intent
import android.content.pm.PackageManager
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.voicegmail.ui.viewmodel.InboxViewModel
import com.example.voicegmail.voice.LauncherApp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppLauncherPanel(viewModel: InboxViewModel) {
    val context = LocalContext.current
    val apps by viewModel.launcherApps.collectAsState()

    var showPicker by remember { mutableStateOf(false) }
    var editingApp by remember { mutableStateOf<LauncherApp?>(null) }
    var voiceCmdInput by remember { mutableStateOf("") }

    if (showPicker) {
        val allApps = remember {
            val pm = context.packageManager
            val intent = Intent(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_LAUNCHER) }
            pm.queryIntentActivities(intent, 0)
                .filter { it.activityInfo.packageName != context.packageName }
                .distinctBy { it.activityInfo.packageName }
                .sortedBy { it.loadLabel(pm).toString() }
        }

        var searchQuery by remember { mutableStateOf("") }

        AlertDialog(
            onDismissRequest = { showPicker = false },
            title = { Text("Select an App") },
            text = {
                Column {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        label = { Text("Search apps") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    val filtered = allApps.filter {
                        searchQuery.isBlank() ||
                            it.loadLabel(context.packageManager).toString()
                                .contains(searchQuery, ignoreCase = true) ||
                            it.activityInfo.packageName.contains(searchQuery, ignoreCase = true)
                    }
                    LazyColumn(modifier = Modifier.heightIn(max = 400.dp)) {
                        items(filtered) { resolveInfo ->
                            val label = resolveInfo.loadLabel(context.packageManager).toString()
                            val pkg = resolveInfo.activityInfo.packageName
                            val alreadyAdded = apps.any { it.packageName == pkg }
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable(enabled = !alreadyAdded) {
                                        editingApp = LauncherApp(
                                            packageName = pkg,
                                            displayName = label,
                                            voiceCommand = ""
                                        )
                                        voiceCmdInput = ""
                                        showPicker = false
                                    }
                                    .padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = label,
                                    modifier = Modifier.weight(1f)
                                )
                                if (alreadyAdded) {
                                    Text(
                                        text = "Already added",
                                        color = MaterialTheme.colorScheme.error,
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showPicker = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    editingApp?.let { app ->
        AlertDialog(
            onDismissRequest = { editingApp = null },
            title = { Text("Voice Command for ${app.displayName}") },
            text = {
                Column {
                    Text(
                        "What voice command should launch this app? " +
                            "For example, 'maps' to say 'launch maps'.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = voiceCmdInput,
                        onValueChange = { voiceCmdInput = it },
                        label = { Text("Voice command") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (voiceCmdInput.isNotBlank()) {
                            viewModel.saveLauncherApp(
                                app.copy(voiceCommand = voiceCmdInput.trim().lowercase())
                            )
                            editingApp = null
                        }
                    },
                    enabled = voiceCmdInput.isNotBlank()
                ) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { editingApp = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    Surface(
        modifier = Modifier
            .fillMaxSize()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {}
            ),
        color = MaterialTheme.colorScheme.surface
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            TopAppBar(
                title = {
                    Text(
                        "App Launcher",
                        modifier = Modifier.semantics { heading() }
                    )
                },
                navigationIcon = {
                    IconButton(
                        onClick = { viewModel.closeLauncherPanel() },
                        modifier = Modifier.semantics {
                            contentDescription = "Close app launcher panel"
                        }
                    ) {
                        Icon(Icons.Default.ArrowBack, contentDescription = null)
                    }
                },
                actions = {
                    IconButton(
                        onClick = { showPicker = true },
                        modifier = Modifier.semantics {
                            contentDescription = "Add app"
                        }
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null)
                    }
                }
            )

            if (apps.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "No apps configured yet. Tap the + button to add an app " +
                            "and assign a voice command to it.",
                        style = MaterialTheme.typography.bodyLarge
                    )
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(apps) { app ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = app.displayName,
                                style = MaterialTheme.typography.titleSmall,
                                modifier = Modifier
                                    .weight(1f)
                                    .semantics {
                                        contentDescription =
                                            "${app.displayName}. Voice command: ${app.voiceCommand}"
                                    }
                            )
                            Text(
                                text = "'${app.voiceCommand}'",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.semantics {
                                    contentDescription = "Voice command: ${app.voiceCommand}"
                                }
                            )
                            IconButton(
                                onClick = { viewModel.removeLauncherApp(app.packageName) },
                                modifier = Modifier.semantics {
                                    contentDescription = "Remove ${app.displayName}"
                                }
                            ) {
                                Icon(
                                    Icons.Default.Delete,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}
