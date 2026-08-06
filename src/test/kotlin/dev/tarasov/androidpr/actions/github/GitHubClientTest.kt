package dev.tarasov.androidpr.actions.github

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GitHubClientTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `returns null only when collaborator permission endpoint returns not found`() = runTest {
        val client = client { respond(ERROR_BODY, HttpStatusCode.NotFound) }

        assertNull(client.collaboratorPermission(REPOSITORY, USERNAME))
    }

    @Test
    fun `propagates collaborator permission API errors`() = runTest {
        val client = client { respond(ERROR_BODY, HttpStatusCode.Forbidden) }

        val error = assertFailsWith<ApiException> {
            client.collaboratorPermission(REPOSITORY, USERNAME)
        }

        assertEquals(HttpStatusCode.Forbidden.value, error.status)
    }

    @Test
    fun `retries transient collaborator permission error`() = runTest {
        var requests = 0
        val client = client {
            requests++
            if (requests == 1) respond(ERROR_BODY, HttpStatusCode.InternalServerError)
            else respond(PERMISSION_BODY, HttpStatusCode.OK)
        }

        assertEquals("write", client.collaboratorPermission(REPOSITORY, USERNAME))
        assertEquals(2, requests)
    }

    @Test
    fun `rejects truncated recursive repository tree`() = runTest {
        val client = client { respond(TRUNCATED_TREE_BODY, HttpStatusCode.OK) }

        assertFailsWith<GitHubTreeTruncatedException> {
            client.listRepositoryPaths(REPOSITORY, HEAD_SHA)
        }
    }

    @Test
    fun `paginates changed files`() = runTest {
        val client = client { request ->
            val page = request.url.parameters["page"]?.toInt()
            val count = if (page == 1) 100 else 1
            val body = buildJsonArray {
                repeat(count) { index ->
                    add(buildJsonObject {
                        put("filename", "file-$page-$index.kt")
                        put("status", "modified")
                        put("patch", "+ change")
                    })
                }
            }.toString()
            respond(body, HttpStatusCode.OK)
        }

        val files = client.listChangedFiles(REPOSITORY, PULL_REQUEST_NUMBER)

        assertEquals(101, files.size)
        assertEquals("file-2-0.kt", files.last().path)
    }

    @Test
    fun `decodes base64 content and encodes path and ref`() = runTest {
        val client = client { request ->
            assertTrue(request.url.encodedPath.contains("My%20File.kt"))
            assertEquals("feature/test", request.url.parameters["ref"])
            respond(CONTENT_BODY, HttpStatusCode.OK)
        }

        assertEquals("hello", client.getFileContent(REPOSITORY, "folder/My File.kt", "feature/test"))
    }

    private fun client(handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData) =
        GitHubTokenApiClient(
            http = HttpClient(MockEngine { request -> handler(request) }),
            token = "token",
            json = json,
        )

    private companion object {
        const val REPOSITORY = "owner/repository"
        const val USERNAME = "mentor"
        const val HEAD_SHA = "abc123"
        const val PULL_REQUEST_NUMBER = 42
        const val ERROR_BODY = "{\"message\":\"error\"}"
        const val PERMISSION_BODY = "{\"permission\":\"write\"}"
        const val TRUNCATED_TREE_BODY = "{\"truncated\":true,\"tree\":[]}"
        const val CONTENT_BODY = "{\"encoding\":\"base64\",\"content\":\"aGVsbG8=\"}"
    }
}
