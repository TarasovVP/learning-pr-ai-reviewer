package dev.tarasov.androidpr.actions

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
            System.err.println("Review failed: ${error::class.simpleName}: ${error.message}")
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
        val rubric = RubricLoader.load()
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
            rubric = rubric,
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
                "AI review published repository=${config.repository} pr=${config.pullRequestNumber} " +
                    "sha=${outcome.sha.take(12)} contextBytes=${outcome.contextBytes}",
            )
            is ReviewOutcome.Skipped -> println(
                "AI review skipped repository=${config.repository} pr=${config.pullRequestNumber} reason=${outcome.reason}",
            )
        }
    } finally {
        http.close()
    }
}
