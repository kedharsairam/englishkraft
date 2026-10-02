package com.krafttools.englishkraft.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * FSRS checked against the reference implementation.
 *
 * The expected values are NOT hand-computed and NOT read back from the Kotlin. They
 * come from running the reference algorithm — `open-spaced-repetition/py-fsrs`,
 * `DEFAULT_PARAMETERS` — over the same answer histories in Python, and pasting the
 * output in. That is the only way a port can be verified: a test whose expectations
 * come from the code under test proves nothing.
 *
 * An earlier draft of FSRS.kt was written from memory and was wrong in every
 * constant, the decay, the scale factor and the interval formula. It would have
 * produced believable intervals forever. These tests exist because of that.
 */
class FSRSTest {

    private fun run(history: List<Int>): List<Triple<Double, Double, Int>> {
        var stability = 0.0
        var difficulty = 0.0
        var dueDays = 0.0
        var seen = false
        val out = mutableListOf<Triple<Double, Double, Int>>()
        for (rating in history) {
            val (s, d, interval) =
                FSRS.next(stability, difficulty, dueDays, rating, seen)
            stability = s
            difficulty = d
            out.add(Triple(s, d, interval))
            dueDays += interval
            // Every card becomes "seen" after its first answer. Without this the
            // schedule never advances past the initial values and every test compares
            // one row against the reference's sixth.
            seen = true
        }
        return out
    }

    private fun assertClose(expected: Double, actual: Double, tolerance: Double = 1e-6) {
        assertTrue(
            "expected $expected but was $actual",
            kotlin.math.abs(expected - actual) <= tolerance
        )
    }

    /**
     * Reference output, all answers "Good": 2d, 11d, 51d, 199d, 678d…
     *
     * The first interval is 2 days rather than 1 because a brand-new card's interval is
     * solved from its starting stability, as every other interval is. The reference
     * instead sends a new card through learning steps before graduating it; this app has
     * none, so it graduates immediately. Hard-coding 1 for the first review made Good
     * and Easy identical on a new word, which put two of the four review buttons out of
     * use.
     */
    @Test
    fun aWordAnsweredGoodEveryTimeGrowsAsTheReferenceDoes() {
        val rows = run(List(8) { FSRS.GRADE_GOOD })
        assertClose(2.3065, rows[0].first, 1e-4)
        assertClose(10.9643, rows[1].first, 1e-3)
        assertClose(50.6626, rows[2].first, 1e-2)
        assertClose(199.1925, rows[3].first, 1e-1)

        assertEquals(listOf(2, 11, 51, 199, 678, 2055, 5638, 14222), rows.map { it.third })
        // Difficulty settles rather than drifting: mean reversion.
        assertClose(2.1181, rows[0].second, 1e-4)
        assertClose(2.0700, rows[7].second, 1e-4)
    }

    /** Reference output, all answers "Again": stability collapses toward 0.01. */
    @Test
    fun aWordAlwaysForgottenCollapses() {
        val rows = run(List(6) { FSRS.GRADE_AGAIN })
        assertClose(0.212, rows[0].first, 1e-4)
        assertClose(0.1009, rows[1].first, 1e-4)
        assertClose(0.0613, rows[2].first, 1e-4)
        // Difficulty climbs to nearly impossible and then flattens.
        assertClose(6.4133, rows[0].second, 1e-4)
        assertTrue(
            "difficulty must reach the top of the range, was ${rows.last().second}",
            rows.last().second > 9.9
        )
        // Every interval stays at one day: the learner keeps seeing it.
        assertEquals(List(6) { 1 }, rows.map { it.third })
    }

    /** Reference output for 3,3,2,3,4,3,1,3,3,2 — the case with a lapse in it. */
    @Test
    fun aLapseCostsStabilityWithoutErasingIt() {
        val rows = run(listOf(3, 3, 2, 3, 4, 3, 1, 3, 3, 2))
        assertEquals(
            listOf(2, 11, 35, 116, 537, 1517, 10, 94, 445, 933),
            rows.map { it.third },
        )
        // The lapse at review 7 drops stability from 1517.36 to 10.05 — a big loss, but
        // it was learned over six reviews and is not back at the start.
        assertClose(1517.3583, rows[5].first, 1e-1)
        assertClose(10.0473, rows[6].first, 1e-3)
        assertTrue(
            "a lapse must not reset stability to its initial value",
            rows[6].first > rows[0].first
        )
    }

    /** A learner who finds everything hard stays on short intervals. */
    @Test
    fun hardAnswersKeepTheWordComingBack() {
        val rows = run(listOf(2, 2, 1, 2, 3, 2, 1, 2))
        assertEquals(listOf(1, 3, 1, 2, 6, 10, 1, 4), rows.map { it.third })  // unchanged
        // Difficulty approaches the ceiling rather than exceeding it.
        assertTrue("difficulty must stay within 1..10", rows.all { it.second in 1.0..10.0 })
        assertClose(9.8636, rows[7].second, 1e-4)
    }

    @Test
    fun theIntervalFollowsTheStabilityNotAFixedLadder() {
        // Two words with the same number of reviews must diverge if their answers
        // differed. A fixed ladder would give them the same interval.
        val easy = run(listOf(4, 4, 4))
        val hard = run(listOf(2, 2, 2))
        assertTrue(
            "an Easy word should be scheduled further out than a Hard one: " +
                "${easy.last().third} vs ${hard.last().third}",
            easy.last().third > hard.last().third,
        )
    }

    @Test
    fun theCurveStartsAtOneAndDecays() {
        assertEquals(1.0, FSRS.forgettingCurve(10.0, 0.0), 1e-9)
        // Ten days out from a stability of ten, recall is lower than at one day.
        assertTrue(FSRS.forgettingCurve(10.0, 10.0) < FSRS.forgettingCurve(10.0, 1.0))
        // A stable word decays more slowly than an unstable one.
        assertTrue(
            "a stable word should retain more at 30 days",
            FSRS.forgettingCurve(100.0, 30.0) > FSRS.forgettingCurve(2.0, 30.0)
        )
    }

    @Test
    fun initialValuesComeFromTheReferenceParameters() {
        // parameters[rating - 1] and parameters[4] - e^(parameters[5]*(rating-1)) + 1
        assertClose(0.212, FSRS.initialStability(FSRS.GRADE_AGAIN), 1e-9)
        assertClose(1.2931, FSRS.initialStability(FSRS.GRADE_HARD), 1e-9)
        assertClose(2.3065, FSRS.initialStability(FSRS.GRADE_GOOD), 1e-9)
        assertClose(8.2956, FSRS.initialStability(FSRS.GRADE_EASY), 1e-9)

        assertClose(6.4133, FSRS.initialDifficulty(FSRS.GRADE_AGAIN), 1e-3)
        // 5.1122 was the oracle's ROUNDED output; the true value is 5.1121707...
        assertClose(5.1121707, FSRS.initialDifficulty(FSRS.GRADE_HARD), 1e-5)
        assertClose(2.1181, FSRS.initialDifficulty(FSRS.GRADE_GOOD), 1e-3)
    }

    @Test
    fun difficultyStaysInsideItsBounds() {
        var d = 1.0
        repeat(200) { d = FSRS.nextDifficulty(d, FSRS.GRADE_AGAIN) }
        assertTrue("difficulty escaped above 10: $d", d <= 10.0)
        var e = 10.0
        repeat(200) { e = FSRS.nextDifficulty(e, FSRS.GRADE_EASY) }
        assertTrue("difficulty escaped below 1: $e", e >= 1.0)
    }

    @Test
    fun stabilityNeverGoesToZero() {
        var s = 1.0
        repeat(500) {
            s = FSRS.stabilityAfterFailure(10.0, s, 0.9)
            assertTrue("stability reached $s", s > 0.0)
        }
    }

    @Test
    fun everyIntervalIsAtLeastOneDay() {
        for (s in listOf(0.01, 0.1, 0.212, 1.0, 7.3, 100.0, 10000.0)) {
            assertTrue(
                "stability $s produced interval ${FSRS.nextInterval(s)}",
                FSRS.nextInterval(s) >= 1,
            )
        }
    }
}