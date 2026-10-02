package com.krafttools.englishkraft.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The figures the README publishes.
 *
 * Written because three of them had gone stale and nothing noticed: the WordNet sense
 * count (145,695 published, 144,986 stored), the corpus size (427.3 MB published,
 * 451.3 MiB actual) and the compressed size inside the APK. A README that reports
 * measurements makes testable claims, and a claim that cannot fail is decoration.
 *
 * These are asserted on the device against the corpus that shipped in the APK, which is
 * the thing the numbers describe. An earlier attempt also parsed the README to compare
 * it; that was removed, because the README lives on the build machine and not on the
 * phone, so reading it from a test would have depended on a guessed working directory.
 * The literals below are what must be edited in the README too, and the comment on each
 * names the README row.
 */
@RunWith(AndroidJUnit4::class)
class CorpusFiguresTest {

    private lateinit var repo: DictionaryRepository
    private lateinit var db: android.database.sqlite.SQLiteDatabase

    @Before
    fun openCorpus() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val installer = DictionaryInstaller(context)
        val outcome = kotlinx.coroutines.runBlocking { installer.install { _, _ -> } }
        val file = (outcome as? DictionaryInstaller.State.Ready)?.file
        assertTrue("the dictionary must install on device", file != null)
        db = openDictionary(file!!)
        repo = DictionaryRepository(db)
    }

    @Test
    fun everyPublishedFigureStillHolds() {
        val published = mapOf(
            // README: "entries (headword × part of speech)"
            "entries" to 1_456_903,
            // README: "senses"
            "senses" to 1_745_189,
            // README: "inflected forms"
            "forms" to 880_940,
            // README: "lexical relations"
            "relations" to 270_728,
            // README: "WordNet senses cross-referenced" -- was published as 145,695
            "wordnet senses cross-referenced" to 144_986,
            // README: "attested example sentences"
            "attested example sentences" to 366_506,
            // README: "IPA transcriptions"
            "IPA transcriptions" to 129_301,
            // README: "senses with a usage/quality tag"
            "senses with a usage/quality tag" to 1_217_027,
        )
        val actual = repo.corpusFigures()

        val wrong = published.filter { (label, expected) ->
            actual[label] != expected
        }
        assertEquals(
            "the corpus no longer matches the published figures, so README.md and " +
                "docs/DATA.md are now wrong: $wrong",
            emptyMap<String, Int>(),
            wrong,
        )
    }

    @Test
    fun theCorpusIsNotSilentlyTruncated() {
        // A partial copy opens, answers queries, and would satisfy every count above
        // only if the counts were also small. This is the cheap end of the check:
        // LookupQueriesTest walks the real queries.
        val figures = repo.corpusFigures()
        assertTrue(
            "entries: ${figures["entries"]}",
            figures.getValue("entries") > 1_400_000,
        )
        assertTrue(
            "senses: ${figures["senses"]}",
            figures.getValue("senses") > 1_700_000,
        )
    }

    @Test
    fun theRelationSplitAddsUpToTheTotal() {
        val figures = repo.corpusFigures()
        assertEquals(
            "the two sources' relations must account for every row",
            figures.getValue("relations"),
            figures.getValue("wordnet relations") + figures.getValue("wiktionary relations"),
        )
        // README: "167,501 from WordNet, 103,227 from Wiktionary"
        assertEquals(167_501, figures.getValue("wordnet relations"))
        assertEquals(103_227, figures.getValue("wiktionary relations"))
    }

    /**
     * A repeated relation is always the two sources agreeing, and the app shows it once.
     *
     * 3,140 (entry, relation, target) triples are stored twice. That is not a defect and
     * not a duplicate: each is one row from Wiktionary and one from WordNet recording
     * the same link independently, which is the corroboration the entry screen labels.
     * The app's own query groups by (rel, target) and collapses them.
     *
     * Two earlier versions of this check were wrong. One grouped by (rel, target)
     * without entry_id and reported 22,987 duplicates, all of them the same legitimate
     * relation on different words. The other asserted there were none, which failed
     * against 3,140 real ones and would have been fixed by deleting the data rather
     * than by understanding it. Both were a check written before reading what the
     * numbers meant.
     */
    @Test
    fun everyRepeatedRelationIsTwoSourcesAgreeing() {
        val doubled = db.rawQuery(
            """
            SELECT count(*) FROM (
              SELECT entry_id, rel, target FROM relation
              GROUP BY entry_id, rel, target HAVING count(*) > 1
            )
            """.trimIndent(),
            null,
        ).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }

        assertEquals(
            "the number of relations both sources record independently; docs/DATA.md " +
                "publishes this as 3,140",
            3_140,
            doubled,
        )

        // Every one of them is exactly two rows, one per source, and nothing else.
        val shapes = db.rawQuery(
            """
            SELECT copies, sources, count(*) FROM (
              SELECT entry_id, rel, target, count(*) AS copies,
                     (SELECT group_concat(src) FROM relation x
                       WHERE x.entry_id = relation.entry_id
                         AND x.rel = relation.rel
                         AND x.target = relation.target) AS sources
              FROM relation
              GROUP BY entry_id, rel, target
            ) WHERE copies > 1 GROUP BY copies, sources
            """.trimIndent(),
            null,
        ).use { c ->
            buildList { while (c.moveToNext()) add(Triple(c.getInt(0), c.getString(1), c.getInt(2))) }
        }

        assertEquals(
            "a repeated relation must be exactly one row from each source",
            listOf(Triple(2, "wik,wn", 3_140)),
            shapes,
        )
    }

    @Test
    fun theAppShowsARepeatedRelationOnce() {
        // The point of storing both sources is the corroboration flag, which the entry
        // screen labels. If the query did not group, every one of those 3,140 would be
        // printed twice with the learner given no way to tell.
        val entry = db.rawQuery(
            """
            SELECT entry_id FROM relation
            GROUP BY entry_id, rel, target HAVING count(*) > 1 LIMIT 1
            """.trimIndent(),
            null,
        ).use { c -> if (c.moveToFirst()) c.getInt(0) else -1 }

        val shown = repo.relationsOf(entry.toLong())
        val keys = shown.map { it.rel to it.target }
        assertEquals(
            "the entry screen would show a relation twice",
            keys.size,
            keys.toSet().size,
        )
        assertTrue(
            "the corroborated relation should be flagged so the screen can label it",
            shown.any { it.corroborated },
        )
    }
}
