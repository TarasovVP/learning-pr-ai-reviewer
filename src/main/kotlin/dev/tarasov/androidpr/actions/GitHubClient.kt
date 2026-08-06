package dev.tarasov.androidpr.actions

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.accept
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.net.URLEncoder
import java.util.Base64

interface GitHubService {
    suspend fun getPullRequest(repository: String, number: Int): PullRequestInfo
    suspend fun listChangedFiles(repository: String, number: Int): List<ChangedFile>
    suspend fun listRepositoryPaths(repository: String, ref: String): List<String>
    suspend fun getFileContent(repository: String, path: String, ref: String): String?
    suspend fun collaboratorPermission(repository: String, username: String): String?
    suspend fun existingReviewBodies(repository: String, number: Int): List<String>
    suspend fun createCommentReview(repository: String, number: Int, sha: String, body: String)
}

class GitHubTokenApiClient(
    private val http: HttpClient,
    private val token: String,
    private val json: Json,
    private val apiBase: String = "https://api.github.com",
) : GitHubService {
    override suspend fun getPullRequest(repository: String, number: Int): PullRequestInfo {
        val root = get("/repos/$repository/pulls/$number").jsonObject
        return PullRequestInfo(
            number = root.getValue("number").jsonPrimitive.int,
            title = root.getValue("title").jsonPrimitive.content,
            body = root["body"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            state = root.getValue("state").jsonPrimitive.content,
            draft = root["draft"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false,
            baseRef = root.getValue("base").jsonObject.getValue("ref").jsonPrimitive.content,
            headSha = root.getValue("head").jsonObject.getValue("sha").jsonPrimitive.content,
            author = root.getValue("user").jsonObject.getValue("login").jsonPrimitive.content,
        )
    }

    override suspend fun listChangedFiles(repository: String, number: Int): List<ChangedFile> =
        paginate("/repos/$repository/pulls/$number/files").map { item ->
            val obj = item.jsonObject
            ChangedFile(
                path = obj.getValue("filename").jsonPrimitive.content,
                status = obj.getValue("status").jsonPrimitive.content,
                patch = obj["patch"]?.jsonPrimitive?.contentOrNull,
            )
        }

    override suspend fun listRepositoryPaths(repository: String, ref: String): List<String> {
        val root = get("/repos/$repository/git/trees/${encode(ref)}?recursive=1").jsonObject
        return root.getValue("tree").jsonArray.mapNotNull { node ->
            val obj = node.jsonObject
            if (obj["type"]?.jsonPrimitive?.content == "blob") obj["path"]?.jsonPrimitive?.content else null
        }
    }

    override suspend fun getFileContent(repository: String, path: String, ref: String): String? {
        val root = get("/repos/$repository/contents/${encodePath(path)}?ref=${encode(ref)}").jsonObject
        if (root["encoding"]?.jsonPrimitive?.content != "base64") return null
        val encoded = root["content"]?.jsonPrimitive?.content?.replace("\n", "") ?: return null
        return runCatching { Base64.getDecoder().decode(encoded).toString(Charsets.UTF_8) }.getOrNull()
    }

    override suspend fun collaboratorPermission(repository: String, username: String): String? = runCatching {
        get("/repos/$repository/collaborators/${encode(username)}/permission")
            .jsonObject["permission"]?.jsonPrimitive?.content
    }.getOrNull()

    override suspend fun existingReviewBodies(repository: String, number: Int): List<String> =
        paginate("/repos/$repository/pulls/$number/reviews")
            .mapNotNull { it.jsonObject["body"]?.jsonPrimitive?.contentOrNull }

    override suspend fun createCommentReview(repository: String, number: Int, sha: String, body: String) {
        post(
            "/repos/$repository/pulls/$number/reviews",
            buildJsonObject {
                put("event", "COMMENT")
                put("commit_id", sha)
                put("body", body)
            },
        )
    }

    private suspend fun paginate(path: String): List<JsonElement> {
        val result = mutableListOf<JsonElement>()
        var page = 1
        while (true) {
            val separator = if ('?' in path) '&' else '?'
            val items = get("$path${separator}per_page=100&page=$page").jsonArray
            result.addAll(items)
            if (items.size < 100) break
            page++
        }
        return result
    }

    private suspend fun get(path: String): JsonElement = retrying {
        val response = http.get("$apiBase$path") { githubHeaders() }
        checkResponse(response.status, response.body<String>())
    }

    private suspend fun post(path: String, body: JsonObject): JsonElement = retrying {
        val response = http.post("$apiBase$path") {
            githubHeaders()
            contentType(ContentType.Application.Json)
            setBody(body.toString())
        }
        checkResponse(response.status, response.body<String>())
    }

    private fun io.ktor.client.request.HttpRequestBuilder.githubHeaders() {
        accept(ContentType.Application.Json)
        header("X-GitHub-Api-Version", "2022-11-28")
        bearerAuth(token)
    }

    private fun checkResponse(status: HttpStatusCode, body: String): JsonElement {
        if (status.value !in 200..299) throw ApiException(status.value, body.take(500))
        return if (body.isBlank()) JsonObject(emptyMap()) else json.parseToJsonElement(body)
    }

    private suspend fun <T> retrying(block: suspend () -> T): T {
        var last: Throwable? = null
        repeat(3) { attempt ->
            try {
                return block()
            } catch (error: ApiException) {
                last = error
                if (error.status !in listOf(429, 500, 502, 503, 504) || attempt == 2) throw error
                delay(250L shl attempt)
            }
        }
        throw requireNotNull(last)
    }
}

class ApiException(val status: Int, message: String) : RuntimeException("API request failed ($status): $message")

private fun encode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8).replace("+", "%20")
private fun encodePath(path: String): String = path.split('/').joinToString("/") { encode(it) }
