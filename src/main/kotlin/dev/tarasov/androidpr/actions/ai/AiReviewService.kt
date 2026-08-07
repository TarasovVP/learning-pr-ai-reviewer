package dev.tarasov.androidpr.actions.ai

import dev.tarasov.androidpr.actions.domain.AiReviewResult
import dev.tarasov.androidpr.actions.domain.ReviewContext
import dev.tarasov.androidpr.actions.rubric.Rubric

interface AiReviewService {
    suspend fun review(context: ReviewContext, rubric: Rubric): AiReviewResult
}
