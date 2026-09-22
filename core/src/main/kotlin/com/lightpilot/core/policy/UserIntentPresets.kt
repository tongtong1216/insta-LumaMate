package com.lightpilot.core.policy

import com.lightpilot.core.model.UserIntent

enum class IntentPreset {
    SUBJECT_FIRST,
    HIGHLIGHT_FIRST,
    BALANCED,
    STABLE_EXPOSURE,
    MOTION_FIRST,
    LOW_NOISE,
    NATURAL_COLOR,
    ATMOSPHERE_FIRST
}

object UserIntentPresets {
    fun create(
        preset: IntentPreset,
        revision: Long,
        sourceText: String? = null,
        createdAtEpochMs: Long = 0L
    ): UserIntent {
        return when (preset) {
            IntentPreset.SUBJECT_FIRST -> UserIntent(
                revision = revision,
                subjectDetail = 1.0f,
                highlightDetail = 0.25f,
                exposureStability = 0.65f,
                sourceText = sourceText,
                createdAtEpochMs = createdAtEpochMs
            )
            IntentPreset.HIGHLIGHT_FIRST -> UserIntent(
                revision = revision,
                subjectDetail = 0.25f,
                highlightDetail = 1.0f,
                exposureStability = 0.65f,
                sourceText = sourceText,
                createdAtEpochMs = createdAtEpochMs
            )
            IntentPreset.BALANCED -> UserIntent(
                revision = revision,
                subjectDetail = 0.7f,
                highlightDetail = 0.7f,
                exposureStability = 0.9f,
                sourceText = sourceText,
                createdAtEpochMs = createdAtEpochMs
            )
            IntentPreset.STABLE_EXPOSURE -> UserIntent(
                revision = revision,
                subjectDetail = 0.45f,
                highlightDetail = 0.45f,
                exposureStability = 1.0f,
                sourceText = sourceText,
                createdAtEpochMs = createdAtEpochMs
            )
            IntentPreset.MOTION_FIRST -> UserIntent(
                revision = revision,
                motionClarity = 1.0f,
                lowNoise = 0.25f,
                exposureStability = 0.7f,
                sourceText = sourceText,
                createdAtEpochMs = createdAtEpochMs
            )
            IntentPreset.LOW_NOISE -> UserIntent(
                revision = revision,
                motionClarity = 0.25f,
                lowNoise = 1.0f,
                exposureStability = 0.7f,
                sourceText = sourceText,
                createdAtEpochMs = createdAtEpochMs
            )
            IntentPreset.NATURAL_COLOR -> UserIntent(
                revision = revision,
                colorNeutrality = 1.0f,
                atmospherePreservation = 0.25f,
                exposureStability = 0.7f,
                sourceText = sourceText,
                createdAtEpochMs = createdAtEpochMs
            )
            IntentPreset.ATMOSPHERE_FIRST -> UserIntent(
                revision = revision,
                colorNeutrality = 0.25f,
                atmospherePreservation = 1.0f,
                exposureStability = 0.75f,
                sourceText = sourceText,
                createdAtEpochMs = createdAtEpochMs
            )
        }
    }
}
