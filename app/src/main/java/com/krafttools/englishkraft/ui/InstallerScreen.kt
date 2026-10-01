package com.krafttools.englishkraft.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.krafttools.englishkraft.data.DictionaryInstaller

/**
 * First-launch install, shown while the corpus is copied out of the APK.
 *
 * The progress is real bytes moved, not an indeterminate spinner. A 427 MB copy
 * takes long enough that an indeterminate bar reads as a hang, and the user has no
 * way to tell a working copy from a stalled one.
 */
@Composable
fun InstallScreen(state: DictionaryInstaller.State) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(28.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "EnglishKraft",
            style = MaterialTheme.typography.headlineMedium,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Every word in English, on this phone, offline.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )

        Spacer(Modifier.height(32.dp))

        when (state) {
            is DictionaryInstaller.State.Copying -> {
                val fraction = if (state.total > 0) {
                    (state.copied.toFloat() / state.total).coerceIn(0f, 1f)
                } else 0f
                Column(Modifier.fillMaxWidth()) {
                    LinearProgressIndicator(
                        progress = { fraction },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("install_progress"),
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = formatMb(state.copied),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = formatMb(state.total),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Spacer(Modifier.height(14.dp))
                Text(
                    text = "Preparing the dictionary. This happens once.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }

            is DictionaryInstaller.State.NotStarted -> {
                CircularProgressIndicator()
                Spacer(Modifier.height(14.dp))
                Text(
                    text = "Opening…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            is DictionaryInstaller.State.Failed -> {
                Text(
                    text = "The dictionary could not be prepared.",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(8.dp))
                // The reason is shown verbatim. "Something went wrong" hides the
                // difference between a truncated copy and a disk that is full, and
                // those need different fixes.
                Text(
                    text = state.reason,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.testTag("install_error"),
                )
            }

            is DictionaryInstaller.State.Ready -> Unit // brief; the app swaps to search
        }
    }
}

private fun formatMb(bytes: Long): String =
    if (bytes >= 1_048_576) {
        String.format("%.0f MB", bytes / 1_048_576.0)
    } else {
        String.format("%.0f kB", bytes / 1024.0)
    }