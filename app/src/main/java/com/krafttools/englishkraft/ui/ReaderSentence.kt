package com.krafttools.englishkraft.ui

import androidx.compose.foundation.text.ClickableText
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString

/**
 * One reader sentence, with unfamiliar words marked and tappable.
 *
 * Marking is not by colour alone. An unfamiliar word also gets a semantics label,
 * so a screen reader announces "new word" and a learner who cannot separate the two
 * hues is not excluded. Colour is the fast channel; the label is the one that
 * always works.
 *
 * Every word stays tappable, not only the unfamiliar ones. A learner who meets a
 * word they happen to know in a new sense still needs to open it, and restricting
 * taps to marked words would make the passage a dead zone.
 */
@Composable
fun ReaderSentence(
    sentence: String,
    unknown: List<String>,
    onWordClick: (String) -> Unit,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyLarge,
) {
    val unknownSet = unknown.map { it.lowercase() }.toSet()
    val annotated: AnnotatedString = buildAnnotatedString {
        var i = 0
        val len = sentence.length

        while (i < len) {
            val ch = sentence[i]
            if (!ch.isLetter() && ch != '\'') {
                var j = i
                while (j < len && !sentence[j].isLetter() && sentence[j] != '\'') j++
                append(sentence.substring(i, j))
                i = j
                continue
            }

            val start = i
            while (i < len) {
                val c = sentence[i]
                if (c.isLetter() || c == '\'') {
                    i++
                    continue
                }
                if ((c == '-' || c == '.') && i + 1 < len && sentence[i + 1].isLetter()) {
                    i += 2
                    continue
                }
                break
            }

            val raw = sentence.substring(start, i)
            val bare = raw.trim('\'', '-', '.')
            val isWord = bare.length >= 2 &&
                bare.any { it.isLowerCase() } &&
                bare.none { it.code > 0x2FF }

            if (!isWord) {
                append(raw)
                continue
            }

            val isUnknown = bare.lowercase() in unknownSet
            val at = length
            if (isUnknown) {
                pushStyle(
                    SpanStyle(
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
                    )
                )
            }
            append(raw)
            pop()
            addStringAnnotation(WORD_TAG, bare, at, at + raw.length)
        }
    }

    val description = buildString {
        append(sentence)
        if (unknown.isNotEmpty()) {
            append(". New words: ")
            append(unknown.joinToString(", "))
        }
    }

    ClickableText(
        text = annotated,
        style = style,
        modifier = modifier.semantics { contentDescription = description },
        onClick = { offset ->
            annotated.getStringAnnotations(WORD_TAG, offset, offset)
                .firstOrNull()
                ?.let { onWordClick(it.item) }
        },
    )
}

private const val WORD_TAG = "word"