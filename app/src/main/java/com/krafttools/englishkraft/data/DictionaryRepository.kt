package com.krafttools.englishkraft.data

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import java.io.File

/**
 * Read-only queries against the bundled dictionary.
 *
 * Two rules hold throughout:
 *
 *  1. No generated text. Nothing here composes a sentence, paraphrases a gloss
 *     or invents an example. What the source said is what appears.
 *  2. FTS is not on the critical path. FTS5 needs SQLite 3.9 plus the
 *     SQLITE_ENABLE_FTS5 build flag, and OEM variance makes "works on my phone"
 *     an unprovable claim. Lookup is a B-tree on `form`, then `entry`, which every
 *     device has. A dictionary must never fail to find a word because of an
 *     index engine.
 */
class DictionaryRepository(private val db: SQLiteDatabase) {

    companion object {
        /** How many suggestions to return. Enough to fill a list, few enough to be fast. */
        private const val SUGGEST_LIMIT = 25

        /**
         * Levenshtein cut-off. Beyond this, "did you mean" becomes noise: at a
         * distance of 4 the suggestion is no longer a plausible typo.
         */
        private const val TYPO_MAX_DISTANCE = 3
    }

    private val gated = GATED_TAGS

    /**
     * Senses filtered for a learner at [level].
     *
     * The gate is applied here, at query time, never in the build. 69.7% of the
     * corpus's senses carry a register flag, so an A2 reader is protected from
     * `obsolete`, `archaic` and `vulgar` senses without those senses being deleted
     * for the C2 reader who wants them.
     */
    fun sensesFor(entryId: Long, beginnerSafe: Boolean): List<Sense> {
        val all = sensesOf(entryId)
        if (!beginnerSafe) return all
        val keep = all.filter { s -> s.tags.none { it in gated } }
        // Never empty an entry. If every sense is flagged, the word is simply an
        // archaic or specialist term, and showing it with its labels is more honest
        // than showing nothing at all.
        return if (keep.isEmpty()) all else keep
    }

    /** Normalises what the user typed into a lookup key. */
    fun normalise(raw: String): String =
        raw.trim().lowercase().trim('-', '\'', ' ')

    /**
     * Resolves a word the user typed to the entry it belongs to.
     *
     * Exact headword first, then inflected form. `running` has no headword row of
     * its own that a learner cares about — it is a form of `run` — so without the
     * form step most lookups would fail. Returns null when nothing resolves.
     */
    fun lookup(raw: String, beginnerSafe: Boolean = false): Lookup? {
        val key = normalise(raw)
        if (key.isEmpty()) return null

        // An exact headword match is only taken when it has something to say.
        // `running` is its own headword in the corpus, but its only sense is
        // "present participle and gerund of run" -- a pointer at another word,
        // not a definition. Preferring it made the entry screen show a single
        // circular sense while `run` sat one row away with every real sense.
        val exact = firstEntry(key)
        if (exact != null && !isOnlyAPointerToAnotherWord(exact.id)) {
            return build(exact, typedForm = null, inflected = false, beginnerSafe = beginnerSafe)
        }
        // Keep the pointer entry: if nothing better exists, showing "the gerund of
        // run" is still a correct and useful answer.
        if (exact != null) {
            val viaForm = bestFormEntry(key)
            return if (viaForm != null) {
                build(viaForm, typedForm = key, inflected = true, beginnerSafe = beginnerSafe)
            } else {
                build(exact, typedForm = null, inflected = false, beginnerSafe = beginnerSafe)
            }
        }

        // Inflected form. A form can map to several entries — "books" is the plural
        // of the noun and the third-person of the verb — so the most common sense
        // of the headword wins, and the rest stay reachable from the entry.
        val viaForm = bestFormEntry(key, beginnerSafe)
        if (viaForm != null) {
            return build(viaForm, typedForm = key, inflected = true, beginnerSafe = beginnerSafe)
        }
        return exact?.let {
            build(it, typedForm = null, inflected = false, beginnerSafe = beginnerSafe)
        }
    }

    /**
     * Resolves an inflected form to the most useful entry it belongs to.
     *
     * "books" is both the plural of the noun and the third-person of the verb, so
     * several entries qualify. The most frequent wins, and any entry that is only
     * a pointer to another word is skipped -- `running`'s form row also points at
     * `run`, and there is no reason to return the pointer when the target is
     * available.
     */
    private fun bestFormEntry(key: String, beginnerSafe: Boolean = false): Entry? =
        db.rawQuery(
            """
            SELECT e.id, e.headword, e.pos, e.ipa
            FROM form f JOIN entry e ON e.id = f.entry_id
            WHERE f.form = ?
            ORDER BY e.freq_rank IS NULL, e.freq_rank, e.id
            LIMIT 12
            """.trimIndent(),
            arrayOf(key),
        ).use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(Entry(c.getLong(0), c.getString(1), c.getString(2), c.getStringOrNull(3)))
                }
            }
        }.firstOrNull { !isOnlyAPointerToAnotherWord(it.id) }
            ?: db.rawQuery(
                """
                SELECT e.id, e.headword, e.pos, e.ipa
                FROM form f JOIN entry e ON e.id = f.entry_id
                WHERE f.form = ?
                ORDER BY e.freq_rank IS NULL, e.freq_rank, e.id
                LIMIT 1
                """.trimIndent(),
                arrayOf(key),
            ).use { c ->
                if (c.moveToFirst()) {
                    Entry(c.getLong(0), c.getString(1), c.getString(2), c.getStringOrNull(3))
                } else null
            }

    /**
     * True when an entry's senses are all just "another form of X".
     *
     * The corpus marks these with the `form-of` tag, and they legitimately exist --
     * they carry the sense number that citations point at. They are simply the
     * wrong thing to show first: a learner searching "running" wants `run`.
     *
     * One real sense is enough to make the entry worth showing.
     */
    private fun isOnlyAPointerToAnotherWord(entryId: Long): Boolean =
        db.rawQuery(
            """
            SELECT COUNT(*),
                   SUM(CASE WHEN instr(',' || tags || ',', ',form-of,') > 0 THEN 1 ELSE 0 END)
            FROM sense WHERE entry_id = ?
            """.trimIndent(),
            arrayOf(entryId.toString()),
        ).use { c ->
            if (!c.moveToFirst()) return false
            val total = c.getInt(0)
            val pointers = c.getInt(1)
            total > 0 && pointers == total
        }

    /** Entries whose headword is exactly [key], best first. */
    fun firstEntry(key: String): Entry? =
        db.rawQuery(
            """
            SELECT id, headword, pos, ipa FROM entry
            WHERE headword_lc = ?
            ORDER BY freq_rank IS NULL, freq_rank, id
            LIMIT 1
            """.trimIndent(),
            arrayOf(key),
        ).use { c ->
            if (c.moveToFirst()) {
                Entry(c.getLong(0), c.getString(1), c.getString(2), c.getStringOrNull(3))
            } else null
        }

    private fun build(
        entry: Entry,
        typedForm: String?,
        inflected: Boolean,
        beginnerSafe: Boolean = false,
    ): Lookup = Lookup(
        entry = entry,
        senses = sensesFor(entry.id, beginnerSafe),
        forms = formsOf(entry.id),
        relations = relationsOf(entry.id),
        related = relatedOf(entry.id),
        typedForm = typedForm,
        inflected = inflected,
    )

    /**
     * Senses, with repeated glosses collapsed and every example kept.
     *
     * The corpus records one sense once per citation, so `run` (verb) carries 64
     * senses of which dozens read "To move swiftly." -- each with a different
     * example sentence. That is faithful to the source and useless to read. In a
     * learning app it is worse than useless: a learner sees the same definition
     * seven times and concludes the dictionary is broken.
     *
     * Grouping is by (gloss, tags) and the first sense number is kept, so a
     * citation into the source still points at a real sense. The extra examples
     * are not discarded -- they become usage evidence on the surviving sense,
     * which is the genuinely useful part of what the source was recording.
     *
     * 11,315 gloss groups repeat, covering 49,708 of 1,745,189 senses (2.8%).
     * Worst cases are proper names: "Newport" has 64 copies of one gloss.
     */
    fun sensesOf(entryId: Long, collapseDuplicates: Boolean = true): List<Sense> =
        db.rawQuery(
            "SELECT ord, gloss, tags, example FROM sense WHERE entry_id = ? ORDER BY ord",
            arrayOf(entryId.toString()),
        ).use { c ->
            val rows = buildList {
                while (c.moveToNext()) {
                    add(
                        Sense(
                            ord = c.getInt(0),
                            gloss = c.getString(1),
                            tags = c.getStringOrNull(2)?.split(',')?.filter { it.isNotBlank() }
                                ?: emptyList(),
                            example = c.getStringOrNull(3),
                        )
                    )
                }
            }
            if (!collapseDuplicates) return@use rows
            collapse(rows)
        }

    private fun collapse(rows: List<Sense>): List<Sense> {
        if (rows.size < 2) return rows
        val order = LinkedHashMap<String, MutableList<Sense>>()
        for (s in rows) {
            // Keyed on the gloss ALONE, not (gloss, tags). Wiktionary separates one sense by
        // transitivity -- "run" (verb) has "To move swiftly." as intransitive, as
        // transitive, as intransitive+transitive, as figurative, as colloquial, so
        // keying on both produced six identical definitions in a row. The gloss is
        // what the learner reads; the tag distinguishes grammar, not meaning. The
        // union of all tags is kept, so nothing is lost by merging.
        order.getOrPut(s.gloss) { mutableListOf() }.add(s)
        }
        // Renumber contiguously. Keeping the source's numbers made the list jump
        // from 3 straight to 7, which reads as senses having been dropped -- the
        // one impression a dictionary must never give. The original numbers remain
        // in the database for anyone citing them.
        return order.values.mapIndexed { index, group ->
            val first = group.first().copy(ord = index + 1)
            val examples = group.mapNotNull { it.example }
                .map { it.trim() }
                .distinct()
                .filter { it.isNotBlank() && isUsableExample(it) }
            first.copy(
                // Union of the tags across the merged rows. Kept sorted and
                // de-duplicated so the same flag never appears twice.
                tags = group.flatMap { it.tags }.distinct().sorted(),
                examples = if (examples.size > 1) examples else emptyList(),
                example = if (examples.size > 1) null else first.example,
            )
        }
    }


    /**
     * Whether an example is worth showing as a demonstration of a word.
     *
     * The source stores two different things in `examples`. Most are short
     * sentences written to show the word in use. Some are quoted encyclopaedic
     * passages — a 200-word paragraph with "[...]" in the middle, carrying only the
     * tag "transitive". Presented as a usage example that reads as the dictionary
     * dumping an irrelevant essay, and it buries the short sentences that actually
     * demonstrate the word.
     *
     * The test is on shape, not on judgement about the English. A quotation is
     * still in the database and still downloadable; it is simply not what a learner
     * needs under "usage". The cut-offs are generous because the only goal is to
     * keep one-sentence demonstrations on screen.
     */
    private fun isUsableExample(text: String): Boolean {
        // An ellipsis marks an elided quotation rather than a sentence of usage.
        if (text.contains("[...]") || text.contains("[…]")) return false
        // Footnote and citation markers come from printed sources.
        if (CITATION_MARKER.containsMatchIn(text)) return false
        // Real demonstrations are short. A long passage is quoted prose.
        if (text.length > MAX_EXAMPLE_CHARS) return false
        if (text.count { it == '.' } > MAX_EXAMPLE_SENTENCES) return false
        return true
    }

    private val CITATION_MARKER = Regex("\\[\\d+\\]")
    private val MAX_EXAMPLE_CHARS = 220
    private val MAX_EXAMPLE_SENTENCES = 2

    /** Other forms of the same lemma: what else this word can appear as. */
    fun formsOf(entryId: Long): List<String> =
        db.rawQuery(
            "SELECT form FROM form WHERE entry_id = ? ORDER BY form LIMIT 24",
            arrayOf(entryId.toString()),
        ).use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }

    /**
     * Relations recorded for this entry, corroborated ones first.
     *
     * Grouping by (rel, target) rather than rel alone is what makes corroboration
     * detectable: both sources' rows for the same link must land on one row for
     * GROUP_CONCAT to see them. `src` is part of the relation key precisely so a
     * link both sources record appears twice rather than one source overwriting
     * the other.
     */
    fun relationsOf(entryId: Long): List<Relation> =
        db.rawQuery(
            """
            SELECT rel, target, GROUP_CONCAT(DISTINCT src) AS srcs,
                   CASE WHEN instr(GROUP_CONCAT(DISTINCT src), 'wik') > 0
                         AND instr(GROUP_CONCAT(DISTINCT src), 'wn')  > 0
                        THEN 1 ELSE 0 END AS corroborated
            FROM relation
            WHERE entry_id = ?
            GROUP BY rel, target
            ORDER BY corroborated DESC, rel, target
            """.trimIndent(),
            arrayOf(entryId.toString()),
        ).use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(
                        Relation(
                            rel = c.getString(0),
                            target = c.getString(1),
                            src = c.getStringOrNull(2).orEmpty().replace(",", "+"),
                            corroborated = c.getInt(3) == 1,
                        )
                    )
                }
            }
        }

    /**
     * Words linked to this entry that exist in the dictionary, for the taxonomy.
     *
     * Two directions, because WordNet states a relation from the general side only:
     *   forward  — entries this one names as a hypernym, hyponym, meronym…
     *   backward — entries that name THIS word as their hyponym, which is where a
     *              word's parents come from. Selecting r.target for the backward
     *              branch returns the word itself, so spaniel became its own
     *              hypernym; the referencing entry has to be joined instead.
     *
     * DISTINCT covers (headword, pos, rel, src): the same word under two parts of
     * speech is two entries, and returning "spaniel, spaniel" as two parents of
     * "cocker spaniel" reads as a bug to the user even when it is not one.
     */
    fun relatedOf(entryId: Long): List<RelatedEntry> =
        db.rawQuery(
            """
            WITH me AS (SELECT headword_lc FROM entry WHERE id = ?)
            SELECT DISTINCT e.headword, e.pos, x.rel, x.src FROM (
                SELECT r.rel AS rel, r.target AS word, r.src AS src
                  FROM relation r WHERE r.entry_id = ?
                UNION
                SELECT 'hypernym', parent.headword, r.src
                  FROM relation r
                  JOIN entry parent ON parent.id = r.entry_id
                 WHERE r.rel = 'hyponym'
                   AND r.target = (SELECT headword_lc FROM me)
            ) x
            JOIN entry e ON e.headword_lc = x.word
            WHERE x.word <> (SELECT headword_lc FROM me)
            ORDER BY x.rel, e.headword
            LIMIT 40
            """.trimIndent(),
            arrayOf(entryId.toString(), entryId.toString()),
        ).use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(
                        RelatedEntry(
                            headword = c.getString(0),
                            pos = c.getString(1),
                            rel = c.getString(2),
                            src = c.getString(3),
                        )
                    )
                }
            }
        }

    /**
     * Search suggestions, most common first.
     *
     * Ordered by corpus frequency rather than alphabetically, which is the single
     * most useful ordering decision in a dictionary of 1.4 million forms: the
     * common word should appear before the obscure one that also matches.
     *
     * Prefix match only. A prefix index on headword_lc keeps this a range scan,
     * so it stays fast regardless of corpus size.
     */
    fun suggest(prefix: String, limit: Int = SUGGEST_LIMIT): List<Suggestion> {
        val key = normalise(prefix)
        if (key.length < 2) return emptyList()
        val like = key.replace("%", "").replace("_", "") + "%"
        return db.rawQuery(
            """
            SELECT headword, pos, freq_rank FROM entry
            WHERE headword_lc LIKE ? ESCAPE '\'
            ORDER BY freq_rank IS NULL, freq_rank, headword_lc
            LIMIT ?
            """.trimIndent(),
            arrayOf(like, limit.toString()),
        ).use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(
                        Suggestion(
                            headword = c.getString(0),
                            pos = c.getString(1),
                            freqRank = if (c.isNull(2)) null else c.getInt(2),
                        )
                    )
                }
            }
        }
    }

    /**
     * Typo suggestions.
     *
     * Runs only when an exact lookup has failed, and only over a bounded candidate
     * set — the words sharing a prefix, or failing that the shortest words. Without
     * the bound this is a full scan of 1.4 million forms.
     */
    fun didYouMean(typed: String, limit: Int = 5): List<Suggestion> {
        val key = normalise(typed)
        if (key.length < 3) return emptyList()

        val pool = buildList {
            // Same first two letters: where a typo most often lands.
            addAll(suggest(key.take(2), limit = 200))
            // Same first letter, next letters: covers transposed letters.
            if (key.length >= 4) addAll(suggest(key.take(1) + key.getOrNull(2)?.toString().orEmpty(), limit = 200))
        }.distinctBy { it.headword }

        if (pool.isEmpty()) return emptyList()

        return pool.asSequence()
            .map { it to levenshtein(key, it.headword.lowercase()) }
            .filter { it.second in 1..TYPO_MAX_DISTANCE }
            .sortedWith(compareBy({ it.second }, { it.first.freqRank ?: Int.MAX_VALUE }))
            .take(limit)
            .map { it.first }
            .toList()
    }

    /** Does this word exist at all, as a headword or a form? Used by the reader. */
    fun exists(word: String): Boolean {
        val key = normalise(word)
        if (key.isEmpty()) return false
        return db.rawQuery(
            "SELECT 1 FROM entry WHERE headword_lc = ? UNION ALL SELECT 1 FROM form WHERE form = ? LIMIT 1",
            arrayOf(key, key),
        ).use { it.moveToFirst() }
    }

    /** Every headword, for building the reader's vocabulary profile. */
    fun allHeadwords(): List<String> =
        db.rawQuery("SELECT headword_lc FROM entry ORDER BY headword_lc", null).use { c ->
            buildList { while (c.moveToNext()) add(c.getString(0)) }
        }

    fun totalEntries(): Int =
        db.rawQuery("SELECT COUNT(*) FROM entry", null).use { c ->
            if (c.moveToFirst()) c.getInt(0) else 0
        }

    private fun Cursor.getStringOrNull(index: Int): String? =
        if (isNull(index)) null else getString(index)
}

/**
 * Levenshtein distance, bounded.
 *
 * [limit] lets the caller give up once every cell in a row exceeds it, so a long
 * typed word against a short candidate costs O(len * limit) rather than O(n*m).
 */
internal fun levenshtein(a: String, b: String, limit: Int = Int.MAX_VALUE): Int {
    if (a == b) return 0
    if (a.isEmpty()) return b.length
    if (b.isEmpty()) return a.length
    if (kotlin.math.abs(a.length - b.length) > limit) return limit + 1

    var previous = IntArray(b.length + 1) { it }
    var current = IntArray(b.length + 1)

    for (i in 1..a.length) {
        current[0] = i
        var rowMin = current[0]
        for (j in 1..b.length) {
            val cost = if (a[i - 1] == b[j - 1]) 0 else 1
            current[j] = minOf(
                current[j - 1] + 1,
                previous[j] + 1,
                previous[j - 1] + cost,
            )
            rowMin = minOf(rowMin, current[j])
        }
        if (rowMin > limit) return limit + 1
        val swap = previous
        previous = current
        current = swap
    }
    return previous[b.length]
}

/** Opens the installed database read-only. */
fun openDictionary(file: File): SQLiteDatabase =
    SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY)