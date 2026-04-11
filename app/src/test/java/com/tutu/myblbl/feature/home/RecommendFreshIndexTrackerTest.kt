package com.tutu.myblbl.feature.home

import org.junit.Assert.assertEquals
import org.junit.Test

class RecommendFreshIndexTrackerTest {

    @Test
    fun keepsPagingSequentialBeforeRefresh() {
        val tracker = RecommendFreshIndexTracker()

        assertEquals(1, tracker.resolve(1))
        tracker.markFirstPageLoaded()
        assertEquals(2, tracker.resolve(2))
        assertEquals(3, tracker.resolve(3))
    }

    @Test
    fun advancesFirstPageAfterSuccessfulRefresh() {
        val tracker = RecommendFreshIndexTracker()

        assertEquals(1, tracker.resolve(1))
        tracker.markFirstPageLoaded()

        assertEquals(2, tracker.resolve(1))
        assertEquals(3, tracker.resolve(2))
    }

    @Test
    fun refreshStartsAfterHighestLoadedPage() {
        val tracker = RecommendFreshIndexTracker()

        assertEquals(1, tracker.resolve(1))
        tracker.markFirstPageLoaded()
        assertEquals(2, tracker.resolve(2))
        assertEquals(3, tracker.resolve(3))

        assertEquals(4, tracker.resolve(1))
        assertEquals(5, tracker.resolve(2))
    }

    @Test
    fun doesNotAdvanceBeforeFirstPageSucceeds() {
        val tracker = RecommendFreshIndexTracker()

        assertEquals(1, tracker.resolve(1))
        assertEquals(1, tracker.resolve(1))
    }
}
