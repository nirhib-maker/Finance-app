package com.nirhib.kidsreminders.core

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.JsonValue
import com.anthropic.models.beta.messages.BetaJsonOutputFormat
import com.anthropic.models.beta.messages.BetaOutputConfig
import com.anthropic.models.beta.messages.BetaStopReason
import com.anthropic.models.beta.messages.MessageCreateParams
import java.time.LocalDate

/**
 * חילוץ וסיכום בעזרת Claude (דרך ה-API של Anthropic, צריך מפתח API).
 * מבין הקשר הרבה יותר טוב מהחוקים: "תזכורת למה שכתבתי אתמול", "בשבוע הבא אחרי החג" וכו'.
 */
class ClaudeExtractor(
    apiKey: String,
    private val model: String = DEFAULT_MODEL,
    client: AnthropicClient? = null,
) : LlmExtractor {
    companion object {
        val DEFAULT_MODEL = LlmProvider.CLAUDE.defaultModel
        private const val FALLBACK_BETA = "server-side-fallback-2026-07-01"

        /** JSON Schema לפלט מובנה. */
        private val SCHEMA: Map<String, Any> = mapOf(
            "type" to "object",
            "additionalProperties" to false,
            "required" to listOf("summary", "items"),
            "properties" to mapOf(
                "summary" to mapOf("type" to "string"),
                "items" to mapOf(
                    "type" to "array",
                    "items" to mapOf(
                        "type" to "object",
                        "additionalProperties" to false,
                        "required" to ExtractionPrompt.ITEM_FIELDS,
                        "properties" to ExtractionPrompt.ITEM_FIELDS.associateWith { f ->
                            if (f == "type") mapOf("type" to "string", "enum" to ExtractionPrompt.TYPE_VALUES)
                            else mapOf("type" to "string")
                        },
                    ),
                ),
            ),
        )
    }

    private val client: AnthropicClient =
        client ?: AnthropicOkHttpClient.builder().apiKey(apiKey).build()

    override fun extract(messages: List<KidMessage>, kids: List<String>, today: LocalDate): ExtractionResult {
        if (messages.isEmpty()) return ExtractionResult(emptyList())

        val params = MessageCreateParams.builder()
            .model(model)
            .maxTokens(16000L)
            .system(ExtractionPrompt.SYSTEM_PROMPT)
            .addUserMessage(ExtractionPrompt.buildUserPrompt(messages, kids, today))
            .outputConfig(
                BetaOutputConfig.builder()
                    .effort(BetaOutputConfig.Effort.LOW)
                    .format(
                        BetaJsonOutputFormat.builder()
                            .schema(
                                BetaJsonOutputFormat.Schema.builder()
                                    .apply { SCHEMA.forEach { (k, v) -> putAdditionalProperty(k, JsonValue.from(v)) } }
                                    .build()
                            )
                            .build()
                    )
                    .build()
            )
            // אם המודל מסרב (סיווג בטיחות שגוי), הבקשה עוברת אוטומטית למודל חלופי
            .addBeta(FALLBACK_BETA)
            .fallbacksDefault()
            .build()

        val response = client.beta().messages().create(params)
        if (response.stopReason().orElse(null) == BetaStopReason.REFUSAL) {
            throw IllegalStateException("Claude declined to process this batch")
        }
        val text = response.content().mapNotNull { it.text().orElse(null)?.text() }.joinToString("")
        return ExtractionPrompt.parseResponse(text, kids)
    }
}
