package com.krafttools.englishkraft.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Reader tests on the real corpus.
 *
 * Instrumented because `android.database.sqlite` does not exist off-device. The
 * point of these is one question a JVM test cannot answer: does the reader produce a
 * real passage on a phone, from the shipped database, within a sane time?
 *
 * The timing assertions are deliberate. A passage search that resolved every word
 * individually ran at an estimated 8,654 seconds, which reads as "slow" and is
 * actually broken, and a test with no time bound passes while a screen spins
 * forever.
 *
 * [looksLikeEnglish] restates the production rule rather than calling it. If the test
 * called the real function it would agree with any change to it, including a change
 * that stops filtering scanning damage altogether. That duplication has already cost
 * two false passes here: the rule was fixed in production while the test kept the
 * old copy and quietly disagreed.
 */
@RunWith(AndroidJUnit4::class)
class AdaptiveReaderTest {

    private lateinit var repo: DictionaryRepository

    @Before
    fun open() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val installed = DictionaryInstaller(context).install { _, _ -> }
        val file = (installed as? DictionaryInstaller.State.Ready)?.file!!
        repo = DictionaryRepository(openDictionary(file))
    }

    // --- the rule, restated -----------------------------------------------

    /** Mirrors DictionaryRepository.looksLikeEnglish. Keep the two in step. */
    private fun looksLikeEnglish(word: String): Boolean {
        // The abbreviation check must come first. Trimming the period out of "d.j"
        // leaves "dj", which has a vowel in it, and the rule would accept the damage.
        val titles = setOf("Mr","Mrs","Ms","Dr","Prof","St","Rev","Hon","Gen","Col",
            "Capt","Lt","Sgt","Maj","Cpl","Pvt","Fr","Sr","Jr","Esq","Messrs","Mmes",
            "Mme","Mlle","Mt","Ft","Rabbi","Bish","Gov")
        if (word.trimEnd('.') in titles) return true
        if (Regex("^[A-Za-z]\\.[A-Za-z]$").matches(word)) return false
        val stem = word.trimEnd('\'')
        if (stem.length < 2) return false
        // A VOWEL must appear, not merely a letter: every character of "cnvs" is a
        // letter, so `any { it in 'a'..'z' }` accepts the damage it is meant to catch.
        for (ch in stem) {
            if (ch in 'a'..'z' || ch in 'A'..'Z') {
                if (ch.lowercaseChar() in "aeiouy") return true
            }
        }
        return word.endsWith('\'')
    }

    // --- levels ------------------------------------------------------------

    @Test
    fun levelsResolveToRealRanks() = runBlocking {
        val reader = AdaptiveReader(repo)
        val ranks = reader.ranksFor(reader.LEVELS)
        for (level in reader.LEVELS) {
            assertTrue("no rank for $level", ranks[level] != null)
        }
        // A higher coverage target must mean a larger vocabulary, never smaller.
        val ordered = reader.LEVELS.sorted().map { ranks[it]!! }
        assertTrue(
            "coverage ranks must increase: $ordered",
            ordered.zipWithNext().all { (a, b) -> b > a },
        )
    }

    // --- the passage -------------------------------------------------------

    @Test
    fun passageIsBuiltAndEveryUnknownWordIsResolvable() = runBlocking {
        val reader = AdaptiveReader(repo)
        val rank = reader.ranksFor(reader.LEVELS)[0.98]!!
        val started = System.currentTimeMillis()
        val passage = reader.passage(rank, sentences = 5)
        val elapsed = System.currentTimeMillis() - started

        assertTrue(
            "a passage at 98% must exist; got ${passage.sentences.size} sentences",
            passage.sentences.isNotEmpty(),
        )
        assertTrue(
            "passage search took ${elapsed}ms; per-word ranking put this at ~2h",
            elapsed < 20_000,
        )
        println(
            "PASSAGE in ${elapsed}ms: ${passage.sentences.size} sentences, " +
                "${passage.unknown.size} new words"
        )
        for (sentence in passage.sentences) {
            println("  SENTENCE: $sentence")
        }

        // Every word the reader asks for must be a word the app can open. Marking a
        // word unfamiliar that resolves to nothing is a dead end for the learner, and
        // an instrumented test caught exactly one: "store-cupboard", a real English
        // compound with no headword of its own.
        for (word in passage.unknown) {
            assertTrue(
                "reader asked for unknown word '$word' which cannot be resolved",
                repo.lookup(word) != null,
            )
        }
    }

    @Test
    fun everyLevelYieldsAPassage() = runBlocking {
        val reader = AdaptiveReader(repo)
        val ranks = reader.ranksFor(reader.LEVELS)
        for (level in reader.LEVELS) {
            val p = reader.passage(ranks[level]!!, sentences = 3)
            assertTrue("no passage at ${(level * 100).toInt()}%", p.sentences.isNotEmpty())
        }
    }

    @Test
    fun theHardestLevelDoesNotDemandMoreNewWords() = runBlocking {
        val reader = AdaptiveReader(repo)
        val ranks = reader.ranksFor(reader.LEVELS)
        val easy = reader.passage(ranks[0.80]!!, sentences = 5)
        val hard = reader.passage(ranks[0.98]!!, sentences = 5)
        val easyPer = easy.unknown.size.toDouble() / easy.sentences.size.coerceAtLeast(1)
        val hardPer = hard.unknown.size.toDouble() / hard.sentences.size.coerceAtLeast(1)
        assertTrue(
            "80% asked for $easyPer new words/sentence, 98% asked for $hardPer; inverted",
            hardPer <= easyPer + 0.5,
        )
    }

    // --- what must never reach a learner -----------------------------------

    @Test
    fun noShippedPassageContainsScanningDamage() = runBlocking {
        // Asserted on what the reader SHIPS, not on the raw candidate pool. A test
        // over the pool checks a layer the pool never passes through, which is how
        // this hid: the vowel rule lived in splitForRead while readingPool() returned
        // unfiltered rows.
        val reader = AdaptiveReader(repo)
        val rank = reader.ranksFor(reader.LEVELS)[0.98]!!
        repeat(6) {
            val p = reader.passage(rank, sentences = 5)
            for (sentence in p.sentences) {
                val damaged = sentence.split(Regex("[^A-Za-z']+"))
                    .filter { it.length >= 2 && !looksLikeEnglish(it) }
                assertTrue(
                    "shipped passage contains scanning damage $damaged in: $sentence",
                    damaged.isEmpty(),
                )
            }
        }
    }

    @Test
    fun noShippedPassageCarriesDiacritics() = runBlocking {
        // The corpus quotes translated literature, so "brāhmaṇa" and "Virāṭa" appear.
        // They are real text and not scanning damage, but a macron is not how English
        // is written and must not be handed to a learner learning English.
        val reader = AdaptiveReader(repo)
        val rank = reader.ranksFor(reader.LEVELS)[0.98]!!
        repeat(6) {
            val p = reader.passage(rank, sentences = 5)
            for (sentence in p.sentences) {
                val marked = sentence.filter { it.code in 0x300..0x36F || it.code in 0x1E00..0x1EFF }
                assertTrue(
                    "shipped passage carries diacritics ('$marked') in: $sentence",
                    marked.isEmpty(),
                )
            }
        }
    }

    @Test
    fun elidedSpeechIsNotConfusedWithScanningDamage() = runBlocking {
        // "plannin'", "y'know", "makin'" are ordinary transcribed speech. An earlier
        // vowel rule rejected 52 real sentences for exactly this — it was filtering
        // the language rather than the damage.
        for (word in listOf("plannin'", "y'know", "makin'", "goin'", "Mr", "Mrs", "Dr")) {
            assertTrue("'$word' is valid English and must pass", looksLikeEnglish(word))
        }
        for (damage in listOf("d.j", "u.s", "cnvs", "dd", "dft")) {
            assertTrue("'$damage' is an abbreviation or damage and must fail", !looksLikeEnglish(damage))
        }
    }

    // --- performance -------------------------------------------------------

    @Test
    fun batchedRankingIsFast() = runBlocking {
        val pool = repo.readingPool().take(200)
        val words = repo.wordsIn(pool)
        val started = System.currentTimeMillis()
        val ranks = repo.ranksOf(words)
        val elapsed = System.currentTimeMillis() - started
        assertTrue("resolved ${words.size} words in ${elapsed}ms", ranks.isNotEmpty())
        assertTrue(
            "batched ranking of ${words.size} words took ${elapsed}ms; must be seconds",
            elapsed < 5_000,
        )
    }

    @Test
    fun shippedPassagesAreOrdinaryEnglishNotTranscripts() = runBlocking {
        // The first version passed every other test while shipping a cricket report
        // and a stage direction. Each existing check asked something true; none asked
        // whether the text was everyday English, which is the thing the screen claims.
        val reader = AdaptiveReader(repo)
        val rank = reader.ranksFor(reader.LEVELS)[0.98]!!
        val problems = mutableListOf<String>()
        repeat(8) {
            val p = reader.passage(rank, sentences = 5)
            for (s in p.sentences) {
                if (s.startsWith("- ") || s.contains(" - ")) problems += "dash prefix: $s"
                if (s.startsWith('"') || s.endsWith('"')) problems += "quoted: $s"
                if (Regex("\\b(said|asked|replied|answered)\\b").containsMatchIn(s))
                    problems += "attribution: $s"
                if (s.length < 30) problems += "too short: $s"
            }
        }
        assertTrue("transcript-like sentences shipped: ${problems.take(3)}", problems.isEmpty())
    }

    @Test
    fun candidatePoolIsBigEnoughToFilter() = runBlocking {
        val pool = repo.readingPool()
        assertTrue("reader pool is only ${pool.size} sentences", pool.size >= 500)
    }
}