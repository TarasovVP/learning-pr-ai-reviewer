package dev.tarasov.androidpr.actions

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
            "review repository={} pr={} result={} durationMs={}",
            job.repository,
            job.pullRequestNumber,
            outcome::class.simpleName,
            timed.duration.inWholeMilliseconds,
        )
        return outcome
    }

    private suspend fun processInternal(job: ReviewJob): ReviewOutcome {
        if (job.repository !in allowedRepositories) return ReviewOutcome.Skipped("repository is not allowed")
        val pr = github.getPullRequest(job.repository, job.pullRequestNumber)
        if (pr.state != "open") return ReviewOutcome.Skipped("pull request is not open")
        if (pr.baseRef != "main") return ReviewOutcome.Skipped("base branch is not main")
        if (pr.body.moduleId() != SUPPORTED_MODULE) return ReviewOutcome.Skipped("unsupported or missing module marker")
        if (job.trigger == Trigger.MANUAL && !isTrustedManualRequester(job, pr)) {
            return ReviewOutcome.Skipped("manual requester is not authorized")
        }
        val marker = "$REVIEWER_MARKER rubric=${rubric.version} sha=${pr.headSha}"
        if (github.existingReviewBodies(job.repository, pr.number).any { marker in it }) {
            return ReviewOutcome.Skipped("commit already reviewed with this rubric")
        }
        val context = collector.collect(job.repository, pr)
        val result = ai.review(context, rubric)
        val body = ReviewFormatter.format(result, context, rubric)
        github.createCommentReview(job.repository, pr.number, pr.headSha, body)
        return ReviewOutcome.Published(pr.headSha, context.totalBytes)
    }

    private suspend fun isTrustedManualRequester(job: ReviewJob, pr: PullRequestInfo): Boolean {
        if (job.requestedBy.equals(pr.author, ignoreCase = true)) return true
        return github.collaboratorPermission(job.repository, job.requestedBy) in setOf("write", "maintain", "admin")
    }
}
