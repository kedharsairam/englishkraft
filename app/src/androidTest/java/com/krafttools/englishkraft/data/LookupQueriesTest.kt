package com.krafttools.englishkraft.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Query tests against the REAL corpus, on a real device.
 *
 * Instrumented, not JVM. android.database.sqlite is not implemented on the JVM --
 * it throws "not mocked" -- and mocking it would test the mock rather than the SQL.
 * These assertions are about returning the right word for "running" across 1.4
 * million forms on the actual SQLite build the phone ships, which is the only
 * failure mode that matters and the only place it can be observed.
 */
@RunWith(AndroidJUnit4::class)
class LookupQueriesTest {

    private lateinit var db: android.database.sqlite.SQLiteDatabase
    private lateinit var repo: DictionaryRepository

    @Before
    fun openCorpus() {
        // Installed through the real first-launch path, so this also proves the
        // 427 MB copy completes and the file that lands is the whole corpus.
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val installer = DictionaryInstaller(context)
        val outcome = kotlinx.coroutines.runBlocking { installer.install { _, _ -> } }
        val file = (outcome as? DictionaryInstaller.State.Ready)?.file
        assertNotNull("the dictionary must install on device", file)
        db = openDictionary(file!!)
        repo = DictionaryRepository(db)
    }

    /**
     * No quoted Early Modern English reaches a learner.
     *
     * The filter that should have caught this was `code > 0x2FF`, which cannot see
     * U+017F. 4,028 long-s sentences were passing it. This asserts on the count over
     * the whole corpus rather than on one example, because the original check would
     * have passed any test that tried a single string.
     */
    @Test
    fun noQuotedEarlyModernEnglishIsShownAsAUsage() {
        assertEquals(
            "long-s examples are reaching the entry screen",
            0,
            repo.countStraysAccepted("\\u017F"),
        )
    }

    /**
     * No bibliographic citation is shown as a usage.
     *
     * Found by using the app: the entry for "ephemeral" opened on
     * "1821-1822, Vicesimus Knox, Remarks on the tendency of certain Clauses in a
     * Bill...". It is a bibliography entry wearing a sentence's clothes, and there are
     * 599 of them. Matched on the comma that follows the year, because a bare year at
     * the start of a sentence is ordinary English.
     */
    /**
 * No entry hands the screen an example the filter rejects.
 *
 * This is the test that matters, and it is written against the lookup rather than
 * against the predicate. Testing `isUsableExample` directly is what let the bug
 * through: the predicate was correct and correct-looking, and was simply never called
 * on the path most senses take. A sense carrying exactly one example was assigned
 * `first.example` straight from the database row, so the filter was inert for most of
 * the dictionary.
 */
@Test
fun noLookupReturnsAnExampleTheFilterRejects() {
    val heads = repo.commonHeadwords(3000)
    assertTrue("the corpus must have that many common words", heads.size >= 3000)

    var checked = 0
    for (headword in heads) {
        val found = repo.lookup(headword) ?: continue
        for (sense in found.senses) {
            for (usage in listOfNotNull(sense.example) + sense.examples) {
                checked++
                assertTrue(
                    "'$headword' returned an example the filter rejects: $usage",
                    repo.isUsableUsage(usage),
                )
            }
        }
    }
    assertTrue(
        "the loop checked no examples at all, so it proved nothing",
        checked > 1000,
    )
}

@Test
    fun noCitationIsShownAsAUsage() {
        assertEquals(
            "year-and-author citations are reaching the entry screen",
            0,
            repo.countStraysAccepted(", Walter Raleigh,"),
        )
        assertFalse(
            "a ranged citation",
            repo.isUsableUsage(
                "1603-16, Walter Raleigh, The History of the World.\\n" +
                    "They promise to absist from their purpose of making a war.",
            ),
        )
        assertFalse(
            "a single-year citation with a page number",
            repo.isUsableUsage(
                "2005, Paul Mitchell, The Favourite, page 145,\\n" +
                    "There are three kinds of silence.",
            ),
        )
        // The rule must not eat ordinary sentences that happen to open with a number.
        assertTrue(
            "a sentence opening with a number is not a citation",
            repo.isUsableUsage("Three of the runners finished before the rain started."),
        )
    }

    @Test
    fun theFilterKeepsGenuineAccentedEnglish() {
        // The fix must not become "reject everything non-ASCII". These are real English
        // examples, and 4,477 genuine ones were dropped alongside the 4,028 bad ones
        // when the range was first set too wide.
        assertTrue(
            "a French loanword is ordinary English",
            repo.isUsableUsage("She said a caf\u00e9 was open and asked for a cr\u00e8me br\u00fbl\u00e9e."),
        )
        assertTrue(
            "British spelling and money are ordinary English",
            repo.isUsableUsage("She colour-coded the files and paid \u00a340 for the ticket."),
        )
        assertTrue(
            "the oe ligature is ordinary English and must survive the fix",
            repo.isUsableUsage("The \u0153sophagus and the \u00c6sop fables are on the shelf."),
        )
    }

    @Test
    fun theFilterRefusesTheSpecificJunk() {
        assertFalse(
            "Early Modern English",
            repo.isUsableUsage("The couaitous de\u017fyre of riche men is euer vn\u017faciable."),
        )
        assertFalse("a bracketed ellipsis", repo.isUsableUsage("The rest [...] follows."))
        assertFalse(
            "a computer-science textbook",
            repo.isUsableUsage(
                "Notice that the NOT symbol is simply a BUF symbol followed by a bubble, " +
                    "and that the bubble represents logical inversion, which is the actual " +
                    "NOT gate in every one of the compilers that implement it this way " +
                    "rather than as a primitive of the language.",
        ),
        )
        assertFalse(
            "a Middle English yogh",
            repo.isUsableUsage("O wityng bath god and ill \u021dee suld be lauerds at \u021dfour will."),
        )
        assertFalse(
            "a quoted page of dialogue",
            repo.isUsableUsage("He said, \u201cyou shall each read a page by turns; so that Miss Short may have an opportunity.\u201d"),
        )
    }

    @Test
    fun corpusIsTheWholeThing() {
        val entries = repo.totalEntries()
        // A truncated copy opens and answers queries. This is the check that notices.
        assertTrue("expected >1.4M entries, got $entries", entries > 1_400_000)
    }

    @Test
    fun exactHeadwordResolves() {
        val lookup = repo.lookup("ephemeral")
        assertNotNull("ephemeral must resolve", lookup)
        assertEquals("ephemeral", lookup!!.entry.headword)
        assertTrue("must have senses", lookup.senses.isNotEmpty())
        assertFalse("no inflection notice for an exact word", lookup.inflected)
    }

    /**
     * The case that decides whether the app is usable.
     *
     * Most words a person looks up are inflected. If `running` does not reach `run`,
     * a large fraction of real lookups simply fail.
     */
    @Test
    fun inflectedFormResolvesToItsLemma() {
        val lookup = repo.lookup("running")
        assertNotNull("running must resolve", lookup)
        assertEquals("run", lookup!!.entry.headword.lowercase())
        assertTrue("must be flagged as inflected", lookup.inflected)
        assertEquals("running", lookup.typedForm)
    }

    @Test
    fun irregularInflectionsResolve() {
        // Only words whose ONLY senses point at another word. "better" is deliberately
        // absent: it looks like a pointer but has three senses of its own ("Greater
        // in amount", "Greater or lesser", "Healed"), so returning it is correct and
        // asserting it should reach "good" was asserting something false about English.
        val pairs = mapOf(
            "wolves" to "wolf",
            "wrote" to "write",
            "mice" to "mouse",
            "ran" to "run",
        )
        for ((typed, lemma) in pairs) {
            val lookup = repo.lookup(typed)
            assertNotNull("$typed must resolve", lookup)
            assertEquals("$typed must reach its lemma", lemma, lookup!!.entry.headword.lowercase())
        }
    }

    /** A word that does not exist must return null, never a wrong answer. */
    @Test
    fun nonsenseReturnsNull() {
        assertEquals(null, repo.lookup("zzxqvkjw"))
        assertEquals(null, repo.lookup(""))
        assertEquals(null, repo.lookup("   "))
    }

    @Test
    fun suggestionsAreFrequencyOrdered() {
        val results = repo.suggest("ru")
        assertTrue("expected suggestions for 'ru'", results.isNotEmpty())
        val ranks = results.mapNotNull { it.freqRank }
        assertEquals(
            "suggestions must come back in corpus-frequency order",
            ranks.sortedBy { it },
            ranks,
        )
    }

    @Test
    fun typoIsCorrectedOnlyWhenNothingMatches() {
        val exact = repo.lookup("receive")
        assertNotNull("receive exists, so didYouMean must not be consulted", exact)

        val typos = repo.didYouMean("recieve")
        assertTrue("recieve should suggest receive", typos.any { it.headword == "receive" })
    }

    /** Both sources' rows for one link must land on a single corroborated row. */
    @Test
    fun relationsDetectCorroboration() {
        val dog = repo.lookup("dog")!!
        val canine = dog.relations.firstOrNull { it.target == "canine" }
        assertNotNull("dog should have a hypernym canine", canine)
        assertTrue(
            "canine is recorded by both Wiktionary and WordNet",
            canine!!.corroborated,
        )
    }

    /**
     * A word's parents come from the entries that name it a hyponym, because
     * WordNet states a relation from the general side only.
     */
    @Test
    fun taxonomyWalkFindsParents() {
        val spaniel = repo.lookup("spaniel")!!
        val parents = spaniel.related.filter { it.rel == "hypernym" }
        assertTrue("spaniel must have a parent, got ${parents.map { it.headword }}", parents.isNotEmpty())

        // The bug this guards: the reverse branch selected r.target instead of the
        // referencing entry, so spaniel came back as its own hypernym.
        assertFalse(
            "a word must not be its own parent",
            parents.any { it.headword.equals("spaniel", ignoreCase = true) },
        )
    }

    @Test
    fun theSameWordUnderTwoPosIsNotListedTwice() {
        val cocker = repo.lookup("cocker spaniel")!!
        val parents = cocker.related.filter { it.rel == "hypernym" }.map { it.headword to it.pos }
        assertEquals(
            "parents must be distinct (headword, pos) pairs",
            parents.distinct(),
            parents,
        )
    }

    /** No serialised source object may reach the search index. */
    @Test
    fun noObjectReprInAnyResult() {
        for (word in listOf("dog", "ephemeral", "ubiquitous", "run")) {
            val lookup = repo.lookup(word) ?: continue
            lookup.senses.forEach {
                assertFalse("gloss contains an object repr", it.gloss.contains("_dis1"))
            }
            lookup.relations.forEach {
                assertFalse("relation target contains an object repr", it.target.contains("_dis1"))
                assertFalse("relation target contains a brace", it.target.contains("{"))
            }
        }
    }

    @Test
    fun gateProtectsBeginnersButNotEmptyingTheEntry() {
        val thee = repo.lookup("thee")
        assertNotNull("thee must resolve", thee)

        val gated = repo.sensesFor(thee!!.entry.id, beginnerSafe = true)
        val all = repo.sensesOf(thee.entry.id)
        assertTrue("the gate must remove something for thee", gated.size < all.size)
        assertTrue(
            "every surviving sense must be ungated",
            gated.all { s -> s.tags.none { it in GATED_TAGS } },
        )
    }
}