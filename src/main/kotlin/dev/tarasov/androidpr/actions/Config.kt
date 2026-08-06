package dev.tarasov.androidpr.actions

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
    val maxFiles: Int = 60,
    val maxContextBytes: Int = 400 * 1024,
) {
    companion object {
        fun from(args: Array<String>, env: Map<String, String> = System.getenv()): CliConfig {
            val flags = parseFlags(args)
            val repository = flags["repository"] ?: env.required("GITHUB_REPOSITORY")
            require(repository.matches(Regex("[^/]+/[^/]+"))) { "Repository must use owner/name format" }
            val pullRequestNumber = (flags["pr"] ?: env.required("PR_NUMBER")).toIntOrNull()
                ?.takeIf { it > 0 } ?: error("PR number must be a positive integer")
            val requestedBy = flags["requested-by"] ?: env["GITHUB_ACTOR"].orEmpty()
            val trigger = when ((flags["trigger"] ?: env["REVIEW_TRIGGER"] ?: "ready_for_review").lowercase()) {
                "ready_for_review" -> Trigger.READY_FOR_REVIEW
                "manual" -> Trigger.MANUAL
                else -> error("Trigger must be ready_for_review or manual")
            }
            if (trigger == Trigger.MANUAL) require(requestedBy.isNotBlank()) {
                "Manual review requires --requested-by or GITHUB_ACTOR"
            }
            val expectedRepository = env["GITHUB_REPOSITORY"]
            if (expectedRepository != null) require(repository == expectedRepository) {
                "Requested repository does not match the GitHub Actions repository"
            }
            val allowed = env["ALLOWED_REPOSITORIES"]
                ?.split(',')?.map(String::trim)?.filter(String::isNotEmpty)?.toSet()
                ?: setOf(repository)
            return CliConfig(
                repository = repository,
                pullRequestNumber = pullRequestNumber,
                requestedBy = requestedBy,
                trigger = trigger,
                githubToken = env.required("GITHUB_TOKEN"),
                openAiApiKey = env.required("OPENAI_API_KEY"),
                openAiModel = env["OPENAI_MODEL"] ?: "provider-model",
                openAiReasoningEffort = env["OPENAI_REASONING_EFFORT"] ?: "medium",
                allowedRepositories = allowed,
            )
        }

        private fun parseFlags(args: Array<String>): Map<String, String> {
            require(args.size % 2 == 0) { "Arguments must be --name value pairs" }
            return args.asList().chunked(2).associate { (name, value) ->
                require(name.startsWith("--")) { "Unknown argument: $name" }
                name.removePrefix("--") to value
            }
        }
    }
}

private fun Map<String, String>.required(name: String): String =
    get(name)?.takeIf(String::isNotBlank) ?: error("Missing required environment variable: $name")
