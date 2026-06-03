package com.example.voicegmail.ui.screens

import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
fun VoiceSettingsPanel(viewModel: InboxViewModel) {

    val engines          by viewModel.availableEngines.collectAsState()
    val voices           by viewModel.settingsVoices.collectAsState()
    val selectedEngine   by viewModel.selectedEngineName.collectAsState()
    val selectedVoice    by viewModel.selectedVoiceName.collectAsState()
    val switching        by viewModel.isSwitchingEngine.collectAsState()

    ModalBottomSheet(
        onDismissRequest = { viewModel.closeSettingsPanel() },
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 36.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = "Voice Settings",
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            // ---- Run Mode (at top for easy access) ---------------------------
            val runInBackground by viewModel.runInBackground.collectAsState()

            Text(
                text = "Run Mode",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 4.dp, bottom = 4.dp)
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp)
                    .semantics(mergeDescendants = true) {
                        contentDescription = if (runInBackground)
                            "Run in background is on. App wakes when power button is pressed."
                        else
                            "Run in background is off. App only works when in the foreground."
                    },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Run in background",
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Text(
                        text = if (runInBackground)
                            "Wakes on power button press (accessibility mode)"
                        else
                            "Only active when app is open (foreground only)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = runInBackground,
                    onCheckedChange = { viewModel.setRunInBackground(it) }
                )
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

            // ---- Verbose Logging ---------------------------------------------
            val verboseLogging by viewModel.verboseLogging.collectAsState()

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp)
                    .semantics(mergeDescendants = true) {
                        contentDescription = if (verboseLogging)
                            "Verbose logging is on. Detailed events are written to the log file."
                        else
                            "Verbose logging is off. Only important events are logged."
                    },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Verbose logging",
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Text(
                        text = if (verboseLogging)
                            "Logs TTS, mic, Bluetooth, and all events (for debugging)"
                        else
                            "Logs only commands and errors",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = verboseLogging,
                    onCheckedChange = { viewModel.setVerboseLogging(it) }
                )
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

            // ---- TTS Engine ------------------------------------------------
            Text(
                text = "Text-to-Speech Engine",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 4.dp, bottom = 4.dp)
            )

            if (engines.isEmpty()) {
                Text(
                    "No TTS engines found. Install Google Text-to-Speech from the Play Store.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                engines.forEach { engine ->
                    EngineRow(
                        engine    = engine,
                        selected  = engine.name == selectedEngine,
                        switching = switching,
                        onSelect  = { viewModel.selectEngineFromPanel(engine.name) }
                    )
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

            // ---- Voice -------------------------------------------------------
            Text(
                text = "Voice",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(bottom = 4.dp)
            )

            if (switching) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(80.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(modifier = Modifier.semantics {
                        contentDescription = "Loading voices for selected engine"
                    })
                }
            } else if (voices.isEmpty()) {
                Text(
                    "No voices available for this engine.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                // Engine default option (clears any saved voice preference)
                VoiceRow(
                    voice    = null,
                    label    = "Engine default",
                    selected = selectedVoice == null,
                    onSelect = {
                        viewModel.clearVoicePreferenceFromPanel()
                        viewModel.testVoice()
                    }
                )
                voices.forEach { voice ->
                    VoiceRow(
                        voice      = voice,
                        label      = viewModel.friendlyVoiceName(voice),
                        selected   = voice.name == selectedVoice,
                        onSelect   = { viewModel.selectVoiceFromPanel(voice.name) }
                    )
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

            // ---- Actions -----------------------------------------------------
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(
                    onClick   = { viewModel.testVoice() },
                    modifier  = Modifier
                        .weight(1f)
                        .semantics { contentDescription = "Test current voice" },
                    enabled   = !switching
                ) {
                    Text("Test Voice")
                }
                Button(
                    onClick  = { viewModel.closeSettingsPanel() },
                    modifier = Modifier
                        .weight(1f)
                        .semantics { contentDescription = "Close voice settings" }
                ) {
                    Text("Done")
                }
            }
        }
    }
}

@Composable
private fun EngineRow(
    engine: TextToSpeech.EngineInfo,
    selected: Boolean,
    switching: Boolean,
    onSelect: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !switching, onClick = onSelect)
            .padding(vertical = 4.dp)
            .semantics(mergeDescendants = true) {
                contentDescription = "TTS engine ${engine.label}${if (selected) ", currently selected" else ""}"
            },
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(
            selected = selected,
            onClick  = if (switching) null else onSelect
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text  = engine.label,
            style = MaterialTheme.typography.bodyLarge
        )
    }
}

@Composable
private fun VoiceRow(
    voice: Voice?,
    label: String,
    selected: Boolean,
    onSelect: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect)
            .padding(vertical = 4.dp)
            .semantics(mergeDescendants = true) {
                contentDescription = "Voice $label${if (selected) ", currently selected" else ""}"
            },
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Spacer(Modifier.width(8.dp))
        Column {
            Text(text = label, style = MaterialTheme.typography.bodyMedium)
            if (voice?.isNetworkConnectionRequired == true) {
                Text(
                    text  = "Requires internet connection",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
