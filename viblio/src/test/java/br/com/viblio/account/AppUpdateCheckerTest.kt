package br.com.viblio.account

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdateCheckerTest {
    @Test
    fun comparesSemanticVersionsCorrectly() {
        assertTrue(AppUpdateChecker.compareVersions("0.9.8", "0.9.9") < 0)
        assertTrue(AppUpdateChecker.compareVersions("0.9.9", "0.9.10") < 0)
        assertTrue(AppUpdateChecker.compareVersions("0.9.9.1", "0.9.9.2") < 0)
        assertTrue(AppUpdateChecker.compareVersions("0.9.9", "0.9.9.1") < 0)
        assertTrue(AppUpdateChecker.compareVersions("0.9.10", "1.0.0") < 0)
        assertEquals(0, AppUpdateChecker.compareVersions("v0.9.8", "0.9.8"))
        assertTrue(AppUpdateChecker.compareVersions("1.0.0", "0.9.9") > 0)
    }

    @Test
    fun parsesNewerReleaseWithVerifiedApkAndChecksumsAsset() {
        val json = """
            [
              {
                "tag_name": "viblio-v0.9.9.1",
                "draft": false,
                "prerelease": false,
                "html_url": "https://github.com/Valtecir-C-Heckert/Viblio/releases/tag/viblio-v0.9.9.1",
                "assets": [
                  {
                    "name": "viblio-0.9.9.1.apk",
                    "browser_download_url": "https://github.com/Valtecir-C-Heckert/Viblio/releases/download/viblio-v0.9.9.1/viblio-0.9.9.1.apk"
                  },
                  {
                    "name": "viblio-0.9.9.1-SHA256SUMS.txt",
                    "browser_download_url": "https://github.com/Valtecir-C-Heckert/Viblio/releases/download/viblio-v0.9.9.1/viblio-0.9.9.1-SHA256SUMS.txt"
                  }
                ]
              }
            ]
        """.trimIndent()
        val info = AppUpdateChecker.parseLatestNewerRelease(json, "0.9.9")
        assertEquals("0.9.9.1", info?.version)
        assertEquals("viblio-v0.9.9.1", info?.tag)
        assertEquals("viblio-0.9.9.1.apk", info?.apkName)
        assertEquals(
            "https://github.com/Valtecir-C-Heckert/Viblio/releases/download/viblio-v0.9.9.1/viblio-0.9.9.1.apk",
            info?.apkUrl,
        )
        assertEquals(
            "https://github.com/Valtecir-C-Heckert/Viblio/releases/download/viblio-v0.9.9.1/viblio-0.9.9.1-SHA256SUMS.txt",
            info?.sumsUrl,
        )
    }

    @Test
    fun parsesExpectedSha256FromStandardChecksumsFile() {
        val shaA = "f528ed1f770f71f887ce3b2c3a74696babc4d7ed3e0794bb76b71449caa9f084"
        val shaB = "ABCDEF0123456789abcdef0123456789abcdef0123456789abcdef0123456789"
        val sums = """
            $shaA  viblio-0.9.9.apk
            $shaB *downloads/viblio-0.9.9.1.apk
            not-a-valid-line
        """.trimIndent()
        assertEquals(shaA, AppUpdateChecker.parseExpectedSha256(sums, "viblio-0.9.9.apk"))
        assertEquals(shaB.lowercase(), AppUpdateChecker.parseExpectedSha256(sums, "viblio-0.9.9.1.apk"))
        assertNull(AppUpdateChecker.parseExpectedSha256(sums, "viblio-0.9.8.apk"))
        assertNull(AppUpdateChecker.parseExpectedSha256("1234  viblio-0.9.9.apk", "viblio-0.9.9.apk"))
    }

    @Test
    fun validatesSumsUrlsStrictly() {
        assertTrue(
            AppUpdateChecker.isAllowedSumsUrl(
                "https://github.com/Valtecir-C-Heckert/Viblio/releases/download/viblio-v0.9.9.1/viblio-0.9.9.1-SHA256SUMS.txt",
            ),
        )
        assertFalse(AppUpdateChecker.isAllowedSumsUrl("https://evil.example.com/viblio-0.9.9.1-SHA256SUMS.txt"))
        assertFalse(AppUpdateChecker.isAllowedSumsUrl("http://github.com/Valtecir-C-Heckert/Viblio/releases/download/v1/SHA256SUMS.txt"))
        assertFalse(AppUpdateChecker.isAllowedSumsUrl("https://github.com/Valtecir-C-Heckert/Viblio/releases/download/v1/../SHA256SUMS.txt"))
    }

    @Test
    fun ignoresSameOrOlderDraftPrereleaseAndUntrustedUrls() {
        val json = """
            [
              {
                "tag_name": "viblio-v0.9.9",
                "draft": true,
                "prerelease": false,
                "assets": [{"name": "viblio-0.9.9.apk", "browser_download_url": "https://github.com/Valtecir-C-Heckert/Viblio/releases/download/viblio-v0.9.9/viblio-0.9.9.apk"}]
              },
              {
                "tag_name": "viblio-v0.9.9-beta",
                "draft": false,
                "prerelease": true,
                "assets": [{"name": "viblio-0.9.9.apk", "browser_download_url": "https://github.com/Valtecir-C-Heckert/Viblio/releases/download/viblio-v0.9.9/viblio-0.9.9.apk"}]
              },
              {
                "tag_name": "viblio-v0.9.10",
                "draft": false,
                "prerelease": false,
                "assets": [{"name": "viblio-0.9.10.apk", "browser_download_url": "https://evil.example.com/viblio-0.9.10.apk"}]
              },
              {
                "tag_name": "viblio-v0.9.8",
                "draft": false,
                "prerelease": false,
                "assets": [{"name": "viblio-0.9.8.apk", "browser_download_url": "https://github.com/Valtecir-C-Heckert/Viblio/releases/download/viblio-v0.9.8/viblio-0.9.8.apk"}]
              }
            ]
        """.trimIndent()
        assertNull(AppUpdateChecker.parseLatestNewerRelease(json, "0.9.8"))
    }
}
