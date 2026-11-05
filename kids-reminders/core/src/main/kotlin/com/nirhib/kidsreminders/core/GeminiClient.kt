package com.nirhib.kidsreminders.core

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Gemini (מפתח מ-Google AI Studio) דרך ה-REST API ‏generateContent,
 * עם פלט JSON מובנה (responseSchema).
 */
class GeminiClient(
    private val apiKey: String,
    private val model: String = LlmProvider.GEMINI.defaultModel,
    private val http: OkHttpClient = defaultClient,
    private val baseUrl: String = "https://generativelanguage.googleapis.com/v1beta",
) : LlmClient {

    companion object {
        private val mapper = ObjectMapper()
        private val JSON = "application/json; charset=utf-8".toMediaType()
        private val defaultClient = OkHttpClient.Builder()
            .callTimeout(120, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .build()

        /**
         * ממיר JSON Schema רגיל לפורמט של Gemini: טיפוסים באותיות גדולות, בלי additionalProperties,
         * ועם propertyOrdering כדי שהשדות ייצאו בסדר שהגדרנו.
         */
        @Suppress("UNCHECKED_CAST")
        fun toGeminiSchema(schema: Map<String, Any>): Map<String, Any> {
            val out = linkedMapOf<String, Any>()
            for ((k, v) in schema) {
                when (k) {
                    "additionalProperties" -> Unit
                    "type" -> out[k] = (v as String).uppercase()
                    "items" -> out[k] = toGeminiSchema(v as Map<String, Any>)
                    "properties" -> {
                        val props = v as Map<String, Map<String, Any>>
                        out[k] = props.mapValues { toGeminiSchema(it.value) }
                        out["propertyOrdering"] = props.keys.toList()
                    }
                    else -> out[k] = v
                }
            }
            return out
        }

        fun buildRequestBody(system: String, user: String, schema: Map<String, Any>): String =
            mapper.writeValueAsString(
                mapOf(
                    "systemInstruction" to mapOf("parts" to listOf(mapOf("text" to system))),
                    "contents" to listOf(mapOf("role" to "user", "parts" to listOf(mapOf("text" to user)))),
                    "generationConfig" to mapOf(
                        "responseMimeType" to "application/json",
                        "responseSchema" to toGeminiSchema(schema),
                    ),
                )
            )

        /** שולף את הטקסט מתשובת generateContent (בלי חלקי "מחשבה"). */
        fun extractText(responseJson: String): String {
            val root: JsonNode = mapper.readTree(responseJson)
            root.path("promptFeedback").path("blockReason").asText().takeIf { it.isNotEmpty() }?.let {
                throw IllegalStateException("Gemini blocked the request: $it")
            }
            val candidate = root.path("candidates").path(0)
            if (candidate.isMissingNode) throw IllegalStateException("Gemini returned no answer")
            val text = candidate.path("content").path("parts")
                .filter { !it.path("thought").asBoolean(false) }
                .joinToString("") { it.path("text").asText("") }
            if (text.isBlank()) {
                throw IllegalStateException("Gemini returned an empty answer (${candidate.path("finishReason").asText()})")
            }
            return text
        }
    }

    override fun json(system: String, user: String, schema: Map<String, Any>): String {
        val request = Request.Builder()
            .url("$baseUrl/models/${model.trim()}:generateContent")
            .header("x-goog-api-key", apiKey)
            .post(buildRequestBody(system, user, schema).toRequestBody(JSON))
            .build()

        http.newCall(request).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                val msg = runCatching { mapper.readTree(body).path("error").path("message").asText() }
                    .getOrNull().orEmpty().ifEmpty { body.take(200) }
                throw IOException("Gemini HTTP ${resp.code}: $msg")
            }
            return extractText(body)
        }
    }
}
