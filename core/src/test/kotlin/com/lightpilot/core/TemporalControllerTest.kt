package com.lightpilot.core

import com.lightpilot.core.model.PolicyAction
import com.lightpilot.core.policy.TemporalController
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TemporalControllerTest {
    @Test
    fun requiresConsecutiveConfirmationBeforeCommit() {
        val controller = TemporalController(
            smoothingAlpha = 0.5f,
            confirmationFrames = 3,
            cooldownMs = 0L
        )

        val first = controller.observe(TestFixtures.metrics(), PolicyAction.EV_ONE_STEP_UP, TestFixtures.NOW)
        val second = controller.observe(
            TestFixtures.metrics(frameId = "frame-002"),
            PolicyAction.EV_ONE_STEP_UP,
            TestFixtures.NOW + 100L
        )
        val third = controller.observe(
            TestFixtures.metrics(frameId = "frame-003"),
            PolicyAction.EV_ONE_STEP_UP,
            TestFixtures.NOW + 200L
        )

        assertFalse(first.ready)
        assertFalse(second.ready)
        assertTrue(third.ready)
        assertEquals(PolicyAction.EV_ONE_STEP_UP, third.action)
    }

    @Test
    fun cooldownBlocksImmediateSecondAction() {
        val controller = TemporalController(
            confirmationFrames = 1,
            cooldownMs = 3_000L
        )

        val first = controller.observe(TestFixtures.metrics(), PolicyAction.EV_ONE_STEP_UP, TestFixtures.NOW)
        val second = controller.observe(
            TestFixtures.metrics(frameId = "frame-002"),
            PolicyAction.EV_ONE_STEP_DOWN,
            TestFixtures.NOW + 100L
        )

        assertTrue(first.ready)
        assertFalse(second.ready)
        assertEquals("cooldown", second.reason)
        assertEquals(PolicyAction.HOLD, second.action)
    }
}
