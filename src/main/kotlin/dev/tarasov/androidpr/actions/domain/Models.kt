package dev.tarasov.androidpr.actions.domain

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

data class ReviewJob(
    val repository: String,
    val pullRequestNumber: Int,
    val requestedBy: String,
    val trigger: Trigger,
)

enum class Trigger { READY_FOR_REVIEW, MANUAL }

data class PullRequestInfo(
    val number: Int,
    val title: String,
    val body: String,
    val state: String,
    val draft: Boolean,
    val baseRef: String,
    val headSha: String,
    val author: String,
)

data class ChangedFile(
    val path: String,
    val status: String,
    val patch: String?,
)

data class SourceFile(
    val path: String,
    val content: String,
    val patch: String? = null,
)

data class ReviewContext(
    val pullRequest: PullRequestInfo,
    val files: List<SourceFile>,
    val totalBytes: Int,
    val truncated: Boolean,
)

@Serializable
enum class RequirementStatus { MET, PARTIAL, NOT_MET, NOT_VERIFIABLE }

@Serializable
data class Evidence(
    val path: String,
    val line: Int? = null,
    val explanation: String,
)

@Serializable
data class RequirementResult(
    val id: String,
    val status: RequirementStatus,
    val summary: String,
    val evidence: List<Evidence> = emptyList(),
)

@Serializable
enum class Severity { INFO, LOW, MEDIUM, HIGH }

@Serializable
data class Finding(
    val severity: Severity,
    val title: String,
    val description: String,
    val path: String? = null,
    val line: Int? = null,
)

@Serializable
data class AiReviewResult(
    @SerialName("overall_summary") val overallSummary: String,
    val requirements: List<RequirementResult>,
    val findings: List<Finding>,
    @SerialName("assessment_questions") val assessmentQuestions: List<String>,
    val limitations: List<String>,
)
