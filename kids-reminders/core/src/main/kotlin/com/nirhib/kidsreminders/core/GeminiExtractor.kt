package com.nirhib.kidsreminders.core

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.time.LocalDate
import java.util.concurrent.TimeUnit

/**
 * חילוץ וסיכום בעזרת Gemini (Google AI Studio API key).
 * עובד מול ה-REST API ‏generateContent עם פלט JSON מובנה (responseSchema).
 */
class GeminiExtractor(
    private val apiKey: String,
    private val model: String = LlmProvider.GEMINI.defaultModel,
    private val http: OkHttpClient = defaultClient,
    private val baseUrl: String = "https://generativelanguage.googleapis.com/v1beta",
) : LlmExtractor {

    companion object {
        private val mapper = ObjectMapper()
        private val JSON = "application/json; charset=utf-8".toMediaType()
        private val defaultClient = OkHttpClient.Builder()
            .callTimeout(120, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .build()

        /** סכמה בפורמט של Gemini (תת-קבוצה של OpenAPI: טיפוסים באותיות גדולות). */
        private val SCHEMA: Map<String, Any> = mapOf(
            "type" to "OBJECT",
            "required" to listOf("summary", "items"),
            "propertyOrdering" to listOf("summary", "items"),
            "properties" to mapOf(
                "summary" to mapOf("type" to "STRING"),
                "items" to mapOf(
                    "type" to "ARRAY",
                    "items" to mapOf(
                        "type" to "OBJECT",
                        "required" to ExtractionPrompt.ITEM_FIELDS,
                        "propertyOrdering" to ExtractionPrompt.ITEM_FIELDS,
                        "properties" to ExtractionPrompt.ITEM_FIELDS.associateWith { f ->
                            if (f == "type") mapOf("type" to "STRING", "enum" to ExtractionPrompt.TYPE_VALUES)
                            else mapOf("type" to "STRING")
                        },
                    ),
                ),
            ),
        )

        fun buildRequestBody(messages: List<KidMessage>, kids: List<String>, today: LocalDate): String =
            mapper.writeValueAsString(
                mapOf(
                    "systemInstruction" to mapOf("parts" to listOf(mapOf("text" to ExtractionPrompt.SYSTEM_PROMPT))),
                    "contents" to listOf(
                        mapOf(
                            "role" to "user",
                            "parts" to listOf(mapOf("text" to ExtractionPrompt.buildUserPrompt(messages, kids, today))),
                        )
                    ),
                    "generationConfig" to mapOf(
                        "responseMimeType" to "application/json",
                        "responseSchema" to SCHEMA,
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

    override fun extract(messages: List<KidMessage>, kids: List<String>, today: LocalDate): ExtractionResult {
        if (messages.isEmpty()) return ExtractionResult(emptyList())

        val request = Request.Builder()
            .url("$baseUrl/models/${model.trim()}:generateContent")
            .header("x-goog-api-key", apiKey)
            .post(buildRequestBody(messages, kids, today).toRequestBody(JSON))
            .build()

        http.newCall(request).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                val msg = runCatching { mapper.readTree(body).path("error").path("message").asText() }
                    .getOrNull().orEmpty().ifEmpty { body.take(200) }
                throw IOException("Gemini HTTP ${resp.code}: $msg")
            }
            return ExtractionPrompt.parseResponse(extractText(body), kids)
        }
    }
}
