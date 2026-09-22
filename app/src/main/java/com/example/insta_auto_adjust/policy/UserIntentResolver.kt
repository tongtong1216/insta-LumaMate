package com.example.insta_auto_adjust.policy

import com.lightpilot.core.model.UserIntent
import com.lightpilot.core.policy.IntentPreset
import com.lightpilot.core.policy.UserIntentPresets

data class IntentResolution(
    val intent: UserIntent,
    val matchedSignals: List<String>,
    val usedFallback: Boolean,
    val resolverName: String
)

/**
 * B-side boundary for converting user language into the shared UserIntent DTO.
 *
 * This local implementation is only a deterministic Mock adapter. When D's
 * backend is available, replace it with an HTTP-backed implementation without
 * changing PolicyEngine or the C-side contract.
 */
interface UserIntentResolver {
    fun resolve(
        sourceText: String,
        revision: Long,
        nowEpochMs: Long
    ): IntentResolution
}

class LocalKeywordIntentResolver : UserIntentResolver {
    override fun resolve(
        sourceText: String,
        revision: Long,
        nowEpochMs: Long
    ): IntentResolution {
        val text = sourceText.trim()
        require(text.isNotEmpty()) { "User intent must not be blank" }

        val subjectRequested = containsAny(
            text,
            "人物",
            "人脸",
            "主体",
            "宠物",
            "商品",
            "看清人",
            "看清主体",
            "subject",
            "face",
            "person"
        )
        val highlightRequested = containsAny(
            text,
            "高光",
            "亮部",
            "天空",
            "窗户",
            "灯牌",
            "不过曝",
            "保留亮",
            "highlight",
            "sky",
            "window"
        )
        val motionRequested = containsAny(
            text,
            "运动",
            "动态",
            "动作",
            "拖影",
            "冻结",
            "快门",
            "motion",
            "moving"
        )
        val lowNoiseRequested = containsAny(
            text,
            "噪点",
            "干净",
            "夜景",
            "低噪",
            "low noise",
            "noise"
        )
        val naturalColorRequested = containsAny(
            text,
            "颜色自然",
            "色彩自然",
            "白平衡准确",
            "还原颜色",
            "中性颜色",
            "natural color",
            "white balance"
        )
        val atmosphereRequested = containsAny(
            text,
            "氛围",
            "暖色",
            "冷色",
            "灯光感",
            "现场颜色",
            "保留色彩",
            "atmosphere",
            "warm light",
            "cool light"
        )
        val stableRequested = containsAny(
            text,
            "稳定",
            "不要频繁",
            "别频繁",
            "保持不动",
            "变化小",
            "不要乱调",
            "stable",
            "do not keep changing"
        )

        val explicitSubjectPriority = containsAny(
            text,
            "主体优先",
            "人物优先",
            "人脸优先",
            "优先看清",
            "subject first"
        )
        val explicitHighlightPriority = containsAny(
            text,
            "亮部优先",
            "高光优先",
            "天空优先",
            "优先保留天空",
            "highlight first"
        )

        val recognized = subjectRequested ||
            highlightRequested ||
            motionRequested ||
            lowNoiseRequested ||
            naturalColorRequested ||
            atmosphereRequested ||
            stableRequested

        val base = UserIntentPresets.create(
            preset = IntentPreset.BALANCED,
            revision = revision,
            sourceText = text,
            createdAtEpochMs = nowEpochMs
        )

        val intent = base.copy(
            subjectDetail = when {
                explicitSubjectPriority -> 1.0f
                subjectRequested && !highlightRequested -> 1.0f
                subjectRequested -> 0.85f
                else -> base.subjectDetail
            },
            highlightDetail = when {
                explicitHighlightPriority -> 1.0f
                highlightRequested && !subjectRequested -> 1.0f
                highlightRequested -> 0.85f
                else -> base.highlightDetail
            },
            motionClarity = if (motionRequested) 1.0f else base.motionClarity,
            lowNoise = if (lowNoiseRequested) 1.0f else base.lowNoise,
            colorNeutrality = if (naturalColorRequested) 1.0f else base.colorNeutrality,
            atmospherePreservation = if (atmosphereRequested) 1.0f else base.atmospherePreservation,
            exposureStability = if (stableRequested) 1.0f else base.exposureStability
        )

        val signals = buildList {
            if (subjectRequested) add("主体细节")
            if (highlightRequested) add("亮部细节")
            if (motionRequested) add("运动清晰")
            if (lowNoiseRequested) add("低噪点")
            if (naturalColorRequested) add("自然色彩")
            if (atmosphereRequested) add("氛围保留")
            if (stableRequested) add("曝光稳定")
        }

        return IntentResolution(
            intent = intent,
            matchedSignals = signals,
            usedFallback = !recognized,
            resolverName = "local_keyword_mock"
        )
    }

    private fun containsAny(text: String, vararg keywords: String): Boolean {
        return keywords.any { keyword ->
            text.contains(keyword, ignoreCase = true)
        }
    }
}
