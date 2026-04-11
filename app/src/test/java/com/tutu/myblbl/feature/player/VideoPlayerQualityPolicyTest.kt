package com.tutu.myblbl.feature.player

import org.junit.Assert.assertEquals
import org.junit.Test

class VideoPlayerQualityPolicyTest {

    private val policy = VideoPlayerQualityPolicy()

    @Test
    fun startsWithPreferredQualityAndThenStepsDown() {
        assertEquals(
            listOf(120, 116, 112, 100, 80, 74, 64, 32, 16),
            policy.buildCandidates(120)
        )
    }

    @Test
    fun fallsBackToSafeDefaultsWhenQualityIsUnknown() {
        assertEquals(
            listOf(200, 127, 126, 129, 125, 120, 116, 80, 64, 32, 16),
            policy.buildCandidates(200)
        )
    }

    @Test
    fun autoModeStartsFrom1080pSafeDefault() {
        assertEquals(
            listOf(80, 74, 64, 32, 16, 6),
            policy.buildCandidates(null)
        )
    }
}
