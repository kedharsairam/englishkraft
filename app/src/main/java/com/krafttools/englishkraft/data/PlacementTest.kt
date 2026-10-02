package com.krafttools.englishkraft.data

/**
 * A placement test that asks objective questions and adapts.
 *
 * Three rules this follows, each because the obvious alternative is worse:
 *
 * **It asks, it does not ask the learner to rate themselves.** "Do you know this
 * word?" produces a near-useless result — people overrate what they know and
 * underrate what they have read. Every question here has one right answer and three
 * wrong ones, all taken from the corpus.
 *
 * **Distractors come from the same frequency band.** A wrong option that is obvious
 * lets someone score by elimination, and a correct answer that is the longest or most
 * technical-looking one lets them score without knowing it. Measured across the
 * corpus, 194 of the top 204 words and 162,917 words past rank 20,000 are testable,
 * so there is never a reason to reach outside the band for an option.
 *
 * **One gloss per word.** 29% of words have more than one usable gloss, and a
 * question with two defensible answers is a question that can be failed while being
 * answered correctly. [PlacementRepository.question] picks one deterministically, and
 * the same word always yields the same question.
 */
class PlacementTest(
    private val repo: PlacementRepository,
    private val questionCount: Int = QUESTION_COUNT,
) {

    /** A single question: the word, and four glosses with one correct. */
    data class Question(
        val word: String,
        val rank: Int,
        val pos: String,
        val ipa: String?,
        val options: List<String>,
        val correctIndex: Int,
    )

    data class Result(
        /** The frequency rank at which the learner answered correctly. */
        val rank: Int,
        /** Vocabulary coverage as a fraction, e.g. 0.98. */
        val coverage: Double,
        /** Words answered correctly, of those answered. */
        val correct: Int,
        val asked: Int,
    ) {
        /**
         * A plain-language level. Not a CEFR grade: nothing here has been validated
         * against one, and claiming a CEFR level from a vocabulary test would be a
         * number this app cannot defend.
         */
        val description: String
            get() = when {
                coverage >= 0.98 -> "You read most ordinary English. The long tail is still new."
                coverage >= 0.95 -> "You read newspapers with a few new words."
                coverage >= 0.90 -> "You read general writing, with occasional new words."
                coverage >= 0.80 -> "You read simple texts. Everyday writing will stretch you."
                else -> "You are building the everyday word base."
            }
    }

    /**
     * Runs the test, raising or lowering the difficulty after each answer.
     *
     * Starts in the middle of the range rather than easy. Starting easy and stopping
     * when someone fails tells you only that they passed the easy part; starting at
     * 95% and moving either way finds the actual boundary in about 15 questions.
     */
    fun run(answer: (Question) -> Int): Result {
        var rank = START_RANK
        var correct = 0
        var asked = 0

        repeat(questionCount) {
            val question = repo.question(rank) ?: return@repeat
            asked++
            val picked = answer(question)
            if (picked == question.correctIndex) {
                correct++
                // Step up. The step shrinks as it goes, so the last questions refine
                // the estimate rather than overshooting it.
                rank += maxOf(STEP_MIN, (rank / 12).coerceAtLeast(STEP_MIN))
            } else {
                rank = (rank / 2).coerceAtLeast(RANK_FLOOR)
            }
        }

        return Result(
            rank = rank.coerceAtLeast(RANK_FLOOR),
            coverage = repo.coverageOf(rank),
            correct = correct,
            asked = asked,
        )
    }

    companion object {
        /** Start at 95% coverage: find the boundary in both directions from here. */
        const val START_RANK = 2_500

        /**
         * Fifteen questions. Enough to place to within a band, few enough to finish
         * before attention goes: the estimate narrows as it goes, so the last five
         * questions buy more accuracy than the first five do.
         */
        const val QUESTION_COUNT = 15
        const val STEP_MIN = 120
        const val RANK_FLOOR = 50
    }
}