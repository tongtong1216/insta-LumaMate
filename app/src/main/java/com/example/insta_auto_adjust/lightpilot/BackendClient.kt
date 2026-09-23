package com.example.insta_auto_adjust.lightpilot

import android.graphics.Bitmap
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

class BackendException(message: String) : Exception(message)

class LightPilotBackendClient(
    private val baseUrl: String,
    private val connectTimeoutMs: Int = 5_000,
    private val readTimeoutMs: Int = 35_000,
) {
    fun parseIntent(sourceText: String, requestId: String = UUID.randomUUID().toString()): IntentParseResult {
        require(sourceText.trim().length in 1..1000)
        val body = JSONObject()
            .put("request_id", requestId)
            .put("source_text", sourceText)
        val response = post("/api/v1/parse-intent", body)
        return IntentResponseMapper.parse(jsonObjectToMap(JSONObject(response)), requestId)
    }

    fun analyze(bitmap: Bitmap, intent: UserIntent, metrics: VisionMetrics): SceneSemantic {
        val image = ByteArrayOutputStream().use { stream ->
            check(bitmap.compress(Bitmap.CompressFormat.JPEG, 85, stream))
            Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)
        }
        val body = JSONObject()
            .put("frame_id", metrics.frameId)
            .put("intent_revision", intent.revision)
            .put("intent", JSONObject()
                .put("exposure_priority", intent.exposurePriority.wireValue)
                .put("stability_preference", intent.stabilityPreference.wireValue)
                .put("source_text", intent.sourceText ?: JSONObject.NULL))
            .put("image_base64", image)
            .put("metrics", JSONObject()
                .put("subject_brightness", intentOrNull(metrics.subjectBrightness))
                .put("background_brightness", metrics.backgroundBrightness)
                .put("highlight_clipping_ratio", metrics.highlightClippingRatio)
                .put("dark_ratio", metrics.darkRatio))

        return parseSceneSemantic(JSONObject(post("/api/v1/analyze-scene", body)))
    }

    private fun post(path: String, body: JSONObject): String {
        val connection = URL("${baseUrl.trimEnd('/')}$path").openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = connectTimeoutMs
            connection.readTimeout = readTimeoutMs
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            if (connection.responseCode != 200) throw BackendException("HTTP_${connection.responseCode}")
            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private fun parseSceneSemantic(json: JSONObject): SceneSemantic {
        val status = SemanticStatus.fromWire(json.getString("status"))
            ?: throw BackendException("INVALID_STATUS")
        val uncertaintyJson = json.getJSONArray("uncertainty")
        val uncertainty = buildList {
            for (index in 0 until uncertaintyJson.length()) add(uncertaintyJson.getString(index))
        }
        val detailsJson = json.optJSONArray("uncertainty_details")
        val details = if (detailsJson == null) emptyList() else buildList {
            for (index in 0 until detailsJson.length()) {
                val detail = detailsJson.getJSONObject(index)
                val code = UncertaintyCode.fromWire(detail.getString("code"))
                    ?: throw BackendException("INVALID_UNCERTAINTY_CODE")
                val severity = UncertaintySeverity.fromWire(detail.getString("severity"))
                    ?: throw BackendException("INVALID_UNCERTAINTY_SEVERITY")
                val affectsJson = detail.getJSONArray("affects")
                val affects = buildSet {
                    for (item in 0 until affectsJson.length()) {
                        add(SemanticField.fromWire(affectsJson.getString(item))
                            ?: throw BackendException("INVALID_UNCERTAINTY_AFFECT"))
                    }
                }
                add(UncertaintyDetail(code, severity, affects, detail.getString("message")))
            }
        }
        return SceneSemantic(
            frameId = json.getLong("frame_id"),
            intentRevision = json.getLong("intent_revision"),
            status = status,
            scene = nullableEnum(json, "scene", SceneLabel::fromWire),
            subjectType = nullableEnum(json, "subject_type", SubjectType::fromWire),
            brightRegionType = nullableEnum(json, "bright_region_type", BrightRegionType::fromWire),
            coloredLight = if (json.isNull("colored_light")) null else json.getBoolean("colored_light"),
            uncertainty = uncertainty,
            reason = if (json.isNull("reason")) null else json.getString("reason"),
            uncertaintyDetails = details,
        )
    }

    private fun <T> nullableEnum(json: JSONObject, key: String, parser: (String) -> T?): T? {
        if (json.isNull(key)) return null
        return parser(json.getString(key)) ?: throw BackendException("INVALID_$key")
    }

    private fun intentOrNull(value: Double?): Any = value ?: JSONObject.NULL

    private fun jsonObjectToMap(json: JSONObject): Map<String, Any?> = buildMap {
        val keys = json.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            put(key, jsonValue(json.get(key)))
        }
    }

    private fun jsonValue(value: Any?): Any? = when (value) {
        null, JSONObject.NULL -> null
        is JSONObject -> jsonObjectToMap(value)
        is JSONArray -> buildList {
            for (index in 0 until value.length()) add(jsonValue(value.get(index)))
        }
        else -> value
    }
}
