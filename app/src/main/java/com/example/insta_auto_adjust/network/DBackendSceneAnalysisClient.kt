package com.example.insta_auto_adjust.network

import com.lightpilot.core.contract.v1.AnalyzeSceneRequest
import com.lightpilot.core.contract.v1.AnalyzeSceneResponse
import com.lightpilot.core.contract.v1.AnalyzeSceneUncertaintyDetail
import com.lightpilot.core.contract.v1.SceneAnalysisClient
import com.lightpilot.core.contract.v1.SceneAnalysisStatus
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Android HTTP adapter for member D's /api/v1/analyze-scene endpoint.
 *
 * This class is deliberately thin: it only performs transport and JSON
 * mapping. Policy decisions remain in the core module.
 */
class DBackendSceneAnalysisClient(
    private val baseUrl: String,
    private val connectTimeoutMs: Int = 10_000,
    private val readTimeoutMs: Int = 35_000
) : SceneAnalysisClient {
    private val requestInFlight = AtomicBoolean(false)

    init {
        require(baseUrl.isNotBlank()) { "D backend baseUrl must not be blank" }
        require(connectTimeoutMs > 0)
        require(readTimeoutMs > 0)
    }

    override fun analyzeScene(request: AnalyzeSceneRequest): AnalyzeSceneResponse {
        if (!requestInFlight.compareAndSet(false, true)) {
            throw IOException("Only one D analyze-scene request may be in flight")
        }
        var connection: HttpURLConnection? = null
        try {
            val endpoint = URL("${baseUrl.trimEnd('/')}/api/v1/analyze-scene")
            val activeConnection = (endpoint.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = connectTimeoutMs
                readTimeout = readTimeoutMs
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Accept", "application/json")
            }
            connection = activeConnection
            val body = request.toJsonObject().toString().toByteArray(Charsets.UTF_8)
            if (body.size > MAX_REQUEST_BODY_BYTES) {
                throw IOException("D request body exceeds 6 MiB")
            }
            activeConnection.outputStream.use { output ->
                output.write(body)
            }
            val responseText = if (activeConnection.responseCode in 200..299) {
                activeConnection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            } else {
                val errorText = activeConnection.errorStream
                    ?.bufferedReader(Charsets.UTF_8)
                    ?.use { it.readText() }
                    .orEmpty()
                throw IOException(
                    "D backend returned HTTP ${activeConnection.responseCode}: $errorText"
                )
            }
            return JSONObject(responseText).toAnalyzeSceneResponse()
        } finally {
            connection?.disconnect()
            requestInFlight.set(false)
        }
    }

    private companion object {
        const val MAX_REQUEST_BODY_BYTES = 6 * 1024 * 1024
    }
}

internal fun AnalyzeSceneRequest.toJsonObject(): JSONObject {
    val json = JSONObject()
        .put("frame_id", frameId)
        .put("intent_revision", intentRevision)
        .put(
            "intent",
            JSONObject()
                .put("exposure_priority", intent.exposurePriority.wireValue)
                .put("stability_preference", intent.stabilityPreference.wireValue)
                .put("source_text", intent.sourceText)
        )
        .put("image_base64", imageBase64)

    val requestMetrics = metrics
    if (requestMetrics == null) {
        json.put("metrics", JSONObject.NULL)
    } else {
        json.put(
            "metrics",
            JSONObject()
                .putNullable("subject_brightness", requestMetrics.subjectBrightness)
                .putNullable("background_brightness", requestMetrics.backgroundBrightness)
                .putNullable("highlight_clipping_ratio", requestMetrics.highlightClippingRatio)
                .putNullable("dark_ratio", requestMetrics.darkRatio)
        )
    }
    return json
}

internal fun JSONObject.toAnalyzeSceneResponse(): AnalyzeSceneResponse {
    return AnalyzeSceneResponse(
        frameId = getLong("frame_id"),
        intentRevision = getLong("intent_revision"),
        status = SceneAnalysisStatus.fromWireValue(getString("status")),
        scene = getNullableString("scene"),
        subjectType = getNullableString("subject_type"),
        brightRegionType = getNullableString("bright_region_type"),
        coloredLight = getNullableBoolean("colored_light"),
        uncertainty = getJSONArray("uncertainty").toStringList(),
        uncertaintyDetails = optJSONArray("uncertainty_details")
            ?.toUncertaintyDetails()
            .orEmpty(),
        reason = getNullableString("reason")
    )
}

private fun JSONObject.putNullable(name: String, value: Float?): JSONObject {
    return put(name, value ?: JSONObject.NULL)
}

private fun JSONObject.getNullableString(name: String): String? {
    return if (isNull(name)) null else getString(name)
}

private fun JSONObject.getNullableBoolean(name: String): Boolean? {
    return if (isNull(name)) null else getBoolean(name)
}

private fun JSONArray.toStringList(): List<String> {
    return List(length()) { index -> getString(index) }
}

private fun JSONArray.toUncertaintyDetails(): List<AnalyzeSceneUncertaintyDetail> {
    return List(length()) { index ->
        val item = getJSONObject(index)
        AnalyzeSceneUncertaintyDetail(
            code = item.getString("code"),
            severity = item.getString("severity"),
            affects = item.get("affects").toAffectsSet(),
            message = item.getNullableString("message")
        )
    }
}

private fun Any.toAffectsSet(): Set<String> {
    return when (this) {
        is String -> setOf(this)
        is JSONArray -> toStringList().toSet()
        else -> error("uncertainty_details.affects must be a string or string array")
    }
}
