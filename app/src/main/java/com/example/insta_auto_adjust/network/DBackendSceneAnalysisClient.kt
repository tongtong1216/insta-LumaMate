package com.example.insta_auto_adjust.network

import com.lightpilot.core.contract.v1.AnalyzeSceneRequest
import com.lightpilot.core.contract.v1.AnalyzeSceneResponse
import com.lightpilot.core.contract.v1.SceneAnalysisClient
import com.lightpilot.core.contract.v1.SceneAnalysisStatus
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONArray
import org.json.JSONObject

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
    init {
        require(baseUrl.isNotBlank()) { "D backend baseUrl must not be blank" }
        require(connectTimeoutMs > 0)
        require(readTimeoutMs > 0)
    }

    override fun analyzeScene(request: AnalyzeSceneRequest): AnalyzeSceneResponse {
        val endpoint = URL("${baseUrl.trimEnd('/')}/api/v1/analyze-scene")
        val connection = (endpoint.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Accept", "application/json")
        }

        val body = request.toJsonObject().toString().toByteArray(Charsets.UTF_8)
        try {
            connection.outputStream.use { output ->
                output.write(body)
            }
            val responseText = if (connection.responseCode in 200..299) {
                connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            } else {
                val errorText = connection.errorStream
                    ?.bufferedReader(Charsets.UTF_8)
                    ?.use { it.readText() }
                    .orEmpty()
                throw IOException(
                    "D backend returned HTTP ${connection.responseCode}: $errorText"
                )
            }
            return JSONObject(responseText).toAnalyzeSceneResponse()
        } finally {
            connection.disconnect()
        }
    }
}

private fun AnalyzeSceneRequest.toJsonObject(): JSONObject {
    val json = JSONObject()
        .put("frame_id", frameId)
        .put("intent_revision", intentRevision)
        .put("intent", intent)
        .put("image_base64", imageBase64)

    val requestMetrics = metrics
    if (requestMetrics == null) {
        json.put("metrics", JSONObject.NULL)
    } else {
        json.put(
            "metrics",
            JSONObject()
                .putNullable("subject_brightness", requestMetrics.subjectBrightness)
                .putNullable("highlight_ratio", requestMetrics.highlightRatio)
                .putNullable("dark_ratio", requestMetrics.darkRatio)
        )
    }
    return json
}

private fun JSONObject.toAnalyzeSceneResponse(): AnalyzeSceneResponse {
    return AnalyzeSceneResponse(
        frameId = getLong("frame_id"),
        intentRevision = getLong("intent_revision"),
        status = SceneAnalysisStatus.fromWireValue(getString("status")),
        scene = getNullableString("scene"),
        subjectType = getNullableString("subject_type"),
        brightRegionType = getNullableString("bright_region_type"),
        coloredLight = getNullableBoolean("colored_light"),
        uncertainty = getJSONArray("uncertainty").toStringList(),
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
