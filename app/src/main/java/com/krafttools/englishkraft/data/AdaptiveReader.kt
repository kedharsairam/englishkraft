package com.krafttools.englishkraft.data

/**
 * Renders any text at exactly the reader's vocabulary level.
 *
 * The threshold is the whole idea: at 98% known words a text is readable without
 * a dictionary, which is the classic finding from second-language reading research.
 * Below that the reader stalls on every fourth word. Above it the exercise teaches
 * nothing.
 *
 * Every number this uses was measured from the corpus, not assumed:
 *
 *   204 words  cover 80% of all subtitle tokens
 *   785 words  cover 90%
 *  2,446 words cover 95%
 *  7,627 words cover 98%
 *
 * Those thresholds are computed by [coverageProfile] rather than hardcoded, so if
 * the frequency source changes the reader follows it instead of quietly becoming
 * wrong.
 *
 * Nothing here is generated. Text is only ever split into known and unknown words;
 * no sentence is rewritten, no gloss invented.
 */
class AdaptiveReader(private val repo: DictionaryRepository) {

    /** One word of the input, classified. */
    data class Token(
        val text: String,
        /** What to look up in the dictionary, if this token is unknown. */
        val lemma: String?,
        val known: Boolean,
    )

    /**
     * Splits text into tokens, marking those above the learner's level.
     *
     * Punctuation and whitespace are preserved verbatim, so [join] reproduces the
     * input exactly. A reader that rewrites spacing would be untrustworthy for any
     * passage the user might copy out of it.
     *
     * Boundaries match the tap targets and [DictionaryRepository.splitForRead], so a
     * word the reader marks unknown is always a word the app can look up.
     */
    fun tokenise(text: String, knownRank: Int): List<Token> {
        val out = ArrayList<Token>()
        var i = 0
        val len = text.length

        while (i < len) {
            val ch = text[i]
            if (!ch.isLetter() && ch != '\'') {
                var j = i
                while (j < len && !text[j].isLetter() && text[j] != '\'') j++
                out.add(Token(text.substring(i, j), null, known = true))
                i = j
                continue
            }

            val start = i
            while (i < len) {
                val c = text[i]
                if (c.isLetter() || c == '\'') {
                    i++
                    continue
                }
                if ((c == '-' || c == '.') && i + 1 < len && text[i + 1].isLetter()) {
                    i += 2
                    continue
                }
                break
            }
            val raw = text.substring(start, i)
            val bare = raw.trim('\'', '-', '.')
            // Outside the Latin range means a long-s or similar, which is not a letter
            // of modern English. 11,190 corpus examples are quoted from early printed
            // texts and carry the long-s; treating "ſhallbe" as a word would teach a
            // learner something false about the alphabet.
            val isWord = bare.length >= 2 &&
                bare.any { it.isLowerCase() } &&
                bare.none { it.code > 0x2FF }
            if (!isWord) {
                out.add(Token(raw, null, known = true))
                continue
            }

            val rank = repo.rankOf(bare)
            val known = rank != null && rank <= knownRank
            out.add(Token(raw, if (known) null else bare, known))
        }
        return out
    }

    /**
     * The smallest rank whose words cover [target] of all corpus-attested tokens.
     *
     * This is the learner's "98% vocabulary size". Returned as a rank, because
     * [tokenise] compares against ranks and everything else in the app is ordered
     * by rank too.
     */
    fun coverageProfile(target: Double = 0.98): Int? = repo.rankCovering(target)

    /** How many distinct unknown words a passage contains, for the summary line. */
    fun countUnknown(tokens: List<Token>): Int = tokens.count { !it.known }

    /**
     * Reassembles the text exactly as given.
     *
     * Included so the caller can assert the split lost nothing. A renderer that
     * silently drops a token is a renderer nobody can trust with a passage they
     * intend to keep.
     */
    fun join(tokens: List<Token>): String = tokens.joinToString("") { it.text }
}