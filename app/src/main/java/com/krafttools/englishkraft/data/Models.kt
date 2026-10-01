package com.krafttools.englishkraft.data

/** A dictionary headword and one of its parts of speech. */
data class Entry(
    val id: Long,
    val headword: String,
    val pos: String,
    val ipa: String?,
)

/** One numbered sense of an entry. */
data class Sense(
    val ord: Int,
    val gloss: String,
    /** Comma-separated register flags: obsolete, archaic, dialectal, vulgar… */
    val tags: List<String>,
    /** Attested usage, verbatim from the source. Never written by this app. */
    val example: String?,
    /**
     * Further attested usages for this same sense.
     *
     * Set when the source recorded one sense several times, each with its own
     * citation. Those are usage evidence rather than separate definitions, so they
     * are shown together instead of repeating the gloss.
     */
    val examples: List<String> = emptyList(),
)

/** A lexical relation, from either source. */
data class Relation(
    val rel: String,
    val target: String,
    /** 'wik' crowd-edited, 'wn' professionally curated. */
    val src: String,
    /**
     * True when Wiktionary and WordNet both record this exact link. The app shows
     * these first and labels them, because it is the only corroboration available
     * for a relation at this corpus size.
     */
    val corroborated: Boolean,
)

/** A lexical relationship between two words, for the taxonomy walk. */
data class RelatedEntry(
    val headword: String,
    val pos: String,
    val rel: String,
    val src: String,
)

/** A search suggestion. */
data class Suggestion(
    val headword: String,
    val pos: String,
    /** Null when the word has no corpus frequency yet. */
    val freqRank: Int?,
)

/**
 * A lookup result.
 *
 * [typedForm] is what the user typed when it differs from the headword, so the UI
 * can say "showing run, you typed running". Without it, inflection resolution
 * looks like the app ignored the user.
 */
data class Lookup(
    val entry: Entry,
    val senses: List<Sense>,
    val forms: List<String>,
    val relations: List<Relation>,
    val related: List<RelatedEntry>,
    val typedForm: String? = null,
    val inflected: Boolean = false,
) {
    /** True when at least one sense is flagged unsuitable for a beginner. */
    val hasObsoleteContent: Boolean
        get() = senses.any { s -> s.tags.any { it in GATED_TAGS } }
}

/**
 * Register flags that a low-level learner should not be shown by default.
 *
 * These are a query-time filter, not a build-time deletion: an A1 learner sees
 * none of these senses while a C2 learner sees all of them, from the same rows.
 * 1,217,027 of the dictionary's senses carry at least one tag, so the filter has
 * something to work with.
 */
val GATED_TAGS: Set<String> = setOf(
    "obsolete", "archaic", "dated", "rare", "vulgar", "derogatory",
    "offensive", "dialectal", "historical", "nonstandard", "uncommon",
    "childish", "pejorative", "ethnic", "sexual",
)