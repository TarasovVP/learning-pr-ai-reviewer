package dev.tarasov.androidpr.actions

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlin.test.Test
import kotlin.test.assertEquals

class OpenAiClientTest {
    @Test
    fun `parses structured response and fills missing rubric requirements`() = runTest {
        val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
        val modelResult = AiReviewResult(
            overallSummary = "Static review completed.",
            requirements = listOf(RequirementResult("animations", RequirementStatus.MET, "Visible in code.")),
            findings = emptyList(),
            assessmentQuestions = emptyList(),
            limitations = emptyList(),
        )
        val responseBody = buildJsonObject {
            putJsonArray("output") {
                add(buildJsonObject {
                    putJsonArray("content") {
                        add(buildJsonObject {
                            put("type", "output_text")
                            put("text", json.encodeToString(modelResult))
                        })
                    }
                })
            }
        }.toString()
        val client = OpenAiReviewClient(
            http = HttpClient(MockEngine { request ->
                assertEquals("Bearer test-key", request.headers[HttpHeaders.Authorization])
                respond(responseBody, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()))
            }),
            apiKey = "test-key",
            model = "test-model",
            reasoningEffort = "medium",
            json = json,
        )
        val rubric = RubricLoader.load()
        val result = client.review(
            ReviewContext(
                PullRequestInfo(1, "Title", "Module: 3-compose-ui", "open", false, "main", "sha", "author"),
                listOf(SourceFile("UserListScreen.kt", "fun screen() = Unit")),
                20,
                false,
            ),
            rubric,
        )
        assertEquals(rubric.requirementIds.size, result.requirements.size)
        assertEquals(RequirementStatus.MET, result.requirements.first { it.id == "animations" }.status)
        assertEquals(
            RequirementStatus.NOT_VERIFIABLE,
            result.requirements.first { it.id == "accessibility" }.status,
        )
    }
}
