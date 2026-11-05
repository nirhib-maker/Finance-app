package com.nirhib.kidsreminders.core

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.JsonValue
import com.anthropic.models.beta.messages.BetaJsonOutputFormat
import com.anthropic.models.beta.messages.BetaOutputConfig
import com.anthropic.models.beta.messages.BetaStopReason
import com.anthropic.models.beta.messages.MessageCreateParams

/** Claude דרך ה-SDK הרשמי של Anthropic, עם פלט JSON מובנה. */
class ClaudeClient(
    apiKey: String,
    private val model: String = LlmProvider.CLAUDE.defaultModel,
    client: AnthropicClient? = null,
) : LlmClient {
    companion object {
        private const val FALLBACK_BETA = "server-side-fallback-2026-07-01"
    }

    private val client: AnthropicClient =
        client ?: AnthropicOkHttpClient.builder().apiKey(apiKey).build()

    override fun json(system: String, user: String, schema: Map<String, Any>): String {
        val params = MessageCreateParams.builder()
            .model(model)
            .maxTokens(16000L)
            .system(system)
            .addUserMessage(user)
            .outputConfig(
                BetaOutputConfig.builder()
                    .effort(BetaOutputConfig.Effort.LOW)
                    .format(
                        BetaJsonOutputFormat.builder()
                            .schema(
                                BetaJsonOutputFormat.Schema.builder()
                                    .apply { schema.forEach { (k, v) -> putAdditionalProperty(k, JsonValue.from(v)) } }
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
            throw IllegalStateException("Claude declined this request")
        }
        return response.content().mapNotNull { it.text().orElse(null)?.text() }.joinToString("")
    }
}
