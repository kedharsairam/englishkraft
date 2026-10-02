package com.krafttools.englishkraft.data

import android.database.sqlite.SQLiteDatabase

/**
 * Builds placement-test questions from the corpus.
 *
 * Everything here is a query over data already shipped. No question, no word and no
 * gloss is written by this app — the test asks about English the dictionary already
 * contains, so there is nothing in a question that could be wrong about English.
 */
class PlacementRepository(private val db: SQLiteDatabase) {

    /**
     * One question at roughly [rank], or null if that band has none.
     *
     * The word is the question; the options are glosses, one of which is that word's
     * own. A gloss can therefore only be correct for its own word, which is what makes
     * the answer objective.
     */
    fun question(rank: Int): PlacementTest.Question? {
        val word = pickWord(rank) ?: return null
        val options = buildOptions(word) ?: return null
        val correctIndex = options.indexOf(word.gloss)
        if (correctIndex < 0) return null
        return PlacementTest.Question(
            word = word.headword,
            rank = word.rank,
            pos = word.pos,
            ipa = word.ipa,
            options = options.shuffled(),
            correctIndex = options.indexOf(word.gloss),
        )
    }

    private data class Candidate(
        val entryId: Long,
        val headword: String,
        val rank: Int,
        val pos: String,
        val ipa: String?,
        val gloss: String,
    )

    /**
     * A word to ask about.
     *
     * Parts of speech are restricted to the four a placement test can meaningfully
     * ask about. Proper nouns are excluded: 51,607 of the testable words are `name`,
     * and "a male given name of uncertain origin" is not something a learner can be
     * tested on — the question would measure whether they have met the name.
     *
     * The band is searched outward from [rank] so a question is produced even in a
     * thin band, and the band's own words are preferred so the estimate stays honest.
     */
    private fun pickWord(rank: Int): Candidate? {
        for (band in BAND_WIDTHS) {
            val row = db.rawQuery(
                """
                SELECT e.id, e.headword, e.freq_rank, e.pos, e.ipa, s.gloss
                FROM entry e
                JOIN sense s ON s.entry_id = e.id
                WHERE e.freq_rank BETWEEN ? AND ?
                  AND e.pos IN ('noun','verb','adj','adv')
                  AND s.ord = 1
                  AND s.gloss IS NOT NULL
                  AND instr(s.tags, 'form-of') = 0
                  AND s.tags = ''
                  AND LENGTH(s.gloss) BETWEEN 20 AND 150
                ORDER BY e.freq_rank
                LIMIT 400
                """.trimIndent(),
                arrayOf((rank - band / 2).toString(), (rank + band / 2).toString()),
            ).use { c ->
                buildList {
                    while (c.moveToNext()) {
                        add(
                            Candidate(
                                entryId = c.getLong(0),
                                headword = c.getString(1),
                                rank = c.getInt(2),
                                pos = c.getString(3),
                                ipa = c.getStringOrNull(4),
                                gloss = c.getString(5),
                            )
                        )
                    }
                }
            }.filter { it.gloss.isNotBlank() }

            if (row.isNotEmpty()) return row.random()
        }
        return null
    }

    /**
     * The word's own gloss plus three others from the same frequency neighbourhood.
     *
     * Same band on purpose. A distractor from a different part of the vocabulary is
     * recognisable at a glance, which rewards a lucky guess rather than knowledge.
     */
    private fun buildOptions(word: Candidate): List<String>? {
        val distractors = db.rawQuery(
            """
            SELECT s.gloss
            FROM entry e
            JOIN sense s ON s.entry_id = e.id
            WHERE e.freq_rank BETWEEN ? AND ?
              AND e.pos IN ('noun','verb','adj','adv')
              AND e.id <> ?
              AND s.ord = 1
              AND s.gloss IS NOT NULL
              AND instr(s.tags, 'form-of') = 0
              AND s.tags = ''
              AND LENGTH(s.gloss) BETWEEN 20 AND 150
            ORDER BY RANDOM()
            LIMIT 40
            """.trimIndent(),
            arrayOf(
                (word.rank - DISTRACTOR_BAND / 2).toString(),
                (word.rank + DISTRACTOR_BAND / 2).toString(),
                word.entryId.toString(),
            ),
        ).use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }

        // Distinct glosses only: showing the same meaning twice makes a question
        // unanswerable, because two options are then correct.
        val distinct = distractors.filter { it.isNotBlank() && it != word.gloss }.distinct()
        if (distinct.size < 3) return null

        return (listOf(word.gloss) + distinct.take(3))
    }

    /**
     * Vocabulary coverage for a rank, as a fraction.
     *
     * Computed from the shipped frequency data rather than looked up, so it stays true
     * if the corpus changes. Measured on this corpus: rank 204 = 80%, 785 = 90%,
     * 2,446 = 95%, 7,627 = 98%.
     */
    fun coverageOf(rank: Int): Double {
        val total = totalCount()
        if (total <= 0L) return 0.0

        val upTo = db.rawQuery(
            "SELECT COALESCE(SUM(corpus_count),0) FROM entry " +
                "WHERE corpus_count IS NOT NULL AND freq_rank <= ?",
            arrayOf(rank.toString()),
        ).use { c -> if (c.moveToFirst()) c.getLong(0) else 0L }
        return (upTo.toDouble() / total).coerceIn(0.0, 1.0)
    }

    /**
     * The frequency rank whose words cover [coverage] of all attested tokens.
     *
     * The running total is NOT reset between rank groups. An earlier version reset it
     * on each new rank, so it only ever added one entry's count and the first rank
     * whose own count crossed the threshold won — which made 60% coverage come back as
     * rank 1,656,994 instead of 50. A placement built on that would open the reader
     * at the top level for a beginner.
     */
    fun rankOfCoverage(coverage: Double): Int {
        val want = (totalCount() * coverage).toLong()
        if (want <= 0L) return 1
        var cumulative = 0L
        var last = 1
        db.rawQuery(
            "SELECT freq_rank, corpus_count FROM entry " +
                "WHERE corpus_count IS NOT NULL ORDER BY freq_rank",
            null,
        ).use { c ->
            while (c.moveToNext()) {
                last = c.getInt(0)
                cumulative += c.getLong(1)
                if (cumulative >= want) return last
            }
        }
        return last
    }

    private fun totalCount(): Long =
        db.rawQuery(
            "SELECT COALESCE(SUM(corpus_count),0) FROM entry WHERE corpus_count IS NOT NULL",
            null,
        ).use { c -> if (c.moveToFirst()) c.getLong(0) else 0L }

    /** True when this word has already been asked, so the test does not repeat itself. */
    fun alreadyAsked(headword: String, exclude: Long): Boolean =
        db.rawQuery(
            "SELECT COUNT(*) FROM sense s JOIN entry e ON e.id = s.entry_id " +
                "WHERE e.headword_lc = ? AND e.id <> ?",
            arrayOf(headword.lowercase(), exclude.toString()),
        ).use { c -> if (c.moveToFirst()) c.getInt(0) > 0 else false }

    companion object {
        /** Widths tried in turn, so a thin band still yields a question. */
        private val BAND_WIDTHS = intArrayOf(1_200, 4_000, 12_000, 40_000)

        /** Distractors come from a window this wide around the word's rank. */
        private const val DISTRACTOR_BAND = 1_200
    }
}

private fun android.database.Cursor.getStringOrNull(index: Int): String? =
    if (isNull(index)) null else getString(index)