package dev.tarasov.androidpr.actions.review

import dev.tarasov.androidpr.actions.domain.ChangedFile
import dev.tarasov.androidpr.actions.domain.PullRequestInfo
import dev.tarasov.androidpr.actions.domain.ReviewContext
import dev.tarasov.androidpr.actions.domain.SourceFile
import dev.tarasov.androidpr.actions.github.GitHubService
import dev.tarasov.androidpr.actions.rubric.Rubric
import dev.tarasov.androidpr.actions.rubric.RubricLoader

class ContextCollector(
    private val github: GitHubService,
    private val maxFiles: Int,
    private val maxBytes: Int,
    private val sanitizer: ContentSanitizer = SecretRedactor,
) {
    suspend fun collect(repository: String, pr: PullRequestInfo, rubric: Rubric): ReviewContext {
        val changed = github.listChangedFiles(repository, pr.number)
        val changedByPath = changed.associateBy(ChangedFile::path)
        val candidates = LinkedHashSet<String>()
        changed.asSequence()
            .filter { FilePolicy.isAllowed(it.path) }
            .mapTo(candidates, ChangedFile::path)

        if (candidates.size < maxFiles) {
            github.listRepositoryPaths(repository, pr.headSha)
                .asSequence()
                .filter(FilePolicy::isAllowed)
                .filter(rubric.context::matches)
                .forEach(candidates::add)
        }

        val files = mutableListOf<SourceFile>()
        val redactedFiles = mutableListOf<String>()
        val skippedFiles = mutableListOf<String>()
        var totalBytes = 0
        var truncated = candidates.size > maxFiles
        for (path in candidates.take(maxFiles)) {
            val changedFile = changedByPath[path]
            if (changedFile?.status == REMOVED_STATUS) {
                val sanitizedPatch = changedFile.patch?.let(sanitizer::sanitize)
                if (sanitizedPatch == null) {
                    skippedFiles += path
                    truncated = true
                    continue
                }
                val patchBytes = sanitizedPatch.text.byteSize()
                if (totalBytes + patchBytes > maxBytes) {
                    truncated = true
                    continue
                }
                if (sanitizedPatch.redactionCount > 0) redactedFiles += path
                files += SourceFile(
                    path = path,
                    content = EMPTY,
                    patch = sanitizedPatch.text,
                    status = REMOVED_STATUS,
                )
                totalBytes += patchBytes
                continue
            }

            val content = github.getFileContent(repository, path, pr.headSha)
            if (content == null) {
                skippedFiles += path
                truncated = true
                continue
            }
            val sanitizedContent = sanitizer.sanitize(content)
            val sanitizedPatch = changedFile?.patch?.let(sanitizer::sanitize)
            val contentBytes = sanitizedContent.text.byteSize()
            if (totalBytes + contentBytes > maxBytes) {
                truncated = true
                continue
            }
            val acceptedPatch = sanitizedPatch?.takeIf {
                totalBytes + contentBytes + it.text.byteSize() <= maxBytes
            }
            if (sanitizedPatch != null && acceptedPatch == null) truncated = true
            if (sanitizedContent.redactionCount > 0 || (sanitizedPatch?.redactionCount ?: 0) > 0) {
                redactedFiles += path
            }
            files += SourceFile(
                path = path,
                content = sanitizedContent.text,
                patch = acceptedPatch?.text,
                status = changedFile?.status ?: UNCHANGED_STATUS,
            )
            totalBytes += contentBytes + (acceptedPatch?.text?.byteSize() ?: 0)
        }
        return ReviewContext(pr, files, totalBytes, truncated, redactedFiles, skippedFiles)
    }
}

object FilePolicy {
    fun isModuleContext(path: String, moduleId: String): Boolean =
        RubricLoader.find(moduleId)?.context?.matches(path) == true

    fun isAllowed(path: String): Boolean {
        val normalized = path.lowercase()
        val segments = normalized.split(PATH_SEPARATOR)
        val name = segments.last()
        val extension = name.substringAfterLast(EXTENSION_SEPARATOR, EMPTY)
        val baseName = name.substringBeforeLast(EXTENSION_SEPARATOR, name)
        if (name in forbiddenNames || extension in binaryExtensions) return false
        if (name !in explicitlyAllowedNames && extension !in allowedExtensions) return false
        if (segments.any { it in forbiddenSegments }) return false
        if (baseName in sensitiveBaseNames) return false
        if (extension in potentiallySensitiveConfigExtensions && sensitiveFragments.any(normalized::contains)) {
            return false
        }
        return true
    }

    private val allowedExtensions = setOf("kt", "kts", "xml", "md", "properties", "toml", "txt", "json", "yaml", "yml")
    private val binaryExtensions = setOf(
        "png", "jpg", "jpeg", "gif", "webp", "ico", "jar", "zip", "aab", "apk", "keystore", "jks", "der", "p12",
    )
    private val forbiddenNames = setOf(".env", "google-services.json", "local.properties")
    private val forbiddenSegments = setOf("build", ".gradle", ".idea", "generated", "captures", "screenshots")
    private val sensitiveBaseNames = setOf("secret", "secrets", "credential", "credentials")
    private val sensitiveFragments = setOf("keystore", "private-key", "private_key", "postman_environment")
    private val potentiallySensitiveConfigExtensions = setOf("properties", "toml", "json", "yaml", "yml")
    private val explicitlyAllowedNames = setOf(".gitignore")
}

private const val REMOVED_STATUS = "removed"
private const val UNCHANGED_STATUS = "unchanged"
private const val EMPTY = ""
private const val PATH_SEPARATOR = '/'
private const val EXTENSION_SEPARATOR = '.'

private fun String.byteSize(): Int = toByteArray(Charsets.UTF_8).size
