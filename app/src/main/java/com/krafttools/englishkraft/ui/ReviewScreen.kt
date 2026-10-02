package com.krafttools.englishkraft.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.krafttools.englishkraft.data.FSRS
import com.krafttools.englishkraft.data.ReviewState
import com.krafttools.englishkraft.data.WordbookViewModel

/**
 * A review session.
 *
 * One word at a time: the word alone, the learner decides what it means, then Show.
 * Nothing else is on screen while they are deciding, because a control that hints at
 * the answer by existing is one the learner answers instead of the word.
 *
 * Each of the four answers carries the interval it will set — "1 day", "10 days",
 * "4 months" — read from the same call that then sets it. A learner who can see that
 * Easy means four months is choosing on the actual trade, rather than on a word like
 * "Easy" that means something different in every flashcard app.
 *
 * The usage sentence is the corpus's own, saved with the word and shown only if it
 * genuinely contains the word. Where there is none, that is said rather than filled in:
 * a sentence about a neighbouring sense teaches the wrong thing faster than no sentence.
 */
@Composable
fun ReviewScreen(
    state: ReviewState,
    onReveal: () -> Unit,
    onAnswer: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val card = state.current ?: return

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "${state.answered} of ${state.planned}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // Said out loud, because "why is this word back" is the question that
            // makes people stop reviewing, and the answer is not obvious from a card
            // that looks like every other one.
            Text(
                text = "${state.requeued} to come round again",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("review_requeued"),
            )
        }
        Spacer(Modifier.height(6.dp))
        LinearProgressIndicator(
            progress = { state.progress.coerceIn(0f, 1f) },
            modifier = Modifier
                .fillMaxWidth()
                .testTag("review_progress"),
        )

        Spacer(Modifier.height(44.dp))

        Text(
            text = card.headword,
            style = MaterialTheme.typography.displaySmall,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("review_word"),
        )
        Text(
            text = card.pos,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag("review_pos"),
        )

        Spacer(Modifier.height(32.dp))

        if (!state.revealed) {
            Text(
                text = "Say what it means, then check yourself.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(20.dp))
            Button(
                onClick = onReveal,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("review_show"),
            ) {
                Text("Show")
            }
            return@Column
        }

        Text(
            text = card.gloss,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("review_gloss"),
        )

        Spacer(Modifier.height(16.dp))
        if (card.example.isNotBlank()) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("review_example"),
            ) {
                Text(
                    text = card.example,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(14.dp),
                )
            }
        } else {
            Text(
                text = "The corpus records no usage for this word.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("review_no_example"),
            )
        }

        Spacer(Modifier.height(28.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            GradeButton(FSRS.GRADE_AGAIN, "Again", state, onAnswer, Modifier.weight(1f))
            GradeButton(FSRS.GRADE_HARD, "Hard", state, onAnswer, Modifier.weight(1f))
            GradeButton(FSRS.GRADE_GOOD, "Good", state, onAnswer, Modifier.weight(1f))
            GradeButton(FSRS.GRADE_EASY, "Easy", state, onAnswer, Modifier.weight(1f))
        }
    }
}

/**
 * One of the four answers, labelled with the interval it will set.
 *
 * The interval is the point. It is the difference between choosing between two lengths
 * of time and choosing between two adjectives, and only the first says anything about
 * the learner's own memory.
 */
@Composable
private fun GradeButton(
    grade: Int,
    label: String,
    state: ReviewState,
    onAnswer: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.testTag("grade_$grade")) {
        Surface(
            color = MaterialTheme.colorScheme.surface,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("grade_tap_$grade"),
            onClick = { onAnswer(grade) },
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp),
            )
        }
        Text(
            text = state.intervals[grade].orEmpty(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp)
                .testTag("interval_$grade"),
        )
    }
}

/**
 * The end of a session.
 *
 * "Learned" would not be true here: the app has scheduled these words to be asked
 * again, and that is all it has done.
 */
@Composable
fun ReviewDone(
    state: ReviewState,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text("Session done", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            text = "${state.answered} answers, ${state.newCards} of them new words.",
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.testTag("review_done_summary"),
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = "Each word is now scheduled from how you answered it. " +
                "Nothing here leaves your device.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(28.dp))
        Button(
            onClick = onDone,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("review_finish"),
        ) {
            Text("Back to the dictionary")
        }
    }
}

/**
 * Nothing saved yet.
 *
 * Says what to do, and — because this is reachable by tapping "Wordbook" from the
 * search screen — says how to get back out. The first version of this had no way out:
 * a learner who tapped Wordbook before saving anything landed here with no visible
 * route to the dictionary. System back worked, which is exactly why it survived being
 * looked at; only someone actually tapping through it would notice.
 */
@Composable
fun NothingSavedYet(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("No words saved yet", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Open any entry and tap the bookmark to save a word. " +
                "Saved words come back to be practised.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.testTag("review_nothing_saved"),
        )
        Spacer(Modifier.height(8.dp))
        Text(
            // Stated because it is the reason this is worth doing at all, and a
            // learner cannot see it from inside the app.
            text = "At most ${WordbookViewModel.NEW_PER_SESSION} new words a session.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))
        Button(
            onClick = onBack,
            modifier = Modifier.testTag("nothing_saved_back"),
        ) {
            Text("Back to the dictionary")
        }
    }
}