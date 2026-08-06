package dev.tarasov.androidpr.actions.review

import dev.tarasov.androidpr.actions.config.ReviewProtocol
import dev.tarasov.androidpr.actions.config.SkipReasons
import dev.tarasov.androidpr.actions.domain.AiReviewResult
import dev.tarasov.androidpr.actions.domain.ChangedFile
import dev.tarasov.androidpr.actions.domain.PullRequestInfo
import dev.tarasov.androidpr.actions.domain.RequirementResult
import dev.tarasov.androidpr.actions.domain.RequirementStatus
import dev.tarasov.androidpr.actions.domain.ReviewContext
import dev.tarasov.androidpr.actions.domain.ReviewJob
import dev.tarasov.androidpr.actions.domain.Trigger
import dev.tarasov.androidpr.actions.github.GitHubService
import dev.tarasov.androidpr.actions.openai.AiReviewService
import dev.tarasov.androidpr.actions.rubric.Rubric
import dev.tarasov.androidpr.actions.rubric.RubricLoader
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ReviewCoordinatorTest {
    @Test
    fun `publishes advisory review for valid pull request`() = runTest {
        val github = FakeGitHub()
        val coordinator = coordinator(github)

        val outcome = coordinator.process(github.job())

        assertIs<ReviewOutcome.Published>(outcome)
        assertEquals(1, github.published.size)
        assert(ReviewProtocol.REVIEWER_MARKER in github.published.single())
    }

    @Test
    fun `deduplicates same SHA and rubric version`() = runTest {
        val github = FakeGitHub().apply {
            reviewBodies +=
                "<!-- ${ReviewProtocol.REVIEWER_MARKER} rubric=sample-assignment@2.0.0 sha=abc123 -->"
        }

        val outcome = coordinator(github).process(github.job())

        assertIs<ReviewOutcome.Skipped>(outcome)
        assertEquals(SkipReasons.ALREADY_REVIEWED, outcome.reason)
    }

    @Test
    fun `rejects manual request from read-only stranger`() = runTest {
        val github = FakeGitHub(permission = "read")

        val outcome = coordinator(github).process(github.job(Trigger.MANUAL, "stranger"))

        assertIs<ReviewOutcome.Skipped>(outcome)
        assertEquals(SkipReasons.UNAUTHORIZED_REQUESTER, outcome.reason)
    }

    private fun coordinator(github: FakeGitHub): ReviewCoordinator {
        return ReviewCoordinator(
            github,
            FakeAi(),
            ContextCollector(github, 60, 400 * 1024),
            RubricLoader,
            setOf(github.repository),
        )
    }
}

private class FakeAi : AiReviewService {
    override suspend fun review(context: ReviewContext, rubric: Rubric) = AiReviewResult(
        overallSummary = "Static review completed.",
        requirements = rubric.requirementIds.map {
            RequirementResult(it, RequirementStatus.PARTIAL, "Partially visible in static code.")
        },
        findings = emptyList(),
        assessmentQuestions = listOf("Why was this API selected?"),
        limitations = listOf("Runtime behavior was not observed."),
    )
}

private class FakeGitHub(private val permission: String = "write") : GitHubService {
    val repository = "owner/repository"
    val published = mutableListOf<String>()
    val reviewBodies = mutableListOf<String>()

    fun job(trigger: Trigger = Trigger.READY_FOR_REVIEW, requestedBy: String = "student") =
        ReviewJob(repository, 42, requestedBy, trigger)

    override suspend fun getPullRequest(repository: String, number: Int) = PullRequestInfo(
        number, "Compose assignment", "Module: 3-compose-ui", "open", false, "main", "abc123", "student",
    )
    override suspend fun listChangedFiles(repository: String, number: Int) =
        listOf(ChangedFile("app/src/main/UserListScreen.kt", "modified", "@@ change"))
    override suspend fun listRepositoryPaths(repository: String, ref: String) = emptyList<String>()
    override suspend fun getFileContent(repository: String, path: String, ref: String) =
        "@Composable fun Screen() = Unit"
    override suspend fun collaboratorPermission(repository: String, username: String) = permission
    override suspend fun existingReviewBodies(repository: String, number: Int) = reviewBodies
    override suspend fun createCommentReview(repository: String, number: Int, sha: String, body: String) {
        published += body
    }
}
