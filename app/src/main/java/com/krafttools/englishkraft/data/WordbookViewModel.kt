package com.krafttools.englishkraft.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** What the review screen is showing. */
data class ReviewState(
    val current: Wordbook.Card?,
    /** True once the learner has tapped Show and the meaning is on screen. */
    val revealed: Boolean,
    /** Grade to human interval, for the four buttons. Computed, not invented. */
    val intervals: Map<Int, String>,
    val answered: Int,
    val planned: Int,
    val newCards: Int,
    val done: Boolean,
    /** Words put back in this session because they were not recalled yet. */
    val requeued: Int,
) {
    val progress: Float get() = if (planned == 0) 0f else answered.toFloat() / planned
}

/**
 * Drives a review session.
 *
 * A session is a queue built once and then walked. Due words come first, hardest
 * first, because that is the work that matters; new words follow, capped at
 * [NEW_PER_SESSION] so a first session with fifty saved words does not become a
 * fifty-word test.
 * **Where this is not FSRS.** A word is only scheduled once it has been recalled. Until
 * then the answer is written to the review log, the schedule is left alone, and the
 * word comes back later in the same sitting. That is a choice of this app, not part of
 * the algorithm, and it is stated here because it is the only place the two differ. The
 * usual alternative is a learning step of "again in ten minutes", which assumes the
 * learner still has the app open in ten minutes — and a learner who reviews twice a
 * week never does. Every word handled this way is logged with `interval_days = 0`, so
 * the log shows exactly which answers scheduled something and which did not.
 */
class WordbookViewModel(
    context: Context,
    private val repository: DictionaryRepository,
    /**
     * Overridden in tests so a run cannot reach a real wordbook. There is no reason
     * to expose it in the app, and a test that can only clear the user's own words is a
     * test nobody will run on a device they use.
     */
    storeName: String = ProgressStore.NAME,
    /**
     * Injectable so a test can make a word due without waiting days. Intervals are
     * measured in days, so a real clock makes "was this due" depend on when the test
     * ran, which is the kind of test that passes on Tuesday and fails on Sunday.
     */
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    private val store = ProgressStore(context, storeName)
    private val wordbook = Wordbook(store, clock)

    private val queue = ArrayDeque<Wordbook.Card>()
    private val attempts = HashMap<String, Int>()
    private var current: Wordbook.Card? = null
    private var revealed = false
    private var answered = 0
    private var planned = 0
    private var newCount = 0
    private var requeued = 0

    fun count(): Int = wordbook.count()

    fun dueCount(): Int = wordbook.dueCount()

    fun freshCount(): Int = wordbook.fresh().size

    fun isSaved(headword: String, pos: String): Boolean = wordbook.has(headword, pos)

    /** Saves or removes a word. Returns the new membership. */
    fun toggle(headword: String, pos: String, gloss: String, example: String): Boolean =
        wordbook.toggle(headword, pos, gloss, example)

    fun remove(headword: String, pos: String) = wordbook.remove(headword, pos)

    fun all(): List<Wordbook.Card> = wordbook.all()

    fun reviewCount(): Int = wordbook.reviewCount()

    fun clear() {
        wordbook.clear()
        queue.clear()
        attempts.clear()
        current = null
        revealed = false
        answered = 0
        planned = 0
        newCount = 0
        requeued = 0
    }

    /**
     * Builds the queue and returns the first state.
     *
     * Synchronous, like the placement engine's, because every read here is a small
     * indexed query. Callers move it off the main thread anyway, since building a long
     * session is not something to do while a finger is on the screen.
     */
    fun start(): ReviewState {
        queue.clear()
        attempts.clear()
        current = null
        revealed = false
        answered = 0
        requeued = 0

        val due = wordbook.due()
        val fresh = wordbook.fresh().take(NEW_PER_SESSION)
        newCount = fresh.size
        planned = due.size + fresh.size
        due.forEach { queue.addLast(it) }
        fresh.forEach { queue.addLast(it) }

        current = queue.removeFirstOrNull()
        return state(done = current == null)
    }

    /** Shows the meaning. Called when the learner taps Show. */
    fun reveal(): ReviewState {
        revealed = true
        return state(done = false)
    }

    /**
     * The interval each button would set, shown before the learner commits.
     *
     * The same call [Wordbook.review] makes with the same arguments, through the same
     * [Wordbook.schedules] rule, so the number on the button is the number the card
     * gets. Anki does this for the same reason: a learner who can see that Easy means
     * four months is choosing on the real trade rather than on a label.
     *
     * [attempt] is how many times this card has already been tried *before* this
     * answer, so the caller adds one for the answer being previewed.
     */
    fun intervalsFor(card: Wordbook.Card, attempt: Int): Map<Int, String> {
        val now = clock()
        val gap = if (card.lastReview == 0L) 0.0 else (now - card.lastReview) / Wordbook.DAY_MS
        val tries = attempt + 1
        return GRADES.associateWith { grade ->
            if (!Wordbook.schedules(card, grade, tries)) {
                SAME_SESSION
            } else {
                formatInterval(
                    FSRS.next(card.stability, card.difficulty, gap, grade, seenBefore = !card.isNew).third,
                )
            }
        }
    }

    /**
     * Records an answer and returns the state for the next card.
     *
     * A word this answer did not schedule is put back at the end of the queue instead,
     * and is shown at most [MAX_ATTEMPTS] times in one sitting. Without the cap a single
     * unrememberable word cycles forever and the session never ends. A word that hits
     * the cap leaves with the schedule untouched, which is the honest outcome: the app
     * does not know this word and will say so tomorrow.
     */
    fun answer(grade: Int): ReviewState {
        val card = current ?: return state(done = true)
        val tries = (attempts[card.key] ?: 0) + 1
        attempts[card.key] = tries
        answered++

        val scheduled = Wordbook.schedules(card, grade, tries)
        val updated = wordbook.review(card, grade, tries)
        // A word this answer did not schedule comes back later in the same sitting,
        // until it has had MAX_ATTEMPTS. Past that it leaves with the schedule
        // untouched, which is the honest outcome: the app does not know this word, and
        // tomorrow it will say so by asking again.
        if (!scheduled && tries < Wordbook.MAX_ATTEMPTS) {
            requeued++
            queue.addLast(updated)
        }

        current = queue.removeFirstOrNull()
        revealed = false
        return state(done = current == null)
    }

    private fun state(done: Boolean) = ReviewState(
        current = current,
        revealed = revealed,
        intervals = if (revealed && current != null) {
            intervalsFor(current!!, attempts[current!!.key] ?: 0)
        } else {
            emptyMap()
        },
        answered = answered,
        planned = planned,
        newCards = newCount,
        done = done,
        requeued = requeued,
    )

    /**
     * The gloss and usage to save with a word.
     *
     * The first sense of the entry that is not flagged unsuitable for a learner who
     * has not asked to see such things. Saving the *obsolete* sense of a word when a
     * common one sits right above it would teach the wrong lesson, and the wordbook is
     * where a learner meets a word a second time — often weeks later, when they no
     * longer remember saving it.
     *
     * The usage has to clear two things, and both are load-bearing:
     *
     * - It must be one the corpus itself considers usable. Measured over the first
     *   sense of the 3000 commonest words, 997 of those examples are a page of quoted
     *   dialogue, a computer-science textbook or early printed English carrying the
     *   long-s. Checking only the length would have let some of those through.
     * - It must genuinely contain the word, in any of its forms. A usage example that
     *   does not contain the headword is about a different form, and pairing it with
     *   this word teaches the learner the two belong together.
     *
     * When neither passes, the card stores an empty usage and the review screen says
     * so. A sentence borrowed from a neighbouring sense is worse than no sentence.
     */
    suspend fun senseToSave(lookup: Lookup): Pair<String, String> =
        withContext(Dispatchers.IO) {
            val sense = lookup.senses.firstOrNull { s -> s.tags.none { it in GATED_TAGS } }
                ?: lookup.senses.firstOrNull()
                ?: return@withContext "" to ""
            val usage = buildList {
                sense.example?.let { add(it) }
                addAll(sense.examples)
            }.firstOrNull { text ->
                text.isNotBlank() &&
                    text.length <= MAX_USAGE_CHARS &&
                    repository.isUsableUsage(text) &&
                    repository.containsWord(text, lookup.entry.id)
            }
            sense.gloss.trim() to usage?.trim().orEmpty()
        }

    /** An interval in days, in words a person would say rather than scheduler jargon. */
    fun formatInterval(days: Int): String = when {
        days < 0 -> "now"
        days == 0 -> SAME_SESSION
        days == 1 -> "1 day"
        days < 14 -> "$days days"
        days < 60 -> "${days / 7} weeks"
        days < 365 -> "${days / 30} months"
        days < 548 -> "1 year"
        else -> "${days / 365} years"
    }

    companion object {
        /** The four FSRS buttons, in order. */
        val GRADES = listOf(FSRS.GRADE_AGAIN, FSRS.GRADE_HARD, FSRS.GRADE_GOOD, FSRS.GRADE_EASY)

        /**
         * How many never-reviewed words a session may introduce.
         *
         * Twenty is a session's worth of time for an adult and near the number every
         * spaced-repetition system settles on: introducing more costs recall on the
         * words already in the queue.
         */
        const val NEW_PER_SESSION = 20

        /** A longer passage is quoted prose rather than a demonstration of one word. */
        const val MAX_USAGE_CHARS = 180

        const val SAME_SESSION = "this session"
    }
}