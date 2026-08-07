package dev.tarasov.androidpr.actions.review

import dev.tarasov.androidpr.actions.domain.AiReviewResult
import dev.tarasov.androidpr.actions.domain.Evidence
import dev.tarasov.androidpr.actions.domain.Finding
import dev.tarasov.androidpr.actions.domain.PullRequestInfo
import dev.tarasov.androidpr.actions.domain.RequirementResult
import dev.tarasov.androidpr.actions.domain.RequirementStatus
import dev.tarasov.androidpr.actions.domain.ReviewContext
import dev.tarasov.androidpr.actions.domain.Severity
import dev.tarasov.androidpr.actions.domain.SourceFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReviewResultValidatorTest {
    @Test
    fun `keeps valid evidence and normalizes its path`() {
        val result = result(
            RequirementResult(
                id = "requirement",
                status = RequirementStatus.MET,
                summary = "Implemented.",
                evidence = listOf(Evidence("./app/File.kt", 2, "Evidence")),
            ),
        )

        val validated = ReviewResultValidator.validate(result, context())

        assertEquals(RequirementStatus.MET, validated.requirements.single().status)
        assertEquals("app/File.kt", validated.requirements.single().evidence.single().path)
    }

    @Test
    fun `downgrades claim without valid evidence and drops hallucinated finding`() {
        val result = result(
            RequirementResult(
                id = "requirement",
                status = RequirementStatus.MET,
                summary = "Implemented.",
                evidence = listOf(Evidence("missing/File.kt", 100, "Not supplied")),
            ),
            findings = listOf(
                Finding(Severity.HIGH, "Missing", "Invalid location", "app/File.kt", 99),
            ),
        )

        val validated = ReviewResultValidator.validate(result, context())

        assertEquals(RequirementStatus.NOT_VERIFIABLE, validated.requirements.single().status)
        assertTrue(validated.findings.isEmpty())
        assertTrue(validated.limitations.any { "2" in it })
    }

    @Test
    fun `accepts path evidence for removed file`() {
        val result = result(
            RequirementResult(
                id = "requirement",
                status = RequirementStatus.NOT_MET,
                summary = "Required file was removed.",
                evidence = listOf(Evidence("app/Removed.kt", 200, "Deletion patch")),
            ),
        )
        val context = context().copy(
            files = context().files + SourceFile(
                path = "app/Removed.kt",
                content = "",
                patch = "@@ -198,5 +0,0 @@\n- old code",
                status = "removed",
            ),
        )

        val validated = ReviewResultValidator.validate(result, context)

        assertEquals(RequirementStatus.NOT_MET, validated.requirements.single().status)
    }

    @Test
    fun `rejects out of range evidence for removed file`() {
        val result = result(
            RequirementResult(
                id = "requirement",
                status = RequirementStatus.NOT_MET,
                summary = "Required file was removed.",
                evidence = listOf(Evidence("app/Removed.kt", 300, "Deletion patch")),
            ),
        )
        val context = context().copy(
            files = context().files + SourceFile(
                path = "app/Removed.kt",
                content = "",
                patch = "@@ -198,5 +0,0 @@\n- old code",
                status = "removed",
            ),
        )

        val validated = ReviewResultValidator.validate(result, context)

        assertEquals(RequirementStatus.NOT_VERIFIABLE, validated.requirements.single().status)
    }

    private fun result(
        requirement: RequirementResult,
        findings: List<Finding> = emptyList(),
    ) = AiReviewResult(
        overallSummary = "Summary",
        requirements = listOf(requirement),
        findings = findings,
        assessmentQuestions = emptyList(),
        limitations = emptyList(),
    )

    private fun context() = ReviewContext(
        pullRequest = PullRequestInfo(
            1, "Title", "Module: 3-compose-ui", "open", false, "main", "head", "student", "base",
        ),
        files = listOf(SourceFile("app/File.kt", "line one\nline two")),
        totalBytes = 17,
        truncated = false,
    )
}
