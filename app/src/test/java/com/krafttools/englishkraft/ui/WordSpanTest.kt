package com.krafttools.englishkraft.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The word splitter decides what is tappable inside a definition.
 *
 * Runs on the JVM with no Compose runtime, which is why buildWordSpans takes no
 * theme colour. Each expectation below is a case that would otherwise ship broken.
 *
 * Assertions are about membership, not about the whole list. Every real word in a
 * sentence is meant to be tappable — `Run fast.` yields [Run, fast] because "fast"
 * is a word too, and demanding the list equal [run] tested the sentence, not the
 * splitter.
 */
class WordSpanTest {

    @Test
    fun trailingFullStopIsNotPartOfTheWord() {
        // Looking up "run." would fail, and the app would look broken.
        val words = wordsIn("run fast.")
        assertTrue("run must be tappable", words.contains("run"))
        assertFalse("a full stop must not be part of a word", words.any { it.endsWith(".") })
    }

    @Test
    fun aSentenceInitialCapitalStillCounts() {
        // "Run" opens a gloss constantly. It must stay tappable, and the lookup
        // lowercases it anyway, so the capital is not a reason to reject it.
        assertTrue(wordsIn("Run quickly").contains("Run"))
    }

    @Test
    fun internalPunctuationStaysInsideTheWord() {
        assertTrue(wordsIn("A well-known fact").contains("well-known"))
        assertTrue(wordsIn("e.g. this").contains("e.g"))
    }

    @Test
    fun singleLettersAreNotTappable() {
        // "I" and "a" must not navigate away from the entry being read.
        val words = wordsIn("I am a reader")
        assertFalse("'I' must not be tappable", words.contains("I"))
        assertFalse("'a' must not be tappable", words.any { it.equals("a", ignoreCase = true) })
        assertTrue("'am' is a word and should be tappable", words.contains("am"))
    }

    @Test
    fun acronymsAreNotTappable() {
        val words = wordsIn("A noun (plural: nouns)")
        assertTrue(words.contains("noun"))
        assertFalse("acronyms must not be tappable", words.contains("noun".uppercase()))
    }

    @Test
    fun capitalisedProperNounsInsideProseAreTappable() {
        assertTrue(wordsIn("visit to Paris").contains("Paris"))
    }

    @Test
    fun quotedTerminologyKeepsItsQuotes() {
        val words = wordsIn("\"early life\" checks")
        assertTrue(words.contains("early"))
        assertTrue(words.contains("life"))
        assertTrue(words.contains("checks"))
        assertFalse("quotes must not leak into a word", words.any { it.contains('"') })
    }

    @Test
    fun emptyAndPunctuationOnlyTextIsSafe() {
        assertEquals(emptyList<String>(), wordsIn(""))
        assertEquals(emptyList<String>(), wordsIn("— () [] {}"))
        assertEquals(emptyList<String>(), wordsIn("123 456"))
    }

    @Test
    fun numbersAreNotTappable() {
        val words = wordsIn("in 2024 there were 3 runs")
        assertFalse("bare numbers must not be tappable", words.contains("2024"))
        assertTrue(words.contains("runs"))
    }
}