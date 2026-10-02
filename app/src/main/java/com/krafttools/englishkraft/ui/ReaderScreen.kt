package com.krafttools.englishkraft.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.krafttools.englishkraft.data.DictionaryRepository

/**
 * The reading screen.
 *
 * The idea in one line: show a text where the only words marked are the ones this
 * reader has not met, and keep that number small enough to read past. The
 * threshold comes from second-language reading research — at 98% known words a text
 * is readable without a dictionary, and below that the reader stalls on every
 * fourth word.
 *
 * The levels are labelled with coverage percentages rather than names, because
 * "98% of everyday English" is a claim the learner can check and "level 3" is not.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ReaderScreen(
    level: Float,
    levels: List<Float>,
    /** Empty while the query runs, which is not the same as an empty result. */
    passages: List<String>,
    unknown: List<String>,
    knownRank: Int,
    loading: Boolean,
    onLevelChange: (Float) -> Unit,
    onNewPassage: () -> Unit,
    onWordClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 12.dp),
    ) {
        Text("Read", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(4.dp))
        Text(
            text = "Everyday English, with only the words you have not met marked.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(12.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            levels.forEach { option ->
                FilterChip(
                    selected = option == level,
                    onClick = { onLevelChange(option) },
                    label = {
                        Text("${(option * 100).toInt()}%", style = MaterialTheme.typography.labelLarge)
                    },
                    modifier = Modifier.testTag("level_${(option * 100).toInt()}"),
                )
            }
            FilterChip(
                selected = false,
                onClick = onNewPassage,
                label = { Text("Another", style = MaterialTheme.typography.labelLarge) },
                modifier = Modifier.testTag("another_passage"),
            )
        }

        Spacer(Modifier.height(16.dp))

        if (passages.isEmpty()) {
            if (loading) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("reader_loading"),
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    text = "Finding a passage at this level…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                // An empty result with nothing loading is a real failure and says so,
                // rather than sitting on a spinner forever.
                Text(
                    text = "No passage found at this level. Try a lower percentage.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.testTag("reader_empty"),
                )
            }
        } else {
            // The level is stated before the text, not after. The learner should know
            // what they are about to be asked for rather than discover it word by word.
            Text(
                text = "${(level * 100).toInt()}% of everyday English",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))

            Column(
                modifier = Modifier.testTag("passage"),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                passages.forEach { sentence ->
                    ReaderSentence(
                        sentence = sentence,
                        unknown = unknown.filter { sentence.contains(it, ignoreCase = true) },
                        onWordClick = onWordClick,
                    )
                }
            }

            Spacer(Modifier.height(22.dp))

            if (unknown.isEmpty()) {
                Text(
                    text = "No unfamiliar words in this passage.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(
                    text = "Words to meet",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "${unknown.size} in this passage. Tap any to open it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    unknown.forEach { word ->
                        Text(
                            text = word,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .clickable { onWordClick(word) }
                                .padding(horizontal = 8.dp, vertical = 5.dp)
                                .testTag("gloss_$word"),
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(40.dp))
    }
}