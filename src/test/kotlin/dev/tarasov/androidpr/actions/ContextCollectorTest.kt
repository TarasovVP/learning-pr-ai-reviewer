package dev.tarasov.androidpr.actions

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ContextCollectorTest {
    @Test
    fun `collects changed and related module files within limits`() = runTest {
        val github = CollectorGitHub()
        val context = ContextCollector(github, maxFiles = 3, maxBytes = 200).collect(
            github.repository,
            github.pullRequest,
        )

        assertEquals(listOf("app/UserListScreen.kt", "app/UserDetails.kt"), context.files.map(SourceFile::path))
        assertTrue(context.files.first().patch?.contains("AnimatedContent") == true)
    }
}

private class CollectorGitHub : GitHubService {
    val repository = "owner/repository"
    val pullRequest = PullRequestInfo(1, "Title", "Module: 3-compose-ui", "open", false, "main", "sha", "student")

    override suspend fun getPullRequest(repository: String, number: Int) = pullRequest
    override suspend fun listChangedFiles(repository: String, number: Int) = listOf(
        ChangedFile("app/UserListScreen.kt", "modified", "+ AnimatedContent"),
        ChangedFile("app/icon.png", "modified", null),
    )
    override suspend fun listRepositoryPaths(repository: String, ref: String) =
        listOf("app/UserListScreen.kt", "app/UserDetails.kt", "build/generated/UiText.kt")
    override suspend fun getFileContent(repository: String, path: String, ref: String) = "content of $path"
    override suspend fun collaboratorPermission(repository: String, username: String) = "read"
    override suspend fun existingReviewBodies(repository: String, number: Int) = emptyList<String>()
    override suspend fun createCommentReview(repository: String, number: Int, sha: String, body: String) = Unit
}
