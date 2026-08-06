package dev.tarasov.androidpr.actions

import dev.tarasov.androidpr.actions.config.CliConfig
import dev.tarasov.androidpr.actions.domain.ReviewJob
import dev.tarasov.androidpr.actions.github.GitHubTokenApiClient
import dev.tarasov.androidpr.actions.openai.OpenAiReviewClient
import dev.tarasov.androidpr.actions.review.ContextCollector
import dev.tarasov.androidpr.actions.review.ReviewCoordinator
import dev.tarasov.androidpr.actions.review.ReviewOutcome
import dev.tarasov.androidpr.actions.rubric.RubricLoader
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    val exitCode = runCatching { runReview(args) }.fold(
        onSuccess = { 0 },
        onFailure = { error ->
            System.err.println("$REVIEW_FAILED_PREFIX${error::class.simpleName}: ${error.message}")
            1
        },
    )
    if (exitCode != 0) exitProcess(exitCode)
}

private fun runReview(args: Array<String>) = runBlocking {
    val config = CliConfig.from(args)
    val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
    }
    val http = HttpClient(CIO) {
        expectSuccess = false
        install(HttpTimeout) {
            connectTimeoutMillis = 30_000
            requestTimeoutMillis = 180_000
            socketTimeoutMillis = 180_000
        }
    }
    try {
        val github = GitHubTokenApiClient(http, config.githubToken, json)
        val coordinator = ReviewCoordinator(
            github = github,
            ai = OpenAiReviewClient(
                http = http,
                apiKey = config.openAiApiKey,
                model = config.openAiModel,
                reasoningEffort = config.openAiReasoningEffort,
                json = json,
            ),
            collector = ContextCollector(github, config.maxFiles, config.maxContextBytes),
            rubricProvider = RubricLoader,
            allowedRepositories = config.allowedRepositories,
        )
        when (val outcome = coordinator.process(
            ReviewJob(
                repository = config.repository,
                pullRequestNumber = config.pullRequestNumber,
                requestedBy = config.requestedBy,
                trigger = config.trigger,
            ),
        )) {
            is ReviewOutcome.Published -> println(
                "$REVIEW_PUBLISHED_PREFIX repository=${config.repository} pr=${config.pullRequestNumber} " +
                        "sha=${outcome.sha.take(12)} contextBytes=${outcome.contextBytes}",
            )

            is ReviewOutcome.Skipped -> println(
                "$REVIEW_SKIPPED_PREFIX repository=${config.repository} " +
                        "pr=${config.pullRequestNumber} reason=${outcome.reason}",
            )
        }
    } finally {
        http.close()
    }
}

private const val REVIEW_FAILED_PREFIX = "Review failed: "
private const val REVIEW_PUBLISHED_PREFIX = "AI review published"
private const val REVIEW_SKIPPED_PREFIX = "AI review skipped"
