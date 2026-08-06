package dev.tarasov.androidpr.actions.config

import dev.tarasov.androidpr.actions.domain.Trigger

data class CliConfig(
    val repository: String,
    val pullRequestNumber: Int,
    val requestedBy: String,
    val trigger: Trigger,
    val githubToken: String,
    val openAiApiKey: String,
    val openAiModel: String,
    val openAiReasoningEffort: String,
    val allowedRepositories: Set<String>,
    val maxFiles: Int = Defaults.MAX_FILES,
    val maxContextBytes: Int = Defaults.MAX_CONTEXT_BYTES,
) {
    companion object {
        fun from(args: Array<String>, env: Map<String, String> = System.getenv()): CliConfig {
            val flags = parseFlags(args)
            val repository =
                flags[CliFlags.REPOSITORY] ?: env.required(EnvironmentNames.GITHUB_REPOSITORY)
            require(repository.matches(REPOSITORY_PATTERN)) { REPOSITORY_FORMAT_ERROR }
            val pullRequestNumber =
                (flags[CliFlags.PULL_REQUEST] ?: env.required(EnvironmentNames.PR_NUMBER))
                    .toIntOrNull()?.takeIf { it > 0 } ?: error(PR_NUMBER_ERROR)
            val requestedBy =
                flags[CliFlags.REQUESTED_BY] ?: env[EnvironmentNames.GITHUB_ACTOR].orEmpty()
            val trigger = when (
                (flags[CliFlags.TRIGGER] ?: env[EnvironmentNames.REVIEW_TRIGGER]
                ?: ReviewProtocol.READY_TRIGGER)
                    .lowercase()
            ) {
                ReviewProtocol.READY_TRIGGER -> Trigger.READY_FOR_REVIEW
                ReviewProtocol.MANUAL_TRIGGER -> Trigger.MANUAL
                else -> error(TRIGGER_ERROR)
            }
            if (trigger == Trigger.MANUAL) require(requestedBy.isNotBlank()) {
                MANUAL_REQUESTER_ERROR
            }
            val expectedRepository = env[EnvironmentNames.GITHUB_REPOSITORY]
            if (expectedRepository != null) require(repository == expectedRepository) {
                REPOSITORY_MISMATCH_ERROR
            }
            val allowed = env[EnvironmentNames.ALLOWED_REPOSITORIES]
                ?.split(',')?.map(String::trim)?.filter(String::isNotEmpty)?.toSet()
                ?: setOf(repository)
            return CliConfig(
                repository = repository,
                pullRequestNumber = pullRequestNumber,
                requestedBy = requestedBy,
                trigger = trigger,
                githubToken = env.required(EnvironmentNames.GITHUB_TOKEN),
                openAiApiKey = env.required(EnvironmentNames.OPENAI_API_KEY),
                openAiModel = env[EnvironmentNames.OPENAI_MODEL] ?: Defaults.OPENAI_MODEL,
                openAiReasoningEffort = env[EnvironmentNames.OPENAI_REASONING_EFFORT]
                    ?: Defaults.OPENAI_REASONING_EFFORT,
                allowedRepositories = allowed,
            )
        }

        private fun parseFlags(args: Array<String>): Map<String, String> {
            require(args.size % 2 == 0) { ARGUMENTS_ERROR }
            return args.asList().chunked(2).associate { (name, value) ->
                require(name.startsWith(CliFlags.PREFIX)) { "$UNKNOWN_ARGUMENT_PREFIX$name" }
                name.removePrefix(CliFlags.PREFIX) to value
            }
        }

        private val REPOSITORY_PATTERN = Regex("[^/]+/[^/]+")
        private const val REPOSITORY_FORMAT_ERROR = "Repository must use owner/name format"
        private const val PR_NUMBER_ERROR = "PR number must be a positive integer"
        private const val TRIGGER_ERROR = "Trigger must be ready_for_review or manual"
        private const val MANUAL_REQUESTER_ERROR =
            "Manual review requires --requested-by or GITHUB_ACTOR"
        private const val REPOSITORY_MISMATCH_ERROR =
            "Requested repository does not match the GitHub Actions repository"
        private const val ARGUMENTS_ERROR = "Arguments must be --name value pairs"
        private const val UNKNOWN_ARGUMENT_PREFIX = "Unknown argument: "
    }
}

private fun Map<String, String>.required(name: String): String =
    get(name)?.takeIf(String::isNotBlank) ?: error("$MISSING_ENVIRONMENT_PREFIX$name")

private const val MISSING_ENVIRONMENT_PREFIX = "Missing required environment variable: "
