package dev.tarasov.androidpr.actions.review

import dev.tarasov.androidpr.actions.rubric.moduleId
import dev.tarasov.androidpr.actions.rubric.MODULE_ONE_ID
import dev.tarasov.androidpr.actions.rubric.MODULE_TWO_ID
import dev.tarasov.androidpr.actions.rubric.MODULE_THREE_ID
import dev.tarasov.androidpr.actions.rubric.MODULE_FOUR_ID
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
    fun `extracts exact module marker`() {
        assertEquals("3-compose-ui", "Title\nModule: 3-compose-ui\nNotes".moduleId())
    }
}
