package dev.tarasov.androidpr.actions.review

import dev.tarasov.androidpr.actions.rubric.moduleId
import dev.tarasov.androidpr.actions.rubric.MODULE_ONE_ID
import dev.tarasov.androidpr.actions.rubric.MODULE_TWO_ID
import dev.tarasov.androidpr.actions.rubric.MODULE_THREE_ID
import dev.tarasov.androidpr.actions.rubric.MODULE_FOUR_ID
import dev.tarasov.androidpr.actions.rubric.MODULE_FIVE_ID
import dev.tarasov.androidpr.actions.rubric.MODULE_SIX_ID
import dev.tarasov.androidpr.actions.rubric.MODULE_SEVEN_ID
import dev.tarasov.androidpr.actions.rubric.MODULE_EIGHT_ID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FilePolicyTest {
    @Test
    fun `allows relevant source and rejects secrets binaries and generated output`() {
        assertTrue(FilePolicy.isAllowed("app/src/main/kotlin/UserListScreen.kt"))
        assertFalse(FilePolicy.isAllowed("app/google-services.json"))
        assertFalse(FilePolicy.isAllowed("release.keystore"))
        assertFalse(FilePolicy.isAllowed("app/build/generated/source.kt"))
        assertFalse(FilePolicy.isAllowed("screenshots/user-list.png"))
        assertFalse(FilePolicy.isAllowed("config/secrets.properties"))
        assertTrue(FilePolicy.isAllowed("feature/SecretaryScreen.kt"))
        assertTrue(FilePolicy.isAllowed(".gitignore"))
        assertTrue(FilePolicy.isAllowed("security/AndroidKeystoreCipher.kt"))
        assertFalse(FilePolicy.isAllowed("config/keystore.properties"))
    }

    @Test
    fun `recognizes module three context`() {
        assertTrue(FilePolicy.isModuleContext("feature/users/UserDetailsScreen.kt", MODULE_THREE_ID))
        assertTrue(FilePolicy.isModuleContext("app/src/androidTest/UserScreensTest.kt", MODULE_THREE_ID))
        assertTrue(FilePolicy.isModuleContext("app/src/main/res/values/strings.xml", MODULE_THREE_ID))
        assertTrue(FilePolicy.isModuleContext("app/build.gradle.kts", MODULE_THREE_ID))
        assertFalse(FilePolicy.isModuleContext("settings/build.gradle.kts", MODULE_THREE_ID))
        assertFalse(FilePolicy.isModuleContext("app/src/androidTest/UserDatabaseMigrationTest.kt", MODULE_THREE_ID))
        assertFalse(FilePolicy.isModuleContext("data/UserRepository.kt", MODULE_THREE_ID))
    }

    @Test
    fun `recognizes module one architecture context`() {
        assertTrue(FilePolicy.isModuleContext("settings.gradle.kts", MODULE_ONE_ID))
        assertTrue(FilePolicy.isModuleContext("account/build.gradle.kts", MODULE_ONE_ID))
        assertTrue(FilePolicy.isModuleContext("settings/src/main/AndroidManifest.xml", MODULE_ONE_ID))
        assertTrue(FilePolicy.isModuleContext("app/src/main/settings/SettingsFeatureLauncher.kt", MODULE_ONE_ID))
        assertTrue(FilePolicy.isModuleContext("account/src/test/AccountFeatureFlagTest.kt", MODULE_ONE_ID))
        assertFalse(FilePolicy.isModuleContext("app/src/main/data/UserDao.kt", MODULE_ONE_ID))
        assertFalse(FilePolicy.isModuleContext("app/src/androidTest/UserDatabaseMigrationTest.kt", MODULE_ONE_ID))
    }

    @Test
    fun `recognizes module two Gradle and variant context`() {
        assertTrue(FilePolicy.isModuleContext("settings.gradle.kts", MODULE_TWO_ID))
        assertTrue(FilePolicy.isModuleContext("build-logic/build.gradle.kts", MODULE_TWO_ID))
        assertTrue(FilePolicy.isModuleContext("build-logic/src/main/kotlin/AndroidApplicationConventionPlugin.kt", MODULE_TWO_ID))
        assertTrue(FilePolicy.isModuleContext("app/src/internalDebug/AndroidManifest.xml", MODULE_TWO_ID))
        assertTrue(FilePolicy.isModuleContext("app/src/debug/java/QualityTestReceiver.kt", MODULE_TWO_ID))
        assertTrue(FilePolicy.isModuleContext(".gitignore", MODULE_TWO_ID))
        assertFalse(FilePolicy.isModuleContext("app/src/main/java/UserListScreen.kt", MODULE_TWO_ID))
        assertFalse(FilePolicy.isModuleContext("app/src/test/UserListViewModelTest.kt", MODULE_TWO_ID))
    }

    @Test
    fun `recognizes module four networking context without Postman environment values`() {
        assertTrue(FilePolicy.isAllowed("docs/openapi.yaml"))
        assertTrue(FilePolicy.isAllowed("postman/User_List.postman_collection.json"))
        assertFalse(FilePolicy.isAllowed("postman/Local.postman_environment.json"))
        assertTrue(FilePolicy.isModuleContext("docs/openapi.yaml", MODULE_FOUR_ID))
        assertTrue(FilePolicy.isModuleContext("postman/User_List.postman_collection.json", MODULE_FOUR_ID))
        assertTrue(FilePolicy.isModuleContext("app/src/main/data/remote/UserApi.kt", MODULE_FOUR_ID))
        assertTrue(FilePolicy.isModuleContext("app/src/main/network/UserStatusWebSocket.kt", MODULE_FOUR_ID))
        assertTrue(FilePolicy.isModuleContext("app/src/debug/res/xml/network_security_config.xml", MODULE_FOUR_ID))
        assertTrue(FilePolicy.isModuleContext("app/src/test/UserRepositoryImplTest.kt", MODULE_FOUR_ID))
        assertFalse(FilePolicy.isModuleContext("app/src/main/data/local/UserDao.kt", MODULE_FOUR_ID))
    }

    @Test
    fun `recognizes module five security storage manifest and workflow context`() {
        assertTrue(FilePolicy.isModuleContext("app/src/main/AndroidManifest.xml", MODULE_FIVE_ID))
        assertTrue(FilePolicy.isModuleContext("app/src/main/res/xml/backup_rules.xml", MODULE_FIVE_ID))
        assertTrue(FilePolicy.isModuleContext("app/src/main/data/local/UserDao.kt", MODULE_FIVE_ID))
        assertTrue(FilePolicy.isModuleContext("app/src/main/security/TinkNoteCipher.kt", MODULE_FIVE_ID))
        assertTrue(FilePolicy.isModuleContext("app/src/test/PlaintextNoteMigrationTest.kt", MODULE_FIVE_ID))
        assertTrue(FilePolicy.isModuleContext(".github/workflows/signed-release.yml", MODULE_FIVE_ID))
        assertTrue(FilePolicy.isModuleContext("docs/security-review.md", MODULE_FIVE_ID))
        assertFalse(FilePolicy.isModuleContext("app/src/main/feature/users/UserListScreen.kt", MODULE_FIVE_ID))
    }

    @Test
    fun `recognizes module six tests HTTP coverage and production targets`() {
        assertTrue(FilePolicy.isModuleContext("build.gradle.kts", MODULE_SIX_ID))
        assertTrue(FilePolicy.isModuleContext("app/build.gradle.kts", MODULE_SIX_ID))
        assertTrue(FilePolicy.isModuleContext("settings/build.gradle.kts", MODULE_SIX_ID))
        assertTrue(FilePolicy.isModuleContext("gradle/libs.versions.toml", MODULE_SIX_ID))
        assertTrue(FilePolicy.isModuleContext("app/src/main/domain/usecase/UserUseCases.kt", MODULE_SIX_ID))
        assertTrue(FilePolicy.isModuleContext("app/src/main/data/remote/RetrofitUserRemoteDataSource.kt", MODULE_SIX_ID))
        assertTrue(FilePolicy.isModuleContext("app/src/test/UserListFilteringPropertyTest.kt", MODULE_SIX_ID))
        assertTrue(FilePolicy.isModuleContext("app/src/test/RetrofitUserRemoteDataSourceTest.kt", MODULE_SIX_ID))
        assertTrue(FilePolicy.isModuleContext("docs/unit-testing-tdd.md", MODULE_SIX_ID))
        assertFalse(FilePolicy.isModuleContext("app/src/main/feature/users/UserListScreen.kt", MODULE_SIX_ID))
        assertFalse(FilePolicy.isModuleContext("app/src/main/data/local/UserDao.kt", MODULE_SIX_ID))
    }

    @Test
    fun `recognizes module seven instrumented UI fake and screenshot context`() {
        assertTrue(FilePolicy.isModuleContext("app/build.gradle.kts", MODULE_SEVEN_ID))
        assertTrue(FilePolicy.isModuleContext("app/src/androidTest/HiltTestRunner.kt", MODULE_SEVEN_ID))
        assertTrue(FilePolicy.isModuleContext("app/src/androidTest/ApplicationUserFlowTest.kt", MODULE_SEVEN_ID))
        assertTrue(FilePolicy.isModuleContext("app/src/androidTest/PhotoPickerUiAutomatorTest.kt", MODULE_SEVEN_ID))
        assertTrue(FilePolicy.isModuleContext("app/src/androidTest/TestFakeRepositories.kt", MODULE_SEVEN_ID))
        assertTrue(FilePolicy.isModuleContext("app/src/screenshotTestDebug/UiScreenshotTest.kt", MODULE_SEVEN_ID))
        assertTrue(FilePolicy.isModuleContext("app/src/main/feature/account/AccountScreen.kt", MODULE_SEVEN_ID))
        assertTrue(FilePolicy.isModuleContext("docs/ui-testing.md", MODULE_SEVEN_ID))
        assertFalse(FilePolicy.isAllowed("app/src/screenshotTestDebug/reference/users.png"))
        assertFalse(FilePolicy.isModuleContext("app/src/main/data/local/UserDao.kt", MODULE_SEVEN_ID))
        assertFalse(FilePolicy.isModuleContext("docs/openapi.yaml", MODULE_SEVEN_ID))
    }

    @Test
    fun `recognizes module eight monitoring lifecycle UI and debug context`() {
        assertTrue(FilePolicy.isModuleContext("build.gradle.kts", MODULE_EIGHT_ID))
        assertTrue(FilePolicy.isModuleContext("app/build.gradle.kts", MODULE_EIGHT_ID))
        assertTrue(FilePolicy.isModuleContext("app/src/debug/AndroidManifest.xml", MODULE_EIGHT_ID))
        assertTrue(FilePolicy.isModuleContext("app/src/main/res/values/crashlytics.xml", MODULE_EIGHT_ID))
        assertTrue(FilePolicy.isModuleContext("app/src/main/core/quality/AppQualityMonitor.kt", MODULE_EIGHT_ID))
        assertTrue(FilePolicy.isModuleContext("app/src/main/core/quality/JankMonitoring.kt", MODULE_EIGHT_ID))
        assertTrue(FilePolicy.isModuleContext("app/src/main/navigation/AppNavigation.kt", MODULE_EIGHT_ID))
        assertTrue(FilePolicy.isModuleContext("app/src/test/UserRefreshPerformanceTest.kt", MODULE_EIGHT_ID))
        assertFalse(FilePolicy.isModuleContext("app/src/main/data/local/UserDao.kt", MODULE_EIGHT_ID))
        assertFalse(FilePolicy.isModuleContext("docs/openapi.yaml", MODULE_EIGHT_ID))
    }

    @Test
    fun `extracts exact module marker`() {
        assertEquals("3-compose-ui", "Title\nModule: 3-compose-ui\nNotes".moduleId())
    }
}
