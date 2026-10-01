package com.krafttools.englishkraft.ui

import androidx.compose.foundation.text.ClickableText
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle

/**
 * Text in which every word is a tap target.
 *
 * This is the one interaction worth taking from any dictionary app: tap a word
 * inside a definition and it resolves in place. It turns a lookup into exploring,
 * and it means the user never has to return to the search box to follow a thread.
 *
 * The colour is applied by the text style, not baked into the string. That keeps
 * the annotated string free of theme state, so `remember(text)` is a correct cache
 * key rather than one that silently goes stale when the theme changes.
 */
@Composable
fun TappableText(
    text: String,
    style: TextStyle,
    onWordClick: (String) -> Unit,
    modifier: Modifier = Modifier,
    italic: Boolean = false,
) {
    val annotated: AnnotatedString = remember(text) { buildWordSpans(text) }

    ClickableText(
        text = annotated,
        style = if (italic) style.copy(fontStyle = FontStyle.Italic) else style,
        modifier = modifier,
        onClick = { offset ->
            annotated.getStringAnnotations(TAG, offset, offset)
                .firstOrNull()
                ?.let { onWordClick(it.item) }
        },
    )
}

private const val TAG = "word"

/**
 * Marks every real word with a positional annotation.
 *
 * Only words are annotated, never the space after one and never a trailing full
 * stop: a span that swallowed the period would look up "run." and fail, which is
 * the sort of small wrongness that makes an app feel broken.
 *
 * Words under two characters are skipped, and so is a capitalised token with no
 * lowercase continuation — "I" and "A" should not navigate, and neither should
 * every acronym in an example sentence.
 *
 * This function is deliberately NOT @Composable. It must be testable on the JVM
 * without a Compose runtime, and it must not read theme state.
 */
internal fun buildWordSpans(text: String): AnnotatedString = buildAnnotatedString {
    var i = 0
    val len = text.length

    while (i < len) {
        val ch = text[i]
        if (!ch.isLetter() && ch != '\'') {
            append(ch)
            i++
            continue
        }

        val start = i
        while (i < len) {
            val c = text[i]
            if (c.isLetter() || c == '\'') {
                i++
                continue
            }
            // An internal hyphen or period is part of the word ("well-known",
            // "e.g."); a trailing one belongs to the sentence.
            if ((c == '-' || c == '.') && i + 1 < len && text[i + 1].isLetter()) {
                i += 2
                continue
            }
            break
        }

        val word = text.substring(start, i)
        val bare = word.trim('\'', '-', '.')
        val at = length
        append(word)
        if (bare.length >= 2 && bare.any { it.isLowerCase() }) {
            // Offsets are relative to the string built so far, so `at` is captured
            // before the append. The annotation spans the whole appended token while
            // carrying the trimmed word, so a trailing period never enters the lookup.
            addStringAnnotation(TAG, bare, at, at + word.length)
        }
    }
}

/** Exposed so the word-splitter can be tested without a Compose runtime. */
internal fun wordsIn(text: String): List<String> =
    buildWordSpans(text).getStringAnnotations(TAG, 0, text.length.coerceAtLeast(1))
        .map { it.item }