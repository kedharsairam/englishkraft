package com.krafttools.englishkraft.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.krafttools.englishkraft.data.Suggestion

/**
 * The search screen: one field, suggestions ranked by corpus frequency.
 *
 * Ordering is the whole design decision here. Alphabetical would put `abandon`
 * above `abbey` and `abnormal` above `abroad` — all rare, all adjacent. Ranking by
 * frequency puts the words people actually look up first, which is the one
 * difference between a usable suggestion list and an alphabetical index that has
 * to be scrolled.
 */
@Composable
fun SearchScreen(
    query: String,
    suggestions: List<Suggestion>,
    didYouMean: List<Suggestion>,
    onQueryChange: (String) -> Unit,
    onSubmit: (String) -> Unit,
    onSuggestionClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp),
    ) {
        Spacer(Modifier.height(20.dp))

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Box(Modifier.weight(1f)) {
                if (query.isEmpty()) {
                    Text(
                        text = "Look up a word",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                BasicTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    singleLine = true,
                    textStyle = LocalTextStyle.current.merge(
                        MaterialTheme.typography.bodyLarge
                    ),
                    cursorBrush = androidx.compose.ui.graphics.SolidColor(
                        MaterialTheme.colorScheme.primary
                    ),
                    keyboardOptions = KeyboardOptions(
                        imeAction = ImeAction.Search,
                        autoCorrectEnabled = false,
                    ),
                    keyboardActions = KeyboardActions(
                        onSearch = {
                            keyboard?.hide()
                            onSubmit(query)
                        }
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester)
                        .testTag("search_field")
                        .padding(vertical = 8.dp),
                )
            }
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQueryChange("") }, modifier = Modifier.testTag("clear")) {
                    Icon(Icons.Filled.Close, contentDescription = "Clear")
                }
            } else {
                Icon(
                    imageVector = Icons.Filled.Search,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 8.dp),
                )
            }
        }

        // Typo suggestions appear above the list so they cannot be missed by
        // scrolling, and only when an exact lookup found nothing.
        if (didYouMean.isNotEmpty() && suggestions.isEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Did you mean",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                didYouMean.take(3).forEach { suggestion ->
                    Text(
                        text = suggestion.headword,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .clickable { onSuggestionClick(suggestion.headword) }
                            .padding(vertical = 4.dp)
                            .testTag("did_you_mean_${suggestion.headword}"),
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .testTag("suggestion_list"),
        ) {
            items(suggestions, key = { it.headword + "/" + it.pos }) { suggestion ->
                SuggestionRow(suggestion) { onSuggestionClick(suggestion.headword) }
            }
        }
    }
}

@Composable
private fun SuggestionRow(suggestion: Suggestion, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = suggestion.headword,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = posHint(suggestion.pos),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun posHint(pos: String): String = when (pos) {
    "adj" -> "adjective"
    "adv" -> "adverb"
    "intj" -> "interjection"
    "prep" -> "preposition"
    else -> pos
}