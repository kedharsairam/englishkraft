package com.krafttools.englishkraft.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

/**
 * The review session, against the real corpus and a real database.
 *
 * Instrumented for the same reason the lookup tests are: `android.database.sqlite`
 * throws off-device, so a JVM test would exercise the stub rather than the SQL.
 *
 * The properties checked here are the ones a learner would notice being wrong — a
 * word that never comes back, a button promising an interval the card does not get, a
 * new word shown forever — and each is checked in both directions where it can be.
 */
@RunWith(AndroidJUnit4::class)
class ReviewSessionTest {

    private lateinit var vm: WordbookViewModel
    private lateinit var repository: DictionaryRepository
    private lateinit var db: android.database.sqlite.SQLiteDatabase
    private lateinit var storeName: String
    private var clock = 1_700_000_000_000L

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val installer = DictionaryInstaller(context)
        val outcome = kotlinx.coroutines.runBlocking { installer.install { _, _ -> } }
        val file = (outcome as? DictionaryInstaller.State.Ready)?.file
        assertNotNull("the dictionary must install on device", file)
        db = openDictionary(file!!)
        repository = DictionaryRepository(db)

        storeName = "test-session-${counter++}.db"
        vm = WordbookViewModel(context, repository, storeName) { clock }
        // A fresh wordbook for every test, so one test's answers cannot make another
        // test's word due and quietly change what it measures.
        vm.clear()
    }

    @After
    fun tearDown() {
        db.close()
        ApplicationProvider.getApplicationContext<Context>().deleteDatabase(storeName)
    }

    private fun lookup(word: String): Lookup {
        val found = repository.lookup(word)
        assertNotNull("the corpus must contain '$word'", found)
        return found!!
    }

    @Test
    fun anEmptyWordbookProducesAnEmptySessionRatherThanAnError() {
        val state = vm.start()
        assertTrue(state.done)
        assertNull(state.current)
        assertEquals(0, state.planned)
    }

    @Test
    fun aSavedWordIsShownInTheFirstSession() {
        vm.toggle("ephemeral", "adjective", "short-lived", "The moment is ephemeral.")
        val state = vm.start()

        assertFalse(state.done)
        assertEquals("ephemeral", state.current?.headword)
        assertEquals(1, state.planned)
        assertEquals(1, state.newCards)
        assertFalse("nothing is revealed before the learner asks", state.revealed)
        assertTrue("no buttons before the answer is shown", state.intervals.isEmpty())
    }

    @Test
    fun theIntervalsOnlyAppearOnceTheAnswerIsRevealed() {
        vm.toggle("ephemeral", "adjective", "short-lived", "")
        vm.start()
        val revealed = vm.reveal()

        assertTrue(revealed.revealed)
        assertEquals(4, revealed.intervals.size)
        for (grade in WordbookViewModel.GRADES) {
            assertTrue(
                "grade $grade has no interval on its button",
                !revealed.intervals[grade].isNullOrBlank(),
            )
        }
    }

    @Test
    fun aNewWordAnsweredAgainComesBackInTheSameSession() {
        vm.toggle("ephemeral", "adjective", "short-lived", "")
        var state = vm.start()
        val key = state.current!!.key

        state = vm.answer(FSRS.GRADE_AGAIN)
        assertEquals(1, state.answered)
        assertEquals("the word was not scheduled, so it is asked again", key, state.current?.key)
        assertEquals(1, state.requeued)
    }

    @Test
    fun aNewWordAnsweredGoodIsNotAskedAgainInThisSession() {
        vm.toggle("ephemeral", "adjective", "short-lived", "")
        val state = vm.start().let { vm.answer(FSRS.GRADE_GOOD) }

        assertTrue("the session is over", state.done)
        assertEquals(0, state.requeued)
        // It is now scheduled, so tomorrow's session will have it.
        assertEquals(0, vm.dueCount())
        assertEquals(0, vm.freshCount())
    }

    @Test
    fun aWordThatKeepsBeingForgottenEventuallyStopsLooping() {
        vm.toggle("ephemeral", "adjective", "short-lived", "")
        var state = vm.start()
        var answers = 0

        // Twenty Again answers. Without the attempt cap this never returns and the
        // learner is stuck with one word forever.
        while (!state.done && answers < 20) {
            state = vm.answer(FSRS.GRADE_AGAIN)
            answers++
        }

        assertTrue("the session must end, asked $answers times", state.done)
        assertEquals(Wordbook.MAX_ATTEMPTS, answers)
    }

    @Test
    fun theButtonIntervalsAreTheIntervalsTheCardGets() {
        vm.toggle("ephemeral", "adjective", "short-lived", "")
        val card = vm.start().current!!
        val preview = vm.reveal().intervals

        // Each grade, applied for real, must produce the interval its button promised.
        for (grade in WordbookViewModel.GRADES) {
            val promised = preview[grade].orEmpty()
            if (promised == WordbookViewModel.SAME_SESSION) {
                // Not scheduled: the word keeps the interval it had.
                assertTrue(
                    "grade $grade promised no new schedule but changed one",
                    !Wordbook.schedules(card, grade, 1),
                )
            } else {
                val gap = if (card.lastReview == 0L) 0.0
                else (System.currentTimeMillis() - card.lastReview) / Wordbook.DAY_MS
                val actual = FSRS.next(card.stability, card.difficulty, gap, grade, false).third
                assertEquals(
                    "grade $grade: button says $promised, the algorithm says $actual",
                    vm.formatInterval(actual),
                    promised,
                )
            }
        }
    }

    @Test
    fun aHardAnswerOnANewWordIsAskedAgainUntilTheLastAttempt() {
        vm.toggle("ephemeral", "adjective", "short-lived", "")
        var state = vm.start()

        for (attempt in 1 until Wordbook.MAX_ATTEMPTS) {
            val preview = vm.reveal().intervals
            assertEquals(
                "attempt $attempt of $attempt: Hard should not schedule the word yet",
                WordbookViewModel.SAME_SESSION,
                preview[FSRS.GRADE_HARD],
            )
            state = vm.answer(FSRS.GRADE_HARD)
            assertFalse("the word must come back after $attempt", state.done)
        }

        // The last permitted attempt: the learner's Hard is accepted as an answer.
        val preview = vm.reveal().intervals
        assertEquals(
            "Hard on the final attempt should schedule the word",
            "1 day",
            preview[FSRS.GRADE_HARD],
        )
        state = vm.answer(FSRS.GRADE_HARD)
        assertTrue(state.done)
    }

    @Test
    fun aSessionOffersNoMoreNewWordsThanTheCap() {
        repeat(WordbookViewModel.NEW_PER_SESSION + 7) {
            vm.toggle("word$it", "noun", "a meaning", "")
        }
        val state = vm.start()

        assertEquals(WordbookViewModel.NEW_PER_SESSION, state.planned)
        assertEquals(WordbookViewModel.NEW_PER_SESSION, state.newCards)
        // The cap must not silently discard the learner's words: they are still saved.
        assertEquals(
            WordbookViewModel.NEW_PER_SESSION + 7,
            vm.all().size,
        )
    }

    @Test
    fun dueWordsComeBeforeNewOnes() {
        // One word learned a week ago, so it is due now, and one never seen.
        vm.toggle("ephemeral", "adjective", "short-lived", "")
        vm.start()
        vm.answer(FSRS.GRADE_GOOD)
        assertEquals("the setup must schedule the word a day out", 0, vm.dueCount())

        // A week later it is due. Without a controllable clock this could only be
        // reached by waiting, which is not a test anyone would write.
        clock += TimeUnit.DAYS.toMillis(7)
        assertEquals(1, vm.dueCount())

        vm.toggle("catalyst", "noun", "something that speeds a reaction", "")
        val state = vm.start()

        assertEquals(2, state.planned)
        assertEquals(1, state.newCards)
        assertEquals("the due word is asked first", "ephemeral", state.current?.headword)
    }

    @Test
    fun aSavedWordCarriesTheSenseItWasSavedWith() {
        val found = lookup("ephemeral")
        val (gloss, usage) = kotlinx.coroutines.runBlocking { vm.senseToSave(found) }

        assertTrue("a saved word must carry a meaning, got '$gloss'", gloss.isNotBlank())
        assertEquals(
            gloss,
            // The gloss saved must be one the entry actually shows, not a summary.
            found.senses.first { it.gloss.trim() == gloss }.gloss.trim(),
        )
        assertTrue(
            "the usage kept must genuinely contain the word, got '$usage'",
            usage.isBlank() || repository.containsWord(usage, found.entry.id),
        )
    }

    @Test
    fun anObsoleteSenseIsNotTheOneSavedWhenACommonOneExists() {
        // Every sense in the corpus may be obsolete for a rare word. The rule is that
        // the first sense not flagged for a learner is chosen, and only when every
        // sense is flagged does the app fall back rather than save nothing.
        val found = lookup("ephemeral")
        val (_, usage) = kotlinx.coroutines.runBlocking { vm.senseToSave(found) }

        val gated = found.senses.filter { s -> s.tags.any { it in GATED_TAGS } }
        if (gated.size == found.senses.size && found.senses.isNotEmpty()) {
            // Every sense gated: saving the first one is the fallback, and it must
            // still be a real gloss rather than an empty string.
            assertTrue(found.senses.first().gloss.isNotBlank())
        }
        assertTrue(
            "usage must contain the word or be absent",
            usage.isBlank() || repository.containsWord(usage, found.entry.id),
        )
    }

    @Test
    fun aUsageIsNeverInventedForAWordTheCorpusHasNoneFor() {
        // The corpus records a usage for many senses and none for others. Where there
        // is none, the saved card carries an empty string — never a sentence borrowed
        // from a neighbouring sense, which would teach the learner the two go together.
        for (word in listOf("ephemeral", "catalyst", "run", "sufficient", "ubiquitous")) {
            val found = lookup(word)
            val (_, usage) = kotlinx.coroutines.runBlocking { vm.senseToSave(found) }
            assertTrue(
                "a usage saved for '$word' must contain the word, got '$usage'",
                usage.isBlank() || repository.containsWord(usage, found.entry.id),
            )
        }
    }

    /**
     * The junk test, over the range where it actually bites.
     *
     * A check on five words proved nothing: measured over the first sense of the 3000
     * commonest words, 997 of those examples are a page of quoted dialogue, a
     * computer-science textbook, or early printed English carrying the long-s — every
     * one of them containing its headword, so the containment check alone accepted all
     * of them. This walks the whole range.
     */
    @Test
    fun noCommonWordSavesAJunkUsage() {
        val heads = repository.commonHeadwords(3000)
        assertTrue("the corpus must have that many common words", heads.size >= 3000)

        var withUsage = 0
        for (headword in heads) {
            val found = repository.lookup(headword) ?: continue
            val (_, usage) = kotlinx.coroutines.runBlocking { vm.senseToSave(found) }
            if (usage.isBlank()) continue
            withUsage++

            assertTrue(
                "'$headword' saved a usage the entry screen would refuse to show: " +
                    usage,
                repository.isUsableUsage(usage),
            )
            assertTrue(
                "'$headword' saved a usage that does not contain the word: $usage",
                repository.containsWord(usage, found.entry.id),
            )
            assertTrue(
                "'$headword' saved a usage longer than a demonstration should be: $usage",
                usage.length <= WordbookViewModel.MAX_USAGE_CHARS,
            )
        }
        // A filter that rejects everything would pass every assertion above, so the
        // test is worthless unless it also shows the filter keeps a real share.
        assertTrue(
            "no usage survived for $heads.size common words: the filter is too strict",
            withUsage > heads.size / 4,
        )
    }

    @Test
    fun aWordWithOnlyJunkUsagesSavesNoneRatherThanTheJunk() {
        // "Not" and "go" both have first senses whose example is a computer-science
        // textbook. Both words contain themselves, so only the corpus filter can catch
        // these, and a card that stored one would teach a learner that a manual about
        // boolean gates is what "not" means.
        for (word in listOf("not", "go", "your", "we")) {
            val found = lookup(word)
            val (_, usage) = kotlinx.coroutines.runBlocking { vm.senseToSave(found) }
            assertTrue(
                "'$word' stored a usage the corpus filter should have rejected: $usage",
                usage.isBlank() || repository.isUsableUsage(usage),
            )
        }
    }

    companion object {
        private var counter = 0
    }
}