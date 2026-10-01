package com.krafttools.englishkraft.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import com.krafttools.englishkraft.data.Lookup
import com.krafttools.englishkraft.data.Relation
import com.krafttools.englishkraft.data.Sense

/**
 * One dictionary entry.
 *
 * Layout follows the reference works: headword, pronunciation, then numbered
 * senses grouped by nothing clever — the sense number is the source's own, so a
 * citation points at the same sense the app shows.
 *
 * Every word in the running text is tappable. This is the interaction worth taking
 * from anywhere: tap a word inside a definition and it resolves in place, so the
 * user never has to go back to the search box to follow a thread.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EntryScreen(
    lookup: Lookup,
    beginnerSafe: Boolean,
    starred: Boolean,
    onWordClick: (String) -> Unit,
    onToggleStar: () -> Unit,
    onSpeak: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val entry = lookup.entry
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
    ) {
        // Headword -------------------------------------------------------
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = entry.headword,
                    style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier.testTag("entry_headword"),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = posLabel(entry.pos),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    entry.ipa?.let {
                        Text(
                            text = "  $it",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }
            }
            IconButton(
                onClick = { onSpeak(entry.headword) },
                modifier = Modifier.testTag("speak"),
            ) {
                Icon(Icons.Filled.VolumeUp, contentDescription = "Read aloud")
            }
            IconButton(onClick = onToggleStar, modifier = Modifier.testTag("star")) {
                Icon(
                    imageVector = if (starred) Icons.Filled.Bookmark else Icons.Filled.BookmarkBorder,
                    contentDescription = if (starred) "Remove from wordbook" else "Save to wordbook",
                    tint = if (starred) MaterialTheme.colorScheme.primary
                           else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // Inflection notice ----------------------------------------------
        // Without this, `running` resolving to `run` looks like the app ignored
        // what was typed.
        if (lookup.inflected && lookup.typedForm != null) {
            Spacer(Modifier.height(10.dp))
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                ),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = "You typed \"${lookup.typedForm}\". This is the word it comes from.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(12.dp),
                )
            }
        }

        // Senses ---------------------------------------------------------
        Spacer(Modifier.height(18.dp))
        lookup.senses.forEach { sense ->
            SenseBlock(sense = sense, onWordClick = onWordClick)
            Spacer(Modifier.height(16.dp))
        }

        // Other forms -----------------------------------------------------
        if (lookup.forms.isNotEmpty()) {
            SectionTitle("Other forms")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                lookup.forms.forEach { form ->
                    Text(
                        text = form,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .clickable { onWordClick(form) }
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
            }
            Spacer(Modifier.height(18.dp))
        }

        // Relations -------------------------------------------------------
        if (lookup.relations.isNotEmpty()) {
            RelationsBlock(lookup.relations, onWordClick)
        }

        Spacer(Modifier.height(32.dp))
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(bottom = 6.dp),
    )
}

@Composable
private fun SenseBlock(sense: Sense, onWordClick: (String) -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.Top) {
            Text(
                text = "${sense.ord}.",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(end = 8.dp),
            )
            TappableText(
                text = sense.gloss,
                style = MaterialTheme.typography.bodyLarge,
                onWordClick = onWordClick,
                modifier = Modifier.weight(1f).testTag("gloss_${sense.ord}"),
            )
        }
        // The first usage, then any further citations the source recorded for the
        // same sense. Showing one keeps the list readable; showing all of them is
        // where the evidence for how a word is actually used comes from.
        sense.example?.let { example ->
            TappableText(
                text = example,
                style = MaterialTheme.typography.bodyMedium,
                italic = true,
                onWordClick = onWordClick,
                modifier = Modifier.padding(start = 24.dp, top = 4.dp),
            )
        }
        sense.examples.forEach { example ->
            TappableText(
                text = example,
                style = MaterialTheme.typography.bodySmall,
                italic = true,
                onWordClick = onWordClick,
                modifier = Modifier.padding(start = 24.dp, top = 2.dp),
            )
        }
        if (sense.tags.isNotEmpty()) {
            Text(
                text = sense.tags.joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.padding(start = 24.dp, top = 4.dp),
            )
        }
    }
}

/**
 * Relations, corroborated ones first.
 *
 * A link both Wiktionary and WordNet record is marked, because at this corpus size
 * that is the only independent confirmation a relation gets and the user should
 * be able to see the difference between "one crowdsourced wiki says" and "both an
 * edited wiki and a curated lexicon say this".
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RelationsBlock(relations: List<Relation>, onWordClick: (String) -> Unit) {
    val groups = relations.groupBy { it.rel }
    groups.forEach { (rel, rows) ->
        SectionTitle(relLabel(rel))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            rows.take(40).forEach { relation ->
                val label = if (relation.corroborated) {
                    "${relation.target} ✓"
                } else {
                    relation.target
                }
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (relation.corroborated) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                    modifier = Modifier
                        .clickable { onWordClick(relation.target) }
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

private fun posLabel(pos: String): String = when (pos) {
    "noun" -> "noun"
    "verb" -> "verb"
    "adj" -> "adjective"
    "adv" -> "adverb"
    "name" -> "proper noun"
    "phrase" -> "phrase"
    "intj" -> "interjection"
    "prep" -> "preposition"
    "pron" -> "pronoun"
    "contraction" -> "contraction"
    "proverb" -> "proverb"
    "prefix" -> "prefix"
    "suffix" -> "suffix"
    "symbol" -> "symbol"
    else -> pos
}

private fun relLabel(rel: String): String = when (rel) {
    "hypernym" -> "Kinds of this"
    "hyponym" -> "Examples of this"
    "meronym" -> "Parts of this"
    "holonym" -> "Whole things"
    "similar" -> "Similar words"
    "synonym" -> "Synonyms"
    "antonym" -> "Antonyms"
    "attribute" -> "Related"
    "domain" -> "Fields"
    else -> rel.replaceFirstChar { it.uppercase() }
}