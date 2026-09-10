package com.nuvio.tv.updater

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VersionUtilsTest {
    @Test
    fun `stable release is newer than its prerelease`() {
        assertTrue(VersionUtils.isRemoteNewer("1.1.0", "1.1.0-rc.2"))
    }

    @Test
    fun `beta identifiers use numeric ordering`() {
        assertTrue(VersionUtils.isRemoteNewer("1.1.0-beta.10", "1.1.0-beta.9"))
    }

    @Test
    fun `prerelease for a later minor is newer than current stable`() {
        assertTrue(VersionUtils.isRemoteNewer("1.1.0-beta.1", "1.0.3"))
    }

    @Test
    fun `stable channel patch is not newer than later minor beta`() {
        assertFalse(VersionUtils.isRemoteNewer("1.0.4", "1.1.0-beta.2"))
    }

    @Test
    fun `version prefix and build metadata do not affect precedence`() {
        assertFalse(VersionUtils.isRemoteNewer("v1.0.0+18", "1.0.0+17"))
    }

    @Test
    fun `invalid remote version is not offered`() {
        assertFalse(VersionUtils.isRemoteNewer("latest", "1.0.0"))
    }

    @Test
    fun `current beta naming is recognized as prerelease`() {
        assertTrue(VersionUtils.isPrerelease("0.8.12-beta"))
    }

    @Test
    fun `lite edition suffix is not a prerelease`() {
        assertFalse(VersionUtils.isPrerelease("1.4.3-lite"))
    }

    @Test
    fun `lite releases compare by version, not by the edition suffix`() {
        assertTrue(VersionUtils.isRemoteNewer("v1.4.3-lite", "1.4.2-lite"))
        assertFalse(VersionUtils.isRemoteNewer("v1.4.2-lite", "1.4.2-lite"))
    }

    // TG-START: own-fork -tg.N iteration is the OTA build number (re-apply on upstream merge)
    @Test
    fun `installed tg build does not see its own release as an update`() {
        assertFalse(VersionUtils.isRemoteNewer("v1.4.5-lite-tg.3", "1.4.5-lite-tg.3"))
    }

    @Test
    fun `next tg iteration is newer`() {
        assertTrue(VersionUtils.isRemoteNewer("v1.4.5-lite-tg.4", "1.4.5-lite-tg.3"))
        assertFalse(VersionUtils.isRemoteNewer("v1.4.5-lite-tg.3", "1.4.5-lite-tg.4"))
    }

    @Test
    fun `tg iterations use numeric ordering`() {
        assertTrue(VersionUtils.isRemoteNewer("v1.4.5-lite-tg.10", "1.4.5-lite-tg.9"))
    }

    @Test
    fun `tg iteration is newer than old-style versionName without iteration`() {
        // One-time transition: builds whose versionName predates the .N suffix
        // still get offered the next iteration (and, while it is latest, its own).
        assertTrue(VersionUtils.isRemoteNewer("v1.4.5-lite-tg.4", "1.4.5-lite-tg"))
        assertTrue(VersionUtils.isRemoteNewer("v1.4.5-lite-tg.3", "1.4.5-lite-tg"))
    }

    @Test
    fun `newer upstream base wins regardless of tg iteration`() {
        assertTrue(VersionUtils.isRemoteNewer("v1.4.6-lite-tg.1", "1.4.5-lite-tg.9"))
        assertFalse(VersionUtils.isRemoteNewer("v1.4.5-lite-tg.9", "1.4.6-lite-tg.1"))
    }

    @Test
    fun `tg iteration of non-tg versions is null`() {
        assertTrue(VersionUtils.tgIterationOf("1.4.5-lite-tg.3") == 3L)
        assertTrue(VersionUtils.tgIterationOf("1.4.5-lite-tg") == null)
        assertTrue(VersionUtils.tgIterationOf("0.8.12-beta") == null)
    }
    // TG-END
}
