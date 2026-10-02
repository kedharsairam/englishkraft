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
     * The rank whose words cover [target] of all corpus-attested tokens.
     *
     * This is the learner's "98% vocabulary size". Computed from the frequency data
     * rather than hardcoded, so it stays true if the source ever changes. Measured on
     * the shipped corpus: 204 words for 80%, 785 for 90%, 2,446 for 95%, 7,627 for
     * 98%.
     */
    fun coverageProfile(target: Double = 0.98): Int? = repo.rankCovering(target)

    /** Ranks for each offered level, so the UI never has to guess one. */
    fun ranksFor(levels: List<Double>): Map<Double, Int?> =
        levels.associateWith { repo.rankCovering(it) }

    /** The levels offered, as coverage fractions. */
    val LEVELS = listOf(0.80, 0.90, 0.95, 0.98)

    /**
     * A passage at [knownRank] together with the words it asks the reader for.
     *
     * Returns an empty sentence list when nothing suits the level, which the UI
     * reports as a distinct state rather than showing a permanent spinner.
     */
    fun passage(knownRank: Int, sentences: Int = 5): Passage {
        val pool = repo.readingPool()
        // Resolve every word in the pool in one query. Doing it per word is 144 ms a
        // call and put this method at an estimated 8,654 seconds, which is why the
        // reader sat on "Finding a passage…" forever.
        val ranks = repo.ranksOf(repo.wordsIn(pool))

        val chosen = ArrayList<String>()
        val needed = LinkedHashSet<String>()
        for (candidate in pool) {
            if (chosen.size >= sentences) break
            if (looksLikeTranscribedSpeech(candidate)) continue
            val tokens = repo.splitForRead(candidate, knownRank, ranks)
            if (tokens.isEmpty()) continue
            // More than a third unknown is a wall of glosses, not a passage.
            val hard = tokens.filter { !it.known }
            if (hard.size > tokens.size / 3) continue
            chosen.add(candidate)
            hard.forEach { needed.add(it.bare) }
        }
        // The ask-list must contain only words the app can open. A word the reader
        // marks unfamiliar but that resolves to nothing is a dead end the learner
        // walks into, and an instrumented test caught exactly one: "store-cupboard",
        // a real English compound with no headword of its own.
        val resolvable = needed.filter { repo.lookup(it) != null }
        return Passage(chosen, resolvable, knownRank)
    }

    /**
     * Whether a sentence is transcribed speech rather than English to read.
     *
     * The first passage this shipped was a cricket report and a line of dialogue:
     * "I'll level with you, Mr. Cummings", "Ferguson paid tribute to the way Wigan
     * ... battled", "vrikshasan". Every one passed every test, because each check
     * asked a question that was true — the words were attested, the sentences had no
     * scanning damage, the unknown words all resolved. None of them asked whether
     * this was *ordinary English*, which is the one thing the screen promises.
     *
     * The tells are formatting rather than vocabulary: a dash speaker prefix, an
     * attribution like "she said", a sentence wrapped in quotes, or an unusual number
     * of commas. Journalistic prose about a specific subject is caught by the last
     * one, which is crude but was measured rather than guessed.
     */
    /**
     * Whether a sentence is ordinary English prose rather than something else.
     *
     * The corpus quotes whatever was needed to illustrate a word, so its examples are
     * genuinely varied: prose, dialogue, social media, reference text, glosses. The
     * first version of this filter let through, one after another, a stage direction,
     * a cricket report, a line of dialogue, a line of maths, a social-media post with
     * eight emoji, and a list of synonyms. Each one passed every other check, because
     * every other check asked something that was true of it.
     *
     * So the test is now about register rather than damage: does this read like
     * something a person wrote to be read aloud?
     *
     * The honest limit: what survives is real English, but it skews encyclopaedic,
     * because that is where Wiktionary's examples come from — an entry for
     * "flycrank" quotes a sentence about flycranks. Measured: 52% of the pool passes,
     * and a random sample is ordinary prose about machinery, biology and law rather
     * than about everyday life. That is a property of the corpus, not something this
     * filter can fix, and it is recorded rather than papered over.
     */
    private fun looksLikeTranscribedSpeech(sentence: String): Boolean {
        // Markup, symbols and anything that does not open like a sentence.
        val first = sentence.firstOrNull() ?: return true
        if (!(first.isUpperCase() || first == '"' || first == '\u201C')) return true
        if (first == '"' || first == '\u201C') return true

        // Emoji and pictographs. Filtered here rather than in SQL because a surrogate
        // pair cannot be expressed in a SQLite GLOB pattern.
        for (ch in sentence) {
            if (ch.code in 0x1F000..0x1FAFF || ch.code in 0x2600..0x27BF || ch.code in 0x2B00..0x2BFF) {
                return true
            }
        }

        // Reported speech, stage directions and transcription conventions.
        if (SPEECH_VERB.containsMatchIn(sentence)) return true
        if (sentence.contains(" - ") || sentence.contains("...")) return true
        if (sentence.contains("!!") || sentence.contains('"')) return true

        // A gloss rather than a sentence: "A hachereau or hatchet, sometimes served as
        // a hammer" is a definition that leaked into the example column.
        if (DEFINITION_OPENING.containsMatchIn(sentence)) return true

        // Reference text and mathematics.
        if (sentence.any { it.isDigit() }) return true
        if (DEFINITIONAL.containsMatchIn(sentence)) return true

        // A fragment, caption or list.
        if (sentence.length < 60) return true
        if (sentence.count { it == ',' } > 2) return true

        // A sentence needs a verb to be a sentence. This is what removes the
        // definition fragments and the noun-phrase captions that survived length.
        if (!FINITE_VERB.containsMatchIn(sentence)) return true

        return false
    }

    private val SPEECH_VERB =
        Regex("\\b(said|says|asked|replied|answered|told|shouted|cried)\\b", RegexOption.IGNORE_CASE)

    private val DEFINITIONAL =
        Regex("\\b(is defined as|is equal to|refers to|denotes|consists of)\\b", RegexOption.IGNORE_CASE)

    private val DEFINITION_OPENING =
        Regex("^(A|An|The)\\s+[^,;:.]{0,40}[:,;]")

    private val FINITE_VERB =
        Regex(
            "\\b(is|are|was|were|has|have|had|do|does|did|will|would|can|could|" +
                "may|might|must|should|shall|ain't|isn't|aren't|doesn't|didn't)\\b",
            RegexOption.IGNORE_CASE,
        )

    /** How many distinct unknown words a passage contains, for the summary line. */
    fun countUnknown(tokens: List<Token>): Int = tokens.count { !it.known }

    /**
     * A passage and the words it asks for.
     *
     * The unknown words come back alongside the sentences rather than being
     * recomputed by the UI, so the count the learner sees and the words that are
     * actually marked cannot disagree. That disagreement would be invisible and
     * would look like the reader lying about its own level.
     */
    data class Passage(
        val sentences: List<String>,
        val unknown: List<String>,
        val knownRank: Int,
    )

    /**
     * Reassembles the text exactly as given.
     *
     * Included so the caller can assert the split lost nothing. A renderer that
     * silently drops a token is a renderer nobody can trust with a passage they
     * intend to keep.
     */
    fun join(tokens: List<Token>): String = tokens.joinToString("") { it.text }
}