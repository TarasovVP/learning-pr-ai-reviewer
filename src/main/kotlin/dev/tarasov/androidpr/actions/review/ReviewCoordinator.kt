package dev.tarasov.androidpr.actions.review

import dev.tarasov.androidpr.actions.config.Defaults
import dev.tarasov.androidpr.actions.config.ReviewProtocol
import dev.tarasov.androidpr.actions.config.SkipReasons
import dev.tarasov.androidpr.actions.domain.PullRequestInfo
import dev.tarasov.androidpr.actions.domain.ReviewJob
import dev.tarasov.androidpr.actions.domain.Trigger
import dev.tarasov.androidpr.actions.github.GitHubService
import dev.tarasov.androidpr.actions.openai.AiReviewService
import dev.tarasov.androidpr.actions.rubric.Rubric
import dev.tarasov.androidpr.actions.rubric.moduleId
import org.slf4j.LoggerFactory
import kotlin.time.measureTimedValue

sealed interface ReviewOutcome {
    data class Published(val sha: String, val contextBytes: Int) : ReviewOutcome
    data class Skipped(val reason: String) : ReviewOutcome
}

class ReviewCoordinator(
    private val github: GitHubService,
    private val ai: AiReviewService,
    private val collector: ContextCollector,
    private val rubric: Rubric,
    private val allowedRepositories: Set<String>,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    suspend fun process(job: ReviewJob): ReviewOutcome {
        val timed = measureTimedValue { processInternal(job) }
        val outcome = timed.value
        logger.info(
            REVIEW_LOG_TEMPLATE,
            job.repository,
            job.pullRequestNumber,
            outcome::class.simpleName,
            timed.duration.inWholeMilliseconds,
        )
        return outcome
    }

    private suspend fun processInternal(job: ReviewJob): ReviewOutcome {
        if (job.repository !in allowedRepositories) return ReviewOutcome.Skipped(SkipReasons.REPOSITORY_NOT_ALLOWED)
        val pr = github.getPullRequest(job.repository, job.pullRequestNumber)
        if (pr.state != ReviewProtocol.OPEN_PULL_REQUEST) return ReviewOutcome.Skipped(SkipReasons.PULL_REQUEST_NOT_OPEN)
        if (pr.baseRef != Defaults.BASE_BRANCH) return ReviewOutcome.Skipped(SkipReasons.WRONG_BASE_BRANCH)
        if (pr.body.moduleId() != ReviewProtocol.SUPPORTED_MODULE) return ReviewOutcome.Skipped(
            SkipReasons.UNSUPPORTED_MODULE
        )
        if (job.trigger == Trigger.MANUAL && !isTrustedManualRequester(job, pr)) {
            return ReviewOutcome.Skipped(SkipReasons.UNAUTHORIZED_REQUESTER)
        }
        val marker = "${ReviewProtocol.REVIEWER_MARKER} rubric=${rubric.version} sha=${pr.headSha}"
        if (github.existingReviewBodies(job.repository, pr.number).any { marker in it }) {
            return ReviewOutcome.Skipped(SkipReasons.ALREADY_REVIEWED)
        }
        val context = collector.collect(job.repository, pr)
        val result = ai.review(context, rubric)
        val body = ReviewFormatter.format(result, context, rubric)
        github.createCommentReview(job.repository, pr.number, pr.headSha, body)
        return ReviewOutcome.Published(pr.headSha, context.totalBytes)
    }

    private suspend fun isTrustedManualRequester(job: ReviewJob, pr: PullRequestInfo): Boolean {
        if (job.requestedBy.equals(pr.author, ignoreCase = true)) return true
        return github.collaboratorPermission(
            job.repository,
            job.requestedBy
        ) in ReviewProtocol.TRUSTED_PERMISSIONS
    }
}

private const val REVIEW_LOG_TEMPLATE = "review repository={} pr={} result={} durationMs={}"
