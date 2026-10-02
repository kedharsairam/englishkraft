package com.krafttools.englishkraft.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * The learner's own state: which words they saved, and how they went on each one.
 *
 * A second database file, separate from the read-only corpus. That split is the whole
 * point — `dictionary.db` is replaced whenever the corpus is rebuilt, and if the
 * learner's words lived inside it every rebuild would cost them their wordbook.
 *
 * Created lazily. Someone who never saves a word never gets this file, which is the
 * right default for an app whose claim is that it stores as little as possible.
 */
class ProgressStore(
    context: Context,
    /** Overridden in tests so a run cannot touch a real wordbook. */
    private val name: String = NAME,
) : SQLiteOpenHelper(context.applicationContext, name, null, VERSION) {

    override fun onConfigure(db: SQLiteDatabase) {
        // No foreign keys to enforce, but the base configuration still has to happen or
        // the file carries a permissive default this app never intended.
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE card (
                id          TEXT PRIMARY KEY,   -- headword/pos, so `run` and `runs` differ
                headword    TEXT NOT NULL,
                pos         TEXT NOT NULL,
                -- The gloss and usage copied from the corpus when the word was saved.
                -- Stored rather than looked up, because a later corpus may word the
                -- sense differently and the learner studied *this* one.
                gloss       TEXT NOT NULL DEFAULT '',
                example     TEXT NOT NULL DEFAULT '',
                stability   REAL NOT NULL,
                difficulty  REAL NOT NULL,
                due         INTEGER NOT NULL,  -- epoch millis
                added       INTEGER NOT NULL,
                last_review INTEGER NOT NULL DEFAULT 0,
                reps        INTEGER NOT NULL DEFAULT 0,
                lapses      INTEGER NOT NULL DEFAULT 0,
                -- Whether this word has ever been recalled. NOT the same as reps > 0:
                -- a failed first attempt is an answer but not a graduation, and using
                -- reps here made a word that had never been learned start being treated
                -- as scheduled after its second wrong answer. Caught by an instrumented
                -- test, which is the only place a schedule this subtle can be seen.
                graduated    INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX idx_card_due ON card (due)")

        // Every answer ever given, including the attempts on a new word that did not
        // graduate it. A scheduler is only auditable if the inputs it was given are
        // kept, and the interval recomputed from this log must match the card.
        db.execSQL(
            """
            CREATE TABLE review_log (
                id               INTEGER PRIMARY KEY,
                card             TEXT NOT NULL,
                reviewed_at      INTEGER NOT NULL,
                grade            INTEGER NOT NULL,
                days_since       REAL NOT NULL,
                stability_after  REAL NOT NULL,
                difficulty_after REAL NOT NULL,
                -- 0 means the answer did not schedule the card: it was a retry on a
                -- word still in its first session.
                interval_days    INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX idx_log_card ON review_log (card, reviewed_at)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Nothing has ever shipped from an earlier version, so there is no path to
        // write yet. This is where the next version adds its ALTER TABLE steps, and it
        // is deliberately loud rather than silent: quietly returning would leave a
        // half-upgraded file that fails later on a learner's own words, while dropping
        // a table here would delete work they cannot get back.
        throw IllegalStateException(
            "progress.db has no migration from $oldVersion to $newVersion; " +
                "write the migration rather than dropping the learner's words"
        )
    }

    /** True when the file on disk has the tables this version expects. */
    fun hasSchema(): Boolean =
        readableDatabase.rawQuery(
            "SELECT count(*) FROM sqlite_master WHERE type='table' AND name IN ('card','review_log')",
            null,
        ).use { c -> c.moveToFirst() && c.getInt(0) == 2 }

    companion object {
        const val NAME = "progress.db"
        const val VERSION = 1
    }
}

/** Reads and writes the wordbook. Thin on purpose: the scheduling rules live in [FSRS]. */
class Wordbook(
    private val store: ProgressStore,
    private val now: () -> Long = { System.currentTimeMillis() },
) {

    /**
     * One saved word.
     *
     * [stability] and [difficulty] are FSRS's two numbers, carried separately for the
     * reason the scheduler keeps them separately: a word that is easy for this learner
     * and hard for everyone else has high stability and high difficulty, and a
     * one-number model cannot represent that at all.
     */
    data class Card(
        val key: String,
        val headword: String,
        val pos: String,
        val gloss: String,
        val example: String,
        /** Days. Rises with successful recall, falls on a lapse, never reaches zero. */
        var stability: Double,
        /** 1 (easy) to 10 (hard), for this learner specifically. */
        var difficulty: Double,
        /** Epoch millis. */
        var due: Long,
        val added: Long,
        var lastReview: Long,
        var reps: Int,
        var lapses: Int,
        /** True once the word has been recalled at least once. */
        var graduated: Boolean,
    ) {
        /**
         * True while the word has never been recalled, so it has no schedule yet.
         *
         * Those are the session's new words. Not the same as "no answers given": a word
         * answered three times and never recalled is still new, and still unscheduled.
         */
        val isNew: Boolean get() = !graduated
    }

    /** Words due now, hardest first so a long session starts with the work that matters. */
    fun due(): List<Card> =
        read("SELECT * FROM card WHERE due <= ? AND graduated = 1 ORDER BY difficulty DESC, due ASC", arrayOf(now().toString()))

    /** Saved words never yet recalled. Oldest first, so the word just saved is next. */
    fun fresh(): List<Card> =
        read("SELECT * FROM card WHERE graduated = 0 ORDER BY added ASC")

    fun count(): Int =
        store.readableDatabase.rawQuery("SELECT count(*) FROM card", null)
            .use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }

    fun dueCount(): Int =
        store.readableDatabase.rawQuery("SELECT count(*) FROM card WHERE due <= ? AND graduated = 1", arrayOf(now().toString()))
            .use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }

    /** Saved words, alphabetical. */
    fun all(): List<Card> = read("SELECT * FROM card ORDER BY headword ASC")

    fun has(headword: String, pos: String): Boolean =
        store.readableDatabase.rawQuery(
            "SELECT 1 FROM card WHERE id = ?", arrayOf(key(headword, pos)),
        ).use { it.moveToFirst() }

    /** A word is identified by headword AND part of speech: `run` and `runs` differ. */
    fun key(headword: String, pos: String) = "${headword.lowercase()}/$pos"

    /**
     * Saves a word, or removes it if it was already saved. Returns the new membership.
     *
     * [gloss] and [example] are stored verbatim from the sense the learner was reading.
     */
    fun toggle(headword: String, pos: String, gloss: String, example: String): Boolean {
        val k = key(headword, pos)
        return if (has(headword, pos)) {
            store.writableDatabase.delete("card", "id = ?", arrayOf(k))
            store.writableDatabase.delete("review_log", "card = ?", arrayOf(k))
            false
        } else {
            store.writableDatabase.insertOrThrow(
                "card",
                null,
                android.content.ContentValues().apply {
                    put("id", k)
                    put("headword", headword)
                    put("pos", pos)
                    put("gloss", gloss)
                    put("example", example)
                    // The starting values assume a Good answer. They are replaced by the
                    // real ones on the first review, so getting them wrong here only
                    // changes which interval is *previewed* before that review.
                    put("stability", FSRS.initialStability(FSRS.GRADE_GOOD))
                    put("difficulty", FSRS.initialDifficulty(FSRS.GRADE_GOOD))
                    // Due immediately: a word just chosen to learn is one the learner
                    // wants to meet, not one to come back to in a week.
                    put("due", now())
                    put("added", now())
                    put("last_review", 0L)
                    put("reps", 0)
                    put("lapses", 0)
                    put("graduated", 0)
                },
            )
            true
        }
    }

    /** Drops a word and every answer given for it. */
    fun remove(headword: String, pos: String) {
        val k = key(headword, pos)
        val db = store.writableDatabase
        db.beginTransaction()
        try {
            db.delete("card", "id = ?", arrayOf(k))
            db.delete("review_log", "card = ?", arrayOf(k))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /**
     * Records an answer and returns the updated card.
     *
     * [tries] is how many times this card has now been tried, including this answer.
     * It only matters while the word has never been recalled; see [schedules], which
     * decides whether this answer changes the schedule at all.
     */
    fun review(card: Card, grade: Int, tries: Int, now: Long = now()): Card {
        val gap = if (card.lastReview == 0L) 0.0 else (now - card.lastReview) / DAY_MS
        val (stability, difficulty, intervalDays) =
            FSRS.next(card.stability, card.difficulty, gap, grade, seenBefore = !card.isNew)

        val graduate = schedules(card, grade, tries)

        val updated = card.copy(
            stability = if (graduate) stability else card.stability,
            difficulty = if (graduate) difficulty else card.difficulty,
            due = if (graduate) now + intervalDays * DAY_MS.toLong() else now(),
            lastReview = now,
            reps = card.reps + 1,
            lapses = card.lapses + if (grade == FSRS.GRADE_AGAIN) 1 else 0,
            // Graduated on the first recall and never un-graduated: a lapse later is a
            // lapse on a known word, not a return to being new.
            graduated = card.graduated || graduate,
        )

        val db = store.writableDatabase
        db.beginTransaction()
        try {
            db.update(
                "card",
                android.content.ContentValues().apply {
                    put("stability", updated.stability)
                    put("difficulty", updated.difficulty)
                    put("due", updated.due)
                    put("last_review", updated.lastReview)
                    put("reps", updated.reps)
                    put("lapses", updated.lapses)
                    put("graduated", if (updated.graduated) 1 else 0)
                },
                "id = ?",
                arrayOf(card.key),
            )
            db.insertOrThrow(
                "review_log",
                null,
                android.content.ContentValues().apply {
                    put("card", card.key)
                    put("reviewed_at", now)
                    put("grade", grade)
                    put("days_since", gap)
                    put("stability_after", updated.stability)
                    put("difficulty_after", updated.difficulty)
                    put("interval_days", if (graduate) intervalDays else 0)
                },
            )
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return updated
    }

    /** How many times each card has been answered. Used by the stats row. */
    fun reviewCount(): Int =
        store.readableDatabase.rawQuery("SELECT count(*) FROM review_log", null)
            .use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }

    /** Forgets every card and every answer. Offered in a dialog, and never automatic. */
    fun clear() {
        val db = store.writableDatabase
        db.beginTransaction()
        try {
            db.delete("review_log", null, null)
            db.delete("card", null, null)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun read(sql: String, args: Array<String>? = null): List<Card> =
        store.readableDatabase.rawQuery(sql, args).use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(
                        Card(
                            key = c.getString(c.getColumnIndexOrThrow("id")),
                            headword = c.getString(c.getColumnIndexOrThrow("headword")),
                            pos = c.getString(c.getColumnIndexOrThrow("pos")),
                            gloss = c.getString(c.getColumnIndexOrThrow("gloss")),
                            example = c.getString(c.getColumnIndexOrThrow("example")),
                            stability = c.getDouble(c.getColumnIndexOrThrow("stability")),
                            difficulty = c.getDouble(c.getColumnIndexOrThrow("difficulty")),
                            due = c.getLong(c.getColumnIndexOrThrow("due")),
                            added = c.getLong(c.getColumnIndexOrThrow("added")),
                            lastReview = c.getLong(c.getColumnIndexOrThrow("last_review")),
                            reps = c.getInt(c.getColumnIndexOrThrow("reps")),
                            lapses = c.getInt(c.getColumnIndexOrThrow("lapses")),
                            graduated = c.getInt(c.getColumnIndexOrThrow("graduated")) == 1,
                        ),
                    )
                }
            }
        }

    companion object {
        const val DAY_MS = 86_400_000.0

        /** A word is shown at most this many times in one sitting before it moves on. */
        const val MAX_ATTEMPTS = 3

        /**
         * Whether an answer puts the card into the schedule.
         *
         * This is the one place the rule lives, and both the interval the review screen
         * promises on a button and the interval the card is actually given are read
         * from it. Two implementations would drift, and a button promising "3 days"
         * that sets something else is the kind of wrong that a learner cannot detect
         * and would not believe anyway.
         *
         * An already-scheduled word is always rescheduled. Hard on one of those means a
         * shorter interval, which is what FSRS says Hard means — the learner's own
         * history is what makes it short, not a lack of recall in this sitting.
         *
         * A word being seen for the first time has to be recalled before it gets a
         * schedule, so it graduates on Good or Easy, and on Hard only at its last
         * permitted attempt: a learner who has said Hard three times running has told
         * the app something true about the word, and asking a fourth time only repeats
         * the question.
         */
        fun schedules(card: Card, grade: Int, tries: Int): Boolean {
            if (!card.isNew) return true
            return grade >= FSRS.GRADE_GOOD ||
                (grade == FSRS.GRADE_HARD && tries >= MAX_ATTEMPTS)
        }
    }
}