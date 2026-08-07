package dev.tarasov.androidpr.actions.config

import dev.tarasov.androidpr.actions.domain.Trigger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ConfigTest {
    private val baseEnv = mapOf(
        EnvironmentNames.GITHUB_TOKEN to "github-token",
        EnvironmentNames.AI_API_KEY to "ai-key",
        EnvironmentNames.GITHUB_REPOSITORY to "owner/repository",
        EnvironmentNames.PR_NUMBER to "7",
        EnvironmentNames.GITHUB_ACTOR to "student",
    )

    @Test
    fun `reads GitHub Actions environment`() {
        val config = CliConfig.from(emptyArray(), baseEnv)

        assertEquals("owner/repository", config.repository)
        assertEquals(7, config.pullRequestNumber)
        assertEquals(Trigger.READY_FOR_REVIEW, config.trigger)
        assertEquals(setOf("owner/repository"), config.allowedRepositories)
        assertEquals(AiProvider.OPENAI, config.aiProvider)
        assertEquals(Defaults.AI_MODEL, config.aiModel)
    }

    @Test
    fun `CLI flags override non-security event values`() {
        val config = CliConfig.from(
            arrayOf("--pr", "9", "--trigger", "manual", "--requested-by", "mentor"),
            baseEnv,
        )

        assertEquals(9, config.pullRequestNumber)
        assertEquals(Trigger.MANUAL, config.trigger)
        assertEquals("mentor", config.requestedBy)
    }

    @Test
    fun `rejects repository different from workflow repository`() {
        assertFailsWith<IllegalArgumentException> {
            CliConfig.from(arrayOf("--repository", "other/repository", "--pr", "1"), baseEnv)
        }
    }

    @Test
    fun `reads provider-neutral AI configuration`() {
        val config = CliConfig.from(
            emptyArray(),
            baseEnv + mapOf(
                EnvironmentNames.AI_PROVIDER to "OPENAI",
                EnvironmentNames.AI_MODEL to "custom-model",
                EnvironmentNames.AI_REASONING_EFFORT to "high",
            ),
        )

        assertEquals(AiProvider.OPENAI, config.aiProvider)
        assertEquals("ai-key", config.aiApiKey)
        assertEquals("custom-model", config.aiModel)
        assertEquals("high", config.aiReasoningEffort)
    }

    @Test
    fun `rejects unsupported AI provider`() {
        assertFailsWith<IllegalStateException> {
            CliConfig.from(
                emptyArray(),
                baseEnv + (EnvironmentNames.AI_PROVIDER to "unsupported"),
            )
        }
    }
}
