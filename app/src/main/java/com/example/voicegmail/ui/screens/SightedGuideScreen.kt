package com.example.voicegmail.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.voicegmail.ui.viewmodel.InboxViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SightedGuideScreen(viewModel: InboxViewModel) {
    ModalBottomSheet(
        onDismissRequest = { viewModel.closeSightedGuide() },
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
                text = "Sighted User's Guide",
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            // ── Overview ──────────────────────────────────────────────────
            Text(
                text = "What is VoiceGmail?",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                text = "VoiceGmail is a completely hands-free email client for a blind user. " +
                    "The user controls everything by voice — reading, composing, searching, " +
                    "and managing email. The screen is never used directly by the primary user. " +
                    "This guide is for a sighted helper who needs to set up or troubleshoot the app.",
                style = MaterialTheme.typography.bodyMedium
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            // ── Getting Started ───────────────────────────────────────────
            Text(
                text = "Getting Started",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                text = "When the app is first opened, the user sees a \"Sign in with Google\" button. " +
                    "Tap it to sign into a Gmail account. The app uses standard Google OAuth (the " +
                    "same kind of sign-in used by Gboard, Google Drive, etc.). After signing in, " +
                    "the user's inbox loads automatically.",
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                text = "The app requests two permissions on first use:\n" +
                    "- Record Audio (so the user's voice commands can be heard)\n" +
                    "- Post Notifications (so the app can run in the background)",
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                text = "To enable background wake (recommended):\n" +
                    "1. Open Settings (gear icon in the top bar)\n" +
                    "2. Turn on \"Run in background\"\n" +
                    "3. Grant the \"VoiceGmail\" accessibility service permission when prompted\n" +
                    "4. Grant battery optimization exemption (\"Don't optimize\") when prompted",
                style = MaterialTheme.typography.bodyMedium
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            // ── Voice Commands ────────────────────────────────────────────
            Text(
                text = "Voice Commands",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                text = "The user speaks commands after a short beep. " +
                    "Here are all the supported commands:",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(bottom = 4.dp)
            )

            val commands = listOf(
                "\"read\"" to "Read the current email aloud",
                "\"read unread\"" to "Read just the first unread email",
                "\"read all\"" to "Read all emails in the current view one after another",
                "\"read all unread\"" to "Read only unread emails one after another",
                "\"next\"" to "Go to the next email",
                "\"previous\"" to "Go to the previous email",
                "\"repeat\" or \"again\"" to "Re-read the current email",
                "\"refresh\"" to "Reload the inbox",
                "\"search [query]\"" to "Search emails by keyword or sender",
                "\"compose\"" to "Write a new email",
                "\"reply\"" to "Reply to the current email",
                "\"reply all\"" to "Reply to all recipients",
                "\"forward\"" to "Forward the current email",
                "\"delete\"" to "Delete the current email (asks for confirmation)",
                "\"mark as read\"" to "Mark current email as read",
                "\"mark as unread\"" to "Mark current email as unread",
                "\"pause\" or \"go to sleep\"" to "Pause reading — press power button then say \"continue\" to resume",
                "\"continue\"" to "Resume reading from where it was paused",
                "\"list attachments\"" to "List attachments on the current email",
                "\"read attachment 1\"" to "Read the first attachment aloud",
                "\"help\" or \"what can I say\"" to "Hear a list of available commands",
                "\"bible\"" to "Open Bible reader (listen to Bible chapters)",
                "\"browser\"" to "Open web browser (search and read articles by voice)",
                "\"read slower\" / \"read faster\"" to "Adjust the email reading speed",
                "\"voice settings\"" to "Open voice/TTS settings panel",
                "\"instructions\"" to "Hear instructions for the current screen",
                "\"battery\"" to "Hear the current battery level",
                "\"play [song/album]\"" to "Play audio from the audio library",
            )

            commands.forEach { (cmd, desc) ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 2.dp)
                ) {
                    Text(
                        text = cmd,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.width(200.dp)
                    )
                    Text(
                        text = "\u2014 $desc",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            // ── Settings Overview ─────────────────────────────────────────
            Text(
                text = "Settings Overview",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                text = "Tap the gear icon in the top bar to open the settings panel. From there you can:",
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                text = "- Choose the TTS (text-to-speech) engine and voice for email reading\n" +
                    "- Choose a separate TTS engine and voice for Bible reading\n" +
                    "- Toggle \"Run in background\" (required for power-button wake)\n" +
                    "- Toggle verbose logging (for troubleshooting)\n" +
                    "- Set a battery warning threshold\n" +
                    "- Change Gmail account (sign out and sign in with a different account)\n" +
                    "- Open Bible reader options\n" +
                    "- Open the audio library settings\n" +
                    "- Add apps to the voice launcher (so the user can say \"launch [app]\")",
                style = MaterialTheme.typography.bodyMedium
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            // ── Debug Logs ────────────────────────────────────────────────
            Text(
                text = "Debug Logs",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                text = "If something is not working correctly, the user reports the problem and may be " +
                    "asked to share a debug log. Tap the bug-report icon in the top bar (next to the " +
                    "gear icon), then choose \"Share\" or \"Email\" to send the log file to a helper.",
                style = MaterialTheme.typography.bodyMedium
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            // ── Troubleshooting ───────────────────────────────────────────
            Text(
                text = "Troubleshooting",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary
            )

            Text(
                text = "App says \"Please sign in\"",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 4.dp)
            )
            Text(
                text = "Tap the \"Sign in with Google\" button. The user's session may have expired. " +
                    "All settings are preserved when re-signing in.",
                style = MaterialTheme.typography.bodyMedium
            )

            Text(
                text = "App does not respond to voice",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 4.dp)
            )
            Text(
                text = "Check that \"Run in background\" is enabled in settings. Verify that the " +
                    "VoiceGmail accessibility service is turned on in System Settings > Accessibility. " +
                    "Check that battery optimization is disabled for VoiceGmail.",
                style = MaterialTheme.typography.bodyMedium
            )

            Text(
                text = "Sign-in fails with \"User cancelled flow\"",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 4.dp)
            )
            Text(
                text = "This often means the Google OAuth client is not configured for this device's " +
                    "SHA-1 fingerprint. Debug builds use the Android debug keystore. The app's CI " +
                    "pipeline prints the SHA-1 during builds. Contact a developer to register the " +
                    "correct SHA-1 in the Google Cloud Console.",
                style = MaterialTheme.typography.bodyMedium
            )

            Text(
                text = "App crashes immediately on launch",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 4.dp)
            )
            Text(
                text = "Clear the app's data in System Settings > Apps > VoiceGmail > Storage > " +
                    "Clear data, then re-open and sign in again. If the problem persists, " +
                    "share a debug log (bug-report icon) and contact a developer.",
                style = MaterialTheme.typography.bodyMedium
            )

            Text(
                text = "Bluetooth headset not working",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 4.dp)
            )
            Text(
                text = "Make sure the \"bt\" (Bluetooth) version of the app is installed. " +
                    "Look for \"VoiceGmail BT\" in the app list. The standard version uses " +
                    "the phone's built-in speaker and microphone.",
                style = MaterialTheme.typography.bodyMedium
            )

            Spacer(Modifier.height(16.dp))

            Button(
                onClick = { viewModel.closeSightedGuide() },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Close")
            }
        }
    }
}
