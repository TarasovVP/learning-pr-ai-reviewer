package dev.tarasov.androidpr.actions.review

import dev.tarasov.androidpr.actions.domain.ChangedFile
import dev.tarasov.androidpr.actions.domain.PullRequestInfo
import dev.tarasov.androidpr.actions.domain.SourceFile
import dev.tarasov.androidpr.actions.github.GitHubService
import dev.tarasov.androidpr.actions.rubric.MODULE_THREE_ID
import dev.tarasov.androidpr.actions.rubric.MODULE_ONE_ID
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
            MODULE_THREE_ID,
        )

        assertEquals(listOf("app/UserListScreen.kt", "app/UserDetailsScreen.kt"), context.files.map(SourceFile::path))
        assertTrue(context.files.first().patch?.contains("AnimatedContent") == true)
    }

    @Test
    fun `collects architecture files for module one`() = runTest {
        val github = ArchitectureCollectorGitHub()
        val context = ContextCollector(github, maxFiles = 4, maxBytes = 500).collect(
            github.repository,
            github.pullRequest,
            MODULE_ONE_ID,
        )

        assertEquals(
            listOf(
                "account/src/main/AccountEntry.kt",
                "settings.gradle.kts",
                "account/build.gradle.kts",
                "app/src/main/settings/SettingsFeatureLauncher.kt",
            ),
            context.files.map(SourceFile::path),
        )
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
        listOf("app/UserListScreen.kt", "app/UserDetailsScreen.kt", "build/generated/UiText.kt")
    override suspend fun getFileContent(repository: String, path: String, ref: String) = "content of $path"
    override suspend fun collaboratorPermission(repository: String, username: String) = "read"
    override suspend fun existingReviewBodies(repository: String, number: Int) = emptyList<String>()
    override suspend fun createCommentReview(repository: String, number: Int, sha: String, body: String) = Unit
}

private class ArchitectureCollectorGitHub : GitHubService {
    val repository = "owner/repository"
    val pullRequest =
        PullRequestInfo(2, "Architecture", "Module: 1-mobile-architecture", "open", false, "main", "sha", "student")

    override suspend fun getPullRequest(repository: String, number: Int) = pullRequest
    override suspend fun listChangedFiles(repository: String, number: Int) =
        listOf(ChangedFile("account/src/main/AccountEntry.kt", "added", "+ interface AccountEntry"))
    override suspend fun listRepositoryPaths(repository: String, ref: String) = listOf(
        "settings.gradle.kts",
        "account/build.gradle.kts",
        "app/src/main/settings/SettingsFeatureLauncher.kt",
        "app/src/main/data/UserDao.kt",
    )
    override suspend fun getFileContent(repository: String, path: String, ref: String) = "content of $path"
    override suspend fun collaboratorPermission(repository: String, username: String) = "read"
    override suspend fun existingReviewBodies(repository: String, number: Int) = emptyList<String>()
    override suspend fun createCommentReview(repository: String, number: Int, sha: String, body: String) = Unit
}
