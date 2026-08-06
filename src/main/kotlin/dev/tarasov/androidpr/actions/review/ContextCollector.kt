package dev.tarasov.androidpr.actions.review

import dev.tarasov.androidpr.actions.domain.ChangedFile
import dev.tarasov.androidpr.actions.domain.PullRequestInfo
import dev.tarasov.androidpr.actions.domain.ReviewContext
import dev.tarasov.androidpr.actions.domain.SourceFile
import dev.tarasov.androidpr.actions.github.GitHubService
import dev.tarasov.androidpr.actions.rubric.MODULE_ONE_ID
import dev.tarasov.androidpr.actions.rubric.MODULE_TWO_ID
import dev.tarasov.androidpr.actions.rubric.MODULE_THREE_ID
import dev.tarasov.androidpr.actions.rubric.MODULE_FOUR_ID

class ContextCollector(
    private val github: GitHubService,
    private val maxFiles: Int,
    private val maxBytes: Int,
) {
    suspend fun collect(repository: String, pr: PullRequestInfo, moduleId: String): ReviewContext {
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
                .filter { FilePolicy.isModuleContext(it, moduleId) }
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
    private val allowedExtensions = setOf(KT, KTS, XML, MD, PROPERTIES, TOML, TXT, JSON, YAML, YML)
    private val binaryExtensions =
        setOf(PNG, JPG, JPEG, GIF, WEBP, ICO, JAR, ZIP, AAB, APK, KEYSTORE, JKS, DER, P12)
    private val forbiddenNames = setOf(ENV_FILE, GOOGLE_SERVICES_FILE, LOCAL_PROPERTIES_FILE)
    private val forbiddenSegments =
        setOf(BUILD, GRADLE_CACHE, IDEA, GENERATED, CAPTURES, SCREENSHOTS)
    private val sensitiveBaseNames = setOf(SECRET, SECRETS, CREDENTIAL, CREDENTIALS)
    private val sensitiveFragments =
        setOf(KEYSTORE, PRIVATE_KEY_DASH, PRIVATE_KEY_UNDERSCORE, POSTMAN_ENVIRONMENT)
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
    private val moduleOneExactPaths = setOf(
        SETTINGS_GRADLE,
        ROOT_BUILD_GRADLE,
        VERSION_CATALOG,
        README,
    )
    private val moduleOneFileNames = setOf(
        BUILD_GRADLE,
        ANDROID_MANIFEST,
        USER_LIST_APPLICATION,
        MAIN_ACTIVITY,
        APP_NAVIGATION,
        DESTINATIONS,
        SETTINGS_FEATURE_LAUNCHER,
        SETTINGS_FEATURE_DEPENDENCIES,
        ACCOUNT_SCREEN,
        AUTH_VIEW_MODEL,
        AUTH_USE_CASES,
        REPOSITORIES,
        MODELS,
        AUTH_SESSION_REPOSITORY,
    )
    private val moduleOnePathFragments = setOf(
        ACCOUNT_FEATURE_PATH,
        SETTINGS_FEATURE_PATH,
        SETTINGS_BRIDGE_PATH,
        NAVIGATION_PATH,
        FEATURE_FLAG,
        FEATURE_FLAG_UNDERSCORE,
        SPLIT_INSTALL,
        SPLIT_COMPAT,
        DELIVERY,
        MODULARIZATION,
    )
    private val moduleTwoExactPaths = setOf(
        SETTINGS_GRADLE,
        ROOT_BUILD_GRADLE,
        VERSION_CATALOG,
        README,
        GRADLE_PROPERTIES_PATH,
        WRAPPER_PROPERTIES_PATH,
        GITIGNORE,
    )
    private val moduleTwoFileNames = setOf(
        BUILD_GRADLE,
        ANDROID_MANIFEST,
        QUALITY_TEST_RECEIVER,
        CRASHLYTICS_RESOURCES,
    )
    private val moduleTwoPathFragments = setOf(
        BUILD_LOGIC_PATH,
        BUILD_SRC_PATH,
        GRADLE_PATH,
        PUBLIC_SOURCE_SET,
        INTERNAL_SOURCE_SET,
        PUBLIC_DEBUG_SOURCE_SET,
        INTERNAL_DEBUG_SOURCE_SET,
        RELEASE_SOURCE_SET,
        DEBUG_SOURCE_SET,
        SIGNING,
        CONVENTION,
        VARIANT,
        FLAVOR,
    )
    private val moduleFourExactPaths = setOf(
        ROOT_BUILD_GRADLE,
        APP_BUILD_GRADLE,
        VERSION_CATALOG,
        README,
        OPENAPI_CONTRACT,
    )
    private val moduleFourFileNames = setOf(
        USER_API,
        AUTH_API,
        USER_DTOS,
        USER_REMOTE_SOURCE,
        RETROFIT_USER_REMOTE_SOURCE,
        USER_REPOSITORY,
        USER_LIST_VIEW_MODEL,
        USER_LIST_SCREEN,
        APP_MODULE,
        APP_MANIFEST,
        USER_REPOSITORY_TEST,
        USER_LIST_VIEW_MODEL_TEST,
    )
    private val moduleFourPathFragments = setOf(
        POSTMAN,
        OPENAPI,
        WEBSOCKET,
        NETWORK_SECURITY,
        NETWORK_SECURITY_UNDERSCORE,
        NETWORK_PATH,
        DATA_REMOTE_PATH,
        CHARLES,
        RETROFIT,
    )

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
        if (sensitiveFragments.any(normalized::contains)) return false
        return true
    }

    fun isModuleContext(path: String, moduleId: String): Boolean = when (moduleId) {
        MODULE_ONE_ID -> isModuleOneContext(path)
        MODULE_TWO_ID -> isModuleTwoContext(path)
        MODULE_THREE_ID -> isModuleThreeContext(path)
        MODULE_FOUR_ID -> isModuleFourContext(path)
        else -> false
    }

    private fun isModuleThreeContext(path: String): Boolean {
        val normalized = path.lowercase()
        val name = normalized.substringAfterLast(PATH_SEPARATOR)
        if (normalized in moduleThreeExactPaths || name in moduleThreeFileNames) return true
        return moduleThreeTestFragments.any(name.replace(UNDERSCORE, EMPTY)::contains)
    }

    private fun isModuleOneContext(path: String): Boolean {
        val normalized = path.lowercase()
        val name = normalized.substringAfterLast(PATH_SEPARATOR)
        if (normalized in moduleOneExactPaths || name in moduleOneFileNames) return true
        if (normalized.startsWith(DOCS_PATH) && moduleOnePathFragments.any(normalized::contains)) return true
        return moduleOnePathFragments.any(normalized::contains)
    }

    private fun isModuleTwoContext(path: String): Boolean {
        val normalized = path.lowercase()
        val name = normalized.substringAfterLast(PATH_SEPARATOR)
        if (normalized in moduleTwoExactPaths || name in moduleTwoFileNames) return true
        if (name == BUILD_GRADLE) return true
        return moduleTwoPathFragments.any(normalized::contains)
    }

    private fun isModuleFourContext(path: String): Boolean {
        val normalized = path.lowercase()
        val name = normalized.substringAfterLast(PATH_SEPARATOR)
        if (normalized in moduleFourExactPaths || name in moduleFourFileNames) return true
        return moduleFourPathFragments.any(normalized::contains)
    }

    private const val KT = "kt"
    private const val KTS = "kts"
    private const val XML = "xml"
    private const val MD = "md"
    private const val PROPERTIES = "properties"
    private const val TOML = "toml"
    private const val TXT = "txt"
    private const val JSON = "json"
    private const val YAML = "yaml"
    private const val YML = "yml"
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
    private const val POSTMAN_ENVIRONMENT = "postman_environment"
    private const val COMPOSE_TEST = "composetest"
    private const val ACCESSIBILITY = "accessibility"
    private const val SETTINGS_GRADLE = "settings.gradle.kts"
    private const val ROOT_BUILD_GRADLE = "build.gradle.kts"
    private const val VERSION_CATALOG = "gradle/libs.versions.toml"
    private const val README = "readme.md"
    private const val BUILD_GRADLE = "build.gradle.kts"
    private const val ANDROID_MANIFEST = "androidmanifest.xml"
    private const val USER_LIST_APPLICATION = "userlistapplication.kt"
    private const val MAIN_ACTIVITY = "mainactivity.kt"
    private const val APP_NAVIGATION = "appnavigation.kt"
    private const val DESTINATIONS = "destinations.kt"
    private const val SETTINGS_FEATURE_LAUNCHER = "settingsfeaturelauncher.kt"
    private const val SETTINGS_FEATURE_DEPENDENCIES = "settingsfeaturedependencies.kt"
    private const val ACCOUNT_SCREEN = "accountscreen.kt"
    private const val AUTH_VIEW_MODEL = "authviewmodel.kt"
    private const val AUTH_USE_CASES = "authusecases.kt"
    private const val REPOSITORIES = "repositories.kt"
    private const val MODELS = "models.kt"
    private const val AUTH_SESSION_REPOSITORY = "authsessionrepositoryimpl.kt"
    private const val ACCOUNT_FEATURE_PATH = "/feature/account/"
    private const val SETTINGS_FEATURE_PATH = "/feature/settings/"
    private const val SETTINGS_BRIDGE_PATH = "/settings/"
    private const val NAVIGATION_PATH = "/navigation/"
    private const val FEATURE_FLAG = "featureflag"
    private const val FEATURE_FLAG_UNDERSCORE = "feature_flag"
    private const val SPLIT_INSTALL = "splitinstall"
    private const val SPLIT_COMPAT = "splitcompat"
    private const val DELIVERY = "delivery"
    private const val MODULARIZATION = "modularization"
    private const val DOCS_PATH = "docs/"
    private const val GRADLE_PROPERTIES_PATH = "gradle.properties"
    private const val WRAPPER_PROPERTIES_PATH = "gradle/wrapper/gradle-wrapper.properties"
    private const val GITIGNORE = ".gitignore"
    private const val QUALITY_TEST_RECEIVER = "qualitytestreceiver.kt"
    private const val CRASHLYTICS_RESOURCES = "crashlytics.xml"
    private const val BUILD_LOGIC_PATH = "build-logic/"
    private const val BUILD_SRC_PATH = "buildsrc/"
    private const val GRADLE_PATH = "gradle/"
    private const val PUBLIC_SOURCE_SET = "/src/public/"
    private const val INTERNAL_SOURCE_SET = "/src/internal/"
    private const val PUBLIC_DEBUG_SOURCE_SET = "/src/publicdebug/"
    private const val INTERNAL_DEBUG_SOURCE_SET = "/src/internaldebug/"
    private const val RELEASE_SOURCE_SET = "/src/release/"
    private const val DEBUG_SOURCE_SET = "/src/debug/"
    private const val SIGNING = "signing"
    private const val CONVENTION = "convention"
    private const val VARIANT = "variant"
    private const val FLAVOR = "flavor"
    private const val APP_BUILD_GRADLE = "app/build.gradle.kts"
    private const val OPENAPI_CONTRACT = "docs/openapi.yaml"
    private const val USER_API = "userapi.kt"
    private const val AUTH_API = "authapi.kt"
    private const val USER_DTOS = "userdtos.kt"
    private const val USER_REMOTE_SOURCE = "userremotedatasource.kt"
    private const val RETROFIT_USER_REMOTE_SOURCE = "retrofituserremotedatasource.kt"
    private const val USER_REPOSITORY = "userrepositoryimpl.kt"
    private const val USER_LIST_VIEW_MODEL = "userlistviewmodel.kt"
    private const val USER_LIST_SCREEN = "userlistscreen.kt"
    private const val APP_MODULE = "appmodule.kt"
    private const val APP_MANIFEST = "androidmanifest.xml"
    private const val USER_REPOSITORY_TEST = "userrepositoryimpltest.kt"
    private const val USER_LIST_VIEW_MODEL_TEST = "userlistviewmodeltest.kt"
    private const val POSTMAN = "postman"
    private const val OPENAPI = "openapi"
    private const val WEBSOCKET = "websocket"
    private const val NETWORK_SECURITY = "network-security"
    private const val NETWORK_SECURITY_UNDERSCORE = "network_security"
    private const val NETWORK_PATH = "/network/"
    private const val DATA_REMOTE_PATH = "/data/remote/"
    private const val CHARLES = "charles"
    private const val RETROFIT = "retrofit"
    private const val EMPTY = ""
    private const val UNDERSCORE = "_"
    private const val PATH_SEPARATOR = '/'
    private const val EXTENSION_SEPARATOR = '.'

    private val explicitlyAllowedNames = setOf(GITIGNORE)
}
