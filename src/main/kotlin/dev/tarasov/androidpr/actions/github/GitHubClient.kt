package dev.tarasov.androidpr.actions.github

import dev.tarasov.androidpr.actions.domain.ChangedFile
import dev.tarasov.androidpr.actions.domain.PullRequestInfo
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.network.sockets.ConnectTimeoutException
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
import java.io.IOException
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
    private val apiBase: String = GITHUB_API_BASE,
) : GitHubService {
    override suspend fun getPullRequest(repository: String, number: Int): PullRequestInfo {
        val root = get(pullRequestPath(repository, number)).jsonObject
        return PullRequestInfo(
            number = root.getValue(NUMBER).jsonPrimitive.int,
            title = root.getValue(TITLE).jsonPrimitive.content,
            body = root[BODY]?.jsonPrimitive?.contentOrNull.orEmpty(),
            state = root.getValue(STATE).jsonPrimitive.content,
            draft = root[DRAFT]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false,
            baseRef = root.getValue(BASE).jsonObject.getValue(REF).jsonPrimitive.content,
            headSha = root.getValue(HEAD).jsonObject.getValue(SHA).jsonPrimitive.content,
            author = root.getValue(USER).jsonObject.getValue(LOGIN).jsonPrimitive.content,
            baseSha = root.getValue(BASE).jsonObject.getValue(SHA).jsonPrimitive.content,
        )
    }

    override suspend fun listChangedFiles(repository: String, number: Int): List<ChangedFile> =
        paginate(pullRequestFilesPath(repository, number)).map { item ->
            val obj = item.jsonObject
            ChangedFile(
                path = obj.getValue(FILENAME).jsonPrimitive.content,
                status = obj.getValue(STATUS).jsonPrimitive.content,
                patch = obj[PATCH]?.jsonPrimitive?.contentOrNull,
            )
        }

    override suspend fun listRepositoryPaths(repository: String, ref: String): List<String> {
        val root = get(repositoryTreePath(repository, ref)).jsonObject
        if (root[TRUNCATED]?.jsonPrimitive?.content?.toBooleanStrictOrNull() == true) {
            throw GitHubTreeTruncatedException(repository, ref)
        }
        return root.getValue(TREE).jsonArray.mapNotNull { node ->
            val obj = node.jsonObject
            if (obj[TYPE]?.jsonPrimitive?.content == BLOB) obj[PATH]?.jsonPrimitive?.content else null
        }
    }

    override suspend fun getFileContent(repository: String, path: String, ref: String): String? {
        val root = get(repositoryContentPath(repository, path, ref)).jsonObject
        if (root[ENCODING]?.jsonPrimitive?.content != BASE64) return null
        val encoded = root[CONTENT]?.jsonPrimitive?.content?.replace(NEW_LINE, EMPTY) ?: return null
        return runCatching {
            Base64.getDecoder().decode(encoded).toString(Charsets.UTF_8)
        }.getOrNull()
    }

    override suspend fun collaboratorPermission(repository: String, username: String): String? =
        try {
            get(collaboratorPermissionPath(repository, username))
                .jsonObject[PERMISSION]?.jsonPrimitive?.content
        } catch (error: ApiException) {
            if (error.status == HttpStatusCode.NotFound.value) null else throw error
        }

    override suspend fun existingReviewBodies(repository: String, number: Int): List<String> =
        paginate(pullRequestReviewsPath(repository, number))
            .mapNotNull { it.jsonObject[BODY]?.jsonPrimitive?.contentOrNull }

    override suspend fun createCommentReview(
        repository: String,
        number: Int,
        sha: String,
        body: String,
    ) {
        post(
            pullRequestReviewsPath(repository, number),
            buildJsonObject {
                put(EVENT, COMMENT)
                put(COMMIT_ID, sha)
                put(BODY, body)
            },
        )
    }

    private suspend fun paginate(path: String): List<JsonElement> {
        val result = mutableListOf<JsonElement>()
        var page = 1
        while (true) {
            val separator = if (QUERY_PREFIX in path) QUERY_SEPARATOR else QUERY_PREFIX
            val items = get("$path${separator}per_page=$PAGE_SIZE&page=$page").jsonArray
            result.addAll(items)
            if (items.size < PAGE_SIZE) break
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
        header(GITHUB_API_VERSION_HEADER, GITHUB_API_VERSION)
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
            } catch (error: Throwable) {
                if (!error.isTransientTransportFailure() || attempt == 2) throw error
                last = error
                delay(250L shl attempt)
            }
        }
        throw requireNotNull(last)
    }
}

class ApiException(val status: Int, message: String) :
    RuntimeException("$API_ERROR_PREFIX$status$API_ERROR_SEPARATOR$message")

class GitHubTreeTruncatedException(repository: String, ref: String) :
    RuntimeException("$TRUNCATED_TREE_PREFIX$repository$TRUNCATED_TREE_REF_SEPARATOR$ref")

private const val GITHUB_API_BASE = "https://api.github.com"
private const val GITHUB_API_VERSION_HEADER = "X-GitHub-Api-Version"
private const val GITHUB_API_VERSION = "2022-11-28"
private const val PAGE_SIZE = 100
private const val NUMBER = "number"
private const val TITLE = "title"
private const val BODY = "body"
private const val STATE = "state"
private const val DRAFT = "draft"
private const val BASE = "base"
private const val REF = "ref"
private const val HEAD = "head"
private const val SHA = "sha"
private const val USER = "user"
private const val LOGIN = "login"
private const val FILENAME = "filename"
private const val STATUS = "status"
private const val PATCH = "patch"
private const val TRUNCATED = "truncated"
private const val TREE = "tree"
private const val TYPE = "type"
private const val BLOB = "blob"
private const val PATH = "path"
private const val ENCODING = "encoding"
private const val BASE64 = "base64"
private const val CONTENT = "content"
private const val PERMISSION = "permission"
private const val EVENT = "event"
private const val COMMENT = "COMMENT"
private const val COMMIT_ID = "commit_id"
private const val NEW_LINE = "\n"
private const val EMPTY = ""
private const val QUERY_PREFIX = '?'
private const val QUERY_SEPARATOR = '&'
private const val API_ERROR_PREFIX = "API request failed ("
private const val API_ERROR_SEPARATOR = "): "
private const val TRUNCATED_TREE_PREFIX = "GitHub returned a truncated repository tree for "
private const val TRUNCATED_TREE_REF_SEPARATOR = " at "

private fun pullRequestPath(repository: String, number: Int) = "/repos/$repository/pulls/$number"
private fun pullRequestFilesPath(repository: String, number: Int) =
    "${pullRequestPath(repository, number)}/files"

private fun pullRequestReviewsPath(repository: String, number: Int) =
    "${pullRequestPath(repository, number)}/reviews"

private fun repositoryTreePath(repository: String, ref: String) =
    "/repos/$repository/git/trees/${encode(ref)}?recursive=1"

private fun repositoryContentPath(repository: String, path: String, ref: String) =
    "/repos/$repository/contents/${encodePath(path)}?ref=${encode(ref)}"

private fun collaboratorPermissionPath(repository: String, username: String) =
    "/repos/$repository/collaborators/${encode(username)}/permission"

private fun encode(value: String): String =
    URLEncoder.encode(value, Charsets.UTF_8).replace("+", "%20")

private fun encodePath(path: String): String = path.split('/').joinToString("/") { encode(it) }

private fun Throwable.isTransientTransportFailure(): Boolean =
    this is HttpRequestTimeoutException || this is ConnectTimeoutException ||
        this is IOException
