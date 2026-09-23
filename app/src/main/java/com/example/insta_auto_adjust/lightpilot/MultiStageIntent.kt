package com.example.insta_auto_adjust.lightpilot

enum class IntentParseStatus(val wireValue: String) {
    OK("ok"), MOCK("mock"), UNAVAILABLE("unavailable");

    companion object {
        fun fromWire(value: String) = entries.firstOrNull { it.wireValue == value }
    }
}

data class StageWeights(
    val exposure: Double,
    val motionNoise: Double,
    val colorAtmosphere: Double,
) {
    init {
        listOf(exposure, motionNoise, colorAtmosphere).forEach {
            require(it.isFinite() && it in 0.0..1.0)
        }
    }

    fun weight(stage: PolicyStage): Double = when (stage) {
        PolicyStage.EXPOSURE -> exposure
        PolicyStage.MOTION_NOISE -> motionNoise
        PolicyStage.COLOR_ATMOSPHERE -> colorAtmosphere
    }
}

data class ParsedIntentDraft(
    val weights: StageWeights,
    val exposurePriority: ExposurePriority?,
    val motionPriority: MotionPriority?,
    val colorPriority: ColorPriority?,
    val stabilityPreference: StabilityPreference,
) {
    val missingActivePriorities: Set<PolicyStage>
        get() = buildSet {
            if (weights.exposure >= ACTIVE_STAGE_WEIGHT && exposurePriority == null) add(PolicyStage.EXPOSURE)
            if (weights.motionNoise >= ACTIVE_STAGE_WEIGHT && motionPriority == null) add(PolicyStage.MOTION_NOISE)
            if (weights.colorAtmosphere >= ACTIVE_STAGE_WEIGHT && colorPriority == null) {
                add(PolicyStage.COLOR_ATMOSPHERE)
            }
        }
}

data class IntentParseResult(
    val requestId: String,
    val status: IntentParseStatus,
    val draft: ParsedIntentDraft?,
    val ambiguities: List<String>,
    val reason: String,
)

data class MultiStageIntent(
    val revision: Long,
    val sourceText: String,
    val weights: StageWeights,
    val exposurePriority: ExposurePriority?,
    val motionPriority: MotionPriority?,
    val colorPriority: ColorPriority?,
    val stabilityPreference: StabilityPreference,
) {
    init {
        require(revision >= 0)
        require(sourceText.trim().length in 1..1000)
        require(weights.exposure < ACTIVE_STAGE_WEIGHT || exposurePriority != null)
        require(weights.motionNoise < ACTIVE_STAGE_WEIGHT || motionPriority != null)
        require(weights.colorAtmosphere < ACTIVE_STAGE_WEIGHT || colorPriority != null)
    }

    fun activeStages(): Set<PolicyStage> = PolicyStage.entries
        .filterTo(linkedSetOf()) { weights.weight(it) >= ACTIVE_STAGE_WEIGHT }
}

class IntentRevisionTracker(initialRevision: Long = 0) {
    private var revision = initialRevision.also { require(it >= 0) }

    @Synchronized
    fun confirm(result: IntentParseResult, sourceText: String): MultiStageIntent {
        require(result.status == IntentParseStatus.OK)
        val draft = requireNotNull(result.draft)
        require(draft.missingActivePriorities.isEmpty())
        revision += 1
        return MultiStageIntent(
            revision = revision,
            sourceText = sourceText,
            weights = draft.weights,
            exposurePriority = draft.exposurePriority,
            motionPriority = draft.motionPriority,
            colorPriority = draft.colorPriority,
            stabilityPreference = draft.stabilityPreference,
        )
    }

    @Synchronized
    fun currentRevision(): Long = revision
}

object IntentResponseMapper {
    fun parse(root: Map<String, Any?>, expectedRequestId: String): IntentParseResult {
        requireKeys(root, setOf("request_id", "status", "intent", "ambiguities", "reason"))
        val requestId = root.requireString("request_id")
        if (requestId != expectedRequestId) throw BackendException("STALE_REQUEST_ID")
        val status = IntentParseStatus.fromWire(root.requireString("status"))
            ?: throw BackendException("INVALID_STATUS")
        val ambiguities = root.requireStringList("ambiguities")
        val reason = root.requireString("reason")
        val rawIntent = root["intent"]
        val draft = if (rawIntent == null) null else parseDraft(rawIntent.requireStringMap("intent"))
        if (status == IntentParseStatus.OK && draft == null) throw BackendException("MISSING_INTENT")
        if (status != IntentParseStatus.OK && draft != null) throw BackendException("UNSAFE_FALLBACK_INTENT")
        if (draft != null && draft.missingActivePriorities.isNotEmpty() && ambiguities.isEmpty()) {
            throw BackendException("MISSING_MANUAL_SELECTION_MESSAGE")
        }
        return IntentParseResult(requestId, status, draft, ambiguities, reason)
    }

    private fun parseDraft(value: Map<String, Any?>): ParsedIntentDraft {
        requireKeys(value, setOf(
            "weights", "exposure_priority", "motion_priority", "color_priority",
            "stability_preference",
        ))
        val rawWeights = value["weights"].requireStringMap("weights")
        requireKeys(rawWeights, setOf("exposure", "motion_noise", "color_atmosphere"))
        val weights = StageWeights(
            exposure = rawWeights.requireWeight("exposure"),
            motionNoise = rawWeights.requireWeight("motion_noise"),
            colorAtmosphere = rawWeights.requireWeight("color_atmosphere"),
        )
        return ParsedIntentDraft(
            weights = weights,
            exposurePriority = value.nullableEnum("exposure_priority", ExposurePriority::fromWire),
            motionPriority = value.nullableEnum("motion_priority", MotionPriority::fromWire),
            colorPriority = value.nullableEnum("color_priority", ColorPriority::fromWire),
            stabilityPreference = StabilityPreference.fromWire(value.requireString("stability_preference"))
                ?: throw BackendException("INVALID_STABILITY_PREFERENCE"),
        )
    }

    private fun requireKeys(value: Map<String, Any?>, expected: Set<String>) {
        if (value.keys != expected) throw BackendException("INVALID_FIELDS")
    }

    private fun Map<String, Any?>.requireString(key: String): String {
        val value = this[key] as? String ?: throw BackendException("INVALID_$key")
        if (value.isBlank()) throw BackendException("INVALID_$key")
        return value
    }

    private fun Map<String, Any?>.requireStringList(key: String): List<String> {
        val values = this[key] as? List<*> ?: throw BackendException("INVALID_$key")
        return values.map {
            val text = it as? String ?: throw BackendException("INVALID_$key")
            if (text.isBlank()) throw BackendException("INVALID_$key")
            text
        }
    }

    private fun Map<String, Any?>.requireWeight(key: String): Double {
        val raw = this[key]
        if (raw is Boolean || raw !is Number) throw BackendException("INVALID_$key")
        val value = raw.toDouble()
        if (!value.isFinite() || value !in 0.0..1.0) throw BackendException("INVALID_$key")
        return value
    }

    private fun Any?.requireStringMap(name: String): Map<String, Any?> {
        @Suppress("UNCHECKED_CAST")
        return this as? Map<String, Any?> ?: throw BackendException("INVALID_$name")
    }

    private fun <T> Map<String, Any?>.nullableEnum(key: String, parser: (String) -> T?): T? {
        val raw = this[key] ?: return null
        val text = raw as? String ?: throw BackendException("INVALID_$key")
        return parser(text) ?: throw BackendException("INVALID_$key")
    }
}

const val ACTIVE_STAGE_WEIGHT = 0.50
