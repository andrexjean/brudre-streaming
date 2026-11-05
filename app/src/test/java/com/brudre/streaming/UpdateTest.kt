package com.brudre.streaming

import com.brudre.streaming.update.UpdateManager
import org.junit.Assert.assertEquals
import org.junit.Test

class UpdateTest {
    @Test fun parsesGithubRelease() {
        val json = """{"tag_name":"v7.0.15","assets":[
            {"name":"BruDre-Streaming-Source.zip","browser_download_url":"https://x/src.zip","size":10},
            {"name":"BruDre-Streaming.apk","browser_download_url":"https://github.com/a/b/releases/download/v7.0.15/BruDre-Streaming.apk","size":22040424}]}"""
        val r = UpdateManager.parseRelease(json)
        assertEquals(15, r.build)
        assertEquals(22040424L, r.sizeBytes)
        assertEquals("https://github.com/a/b/releases/download/v7.0.15/BruDre-Streaming.apk", r.apkUrl)
        assertEquals(12, UpdateManager.buildFromTag("v7.0.12"))
        assertEquals(0, UpdateManager.buildFromTag("sem-numero-x"))
    }

    @Test(expected = java.io.IOException::class)
    fun releaseWithoutApkFails() {
        UpdateManager.parseRelease("""{"tag_name":"v1","assets":[]}""")
    }
}
