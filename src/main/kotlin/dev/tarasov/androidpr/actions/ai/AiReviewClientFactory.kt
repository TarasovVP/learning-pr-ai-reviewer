package dev.tarasov.androidpr.actions.ai

import dev.tarasov.androidpr.actions.config.AiProvider
import dev.tarasov.androidpr.actions.config.CliConfig
import dev.tarasov.androidpr.actions.openai.OpenAiReviewClient
import io.ktor.client.HttpClient
import kotlinx.serialization.json.Json

object AiReviewClientFactory {
    fun create(
        config: CliConfig,
        http: HttpClient,
        json: Json,
    ): AiReviewService = when (config.aiProvider) {
        AiProvider.OPENAI -> OpenAiReviewClient(
            http = http,
            apiKey = config.aiApiKey,
            model = config.aiModel,
            reasoningEffort = config.aiReasoningEffort,
            json = json,
        )
    }
}
