package com.krafttools.englishkraft.data

import android.app.Application
import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Where the learner stands.
 *
 * Held in a plain JSON file rather than SQLite. It is a handful of numbers, it is
 * written a few times per test, and a database for it would be machinery for a
 * record with four fields.
 */
class Progress private constructor(private val app: Context) {

    private val prefs =
        app.getSharedPreferences("progress", Context.MODE_PRIVATE)

    /** The vocabulary rank from the placement test, or null if never taken. */
    var vocabularyRank: Int?
        get() = prefs.getInt(KEY_RANK, -1).takeIf { it > 0 }
        set(value) = prefs.edit().putInt(KEY_RANK, value ?: -1).apply()

    var coverage: Float
        get() = prefs.getFloat(KEY_COVERAGE, -1f).takeIf { it >= 0f } ?: -1f
        set(value) = prefs.edit().putFloat(KEY_COVERAGE, value).apply()

    /** When the test was last taken, epoch millis. */
    var takenAt: Long
        get() = prefs.getLong(KEY_TAKEN, 0L)
        set(value) = prefs.edit().putLong(KEY_TAKEN, value).apply()

    /** Words the learner has starred, as "headword/pos" so `run` and `runs` differ. */
    fun starred(): Set<String> =
        prefs.getStringSet(KEY_STARRED, emptySet()).orEmpty()

    fun setStarred(items: Set<String>) {
        prefs.edit().putStringSet(KEY_STARRED, items).apply()
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    companion object {
        private const val KEY_RANK = "vocabulary_rank"
        private const val KEY_COVERAGE = "vocabulary_coverage"
        private const val KEY_TAKEN = "vocabulary_taken_at"
        private const val KEY_STARRED = "starred"

        @Volatile
        private var instance: Progress? = null

        fun get(context: Context): Progress =
            instance ?: synchronized(this) {
                instance ?: Progress(context.applicationContext).also { instance = it }
            }
    }
}

/** What the placement screen is showing. */
sealed interface PlacementUiState {
    data object Idle : PlacementUiState
    data class Asking(
        val question: PlacementTest.Question,
        val index: Int,
        val total: Int,
        val chosen: Int?,
    ) : PlacementUiState
    data class Done(val result: PlacementTest.Result) : PlacementUiState
}

/**
 * Drives the placement test.
 *
 * The test is stateful across questions — each answer moves the difficulty — so it
 * cannot be a single function over a list. Held here rather than in composable state
 * so that rotating the device mid-test does not lose the running estimate.
 */
class PlacementViewModel(
    private val placement: PlacementRepository,
    private val progress: Progress,
) {
    private var rank = PlacementTest.START_RANK
    private var asked = 0
    private var correct = 0
    private var current: PlacementTest.Question? = null

    val total = PlacementTest.QUESTION_COUNT

    /** The next question, or null when none can be built. */
    fun next(): PlacementUiState {
        asked++
        val q = placement.question(rank)
        current = q
        return if (q == null) {
            finish()
        } else {
            PlacementUiState.Asking(q, asked - 1, total, chosen = null)
        }
    }

    /** Records an answer and returns the state, which now shows the feedback. */
    fun answer(picked: Int): PlacementUiState {
        val q = current ?: return finish()
        val state = PlacementUiState.Asking(q, asked - 1, total, chosen = picked)
        if (picked == q.correctIndex) {
            correct++
            // Step up, shrinking as it goes, so late questions refine rather than
            // overshoot. The bound is the same one the reference run uses.
            rank += maxOf(PlacementTest.STEP_MIN, rank / 12)
        } else {
            // Step down hard on a miss: the boundary is what we are looking for.
            rank = (rank / 2).coerceAtLeast(PlacementTest.RANK_FLOOR)
        }
        return state
    }

    /** Moves to the next question, or finishes the test. */
    fun advance(): PlacementUiState {
        if (asked >= total) return finish()
        return next()
    }

    private fun finish(): PlacementUiState {
        val result = PlacementTest.Result(
            rank = rank.coerceAtLeast(PlacementTest.RANK_FLOOR),
            coverage = placement.coverageOf(rank),
            correct = correct,
            asked = asked,
        )
        progress.vocabularyRank = result.rank
        progress.coverage = result.coverage.toFloat()
        progress.takenAt = System.currentTimeMillis()
        Log.i("EnglishKraft", "placement: rank=${result.rank} coverage=${result.coverage}")
        return PlacementUiState.Done(result)
    }

    /** Starts over. */
    fun reset() {
        rank = PlacementTest.START_RANK
        asked = 0
        correct = 0
        current = null
    }

    /** The level the reader should open at, from the test if taken, else 98%. */
    fun readerRank(fallback: Int): Int =
        if (progress.takenAt > 0L) progress.vocabularyRank ?: fallback else fallback
}