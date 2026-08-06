package dev.tarasov.androidpr.actions.review

import dev.tarasov.androidpr.actions.domain.ChangedFile
import dev.tarasov.androidpr.actions.domain.PullRequestInfo
import dev.tarasov.androidpr.actions.domain.ReviewContext
import dev.tarasov.androidpr.actions.domain.SourceFile
import dev.tarasov.androidpr.actions.github.GitHubService

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
            .filter { it.status != REMOVED_STATUS && FilePolicy.isAllowed(it.path) }
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

private const val REMOVED_STATUS = "removed"

object FilePolicy {
    private val allowedExtensions = setOf(KT, KTS, XML, MD, PROPERTIES, TOML, TXT)
    private val binaryExtensions =
        setOf(PNG, JPG, JPEG, GIF, WEBP, ICO, JAR, ZIP, AAB, APK, KEYSTORE, JKS, DER, P12)
    private val forbiddenNames = setOf(ENV_FILE, GOOGLE_SERVICES_FILE, LOCAL_PROPERTIES_FILE)
    private val forbiddenSegments =
        setOf(BUILD, GRADLE_CACHE, IDEA, GENERATED, CAPTURES, SCREENSHOTS)
    private val sensitiveBaseNames = setOf(SECRET, SECRETS, CREDENTIAL, CREDENTIALS)
    private val sensitiveFragments = setOf(KEYSTORE, PRIVATE_KEY_DASH, PRIVATE_KEY_UNDERSCORE)
    private val moduleThreeFileNames = setOf(
        "userlistscreen.kt",
        "userlistviewmodel.kt",
        "userdetailsscreen.kt",
        "userdetailsviewmodel.kt",
        "accountscreen.kt",
        "settingsscreen.kt",
        "settingsviewmodel.kt",
        "userlisttheme.kt",
        "uitext.kt",
        "userscreenstest.kt",
    )
    private val moduleThreeExactPaths = setOf(
        "build.gradle.kts",
        "app/build.gradle.kts",
        "gradle/libs.versions.toml",
        "app/src/main/res/values/strings.xml",
    )
    private val moduleThreeTestFragments = setOf(COMPOSE_TEST, ACCESSIBILITY)

    fun isAllowed(path: String): Boolean {
        val normalized = path.lowercase()
        val segments = normalized.split(PATH_SEPARATOR)
        val name = segments.last()
        val extension = name.substringAfterLast(EXTENSION_SEPARATOR, EMPTY)
        val baseName = name.substringBeforeLast(EXTENSION_SEPARATOR, name)
        if (name in forbiddenNames || extension in binaryExtensions || extension !in allowedExtensions) return false
        if (segments.any { it in forbiddenSegments }) return false
        if (baseName in sensitiveBaseNames) return false
        if (sensitiveFragments.any(normalized::contains)) return false
        return true
    }

    fun isModuleThreeContext(path: String): Boolean {
        val normalized = path.lowercase()
        val name = normalized.substringAfterLast(PATH_SEPARATOR)
        if (normalized in moduleThreeExactPaths || name in moduleThreeFileNames) return true
        return moduleThreeTestFragments.any(name.replace(UNDERSCORE, EMPTY)::contains)
    }

    private const val KT = "kt"
    private const val KTS = "kts"
    private const val XML = "xml"
    private const val MD = "md"
    private const val PROPERTIES = "properties"
    private const val TOML = "toml"
    private const val TXT = "txt"
    private const val PNG = "png"
    private const val JPG = "jpg"
    private const val JPEG = "jpeg"
    private const val GIF = "gif"
    private const val WEBP = "webp"
    private const val ICO = "ico"
    private const val JAR = "jar"
    private const val ZIP = "zip"
    private const val AAB = "aab"
    private const val APK = "apk"
    private const val KEYSTORE = "keystore"
    private const val JKS = "jks"
    private const val DER = "der"
    private const val P12 = "p12"
    private const val ENV_FILE = ".env"
    private const val GOOGLE_SERVICES_FILE = "google-services.json"
    private const val LOCAL_PROPERTIES_FILE = "local.properties"
    private const val BUILD = "build"
    private const val GRADLE_CACHE = ".gradle"
    private const val IDEA = ".idea"
    private const val GENERATED = "generated"
    private const val CAPTURES = "captures"
    private const val SCREENSHOTS = "screenshots"
    private const val SECRET = "secret"
    private const val SECRETS = "secrets"
    private const val CREDENTIAL = "credential"
    private const val CREDENTIALS = "credentials"
    private const val PRIVATE_KEY_DASH = "private-key"
    private const val PRIVATE_KEY_UNDERSCORE = "private_key"
    private const val COMPOSE_TEST = "composetest"
    private const val ACCESSIBILITY = "accessibility"
    private const val EMPTY = ""
    private const val UNDERSCORE = "_"
    private const val PATH_SEPARATOR = '/'
    private const val EXTENSION_SEPARATOR = '.'
}
