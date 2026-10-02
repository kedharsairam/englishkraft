package com.krafttools.englishkraft.data

import kotlin.math.E
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * FSRS — the Free Spaced Repetition Scheduler.
 *
 * A port of `open-spaced-repetition/py-fsrs` (MIT), the same algorithm Anki has driven
 * since 2022, so a schedule computed here matches the one Anki computes from the same
 * answers.
 *
 * **Every constant and formula below was transcribed from the reference, not
 * recalled.** An earlier draft was written from memory and it was wrong everywhere:
 * 0.40255 and 1.18385 instead of the real 0.212 and 1.2931, a decay of -0.5 instead
 * of -0.1542, a factor of 19/81 instead of 0.9^(1/-decay) - 1, and an interval
 * formula that was not the algorithm at all. A scheduler that produces
 * plausible-looking intervals from wrong constants is worse than no scheduler,
 * because it looks like it works.
 *
 * Source: `fsrs/scheduler.py` in that repository, `DEFAULT_PARAMETERS` plus
 * `_next_interval`, `_next_difficulty`, `_next_stability`, `_next_forget_stability`,
 * `_next_recall_stability`, `_initial_stability` and `_initial_difficulty`.
 *
 * Why not a Leitner box: it carries one number per word, so a word that is easy for
 * this learner and hard for everyone else is scheduled exactly like one they find
 * impossible. FSRS keeps stability and difficulty separately and updates both from
 * every answer.
 */
object FSRS {

    const val GRADE_AGAIN = 1
    const val GRADE_HARD = 2
    const val GRADE_GOOD = 3
    const val GRADE_EASY = 4

    /**
     * Probability of recall at which a card comes back.
     *
     * Higher means more reviews and less forgetting. 0.9 is the reference default,
     * which suits a learner opening the app a few times a week rather than daily.
     */
    const val DESIRED_RETENTION = 0.90

    /**
     * The 21 reference parameters, in the reference order.
     *
     * Indices are named constants rather than bare numbers below, because a bare
     * 8.2956 in an expression tells nobody what it is for.
     */
    private val P = doubleArrayOf(
        0.212, 1.2931, 2.3065, 8.2956, 6.4133, 0.8334, 3.0194, 0.001,
        1.8722, 0.1666, 0.796, 1.4835, 0.0614, 0.2629, 1.6483, 0.6014,
        1.8729, 0.5425, 0.0912, 0.0658, 0.1542,
    )

    private const val DECAY = 0.1542              // P[20]

    /**
     * The curve's scale: `_FACTOR = 0.9 ** (1 / -_DECAY) - 1` in the reference.
     *
     * It depends on desired retention, so it is derived rather than a constant — 0.9
     * appears twice in that expression deliberately: the reference ties the scale to
     * the retention target, not to a fixed number.
     */
    private val SCALE = 0.9.pow(1.0 / -DECAY) - 1.0

    private const val MIN_DIFFICULTY = 1.0
    private const val MAX_DIFFICULTY = 10.0
    private const val MIN_STABILITY = 0.01
    private const val MAXIMUM_INTERVAL_DAYS = 36500

    // --- the curve ----------------------------------------------------------

    /** Probability of recall after [days] at the given [stability]. */
    fun forgettingCurve(stability: Double, days: Double): Double {
        if (days <= 0.0) return 1.0
        return (1.0 + SCALE * days / stability).pow(-DECAY)
    }

    /** Whole days until recall falls to [DESIRED_RETENTION]. */
    fun nextInterval(stability: Double): Int {
        val raw = (stability / SCALE) * (DESIRED_RETENTION.pow(1.0 / -DECAY) - 1.0)
        return raw.roundToInt().coerceIn(1, MAXIMUM_INTERVAL_DAYS)
    }

    // --- initial values -----------------------------------------------------

    /** `parameters[rating - 1]`: the first answer sets the starting stability. */
    fun initialStability(grade: Int): Double =
        P[(grade - 1).coerceIn(0, 3)].coerceAtLeast(MIN_STABILITY)

    /** `parameters[4] - e^(parameters[5] * (rating - 1)) + 1`. */
    fun initialDifficulty(grade: Int): Double =
        (P[4] - E.pow(P[5] * (grade - 1)) + 1.0).coerceIn(MIN_DIFFICULTY, MAX_DIFFICULTY)

    // --- difficulty ---------------------------------------------------------

    /**
     * `_next_difficulty` from the reference.
     *
     * Two stages: a linear damping term, then mean reversion towards the difficulty an
     * "Easy" first answer would give. Without the reversion, difficulty drifts and
     * eventually every word becomes either trivial or impossible.
     */
    fun nextDifficulty(difficulty: Double, grade: Int): Double {
        fun linearDamping(delta: Double, d: Double): Double = (10.0 - d) * delta / 9.0
        fun meanReversion(a1: Double, a2: Double): Double = P[7] * a1 + (1.0 - P[7]) * a2

        val arg1 = P[4] - E.pow(P[5] * (GRADE_EASY - 1)) + 1.0   // initial for Easy
        val delta = -(P[6] * (grade - 3.0))
        val arg2 = difficulty + linearDamping(delta, difficulty)
        return meanReversion(arg1, arg2).coerceIn(MIN_DIFFICULTY, MAX_DIFFICULTY)
    }

    // --- stability ----------------------------------------------------------

    /**
     * Stability after forgetting. `_next_forget_stability`.
     *
     * The minimum of a long-term term and a short-term term. A lapse costs stability
     * but never erases it, so a word that took ten reviews to learn is not thrown back
     * to the beginning by one miss.
     */
    fun stabilityAfterFailure(
        difficulty: Double,
        stability: Double,
        retrievability: Double,
    ): Double {
        val longTerm = P[11] *
            (difficulty.pow(-P[12])) *
            (((stability + 1.0).pow(P[13])) - 1.0) *
            (E.pow((1.0 - retrievability) * P[14]))
        val shortTerm = stability / E.pow(P[17] * P[18])
        return minOf(longTerm, shortTerm).coerceAtLeast(MIN_STABILITY)
    }

    /** Stability after recalling. `_next_recall_stability`. */
    fun stabilityAfterRetrieval(
        difficulty: Double,
        stability: Double,
        retrievability: Double,
        grade: Int,
    ): Double {
        val hardPenalty = if (grade == GRADE_HARD) P[15] else 1.0
        val easyBonus = if (grade == GRADE_EASY) P[16] else 1.0
        return stability * (
            1.0 +
                E.pow(P[8]) *
                (11.0 - difficulty) *
                stability.pow(-P[9]) *
                ((E.pow((1.0 - retrievability) * P[10])) - 1.0) *
                hardPenalty *
                easyBonus
            )
    }

    /**
     * The scheduled result of answering [grade].
     *
     * [daysSinceReview] is the gap since the card was last shown; pass 0 for a card
     * being seen for the first time, in which case the initial values apply.
     */
    fun next(
        stability: Double,
        difficulty: Double,
        daysSinceReview: Double,
        grade: Int,
        seenBefore: Boolean,
    ): Triple<Double, Double, Int> {
        if (!seenBefore) {
            val s = initialStability(grade)
            val d = initialDifficulty(grade)
            // The first interval is solved from the starting stability, exactly as every
            // other interval is. Hard-coding 1 here made Good and Easy show the same
            // thing on a new word -- "1 day" and "1 day" -- so two of the four buttons
            // were indistinguishable and the learner was choosing between labels.
            // Solved properly: Good gives 2 days, Easy gives 8.
            return Triple(s, d, nextInterval(s))
        }

        val retrievability = forgettingCurve(stability, daysSinceReview)
        val newStability = if (grade == GRADE_AGAIN) {
            stabilityAfterFailure(difficulty, stability, retrievability)
        } else {
            stabilityAfterRetrieval(difficulty, stability, retrievability, grade)
        }.coerceAtLeast(MIN_STABILITY)

        val newDifficulty = nextDifficulty(difficulty, grade)
        return Triple(newStability, newDifficulty, nextInterval(newStability))
    }
}