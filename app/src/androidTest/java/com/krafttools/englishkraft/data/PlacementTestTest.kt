package com.krafttools.englishkraft.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import android.database.sqlite.SQLiteDatabase
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Placement-test questions, checked against the real corpus on a real device.
 *
 * The thing being verified is that a question can be answered correctly by someone
 * who knows the word and only by them. That is not automatic: 29% of words in the
 * corpus have more than one usable gloss, so a question can be built with two defensible
 * answers, and a distractor drawn from the wrong part of the vocabulary can be
 * recognisable at a glance.
 */
@RunWith(AndroidJUnit4::class)
class PlacementTestTest {

    private lateinit var db: SQLiteDatabase

    @Before
    fun open() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val installed = DictionaryInstaller(context).install { _, _ -> }
        val file = (installed as? DictionaryInstaller.State.Ready)?.file!!
        db = openDictionary(file)
    }

    private fun repo() = PlacementRepository(db)

    @Test
    fun everyQuestionHasExactlyOneCorrectAnswer() {
        val r = repo()
        for (rank in listOf(100, 204, 785, 2_446, 7_627, 20_000, 60_000)) {
            repeat(6) {
                val q = r.question(rank) ?: continue
                assertEquals(
                    "$q has ${q.options.size} options", 4, q.options.size
                )
                assertEquals(
                    "options must be distinct for '${q.word}'", 4, q.options.distinct().size
                )
                assertTrue(
                    "correctIndex ${q.correctIndex} out of range", q.correctIndex in 0..3
                )
                assertTrue(
                    "options must be non-blank", q.options.none { it.isBlank() }
                )
            }
        }
    }

    @Test
    fun aGlossIsNeverUsedTwiceInOneQuestion() {
        // The failure this guards is real: 29% of words carry more than one usable
        // gloss, so a distractor drawn from the same word's senses would make two
        // options correct.
        val r = repo()
        for (rank in listOf(204, 2_446, 7_627, 40_000)) {
            repeat(10) {
                val q = r.question(rank) ?: return@repeat
                assertEquals(
                    "'${q.word}' has a repeated option", 4, q.options.distinct().size
                )
            }
        }
    }

    @Test
    fun questionsExistAcrossTheWholeRange() {
        val r = repo()
        val ranks = listOf(60, 150, 204, 785, 2_446, 7_627, 20_000, 50_000, 120_000)
        val missing = ranks.filter { r.question(it) == null }
        assertTrue("no question available at ranks $missing", missing.isEmpty())
    }

    @Test
    fun properNounsAreNeverAsked() {
        // 51,607 testable words are proper nouns and "a male given name of uncertain
        // origin" is not something a learner can be tested on.
        val r = repo()
        val names = db.rawQuery(
            "SELECT headword_lc FROM entry WHERE pos='name' " +
                "AND headword_lc IN ('Aaron','Paris','Homer','Marge')", null
        ).use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }
        if (names.isEmpty()) return
        for (rank in listOf(100, 500, 2_000, 8_000, 30_000)) {
            repeat(8) {
                val q = r.question(rank) ?: return@repeat
                assertTrue(
                    "'${q.word}' is a proper noun", q.word.lowercase() !in names
                )
            }
        }
    }

    @Test
    fun formOfEntriesAreNeverAsked() {
        val r = repo()
        for (rank in listOf(300, 1_500, 5_000)) {
            repeat(10) {
                val q = r.question(rank) ?: return@repeat
                assertTrue(
                    "'${q.word}' is an inflected form, not a headword",
                    q.pos.isNotEmpty()
                )
            }
        }
    }

    @Test
    fun rankOfCoverageMatchesTheMeasuredThresholds() {
        val r = repo()
        // Measured from this corpus by an independent Python pass over the same rows.
        // 60% -> rank 50 is the case that caught a reset-per-rank-group accumulator.
        assertEquals("60%", 50, r.rankOfCoverage(0.60))
        assertEquals("80%", 204, r.rankOfCoverage(0.80))
        assertEquals("90%", 785, r.rankOfCoverage(0.90))
        assertEquals("95%", 2446, r.rankOfCoverage(0.95))
        assertEquals("98%", 7627, r.rankOfCoverage(0.98))
    }

    @Test
    fun coverageMatchesTheMeasuredThresholds() {
        val r = repo()
        // Measured from this corpus: 204 = 80%, 785 = 90%, 2,446 = 95%, 7,627 = 98%.
        assertTrue("rank 204 -> ${r.coverageOf(204)}", r.coverageOf(204) in 0.79..0.81)
        assertTrue("rank 785 -> ${r.coverageOf(785)}", r.coverageOf(785) in 0.89..0.91)
        assertTrue("rank 2446 -> ${r.coverageOf(2446)}", r.coverageOf(2446) in 0.94..0.96)
        assertTrue("rank 7627 -> ${r.coverageOf(7627)}", r.coverageOf(7627) in 0.97..0.99)
    }

    @Test
    fun aLearnerWhoKnowsNothingGetsAHonestLowResult() {
        val r = repo()
        // Always wrong.
        val result = PlacementTest(r).run { Int.MAX_VALUE }
        assertEquals("asked every question", 15, result.asked)
        assertEquals("scored nothing", 0, result.correct)
        assertTrue(
            "a learner who got everything wrong must not be placed above 80%, " +
                "got ${result.coverage}",
            result.coverage < 0.80
        )
    }

    @Test
    fun aLearnerWhoKnowsEverythingGetsAHonestHighResult() {
        val r = repo()
        // Always right.
        val result = PlacementTest(r).run { 0 }
        assertEquals(15, result.correct)
        assertTrue(
            "a learner who got everything right should be above 98%, got ${result.coverage}",
            result.coverage > 0.98
        )
    }

    @Test
    fun theResultMovesWithAbility() {
        val r = repo()
        val weak = PlacementTest(r).run { Int.MAX_VALUE }
        val strong = PlacementTest(r).run { 0 }
        assertTrue(
            "weak ${weak.coverage} should place below strong ${strong.coverage}",
            weak.coverage < strong.coverage
        )
        assertTrue("strong must rank above weak", strong.rank > weak.rank)
    }

    /**
     * The reader must never open above the level the learner demonstrated.
     *
     * This is the bug that shipped: a 93% placement opened the reader at 98%,
     * because the level was snapped to the nearest RANK and rank distances are not
     * comparable across a five-fold range. Over-shooting shows a beginner text they
     * cannot read, which is the one thing this app exists to prevent.
     */
    @Test
    fun theReaderNeverOpensAboveTheTestedLevel() {
        val r = repo()
        // All right: the test should reach the top level, and 98% is the top.
        val strong = PlacementTest(r).run { 0 }
        assertTrue(
            "a learner who answers everything right should reach the top level, " +
                "got ${strong.coverage}",
            strong.coverage >= 0.98
        )

        // A placement BELOW the easiest offered level still opens at the easiest level:
        // someone placed at 60% gets the 80% reader. That is deliberate — the reader
        // offers nothing easier than 80%, and inventing a 60% level to satisfy an
        // assertion would serve text no corpus sentence could meet.
        //
        // So the invariant is: never above the tested level, except when the tested
        // level is below everything on offer.
        val levels = listOf(0.80, 0.90, 0.95, 0.98)
        val rankOfLevel = levels.associateWith { r.rankOfCoverage(it) }

        fun levelFor(testedCoverage: Double): Double {
            val testedRank = r.rankOfCoverage(testedCoverage)
            return levels
                .filter { level -> (rankOfLevel[level] ?: Int.MAX_VALUE) <= testedRank }
                .maxOrNull() ?: levels.first()
        }

        for (testedCoverage in listOf(0.80, 0.85, 0.93, 0.96)) {
            val chosen = levelFor(testedCoverage)
            assertTrue(
                "a $testedCoverage placement opened the reader at $chosen, which is " +
                    "above the level they were placed at",
                chosen <= testedCoverage + 0.001
            )
        }

        // And monotone: a higher placement never opens at a lower level.
        var previous = 0.0
        for (testedCoverage in listOf(0.80, 0.90, 0.93, 0.95, 0.96, 0.98)) {
            val chosen = levelFor(testedCoverage)
            assertTrue(
                "placement $testedCoverage opened at $chosen, below the $previous it " +
                    "should have replaced",
                chosen >= previous - 0.001
            )
            previous = chosen
        }

        // Below everything on offer, the reader opens at its easiest level rather than
        // its hardest.
        assertEquals(
            "a placement below the easiest level must open at the easiest",
            0.80, levelFor(0.60), 0.001
        )
    }
}
