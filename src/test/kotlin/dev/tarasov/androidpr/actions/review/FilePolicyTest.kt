package dev.tarasov.androidpr.actions.review

import dev.tarasov.androidpr.actions.rubric.moduleId
import dev.tarasov.androidpr.actions.rubric.MODULE_ONE_ID
import dev.tarasov.androidpr.actions.rubric.MODULE_THREE_ID
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
    fun `extracts exact module marker`() {
        assertEquals("3-compose-ui", "Title\nModule: 3-compose-ui\nNotes".moduleId())
    }
}
