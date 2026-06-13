package com.example.voicegmail.ui.screens

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
fun BibleSettingsScreen(viewModel: InboxViewModel) {
    val translation by viewModel.bibleTranslation.collectAsState()
    val verseNumbers by viewModel.bibleVerseNumbers.collectAsState()
    val continuous by viewModel.bibleContinuousReading.collectAsState()

    ModalBottomSheet(
        onDismissRequest = { viewModel.closeBibleSettings() },
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
                text = "Bible Options",
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            // ── Translation ──────────────────────────────────────────────────
            Text(
                text = "Translation",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 4.dp, bottom = 4.dp)
            )

            TRANSLATIONS.forEach { (id, label) ->
                TranslationRow(
                    label = label,
                    selected = id == translation,
                    onSelect = { viewModel.setBibleTranslation(id) }
                )
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

            // ── Read verse numbers ────────────────────────────────────────────
            Text(
                text = "Reading",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 4.dp, bottom = 4.dp)
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp)
                    .semantics(mergeDescendants = true) {
                        contentDescription = if (verseNumbers)
                            "Read verse numbers is on. Each verse is announced by number."
                        else
                            "Read verse numbers is off. Only verse text is spoken."
                    },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Read verse numbers",
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Text(
                        text = if (verseNumbers)
                            "Announces 'Verse 1', 'Verse 2', etc."
                        else
                            "Only verse text is spoken",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = verseNumbers,
                    onCheckedChange = { viewModel.setBibleVerseNumbers(it) }
                )
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

            // ── Continuous reading ─────────────────────────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp)
                    .semantics(mergeDescendants = true) {
                        contentDescription = if (continuous)
                            "Continuous reading is on. Chapters play automatically."
                        else
                            "Continuous reading is off. The app asks after each chapter."
                    },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Continuous reading",
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Text(
                        text = if (continuous)
                            "Chapters play automatically book to book"
                        else
                            "Asks after each chapter what to do next",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = continuous,
                    onCheckedChange = { viewModel.setBibleContinuousReading(it) }
                )
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

            // ── Done ───────────────────────────────────────────────────────────
            Button(
                onClick  = { viewModel.closeBibleSettings() },
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = "Close Bible options" }
            ) {
                Text("Done")
            }
        }
    }
}

@Composable
private fun TranslationRow(
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
                contentDescription = "Translation $label${if (selected) ", currently selected" else ""}"
            },
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Spacer(Modifier.width(8.dp))
        Text(text = label, style = MaterialTheme.typography.bodyMedium)
    }
}

private val TRANSLATIONS: List<Pair<String, String>> = listOf(
    "web" to "World English Bible (default)",
    "kjv" to "King James Version",
    "asv" to "American Standard Version (1901)",
    "bbe" to "Bible in Basic English",
    "darby" to "Darby Bible",
    "dra" to "Douay-Rheims 1899",
    "ylt" to "Young's Literal Translation",
    "webbe" to "WEB British Edition",
    "oeb-us" to "Open English Bible (US)",
    "oeb-cw" to "Open English Bible (Commonwealth)",
    "cherokee" to "Cherokee New Testament",
    "cuv" to "Chinese Union Version",
    "bkr" to "Czech Bible kralicka",
    "clementine" to "Latin Vulgate",
    "almeida" to "Portuguese (Almeida)",
    "rccv" to "Romanian (RCCV)"
)
