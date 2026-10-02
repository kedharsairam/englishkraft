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

        // A comparative or superlative headword is a pointer like any other form-of
        // entry. "better" has senses of its own, but they are all "comparative of
        // good", so the exact-headword path returned "better" while `good` sat one
        // row away. Irregular comparison is the common case here, not the exception.
        val exact = firstEntry(key)
        if (exact != null && !isOnlyAPointerToAnotherWord(exact.id)) {
            return build(exact, typedForm = null, inflected = false, beginnerSafe = beginnerSafe)
        }

        // The typed word is either absent or is a pointer to another word — a
        // comparative like "better", whose senses are all "comparative of good".
        // Prefer the entry it points at, which has the real definitions.
        val viaForm = bestFormEntry(key, beginnerSafe)
        if (viaForm != null) {
            return build(viaForm, typedForm = key, inflected = true, beginnerSafe = beginnerSafe)
        }

        // Nothing better exists. Showing "the gerund of run" is still a correct and
        // useful answer, so the pointer entry is kept rather than returning nothing.
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
    /**
     * Whether a word is plausibly English rather than scanned-page damage.
     *
     * The corpus quotes from digitised books, and some of that text carries OCR
     * errors: "abaet", "abeaut", "abidin", "d.j", "cnvs". Shown to a learner as a word
     * to learn, that is worse than showing nothing — it teaches a spelling that has
     * never existed. 48 such tokens appear in the reader's candidate pool of 4,000.
     *
     * The test is that a word contains a vowel. English has no vowel-less words of
     * two letters or more, so this cannot reject a real word.
     *
     * A consonant-run test was tried and rejected: it flags "abstractive",
     * "abstruse", "archbishop" and "archly", which are perfectly ordinary. It would
     * have discarded good vocabulary to catch a much smaller problem.
     */
    internal fun looksLikeEnglish(word: String): Boolean {
        // Honorifics and titles. "Mr", "Dr", "Mrs" are ordinary English words a
        // learner needs, and they carry no vowel-free stem to test — an earlier vowel
        // rule rejected them, which showed the rule was filtering the language rather
        // than the damage. Measured: 52 sentences in the pool were lost to this
        // before titles were allowed.
        if (word.trimEnd('.') in TITLES) return true

        // An abbreviation like "d.j" or "u.s" is a period-separated letter pair. This
        // must be rejected BEFORE any trimming, because trimming the period turns
        // "d.j" into "dj" — which contains a vowel and would sail through.
        if (ABBREVIATION.matches(word)) return false

        // A trailing apostrophe is a dropped letter, not a vowel: "plannin'", "y'know"
        // and "makin'" are ordinary transcribed speech and valid to read.
        val stem = word.trimEnd('\'')
        if (stem.length < 2) return false

        // A VOWEL must appear somewhere in the word -- not merely a letter. Written as
        // `any { it in 'a'..'z' }` this accepts every word, because every character of
        // a vowel-less token like "cnvs" is still a letter. English has no words of
        // two letters or more without a vowel, so requiring one rejects the scanning
        // damage without touching real vocabulary.
        for (ch in stem) {
            if (ch in 'a'..'z' || ch in 'A'..'Z') {
                if (ch.lowercaseChar() in VOWELS) return true
            }
        }

        // The only vowel left is the apostrophe itself, which is the elision case.
        return word.endsWith('\'')
    }

    private val VOWELS = "aeiouy"

    /**
     * English titles. Case-sensitive on purpose: an upper-case "MR" in the middle of a
     * sentence is a scanning artefact, while a capitalised "Mr" is a person being
     * addressed.
     */
    private val TITLES = setOf(
        "Mr", "Mrs", "Ms", "Dr", "Prof", "St", "Rev", "Hon", "Gen", "Col",
        "Capt", "Lt", "Sgt", "Maj", "Cpl", "Pvt", "Fr", "Sr", "Jr", "Esq",
        "Messrs", "Mmes", "Mme", "Mlle", "Mt", "Ft", "Rabbi", "Bish", "Gov",
    )

    /** "d.j", "u.s", "a.m" — single letters separated by periods. */
    private val ABBREVIATION = Regex("^[A-Za-z]\\.[A-Za-z]$")

    private fun isUsableExample(text: String): Boolean {
        // An ellipsis marks an elided quotation rather than a sentence of usage.
        if (text.contains("[...]") || text.contains("[…]")) return false
        // Footnote and citation markers come from printed sources.
        if (CITATION_MARKER.containsMatchIn(text)) return false
        // Real demonstrations are short. A long passage is quoted prose.
        if (text.length > MAX_EXAMPLE_CHARS) return false
        if (text.count { it == '.' } > MAX_EXAMPLE_SENTENCES) return false
        // Non-Latin letters. 11,190 examples are quoted from early printed English and
        // carry the long-s (ſ), so "ſhallbe" reads as one unlookable word. In a
        // learning app that teaches a learner ſ is a letter of the alphabet, which
        // is false in modern English. The usage tags do not identify these -- the
        // senses are often tagged plain "transitive" -- so the character test is the
        // only reliable filter. The text stays in the database; it is not shown.
        if (text.any { it.code > 0x2FF }) return false
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

    /**
     * Corpus frequency rank for a word, or null when it was never attested.
     *
     * Null is the honest answer for the long tail: OpenSubtitles records 344,974 of
     * the dictionary's headwords, and a word it never saw has no rank. Callers must
     * treat null as "not known" rather than as "rare" — the two are different and
     * confusing them would mark half the dictionary as hard.
     */
    fun rankOf(word: String): Int? {
        val key = normalise(word)
        if (key.isEmpty()) return null
        // MIN(freq_rank) with the IS NOT NULL filter made SQLite choose idx_freq and
        // scan the frequency index instead of using idx_entry_lc: EXPLAIN reported
        // "SEARCH entry USING INDEX idx_freq (freq_rank>?)". Measured at 144 ms a call,
        // which put the reader's passage search at an estimated 8,654 seconds.
        // Filtering in the outer query keeps the B-tree on the headword.
        return db.rawQuery(
            "SELECT MIN(freq_rank) FROM (SELECT freq_rank FROM entry WHERE headword_lc = ?)",
            arrayOf(key),
        ).use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getInt(0) else null }
    }

    /**
     * Ranks for many words in one query.
     *
     * Used by the reader, which classifies every word of every candidate sentence.
     * Doing that one query per word is the difference between 0.6 seconds and
     * several minutes.
     */
    fun ranksOf(words: Collection<String>): Map<String, Int> {
        if (words.isEmpty()) return emptyMap()
        val keys = words.map { normalise(it) }.filter { it.isNotEmpty() }.distinct()
        if (keys.isEmpty()) return emptyMap()
        // SQLite caps a host parameter list, so chunk well below any limit.
        val out = HashMap<String, Int>(keys.size * 2)
        keys.chunked(400).forEach { chunk ->
            val marks = chunk.joinToString(",") { "?" }
            db.rawQuery(
                "SELECT headword_lc, MIN(freq_rank) FROM entry " +
                    "WHERE headword_lc IN ($marks) GROUP BY headword_lc",
                chunk.toTypedArray(),
            ).use { c ->
                while (c.moveToNext()) {
                    if (!c.isNull(1)) out[c.getString(0)] = c.getInt(1)
                }
            }
        }
        return out
    }

    /**
     * The rank whose cumulative share of attested tokens first reaches [target].
     *
     * Walks the index in rank order and stops at the threshold, so the cost is
     * proportional to the coverage asked for rather than to the corpus size.
     */
    fun rankCovering(target: Double): Int? {
        val total = db.rawQuery(
            "SELECT COALESCE(SUM(corpus_count), 0) FROM entry WHERE corpus_count IS NOT NULL",
            null,
        ).use { c -> if (c.moveToFirst()) c.getLong(0) else 0L }
        if (total <= 0L) return null
        val want = total * target

        return db.rawQuery(
            """
            SELECT freq_rank, SUM(corpus_count) OVER (ORDER BY freq_rank) AS running
            FROM entry WHERE corpus_count IS NOT NULL
            ORDER BY freq_rank
            """.trimIndent(),
            null,
        ).use { c ->
            var seen = Int.MIN_VALUE
            while (c.moveToNext()) {
                val rank = c.getInt(0)
                // One rank can appear on several rows (a word with several parts of
                // speech), so only consider it once its whole group has been counted.
                if (rank == seen) continue
                if (c.getLong(1) >= want) return@use rank
                seen = rank
            }
            null
        }
    }

    /**
     * A passage of ordinary English the reader can attempt at [knownRank].
     *
     * Sentences are drawn from the corpus's attested examples, so every word is real
     * usage and nothing is invented. Sentences where more than a third of the words
     * are unknown are rejected: those are walls of glosses, not passages, and the
     * point of the reader is a text that reads.
     *
     * ORDER BY example rather than RANDOM, so the same passage comes back on every
     * launch until the learner has dealt with it. A reader that reshuffles itself
     * cannot be resumed.
     */
    fun readingPassages(knownRank: Int, limit: Int = 6): List<String> {
        val out = ArrayList<String>()
        for (sentence in readingPool()) {
            if (out.size >= limit) break
            val tokens = splitForRead(sentence, knownRank)
            if (tokens.isEmpty()) continue
            if (tokens.count { !it.known } > tokens.size / 3) continue
            out.add(sentence)
        }
        return out
    }

    /**
     * Candidate sentences for the reader.
     *
     * Filters live here, in SQL, so the pool is small at the point it is built.
     * A filter applied in Kotlin after the query does not shrink the pool, it just
     * discards work already done — which is exactly what happened when the vowel rule
     * ran only inside splitForRead while the pool was returned raw.
     *
     * The per-word vowel test is deliberately NOT here. It has to allow dropped
     * letters, because "plannin'" and "y'know" are transcribed speech and valid to
     * read, while "d.j" and "cnvs" are scanning damage.
     */
    /**
     * The reader's sentence pool.
     *
     * Built by tools/build_sentences.py from Tatoeba: 20,000 short, plain,
     * human-written English sentences, 1.10 MB of text. The previous source was the
     * Wiktionary `example` column, which is whatever was needed to illustrate a word,
     * and it produced a stage direction, a cricket report, a line of arithmetic and a
     * social-media post -- each one passing every check, because every check asked
     * something true of it.
     *
     * Tatoeba is CC BY 2.0 FR / CC0 1.0: public domain or attribution, with no
     * share-alike, so it is a lighter obligation than the Wiktionary content the app
     * already ships.
     */
    fun readingPool(): List<String> =
        db.rawQuery(
            """
            SELECT text FROM reading
            WHERE instr(text, ' - ') = 0 AND instr(text, '...') = 0
            ORDER BY id
            LIMIT 60000
            """.trimIndent(),
            null,
        ).use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }

    /** A word as the reader classifies it. */
    data class ReadToken(val text: String, val bare: String, val known: Boolean)

    /**
     * Splits a sentence into words, marking those above [knownRank].
     *
     * Shares its boundary rule with the tap targets on purpose: a word the reader
     * marks unknown must be a word the app can look up, or the reader asks for
     * glosses the app cannot supply. Stray brackets are dropped for the same
     * reason — they are citation markers, not vocabulary.
     */
    /**
     * Splits one sentence, resolving ranks individually.
     *
     * Fine for a handful of sentences. For a pool, call the overload that takes a
     * prebuilt rank map — one query per word here is 144 ms and that adds up.
     */
    fun splitForRead(sentence: String, knownRank: Int): List<ReadToken> =
        splitForRead(sentence, knownRank, ranksOf(wordsIn(listOf(sentence))))

    /**
     * Splits a sentence, classifying words against a rank lookup supplied by the caller.
     *
     * The reader classifies thousands of words across its candidate pool. Resolving
     * each one with its own query cost 144 ms apiece and put a passage search at an
     * estimated two hours. Passing the ranks in makes the whole pool cost one query.
     */
    fun splitForRead(
        sentence: String,
        knownRank: Int,
        ranks: Map<String, Int>,
    ): List<ReadToken> {
        val out = ArrayList<ReadToken>()
        var i = 0
        val len = sentence.length
        while (i < len) {
            val ch = sentence[i]
            if (!ch.isLetter() && ch != '\'') {
                var j = i
                while (j < len && !sentence[j].isLetter() && sentence[j] != '\'') j++
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
            // Possessives are a separate word. "Ranger's" is a form of "Ranger",
            // and asking a learner to look up "Ranger's" returns nothing -- an
            // instrumented test caught exactly this. Strip the trailing "'s" and
            // keep the stem, which does resolve.
            val (stem, possessive) = splitPossessive(raw.trim('\'', '-', '.'))
            val bare = stem
            val isWord = bare.length >= 2 &&
                bare.any { it.isLowerCase() } &&
                bare.none { it.code > 0x2FF } &&
                looksLikeEnglish(bare)
            if (!isWord) continue
            // A compound is only treated as known when every part is. When a part is
            // unknown the compound is unknown too, and then it must still resolve:
            // the reader may only ask for words the app can open. Anything the
            // dictionary cannot resolve is dropped from the ask-list rather than
            // offered as a dead end.
            val parts = bare.split('-').filter { it.isNotEmpty() }
            val stemKnown = parts.size > 1 && parts.all { part ->
                val pr = ranks[part.lowercase()]
                pr != null && pr <= knownRank
            }
            val direct = ranks[bare.lowercase()]
            val known = if (direct != null) direct <= knownRank else stemKnown
            out.add(ReadToken(raw, bare, known))
        }
        return out
    }

    /**
     * Splits a trailing possessive off a word.
     *
     * Returns the stem and whether one was present, so the display text can keep the
     * apostrophe while the lookup uses the stem.
     */
    private fun splitPossessive(word: String): Pair<String, Boolean> {
        for (suffix in POSSESSIVE_SUFFIXES) {
            if (word.length > suffix.length && word.endsWith(suffix)) {
                val stem = word.dropLast(suffix.length).trimEnd('\'')
                if (stem.length >= 2) return stem to true
            }
        }
        return word to false
    }

    private val POSSESSIVE_SUFFIXES = listOf("'s", "s'")

    /** Words in a passage, so the caller can resolve them all at once. */
    fun wordsIn(sentences: List<String>): Set<String> {
        val out = HashSet<String>()
        for (sentence in sentences) {
            var i = 0
            val len = sentence.length
            while (i < len) {
                val ch = sentence[i]
                if (!ch.isLetter() && ch != '\'') {
                    i++
                    continue
                }
                val start = i
                while (i < len && (sentence[i].isLetter() || sentence[i] == '\'')) i++
                val bare = splitPossessive(
                    sentence.substring(start, i).trim('\'')
                ).first
                if (bare.length >= 2 &&
                    bare.any { it.isLowerCase() } &&
                    bare.none { it.code > 0x2FF }
                ) {
                    out.add(bare.lowercase())
                }
            }
        }
        return out
    }

    /** The unknown words in a sentence, for the reader's glossary line. */
    fun unknownWords(sentence: String, knownRank: Int): List<String> =
        splitForRead(sentence, knownRank).filter { !it.known }.map { it.bare }

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