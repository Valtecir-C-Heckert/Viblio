package br.com.viblio.account

import org.junit.Assert.assertEquals
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
    fun parsesNewerReleaseWithVerifiedApkAsset() {
        val json = """
            [
              {
                "tag_name": "viblio-v0.9.9",
                "draft": false,
                "prerelease": false,
                "html_url": "https://github.com/Valtecir-C-Heckert/Viblio/releases/tag/viblio-v0.9.9",
                "assets": [
                  {
                    "name": "viblio-0.9.9.apk",
                    "browser_download_url": "https://github.com/Valtecir-C-Heckert/Viblio/releases/download/viblio-v0.9.9/viblio-0.9.9.apk"
                  }
                ]
              }
            ]
        """.trimIndent()
        val info = AppUpdateChecker.parseLatestNewerRelease(json, "0.9.8")
        assertEquals("0.9.9", info?.version)
        assertEquals("viblio-v0.9.9", info?.tag)
        assertEquals(
            "https://github.com/Valtecir-C-Heckert/Viblio/releases/download/viblio-v0.9.9/viblio-0.9.9.apk",
            info?.apkUrl,
        )
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
