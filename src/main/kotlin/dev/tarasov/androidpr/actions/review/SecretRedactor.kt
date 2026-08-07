package dev.tarasov.androidpr.actions.review

data class SanitizedContent(
    val text: String,
    val redactionCount: Int,
)

fun interface ContentSanitizer {
    fun sanitize(content: String): SanitizedContent
}

object SecretRedactor : ContentSanitizer {
    override fun sanitize(content: String): SanitizedContent {
        var sanitized = content
        var redactionCount = 0
        patterns.forEach { pattern ->
            sanitized = pattern.regex.replace(sanitized) { match ->
                redactionCount++
                pattern.replacement(match)
            }
        }
        return SanitizedContent(sanitized, redactionCount)
    }

    private data class RedactionPattern(
        val regex: Regex,
        val replacement: (MatchResult) -> String,
    )

    private val PRIVATE_KEY_PATTERN = Regex(
        "-----BEGIN (?:RSA |EC |OPENSSH |ENCRYPTED )?PRIVATE KEY-----[\\s\\S]*?" +
            "-----END (?:RSA |EC |OPENSSH |ENCRYPTED )?PRIVATE KEY-----",
    )
    private val OPENAI_KEY_PATTERN = Regex("\\bsk-[A-Za-z0-9_-]{20,}\\b")
    private val GITHUB_TOKEN_PATTERN = Regex(
        "\\b(?:gh[pousr]_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{20,})\\b",
    )
    private val GOOGLE_API_KEY_PATTERN = Regex("\\bAIza[0-9A-Za-z_-]{30,}\\b")
    private val AWS_ACCESS_KEY_PATTERN = Regex("\\b(?:AKIA|ASIA)[A-Z0-9]{16}\\b")
    private val JWT_PATTERN = Regex(
        "\\beyJ[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}\\b",
    )
    private val BEARER_TOKEN_PATTERN = Regex("(?i)\\bBearer\\s+[A-Za-z0-9._~+/-]{8,}")
    private val BASIC_CREDENTIAL_PATTERN = Regex("(?i)\\bBasic\\s+[A-Za-z0-9+/=]{8,}")
    private val QUOTED_SECRET_ASSIGNMENT_PATTERN = Regex(
        "(?i)([\\\"']?(?:api[_-]?key|access[_-]?token|auth[_-]?token|authorization|token|secret|password|passwd|" +
            "client[_-]?secret|storePassword|keyPassword)[\\\"']?\\s*[:=]\\s*[\\\"'])([^\\\"'\\r\\n]+)([\\\"'])",
    )
    private val UNQUOTED_SECRET_PROPERTY_PATTERN = Regex(
        "(?im)^(\\s*(?:api[_-]?key|access[_-]?token|auth[_-]?token|authorization|token|secret|password|passwd|" +
            "client[_-]?secret|storePassword|keyPassword)\\s*[:=]\\s*)([^\\s#][^#\\r\\n]*)$",
    )

    private val patterns = listOf(
        RedactionPattern(PRIVATE_KEY_PATTERN) { PRIVATE_KEY_REDACTION },
        RedactionPattern(OPENAI_KEY_PATTERN) { OPENAI_KEY_REDACTION },
        RedactionPattern(GITHUB_TOKEN_PATTERN) { GITHUB_TOKEN_REDACTION },
        RedactionPattern(GOOGLE_API_KEY_PATTERN) { GOOGLE_API_KEY_REDACTION },
        RedactionPattern(AWS_ACCESS_KEY_PATTERN) { AWS_ACCESS_KEY_REDACTION },
        RedactionPattern(JWT_PATTERN) { JWT_REDACTION },
        RedactionPattern(BEARER_TOKEN_PATTERN) { BEARER_TOKEN_REDACTION },
        RedactionPattern(BASIC_CREDENTIAL_PATTERN) { BASIC_CREDENTIAL_REDACTION },
        RedactionPattern(QUOTED_SECRET_ASSIGNMENT_PATTERN) { match ->
            "${match.groupValues[1]}$SECRET_VALUE_REDACTION${match.groupValues[3]}"
        },
        RedactionPattern(UNQUOTED_SECRET_PROPERTY_PATTERN) { match ->
            "${match.groupValues[1]}$SECRET_VALUE_REDACTION"
        },
    )

    private const val PRIVATE_KEY_REDACTION = "[REDACTED_PRIVATE_KEY]"
    private const val OPENAI_KEY_REDACTION = "[REDACTED_OPENAI_API_KEY]"
    private const val GITHUB_TOKEN_REDACTION = "[REDACTED_GITHUB_TOKEN]"
    private const val GOOGLE_API_KEY_REDACTION = "[REDACTED_GOOGLE_API_KEY]"
    private const val AWS_ACCESS_KEY_REDACTION = "[REDACTED_AWS_ACCESS_KEY]"
    private const val JWT_REDACTION = "[REDACTED_JWT]"
    private const val BEARER_TOKEN_REDACTION = "Bearer [REDACTED_BEARER_TOKEN]"
    private const val BASIC_CREDENTIAL_REDACTION = "Basic [REDACTED_BASIC_CREDENTIAL]"
    private const val SECRET_VALUE_REDACTION = "[REDACTED_SECRET_VALUE]"
}
