package com.krafttools.englishkraft.data

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import android.content.Context
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

/**
 * The wordbook, against a real SQLite file.
 *
 * Instrumented rather than a JVM test because `android.database.sqlite` is a stub
 * off-device: every method throws there, so a unit test would be testing the stub.
 */
@RunWith(AndroidJUnit4::class)
class WordbookTest {

    private lateinit var store: ProgressStore
    private lateinit var book: Wordbook
    private var clock = 1_700_000_000_000L

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        store = ProgressStore(context, "test-progress-${counter++}.db")
        // A clock the test moves by hand. Intervals are days, so a real clock would
        // make "was this due" depend on how long the test took to run.
        book = Wordbook(store) { clock }
    }

    @After
    fun tearDown() {
        store.close()
        ApplicationProvider.getApplicationContext<Context>()
            .deleteDatabase("test-progress-${counter - 1}.db")
    }

    @Test
    fun aSavedWordIsFoundByHeadwordAndPartOfSpeech() {
        book.toggle("run", "verb", "to move fast", "He ran home.")

        assertTrue(book.has("run", "verb"))
        // The noun is a different word, not the same card with a different label.
        assertFalse(book.has("run", "noun"))
        assertEquals(1, book.count())
    }

    @Test
    fun theHeadwordIsMatchedWithoutRegardToCase() {
        book.toggle("Ephemeral", "adjective", "short-lived", "")
        assertTrue(book.has("ephemeral", "adjective"))
        assertTrue(book.has("EPHEMERAL", "adjective"))
        assertEquals("ephemeral/adjective", book.all().single().key)
    }

    @Test
    fun togglingTwiceRemovesTheWordAndItsAnswers() {
        book.toggle("ephemeral", "adjective", "short-lived", "")
        val card = book.all().single()
        book.review(card, FSRS.GRADE_GOOD, 1)
        assertEquals(1, book.reviewCount())

        assertFalse(book.toggle("ephemeral", "adjective", "short-lived", ""))
        assertEquals(0, book.count())
        // The answers go with the word. Leaving them behind would make a re-added
        // word inherit a schedule the learner never gave it.
        assertEquals(0, book.reviewCount())
    }

    @Test
    fun aSavedWordIsDueImmediatelyAndCountsAsNew() {
        book.toggle("ephemeral", "adjective", "short-lived", "")
        assertEquals(1, book.fresh().size)
        assertEquals(0, book.dueCount())
    }

    /**
     * A first answer schedules the word from the interval its stability implies.
     *
     * Two days for Good, not one. The interval is solved from the starting stability
     * exactly as every later interval is; hard-coding a single day made the Good and
     * Easy buttons read "1 day" and "1 day" on a new word, so two of the four were
     * indistinguishable.
     */
    @Test
    fun theFirstAnswerSchedulesTheWordFromItsStartingStability() {
        book.toggle("ephemeral", "adjective", "short-lived", "")
        val card = book.all().single()
        val after = book.review(card, FSRS.GRADE_GOOD, 1, now = clock)

        assertFalse("a word answered once has been seen", after.isNew)
        assertEquals(0, book.fresh().size)
        assertEquals(
            "Good on a new word starts at two days: interval = f(initial stability)",
            TimeUnit.DAYS.toMillis(2L),
            after.due - clock,
        )
    }

    @Test
    fun aWordNotYetRecalledKeepsTheScheduleItStartedWith() {
        book.toggle("ephemeral", "adjective", "short-lived", "")
        val card = book.all().single()
        val before = card.stability

        val after = book.review(card, FSRS.GRADE_AGAIN, 1, now = clock)

        assertEquals(
            before, after.stability, 1e-12,
        )
        // The answer is still counted, so the learner can see the word is hard for them.
        assertEquals(1, after.lapses)
        assertEquals(1, after.reps)
    }

    @Test
    fun aWordAnsweredAgainIsStillNewUntilItIsRecalled() {
        book.toggle("ephemeral", "adjective", "short-lived", "")
        book.review(book.all().single(), FSRS.GRADE_AGAIN, 1, now = clock)

        // An answer was given — but the word was not learned, so it has no schedule
        // and is still one of the learner's new words. Treating "answered" as "learned"
        // is what let a word that had never been recalled start being scheduled after
        // its second wrong answer.
        assertEquals("still new after one failed answer", 1, book.fresh().size)
        assertEquals(0, book.dueCount())
        assertEquals("the answer is still counted", 1, book.all().single().reps)
    }

    @Test
    fun aWordThatWasNeverRecalledKeepsComingBackInTheNewQueue() {
        book.toggle("ephemeral", "adjective", "short-lived", "")
        repeat(2) { i ->
            book.review(book.all().single(), FSRS.GRADE_AGAIN, i + 1, now = clock)
        }
        assertEquals(1, book.fresh().size)
        assertTrue("it must be due at once", book.all().single().due <= clock)
    }

    @Test
    fun aStableWordIsNotDueForMonths() {
        book.toggle("ephemeral", "adjective", "short-lived", "")
        var card = book.all().single()
        card = book.review(card, FSRS.GRADE_GOOD, 1, now = clock)
        val afterOne = card

        // Eight good answers, each time moving to when the card came due. If the gap
        // were ignored, stability would still rise but the schedule would be wrong.
        for (i in 2..8) {
            clock = card.due
            card = book.review(card, FSRS.GRADE_GOOD, i, now = clock)
        }

        assertTrue(
            "a well-known word should be scheduled far out, got " +
                TimeUnit.MILLISECONDS.toDays(card.due - clock) + " days",
            TimeUnit.MILLISECONDS.toDays(card.due - clock) > 100,
        )
        assertEquals(0, book.dueCount())
        assertTrue("stability must grow", card.stability > afterOne.stability)
    }

    @Test
    fun aWordDueNowIsListedAndOneDueLaterIsNot() {
        book.toggle("ephemeral", "adjective", "short-lived", "")
        val card = book.all().single()
        val answered = book.review(card, FSRS.GRADE_GOOD, 1, now = clock)

        assertEquals(0, book.dueCount())
        clock = answered.due
        assertEquals(1, book.dueCount())
        assertEquals(answered.key, book.due().single().key)
    }

    @Test
    fun dueListsTheHardestWordFirst() {
        // Two words this learner knows, one found easy and one merely recalled. Both
        // are scheduled a day out, so both come due at the same moment.
        book.toggle("cat", "noun", "a small animal", "")
        book.toggle("ephemeral", "adjective", "short-lived", "")
        for (word in listOf("cat", "ephemeral")) {
            val card = book.all().first { it.headword == word }
            book.review(
                card,
                if (word == "cat") FSRS.GRADE_EASY else FSRS.GRADE_GOOD,
                1,
                now = clock,
            )
        }
        // Eight days: Easy starts at a stability of 8.2956, so its first interval is
        // eight days, while Good's is two. Advancing two days would leave cat scheduled
        // and the test would be checking the wrong thing.
        clock += TimeUnit.DAYS.toMillis(9)

        val difficulties = book.all().associate { it.headword to it.difficulty }
        assertTrue(
            "the test needs ephemeral to be the harder word: $difficulties",
            difficulties.getValue("ephemeral") > difficulties.getValue("cat"),
        )

        assertEquals(
            listOf("ephemeral", "cat"),
            book.due().map { it.headword },
        )
    }

    @Test
    fun everyAnswerIsKeptIncludingTheOnesThatDidNotSchedule() {
        book.toggle("ephemeral", "adjective", "short-lived", "")
        var card = book.all().single()
        card = book.review(card, FSRS.GRADE_AGAIN, 1, now = clock)
        clock = card.due
        card = book.review(card, FSRS.GRADE_AGAIN, 2, now = clock)
        clock = card.due
        card = book.review(card, FSRS.GRADE_GOOD, 3, now = clock)

        assertEquals("every answer is kept, scheduled or not", 3, book.reviewCount())
    }

    @Test
    fun aWordTheLearnerNeverRecallsKeepsComingBack() {
        // The whole point of not scheduling an un-recalled word: it must not quietly
        // drop out of circulation. Five wrong answers in a row and it is still offered,
        // and still due at once.
        book.toggle("ephemeral", "adjective", "short-lived", "")
        var card = book.all().single()
        repeat(5) { i ->
            clock = maxOf(clock, card.due)
            card = book.review(card, FSRS.GRADE_AGAIN, i + 1, now = clock)
        }

        assertEquals("it is still one of the learner's new words", 1, book.fresh().size)
        assertEquals("every attempt is on the card", 5, book.fresh().single().reps)
        assertTrue(
            "the word must be due at once, never in the future",
            card.due <= clock,
        )
        assertEquals("and every attempt is recorded", 5, card.lapses)
    }

    @Test
    fun forgettingEverythingRemovesBothTables() {
        book.toggle("ephemeral", "adjective", "short-lived", "")
        book.review(book.all().single(), FSRS.GRADE_GOOD, 1, now = clock)
        book.clear()

        assertEquals(0, book.count())
        assertEquals(0, book.reviewCount())
        assertTrue(book.all().isEmpty())
    }

    @Test
    fun aClosedAndReopenedWordbookStillHasTheWords() {
        book.toggle("ephemeral", "adjective", "short-lived", "")
        store.close()

        val reopened = ProgressStore(
            ApplicationProvider.getApplicationContext(),
            "test-progress-${counter - 1}.db",
        )
        try {
            assertEquals(1, Wordbook(reopened) { clock }.count())
        } finally {
            reopened.close()
        }
    }

    @Test
    fun anEmptyWordbookReportsZeroRatherThanFailing() {
        assertEquals(0, book.count())
        assertEquals(0, book.dueCount())
        assertEquals(0, book.reviewCount())
        assertEquals(emptyList<Wordbook.Card>(), book.all())
        assertEquals(emptyList<Wordbook.Card>(), book.due())
    }

    companion object {
        private var counter = 0
    }
}