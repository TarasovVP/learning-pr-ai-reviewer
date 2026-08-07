package dev.tarasov.androidpr.actions.review

import dev.tarasov.androidpr.actions.config.ReviewProtocol
import dev.tarasov.androidpr.actions.domain.AiReviewResult
import dev.tarasov.androidpr.actions.domain.Evidence
import dev.tarasov.androidpr.actions.domain.ReviewContext
import dev.tarasov.androidpr.actions.rubric.Rubric

object ReviewFormatter {
    fun format(result: AiReviewResult, context: ReviewContext, rubric: Rubric): String =
        buildString {
            appendLine(
                "<!-- ${ReviewProtocol.REVIEWER_MARKER} rubric=${rubric.id}@${rubric.version} " +
                    "head=${context.pullRequest.headSha} base=${context.pullRequest.baseSha} -->",
            )
            appendLine(REVIEW_TITLE)
            appendLine()
            appendLine("**Commit:** `${context.pullRequest.headSha.take(12)}`  ")
            appendLine("**Base:** `${context.pullRequest.baseSha.take(12)}`  ")
            appendLine("**Rubric:** `${rubric.id}@${rubric.version}`")
            appendLine()
            appendLine(result.overallSummary)
            appendLine()
            appendLine(REQUIREMENTS_TITLE)
            appendLine()
            appendLine(REQUIREMENTS_HEADER)
            appendLine(REQUIREMENTS_SEPARATOR)
            result.requirements.forEach {
                appendLine(
                    "| `${it.id.escape()}` | **${it.status}** | ${it.summary.escape()} | " +
                            "${it.evidence.formatEvidence()} |"
                )
            }
            if (result.findings.isNotEmpty()) {
                appendLine()
                appendLine(FINDINGS_TITLE)
                result.findings.forEach { finding ->
                    val location = finding.path?.let { path ->
                        " — `${path.escape()}${
                            finding.line?.let { ":$it" }.orEmpty()
                        }`"
                    }.orEmpty()
                    appendLine("- **${finding.severity}: ${finding.title.escape()}**$location — ${finding.description.escape()}")
                }
            }
            if (result.assessmentQuestions.isNotEmpty()) {
                appendLine()
                appendLine(ASSESSMENT_TITLE)
                result.assessmentQuestions.forEach { appendLine("- ${it.escape()}") }
            }
            val limitations = result.limitations.toMutableList()
            limitations += STATIC_REVIEW_LIMITATION
            if (context.truncated) limitations += TRUNCATED_CONTEXT_LIMITATION
            if (context.redactedFiles.isNotEmpty()) {
                limitations += "$REDACTED_CONTEXT_LIMITATION ${context.redactedFiles.joinToString { "`${it.escape()}`" }}"
            }
            if (context.skippedFiles.isNotEmpty()) {
                limitations += "$SKIPPED_CONTEXT_LIMITATION ${context.skippedFiles.joinToString { "`${it.escape()}`" }}"
            }
            appendLine()
            appendLine(LIMITATIONS_TITLE)
            limitations.distinct().forEach { appendLine("- ${it.escape()}") }
            appendLine()
            appendLine(ADVISORY_NOTICE)
        }
}

private fun List<Evidence>.formatEvidence(): String =
    if (isEmpty()) NO_EVIDENCE else joinToString(EVIDENCE_SEPARATOR) { evidence ->
        val location = evidence.line?.let { line -> "${evidence.path}:$line" } ?: evidence.path
        "`${location.escape()}` — ${evidence.explanation.escape()}"
    }

private fun String.escape(): String =
    replace(TABLE_SEPARATOR, ESCAPED_TABLE_SEPARATOR).replace(NEW_LINE, SPACE)

private const val REVIEW_TITLE = "## Android PR AI Review"
private const val REQUIREMENTS_TITLE = "### Requirements"
private const val REQUIREMENTS_HEADER = "| Requirement | Status | Summary | Evidence |"
private const val REQUIREMENTS_SEPARATOR = "|---|---|---|---|"
private const val FINDINGS_TITLE = "### Findings"
private const val ASSESSMENT_TITLE = "### Assessment questions"
private const val LIMITATIONS_TITLE = "### Limitations"
private const val STATIC_REVIEW_LIMITATION =
    "The reviewer did not build or run the application, tests, emulator, or TalkBack."
private const val TRUNCATED_CONTEXT_LIMITATION =
    "Repository context was truncated by the pilot limits."
private const val REDACTED_CONTEXT_LIMITATION =
    "Potential secret values were redacted before AI analysis in:"
private const val SKIPPED_CONTEXT_LIMITATION =
    "The following files could not be read through the GitHub Contents API:"
private const val ADVISORY_NOTICE =
    "_Advisory result only. Final validation remains with the engineer or mentor._"
private const val NO_EVIDENCE = "—"
private const val EVIDENCE_SEPARATOR = "<br>"
private const val TABLE_SEPARATOR = "|"
private const val ESCAPED_TABLE_SEPARATOR = "\\|"
private const val NEW_LINE = "\n"
private const val SPACE = " "
