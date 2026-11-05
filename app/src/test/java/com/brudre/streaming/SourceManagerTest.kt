package com.brudre.streaming

import com.brudre.streaming.sources.SourceDetector
import com.brudre.streaming.sources.SourceTest
import com.brudre.streaming.sources.SourceType
import com.brudre.streaming.sources.UserSource
import com.brudre.streaming.sources.UserSourcesJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Gerenciar fontes: identificação do formato e importação/exportação. */
class SourceManagerTest {
    private fun d(url: String, type: String?, head: String) = SourceDetector.classify(url, type, head.toByteArray()).type
    private fun d(url: String, type: String?, head: ByteArray) = SourceDetector.classify(url, type, head).type

    @Test fun detectsEveryFormat() {
        assertEquals(SourceType.MAGNET, d("magnet:?xt=urn:btih:08ada5a7a6183aae1e09d831df6748d566095a10&dn=Sintel", null, ""))
        assertNull(d("magnet:?dn=x", null, ""))
        assertEquals(SourceType.M3U, d("https://x/lista.m3u", "audio/x-mpegurl", "#EXTM3U\n#EXTINF:-1 tvg-id=\"a\",Canal\nhttps://x/a.m3u8\n"))
        assertEquals(SourceType.HLS, d("https://x/live.m3u8", "application/vnd.apple.mpegurl", "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1\nlow.m3u8\n"))
        assertEquals(SourceType.HLS, d("https://x/media.m3u8", null, "#EXTM3U\n#EXT-X-TARGETDURATION:6\n#EXTINF:6,\na.ts\n"))
        assertEquals(SourceType.CATALOG_JSON, d("https://x/c.json", "application/json", "{\"name\":\"x\",\"items\":[]}"))
        assertNull(d("https://x/c.json", "application/json", "{\"foo\":1}"))
        assertEquals(SourceType.RSS, d("https://x/feed", "application/rss+xml", "<?xml version=\"1.0\"?><rss version=\"2.0\"><channel>"))
        assertNull(d("https://github.com/", "text/html", "<!DOCTYPE html><html><head>"))
        assertEquals(SourceType.TORRENT, d("https://x/a.torrent", "application/x-bittorrent", "d8:announce35:udp://tracker:13374:infod6:lengthi1ee"))
        val mp4 = byteArrayOf(0, 0, 0, 0x20, 'f'.code.toByte(), 't'.code.toByte(), 'y'.code.toByte(), 'p'.code.toByte(), 'i'.code.toByte(), 's'.code.toByte(), 'o'.code.toByte(), 'm'.code.toByte())
        assertEquals(SourceType.VIDEO, d("https://x/video", null, mp4))
        val mkv = byteArrayOf(0x1A, 0x45, 0xDF.toByte(), 0xA3.toByte(), 0, 0, 0, 0, 0, 0, 0, 0)
        assertEquals(SourceType.VIDEO, d("https://x/v.mkv", "application/octet-stream", mkv))
    }

    @Test fun refusesExecutablesAndUnknown() {
        val apk = byteArrayOf('P'.code.toByte(), 'K'.code.toByte(), 3, 4, 0, 0, 0, 0, 0, 0, 0, 0)
        assertNull(d("https://x/arquivo", "application/octet-stream", apk))
        assertNull(d("https://x/app", "application/vnd.android.package-archive", "qualquer"))
        assertNull(d("https://x/setup", null, "MZ\u0090\u0000"))
        assertNull(d("https://x/bin", null, byteArrayOf(0x7F, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte())))
        assertNull(d("https://x/txt", "text/plain", "olá mundo"))
        assertTrue(SourceDetector.classify("https://github.com/", "text/html", "<html>".toByteArray()).reason.contains("página web"))
    }

    @Test fun exportImportRoundTripAndSafety() {
        val a = UserSource(UserSource.idFor(SourceType.CATALOG_JSON, "https://x.example.com/c.json"), "Catálogo", "https://x.example.com/c.json", SourceType.CATALOG_JSON, priority = 0,
            lastTest = SourceTest(true, 1, "5 títulos", 5))
        val b = UserSource(UserSource.idFor(SourceType.MAGNET, "magnet:?xt=urn:btih:08ada5a7a6183aae1e09d831df6748d566095a10"), "Sintel", "magnet:?xt=urn:btih:08ada5a7a6183aae1e09d831df6748d566095a10", SourceType.MAGNET, enabled = false, priority = 1)
        val json = UserSourcesJson.export(listOf(b, a))
        val back = UserSourcesJson.import(json)
        assertEquals(listOf("Catálogo", "Sintel"), back.map { it.name }) // ordem = prioridade
        assertEquals(false, back[1].enabled)
        assertNull(back[0].lastTest) // o teste não é exportado
        assertTrue(a.id.startsWith("feed_") && b.id.startsWith("link_"))
        assertTrue(UserSource.idFor(SourceType.M3U, "https://x/l.m3u").startsWith("m3u_"))
        // Entradas inseguras são descartadas na importação
        val evil = """{"format":"brudre-fontes","version":1,"sources":[
            {"name":"a","url":"http://x.example.com/c.json","type":"CATALOG_JSON"},
            {"name":"b","url":"file:///sdcard/x","type":"VIDEO"},
            {"name":"c","url":"https://x.example.com/v.mp4","type":"NAO_EXISTE"},
            {"name":"d","url":"https://x.example.com/v.mp4","type":"VIDEO"}]}"""
        assertEquals(listOf("d"), UserSourcesJson.import(evil).map { it.name })
        try { UserSourcesJson.import("{\"x\":1}"); throw AssertionError() } catch (e: IllegalArgumentException) { }
        assertEquals(2, UserSourcesJson.listFromJson(UserSourcesJson.listToJson(listOf(a, b))).size)
    }
}
