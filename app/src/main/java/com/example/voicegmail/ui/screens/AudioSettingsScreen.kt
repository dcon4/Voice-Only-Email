package com.example.voicegmail.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.voicegmail.ui.viewmodel.InboxViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AudioSettingsScreen(viewModel: InboxViewModel) {
    val folderUris by viewModel.audioFolderUris.collectAsState()
    val trackCount by viewModel.audioTrackCount.collectAsState()
    val isScanning by viewModel.isAudioScanning.collectAsState()

    val folderPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        uri?.let { viewModel.addAudioFolder(it) }
    }

    ModalBottomSheet(
        onDismissRequest = { viewModel.closeAudioSettings() },
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 36.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "Audio Library Settings",
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            Text(
                text = if (isScanning) "Scanning..." else "$trackCount tracks indexed",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            val fileAnnouncements by viewModel.audioFileAnnouncements.collectAsState()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
                    .semantics(mergeDescendants = true) {
                        contentDescription = if (fileAnnouncements)
                            "File announcements on"
                        else
                            "File announcements off"
                    },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "File Announcements",
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Text(
                        text = "Announce track title between files",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = fileAnnouncements,
                    onCheckedChange = { viewModel.setAudioFileAnnouncements(it) }
                )
            }

            Button(
                onClick = { folderPicker.launch(null) },
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = "Add Audio Folder" },
                enabled = !isScanning
            ) {
                Text("Add Audio Folder")
            }

            if (folderUris.isNotEmpty()) {
                Text(
                    text = "Indexed Folders",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
                )

                folderUris.forEach { uriString ->
                    val displayPath = uriString.substringAfterLast('/')
                        .substringAfterLast(':')
                        .let { Uri.decode(it) }
                        .ifBlank { uriString.takeLast(40) }
                    FolderRow(
                        displayPath = displayPath,
                        onRemove = { viewModel.removeAudioFolder(Uri.parse(uriString)) }
                    )
                }
            }

            if (trackCount > 0) {
                OutlinedButton(
                    onClick = { viewModel.scanAudioFolders() },
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { contentDescription = "Re-index all audio files" },
                    enabled = !isScanning
                ) {
                    Text("Re-index All")
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            Button(
                onClick = { viewModel.closeAudioSettings() },
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = "Close audio settings" }
            ) {
                Text("Done")
            }
        }
    }
}

@Composable
private fun FolderRow(
    displayPath: String,
    onRemove: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = displayPath,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f)
        )
        IconButton(
            onClick = onRemove,
            modifier = Modifier.semantics { contentDescription = "Remove $displayPath" }
        ) {
            Icon(
                imageVector = Icons.Default.Delete,
                contentDescription = "Remove"
            )
        }
    }
}
