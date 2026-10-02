package com.krafttools.englishkraft.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.krafttools.englishkraft.data.Wordbook
import com.krafttools.englishkraft.data.WordbookViewModel

/**
 * The saved words, and the session in one tap.
 *
 * A list, not a dashboard. Every figure here is one the learner can act on — how many
 * words are due, what happens next — and none of them are there because they look good
 * on a screen. There is no streak: a streak measures how often someone opened the app,
 * and a learner who missed a day has lost nothing that matters.
 */
@Composable
fun WordbookScreen(
    cards: List<Wordbook.Card>,
    answerCount: Int,
    now: Long,
    onReview: () -> Unit,
    onOpenWord: (String, String) -> Unit,
    onRemove: (String, String) -> Unit,
    onForgetAll: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var confirmingForget by remember { mutableStateOf(false) }
    val dueCount = cards.count { !it.isNew && it.due <= now }
    val newCount = cards.count { it.isNew }
    val ready = dueCount + newCount

    Column(modifier = modifier.fillMaxSize()) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Wordbook", style = MaterialTheme.typography.headlineMedium)
                TextButton(onClick = onBack, modifier = Modifier.testTag("wordbook_back")) {
                    Text("Dictionary")
                }
            }
            Spacer(Modifier.height(2.dp))
            Text(
                text = "${cards.size} saved · $answerCount answers given",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("wordbook_counts"),
            )

            Spacer(Modifier.height(16.dp))
            if (ready > 0) {
                Button(
                    onClick = onReview,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("wordbook_review"),
                ) {
                    Text("Practise $ready ${if (ready == 1) "word" else "words"}")
                }
                if (newCount > 0) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        // Stated because the cap is invisible from the outside, and a
                        // learner whose 24th saved word does not appear has no way to
                        // tell that from a bug.
                        text = "$newCount new, $dueCount due. " +
                            "Up to ${WordbookViewModel.NEW_PER_SESSION} new words a session.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.testTag("wordbook_session_note"),
                    )
                }
            } else {
                Text(
                    text = "Nothing due. Come back tomorrow.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("wordbook_nothing_due"),
                )
            }
        }

        LazyColumn(Modifier.fillMaxSize()) {
            items(cards, key = { it.key }) { card ->
                WordbookRow(
                    card = card,
                    now = now,
                    onOpen = { onOpenWord(card.headword, card.pos) },
                    onRemove = { onRemove(card.headword, card.pos) },
                )
            }
            item {
                Spacer(Modifier.height(24.dp))
                if (cards.isNotEmpty()) {
                    TextButton(
                        onClick = { confirmingForget = true },
                        modifier = Modifier
                            .padding(horizontal = 12.dp)
                            .testTag("wordbook_forget_all"),
                    ) {
                        Text(
                            text = "Forget every saved word",
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }
    }

    if (confirmingForget) {
        AlertDialog(
            onDismissRequest = { confirmingForget = false },
            title = { Text("Forget every word?") },
            text = {
                Text(
                    "This deletes all ${cards.size} saved words and every answer " +
                        "given for them. There is no backup and no way to undo it.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = { confirmingForget = false; onForgetAll() },
                    modifier = Modifier.testTag("wordbook_forget_confirm"),
                ) {
                    Text("Delete them", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmingForget = false }) { Text("Keep them") }
            },
        )
    }
}

/** One saved word: what it is, and when it is next asked. */
@Composable
private fun WordbookRow(
    card: Wordbook.Card,
    now: Long,
    onOpen: () -> Unit,
    onRemove: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("word_${card.key}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            color = MaterialTheme.colorScheme.surface,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 20.dp, vertical = 4.dp),
            onClick = onOpen,
        ) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = card.headword,
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = card.pos,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (card.gloss.isNotBlank()) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = card.gloss,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                    )
                }
            }
        }
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(end = 12.dp),
        ) {
            Text(
                // The only scheduling state the learner needs: when will I see this again.
                text = when {
                    card.isNew -> "new"
                    card.due <= now -> "due"
                    else -> inDays(card.due - now)
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(
                onClick = onRemove,
                modifier = Modifier.testTag("remove_${card.key}"),
            ) {
                Text("Remove", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

/**
 * When a card comes back, in the words a person would use.
 *
 * Rounded to the nearest day, not truncated. Floor division turned a card scheduled for
 * two days into "1 day" as soon as a second had passed, so the list disagreed with the
 * button that had promised "2 days" -- and a schedule whose own two screens disagree is
 * one nobody trusts. Found by reading the stored due dates after a real session: both
 * cards were 1.9986 days out.
 *
 * Only up to a month: past that "in 3 months" stops being a fact the learner can check
 * and becomes a forecast.
 */
internal fun inDays(millis: Long): String {
    val day = 86_400_000L
    val days = ((millis + day / 2) / day).coerceAtLeast(1)
    return when {
        days == 1L -> "1 day"
        days < 14 -> "$days days"
        days < 60 -> "${days / 7} weeks"
        else -> "${days / 30} months"
    }
}