package com.itsmikefan.spotifastcustom

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UpdatesTest {
    @Test
    fun readsTheBuildFromTheReleaseTitle() {
        assertEquals(12L, Updates.buildNumber("Android build 12"))
        assertNull(Updates.buildNumber("Android (latest build)"))
    }
}
