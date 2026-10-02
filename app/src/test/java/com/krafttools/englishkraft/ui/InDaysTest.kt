package com.krafttools.englishkraft.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The wordbook's "when does this come back" label.
 *
 * Found by reading the stored due dates after a real session rather than by looking at
 * the code: two cards scheduled for two days both read "1 day", because floor division
 * drops a day the moment a second has passed. The review screen had promised "2 days"
 * on the button, so the two screens disagreed about the same card.
 */
class InDaysTest {

    private val day = 86_400_000L

    @Test
    fun aCardScheduledForTwoDaysReadsTwoDaysNotOne() {
        // The exact case from the device: 1.9986 days of wall clock remaining.
        assertEquals("2 days", inDays(172_679_020L))
        assertEquals("2 days", inDays(2 * day))
        assertEquals("2 days", inDays(2 * day - 1))
    }

    @Test
    fun aCardScheduledForOneDayReadsOneDay() {
        assertEquals("1 day", inDays(day))
        // Seconds of slack must not promote it to two.
        assertEquals("1 day", inDays(day + day / 2 - 1))
        assertEquals("2 days", inDays(day + day / 2))
    }

    @Test
    fun aCardAlreadyDueReadsOneDayRatherThanZeroOrNegative() {
        assertEquals("1 day", inDays(0))
        assertEquals("1 day", inDays(-5 * day))
    }

    @Test
    fun theUnitChangesOnlyWhereAReaderWouldExpectIt() {
        assertEquals("3 days", inDays(3 * day))
        assertEquals("13 days", inDays(13 * day))
        assertEquals("2 weeks", inDays(14 * day))
        assertEquals("2 weeks", inDays(20 * day))
        assertEquals("8 weeks", inDays(59 * day))
        assertEquals("2 months", inDays(60 * day))
        assertEquals("12 months", inDays(364 * day))
    }

    @Test
    fun everyLabelAgreesWithTheButtonThatSetIt() {
        // The interval shown on the review button is the interval stored, so the list
        // must be able to render exactly what was promised.
        for (days in 1..400) {
            val expected = when {
                days == 1 -> "1 day"
                days < 14 -> "$days days"
                days < 60 -> "${days / 7} weeks"
                else -> "${days / 30} months"
            }
            assertEquals(
                "a card stored with a $days-day interval must not read differently",
                expected,
                inDays(days * day),
            )
        }
    }
}