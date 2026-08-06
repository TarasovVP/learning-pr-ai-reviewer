package dev.tarasov.androidpr.actions.config

object Defaults {
    const val OPENAI_MODEL = "provider-model"
    const val OPENAI_REASONING_EFFORT = "medium"
    const val BASE_BRANCH = "main"
    const val MAX_FILES = 60
    const val MAX_CONTEXT_BYTES = 400 * 1024
}

object EnvironmentNames {
    const val GITHUB_REPOSITORY = "GITHUB_REPOSITORY"
    const val PR_NUMBER = "PR_NUMBER"
    const val GITHUB_ACTOR = "GITHUB_ACTOR"
    const val REVIEW_TRIGGER = "REVIEW_TRIGGER"
    const val ALLOWED_REPOSITORIES = "ALLOWED_REPOSITORIES"
    const val GITHUB_TOKEN = "GITHUB_TOKEN"
    const val OPENAI_API_KEY = "OPENAI_API_KEY"
    const val OPENAI_MODEL = "OPENAI_MODEL"
    const val OPENAI_REASONING_EFFORT = "OPENAI_REASONING_EFFORT"
}

object CliFlags {
    const val REPOSITORY = "repository"
    const val PULL_REQUEST = "pr"
    const val REQUESTED_BY = "requested-by"
    const val TRIGGER = "trigger"
    const val PREFIX = "--"
}

object ReviewProtocol {
    const val REVIEWER_MARKER = "android-pr-ai-reviewer-actions"
    const val SUPPORTED_MODULE = "3-compose-ui"
    const val MODULE_PREFIX = "Module:"
    const val OPEN_PULL_REQUEST = "open"
    const val READY_TRIGGER = "ready_for_review"
    const val MANUAL_TRIGGER = "manual"
    const val WRITE_PERMISSION = "write"
    const val MAINTAIN_PERMISSION = "maintain"
    const val ADMIN_PERMISSION = "admin"

    val TRUSTED_PERMISSIONS = setOf(WRITE_PERMISSION, MAINTAIN_PERMISSION, ADMIN_PERMISSION)
}

object SkipReasons {
    const val REPOSITORY_NOT_ALLOWED = "repository is not allowed"
    const val PULL_REQUEST_NOT_OPEN = "pull request is not open"
    const val WRONG_BASE_BRANCH = "base branch is not main"
    const val UNSUPPORTED_MODULE = "unsupported or missing module marker"
    const val UNAUTHORIZED_REQUESTER = "manual requester is not authorized"
    const val ALREADY_REVIEWED = "commit already reviewed with this rubric"
}
