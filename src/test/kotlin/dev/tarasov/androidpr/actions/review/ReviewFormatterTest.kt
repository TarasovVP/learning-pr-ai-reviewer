package dev.tarasov.androidpr.actions.review

import dev.tarasov.androidpr.actions.config.ReviewProtocol
import dev.tarasov.androidpr.actions.domain.AiReviewResult
import dev.tarasov.androidpr.actions.domain.Evidence
import dev.tarasov.androidpr.actions.domain.PullRequestInfo
import dev.tarasov.androidpr.actions.domain.RequirementResult
import dev.tarasov.androidpr.actions.domain.RequirementStatus
import dev.tarasov.androidpr.actions.domain.ReviewContext
import dev.tarasov.androidpr.actions.domain.SourceFile
import dev.tarasov.androidpr.actions.rubric.RubricLoader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReviewFormatterTest {
    @Test
    fun `formats marker escaped evidence and truncation limitation`() {
        val rubric = RubricLoader.load()
        val context = ReviewContext(
            pullRequest = PullRequestInfo(
                number = 3,
                title = "Module 3",
                body = "Module: 3-compose-ui",
                state = "open",
                draft = false,
                baseRef = "main",
                headSha = HEAD_SHA,
                author = "student",
            ),
            files = listOf(SourceFile("UserListScreen.kt", "content")),
            totalBytes = 7,
            truncated = true,
        )
        val result = AiReviewResult(
            overallSummary = "Summary",
            requirements = listOf(
                RequirementResult(
                    id = "animations",
                    status = RequirementStatus.MET,
                    summary = "All states | covered",
                    evidence = listOf(
                        Evidence("app/UserListScreen.kt", 120, "AnimatedContent\nuses state keys"),
                    ),
                ),
            ),
            findings = emptyList(),
            assessmentQuestions = emptyList(),
            limitations = listOf("Repository context was truncated by the pilot limits."),
        )

        val output = ReviewFormatter.format(result, context, rubric)

        assertTrue(
            "<!-- ${ReviewProtocol.REVIEWER_MARKER} rubric=${rubric.id}@${rubric.version} sha=$HEAD_SHA -->" in output,
        )
        assertTrue("All states \\| covered" in output)
        assertTrue("`app/UserListScreen.kt:120` — AnimatedContent uses state keys" in output)
        assertEquals(1, output.windowed(TRUNCATED_LIMITATION.length).count { it == TRUNCATED_LIMITATION })
    }

    private companion object {
        const val HEAD_SHA = "abcdef1234567890"
        const val TRUNCATED_LIMITATION = "Repository context was truncated by the pilot limits."
    }
}
