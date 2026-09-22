package com.lightpilot.core

import com.lightpilot.core.policy.IntentPreset
import com.lightpilot.core.policy.UserIntentPresets
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class UserIntentPresetsTest {
    @Test
    fun subjectFirstProfileHasClearSubjectPriority() {
        val intent = UserIntentPresets.create(
            preset = IntentPreset.SUBJECT_FIRST,
            revision = 4L,
            sourceText = "优先看清人脸"
        )

        assertEquals(1.0f, intent.subjectDetail)
        assertTrue(intent.subjectDetail > intent.highlightDetail)
        assertEquals("优先看清人脸", intent.sourceText)
    }

    @Test
    fun balancedProfilePrefersStabilityWhenTradeoffIsSmall() {
        val intent = UserIntentPresets.create(IntentPreset.BALANCED, revision = 1L)

        assertEquals(intent.subjectDetail, intent.highlightDetail)
        assertTrue(intent.exposureStability > 0.8f)
    }

    @Test
    fun motionOnlyProfileDoesNotPretendToBeAnEvPreference() {
        val intent = UserIntentPresets.create(IntentPreset.MOTION_FIRST, revision = 1L)

        assertEquals(0f, intent.subjectDetail)
        assertEquals(0f, intent.highlightDetail)
        assertEquals(1.0f, intent.motionClarity)
    }
}
