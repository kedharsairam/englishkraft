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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.krafttools.englishkraft.data.PlacementTest

/**
 * The placement test.
 *
 * One question at a time, four glosses, one correct. No timer and no progress pressure:
 * the test is trying to find a level, and hurrying someone produces a level that
 * describes how they answered under time pressure rather than what they know.
 *
 * The feedback after answering is immediate and specific — which gloss was right — so a
 * wrong answer still teaches. That is the only teaching this screen does.
 */
@Composable
fun PlacementScreen(
    question: PlacementTest.Question?,
    index: Int,
    total: Int,
    chosen: Int?,
    onAnswer: (Int) -> Unit,
    onFinish: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
    ) {
        // Progress -------------------------------------------------------
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Question ${index + 1} of $total",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "Find the meaning",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(6.dp))
        LinearProgressIndicator(
            progress = { (index + 1f) / total },
            modifier = Modifier
                .fillMaxWidth()
                .testTag("placement_progress"),
        )

        Spacer(Modifier.height(28.dp))

        if (question == null) {
            Text(
                text = "Finishing…",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@Column
        }

        // The word -------------------------------------------------------
        Text(
            text = question.word,
            style = MaterialTheme.typography.displaySmall,
            modifier = Modifier.fillMaxWidth(),
        )
        question.ipa?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontFamily = FontFamily.Monospace,
            )
        }

        Spacer(Modifier.height(24.dp))

        // Options --------------------------------------------------------
        question.options.forEachIndexed { i, option ->
            val answered = chosen != null
            val isChosen = chosen == i
            val isCorrect = i == question.correctIndex

            val container = when {
                !answered -> MaterialTheme.colorScheme.surface
                isCorrect -> MaterialTheme.colorScheme.primaryContainer
                isChosen -> MaterialTheme.colorScheme.errorContainer
                else -> MaterialTheme.colorScheme.surface
            }
            val content = when {
                !answered -> MaterialTheme.colorScheme.onSurface
                isCorrect -> MaterialTheme.colorScheme.onPrimaryContainer
                isChosen -> MaterialTheme.colorScheme.onErrorContainer
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            }

            Surface(
                color = container,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 5.dp)
                    .testTag("option_$i"),
                onClick = { if (!answered) onAnswer(i) },
                enabled = !answered,
            ) {
                Column(Modifier.padding(14.dp)) {
                    Text(
                        text = option,
                        style = MaterialTheme.typography.bodyLarge,
                        color = content,
                    )
                    if (answered && (isCorrect || isChosen)) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = if (isCorrect) "This one." else "Not this one.",
                            style = MaterialTheme.typography.labelSmall,
                            color = content,
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(28.dp))

        if (chosen != null) {
            OutlinedButton(
                onClick = onFinish,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("placement_continue"),
            ) {
                Text(if (index + 1 >= total) "See your level" else "Next question")
            }
        }
    }
}

/**
 * The result.
 *
 * States a coverage figure and a sentence, and nothing else. No grade, no band, no
 * "level 4" — a vocabulary test can produce a vocabulary figure honestly and cannot
 * produce a proficiency level, because nothing here has been validated against one.
 */
@Composable
fun PlacementResult(
    result: PlacementTest.Result,
    onRead: () -> Unit,
    onRetake: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 24.dp),
    ) {
        Text("Your level", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(4.dp))
        Text(
            text = "From vocabulary only. This is what you can read, not what you can say.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(24.dp))
        Text(
            text = "${(result.coverage * 100).toInt()}%",
            style = MaterialTheme.typography.displayMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.testTag("coverage_value"),
        )
        Text(
            text = "of everyday English",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(16.dp))
        Text(
            text = result.description,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.testTag("coverage_description"),
        )

        Spacer(Modifier.height(12.dp))
        Text(
            text = "${result.correct} of ${result.asked} correct.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(32.dp))
        Button(
            onClick = onRead,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("read_at_level"),
        ) {
            Text("Read at this level")
        }
        Spacer(Modifier.height(10.dp))
        OutlinedButton(
            onClick = onRetake,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("retake"),
        ) {
            Text("Take it again")
        }
    }
}