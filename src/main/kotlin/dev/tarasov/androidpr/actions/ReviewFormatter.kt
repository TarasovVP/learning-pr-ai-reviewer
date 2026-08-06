package dev.tarasov.androidpr.actions

object ReviewFormatter {
    fun format(result: AiReviewResult, context: ReviewContext, rubric: Rubric): String = buildString {
        appendLine("<!-- $REVIEWER_MARKER rubric=${rubric.version} sha=${context.pullRequest.headSha} -->")
        appendLine("## Android PR AI Review")
        appendLine()
        appendLine("**Commit:** `${context.pullRequest.headSha.take(12)}`  ")
        appendLine("**Rubric:** `${rubric.id}@${rubric.version}`")
        appendLine()
        appendLine(result.overallSummary)
        appendLine()
        appendLine("### Requirements")
        appendLine()
        appendLine("| Requirement | Status | Summary |")
        appendLine("|---|---|---|")
        result.requirements.forEach {
            appendLine("| `${it.id.escape()}` | **${it.status}** | ${it.summary.escape()} |")
        }
        if (result.findings.isNotEmpty()) {
            appendLine()
            appendLine("### Findings")
            result.findings.forEach { finding ->
                val location = finding.path?.let { path -> " — `${path.escape()}${finding.line?.let { ":$it" }.orEmpty()}`" }.orEmpty()
                appendLine("- **${finding.severity}: ${finding.title.escape()}**$location — ${finding.description.escape()}")
            }
        }
        if (result.assessmentQuestions.isNotEmpty()) {
            appendLine()
            appendLine("### Assessment questions")
            result.assessmentQuestions.forEach { appendLine("- ${it.escape()}") }
        }
        val limitations = result.limitations.toMutableList()
        limitations += "The reviewer did not build or run the application, tests, emulator, or TalkBack."
        if (context.truncated) limitations += "Repository context was truncated by the pilot limits."
        appendLine()
        appendLine("### Limitations")
        limitations.distinct().forEach { appendLine("- ${it.escape()}") }
        appendLine()
        appendLine("_Advisory result only. Final validation remains with the engineer or mentor._")
    }
}

private fun String.escape(): String = replace("|", "\\|").replace("\n", " ")
