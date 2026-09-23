package com.lightpilot.core

import com.lightpilot.core.contract.v1.SceneSemanticCache
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SceneSemanticCacheTest {
    @Test
    fun permitsOnlyOneRequestAndRejectsOldBinding() {
        val cache = SceneSemanticCache()
        val first = cache.beginRequest(1L, 3L)
        assertNotNull(first)
        assertNull(cache.beginRequest(2L, 3L))

        val accepted = cache.complete(
            token = first,
            semantic = TestFixtures.semantic("2").copy(
                intentRevision = 3L,
                analysisStatus = "ok",
                expiresAtEpochMs = TestFixtures.NOW + 60_000L
            ),
            baseline = TestFixtures.metrics("1"),
            currentIntentRevision = 3L,
            cameraStateRevision = 1L,
            mode = "VIDEO",
            nowEpochMs = TestFixtures.NOW
        )

        assertFalse(accepted)
        assertNull(
            cache.current(3L, 1L, "VIDEO", TestFixtures.metrics("1"), TestFixtures.NOW)
        )
    }

    @Test
    fun invalidatesOnMetricContextAndAgeChanges() {
        val cache = SceneSemanticCache()
        val token = cache.beginRequest(1L, 3L)!!
        assertTrue(
            cache.complete(
                token = token,
                semantic = TestFixtures.semantic("1").copy(
                    intentRevision = 3L,
                    analysisStatus = "ok",
                    expiresAtEpochMs = TestFixtures.NOW + 60_000L
                ),
                baseline = TestFixtures.metrics("1"),
                currentIntentRevision = 3L,
                cameraStateRevision = 1L,
                mode = "VIDEO",
                nowEpochMs = TestFixtures.NOW
            )
        )
        assertNotNull(
            cache.current(3L, 1L, "VIDEO", TestFixtures.metrics("2"), TestFixtures.NOW + 1_000L)
        )
        assertNull(
            cache.current(
                3L,
                1L,
                "VIDEO",
                TestFixtures.metrics("3").copy(darkRatio = 0.66f),
                TestFixtures.NOW + 2_000L
            )
        )

        val second = cache.beginRequest(4L, 3L)!!
        assertTrue(
            cache.complete(
                second,
                TestFixtures.semantic("4").copy(
                    intentRevision = 3L,
                    analysisStatus = "ok",
                    expiresAtEpochMs = TestFixtures.NOW + 60_000L
                ),
                TestFixtures.metrics("4"),
                3L,
                1L,
                "VIDEO",
                TestFixtures.NOW
            )
        )
        assertNull(
            cache.current(3L, 2L, "VIDEO", TestFixtures.metrics("5"), TestFixtures.NOW + 1_000L)
        )
    }
}
