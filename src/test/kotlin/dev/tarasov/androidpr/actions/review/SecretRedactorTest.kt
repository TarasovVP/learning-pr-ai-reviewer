package dev.tarasov.androidpr.actions.review

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SecretRedactorTest {
    @Test
    fun `redacts provider tokens and private keys`() {
        val source = """
            val openAi = "sk-${"abcdefghijklmnopqrstuvwxyz123456"}"
            val github = "github_pat_${"abcdefghijklmnopqrstuvwxyz123456"}"
            ${"-----BEGIN"} PRIVATE KEY-----
            private-material
            -----END PRIVATE KEY-----
        """.trimIndent()

        val result = SecretRedactor.sanitize(source)

        assertEquals(3, result.redactionCount)
        assertFalse("abcdefghijklmnopqrstuvwxyz123456" in result.text)
        assertTrue("[REDACTED_OPENAI_API_KEY]" in result.text)
        assertTrue("[REDACTED_GITHUB_TOKEN]" in result.text)
        assertTrue("[REDACTED_PRIVATE_KEY]" in result.text)
    }

    @Test
    fun `redacts quoted assignments and property values but keeps identifiers`() {
        val source = """
            val password = "super-secret-password"
            api_key=my-api-key
            val token = tokenRepository.load()
        """.trimIndent()

        val result = SecretRedactor.sanitize(source)

        assertEquals(2, result.redactionCount)
        assertTrue("val password = \"[REDACTED_SECRET_VALUE]\"" in result.text)
        assertTrue("api_key=[REDACTED_SECRET_VALUE]" in result.text)
        assertTrue("val token = tokenRepository.load()" in result.text)
    }

    @Test
    fun `leaves ordinary source code unchanged`() {
        val source = "val userName = user.name"

        assertEquals(SanitizedContent(source, 0), SecretRedactor.sanitize(source))
    }
}
