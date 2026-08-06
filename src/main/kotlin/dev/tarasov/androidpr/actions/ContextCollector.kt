package dev.tarasov.androidpr.actions

class ContextCollector(
    private val github: GitHubService,
    private val maxFiles: Int,
    private val maxBytes: Int,
) {
    suspend fun collect(repository: String, pr: PullRequestInfo): ReviewContext {
        val changed = github.listChangedFiles(repository, pr.number)
        val changedByPath = changed.associateBy(ChangedFile::path)
        val candidates = LinkedHashSet<String>()
        changed.asSequence()
            .filter { it.status != "removed" && FilePolicy.isAllowed(it.path) }
            .mapTo(candidates, ChangedFile::path)

        if (candidates.size < maxFiles) {
            github.listRepositoryPaths(repository, pr.headSha)
                .asSequence()
                .filter(FilePolicy::isAllowed)
                .filter(FilePolicy::isModuleThreeContext)
                .forEach(candidates::add)
        }

        val files = mutableListOf<SourceFile>()
        var totalBytes = 0
        var truncated = candidates.size > maxFiles
        for (path in candidates.take(maxFiles)) {
            val content = github.getFileContent(repository, path, pr.headSha) ?: continue
            val bytes = content.toByteArray(Charsets.UTF_8).size
            if (totalBytes + bytes > maxBytes) {
                truncated = true
                continue
            }
            files += SourceFile(path, content, changedByPath[path]?.patch)
            totalBytes += bytes
        }
        return ReviewContext(pr, files, totalBytes, truncated)
    }
}

object FilePolicy {
    private val allowedExtensions = setOf("kt", "kts", "xml", "md", "properties", "toml", "txt")
    private val binaryExtensions = setOf("png", "jpg", "jpeg", "gif", "webp", "ico", "jar", "zip", "aab", "apk", "keystore", "jks", "der", "p12")
    private val forbiddenNames = setOf(".env", "google-services.json", "local.properties")
    private val forbiddenSegments = setOf("build", ".gradle", ".idea", "generated", "captures", "screenshots")
    private val sensitiveFragments = setOf("keystore", "private-key", "private_key", "secret")
    private val moduleThreeFragments = setOf(
        "userlistscreen", "userlistviewmodel", "userdetails", "accountscreen", "signinscreen",
        "settingsscreen", "settingsviewmodel", "userlisttheme", "uitext", "userscreenstest",
        "composetest", "accessibility", "strings.xml", "build.gradle", "libs.versions.toml",
    )

    fun isAllowed(path: String): Boolean {
        val normalized = path.lowercase()
        val name = normalized.substringAfterLast('/')
        val extension = name.substringAfterLast('.', "")
        if (name in forbiddenNames || extension in binaryExtensions || extension !in allowedExtensions) return false
        if (normalized.split('/').any { it in forbiddenSegments }) return false
        if (sensitiveFragments.any(normalized::contains)) return false
        return true
    }

    fun isModuleThreeContext(path: String): Boolean {
        val normalized = path.lowercase().replace("_", "")
        return moduleThreeFragments.any(normalized::contains)
    }
}
