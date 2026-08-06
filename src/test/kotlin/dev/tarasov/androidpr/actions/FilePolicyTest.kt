package dev.tarasov.androidpr.actions

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
    }

    @Test
    fun `recognizes module three context`() {
        assertTrue(FilePolicy.isModuleThreeContext("feature/users/UserDetailsScreen.kt"))
        assertTrue(FilePolicy.isModuleThreeContext("app/src/androidTest/UserListTest.kt"))
        assertFalse(FilePolicy.isModuleThreeContext("data/UserRepository.kt"))
    }

    @Test
    fun `extracts exact module marker`() {
        assertEquals("3-compose-ui", "Title\nModule: 3-compose-ui\nNotes".moduleId())
    }
}
