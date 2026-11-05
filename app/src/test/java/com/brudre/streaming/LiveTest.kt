package com.brudre.streaming

import com.brudre.streaming.live.ChannelPolicy
import com.brudre.streaming.live.LiveCategories
import com.brudre.streaming.live.LiveChannel
import com.brudre.streaming.live.M3uParser
import com.brudre.streaming.sources.UrlGuard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveTest {

    private val sample = """
        #EXTM3U x-tvg-url="https://example.org/epg.xml"
        #EXTINF:-1 tvg-id="TVBrasil.br@SD" tvg-logo="https://i.imgur.com/a.png" group-title="General;News",TV Brasil (720p)
        https://tvbrasil.example.com/live/playlist.m3u8
        #EXTINF:-1 tvg-id="TVBrasil.br@SD" tvg-logo="https://i.imgur.com/a.png" group-title="General;News",TV Brasil (1080p) [Not 24/7]
        https://mirror.example.com/tvbrasil/index.m3u8
        #EXTINF:-1 tvg-id="Kids.br@SD" http-user-agent="Mozilla/5.0 (X11; Linux, like Gecko)" group-title="Kids",Canal, Infantil
        #EXTVLCOPT:http-referrer=https://kids.example.com/
        https://kids.example.com/stream.m3u8?token=1
        #EXTINF:-1 tvg-id="Telecine.br@SD" group-title="Movies",Telecine Action (720p)
        http://170.83.16.50/telecine/index.m3u8
        #EXTINF:-1 tvg-id="ComedyPluto.us@Brazil" group-title="Comedy",Comedy Central Pluto TV BR
        https://jmp2.uk/plu-5f357e91b18f0b00073583d2.m3u8
        #EXTINF:-1 tvg-id="Hot.br@SD" group-title="XXX",Hot Channel
        https://adult.example.com/live.m3u8
        #EXTINF:-1 tvg-id="Raw.br@SD" group-title="General",Raw TS
        http://79.127.238.228:14093
        #EXTINF:-1 tvg-id="Local.br@SD" group-title="General",Local
        http://127.0.0.1/live.m3u8
        #EXTINF:-1 tvg-id="Apk.br@SD" group-title="General",App falso
        https://evil.example.com/player.apk
        #EXTINF:-1 group-title="Documentary",Documentários Abertos (480p) [Geo-blocked]
        https://docs.example.com/hls/live.m3u8
        #EXTINF:-1 tvg-id="SemTls.br@SD" group-title="General",Sem TLS
        http://plain.example.com/live/index.m3u8
    """.trimIndent()

    @Test fun parsesAttributesHeadersAndMirrors() {
        val r = M3uParser.parse(sample, "test")
        val names = r.channels.map { it.name }
        assertEquals(listOf("TV Brasil", "Canal, Infantil", "Comedy Central Pluto TV BR", "Documentários Abertos"), names)
        val tv = r.channels.first()
        assertEquals("720p", tv.quality)
        assertEquals(listOf("https://mirror.example.com/tvbrasil/index.m3u8"), tv.mirrors)
        assertEquals("br", tv.country)
        assertEquals(listOf("General", "News"), tv.groups)
        val kids = r.channels[1]
        assertEquals("Mozilla/5.0 (X11; Linux, like Gecko)", kids.userAgent)
        assertEquals("https://kids.example.com/", kids.referrer)
        assertEquals("https://kids.example.com/", kids.headers["Referer"])
        val docs = r.channels.last()
        assertTrue(docs.geoBlocked)
        assertEquals(11, r.total)
    }

    @Test fun blocksAdultPayTvUnsafeAndNonHls() {
        val r = M3uParser.parse(sample, "test")
        assertEquals(1, r.rejected["adulto"])
        assertEquals(1, r.rejected["pago"])
        assertEquals(1, r.rejected["formato"])
        assertEquals(2, r.rejected["url"])
        assertEquals(1, r.rejected["inseguro"])
        assertTrue(r.channels.none { it.name.contains("Telecine") || it.name.contains("Hot") })
    }

    @Test fun fastChannelsAreAllowedEvenWithCableBrandNames() {
        assertFalse(ChannelPolicy.isPayTv("MTV Pluto TV BR", "https://jmp2.uk/plu-123.m3u8"))
        assertTrue(ChannelPolicy.isPayTv("MTV Brasil", "https://cdn.example.com/mtv.m3u8"))
        assertTrue(ChannelPolicy.isPayTv("SporTV 2", "http://1.2.3.4/sportv/index.m3u8"))
        assertTrue(ChannelPolicy.isPayTv("Premiere Clubes", "https://x.example.com/p.m3u8"))
        assertFalse(ChannelPolicy.isPayTv("Rede Globo", "https://x.example.com/g.m3u8"))
        assertFalse(ChannelPolicy.isPayTv("Band", "https://x.example.com/band.m3u8"))
        assertFalse(ChannelPolicy.isPayTv("TV Cultura", "https://x.example.com/c.m3u8"))
    }

    @Test fun stableIdsAndJsonRoundTrip() {
        val a = M3uParser.parse(sample, "test").channels
        val b = M3uParser.parse(sample, "test").channels
        assertEquals(a.map { it.id }, b.map { it.id })
        val back = LiveChannel.listFromJson(LiveChannel.listToJson(a))
        assertEquals(a, back)
    }

    @Test fun rejectsFilesThatAreNotPlaylists() {
        try {
            M3uParser.parse("<html><body>oi</body></html>", "x")
            throw AssertionError("deveria falhar")
        } catch (e: IllegalArgumentException) {
            assertNotNull(e.message)
        }
    }

    @Test fun categoriesAndSearch() {
        val list = M3uParser.parse(sample, "test").channels
        val groups = LiveCategories.group(list).associate { it.first.key to it.second.map { c -> c.name } }
        assertEquals(listOf("TV Brasil"), groups["news"])
        assertEquals(listOf("Canal, Infantil"), groups["kids"])
        assertEquals(listOf("Documentários Abertos"), groups["docs"])
        assertEquals(listOf("TV Brasil"), LiveCategories.search(list, "tv bras").map { it.name })
        assertEquals(listOf("Documentários Abertos"), LiveCategories.search(list, "documentarios").map { it.name })
    }

    @Test fun realIptvOrgBrazilSample() {
        val text = javaClass.classLoader!!.getResourceAsStream("iptv_br_sample.m3u")!!.bufferedReader().readText()
        val r = M3uParser.parse(text, "iptv_br")
        assertEquals(140, r.total)
        assertTrue("canais válidos: ${r.channels.size}", r.channels.size >= 30)
        assertTrue(r.channels.all { ChannelPolicy.isHls(it.url) && UrlGuard.isAllowed(it.url) && it.url.startsWith("https://") })
        assertTrue(r.channels.none { it.name.startsWith("A&E") || it.name.startsWith("AXN") || it.name.startsWith("Band Sports") })
        assertTrue(r.channels.any { ChannelPolicy.isFastHost(it.url) })
        assertTrue(r.channels.all { it.logo == null || it.logo!!.startsWith("http") })
    }

    @Test fun adultAnimationNeverInKids() {
        val sp = LiveChannel("1", "Comedy Central South Park", "https://jmp2.uk/a.m3u8", groups = listOf("Animation", "Comedy"))
        val kids = LiveChannel("2", "Turma da Monica", "https://x.example.com/a.m3u8", groups = listOf("Animation", "Kids"))
        val sp2 = LiveChannel("3", "South Park Kenny", "https://jmp2.uk/b.m3u8", groups = listOf("Kids"))
        assertEquals("ent", LiveCategories.categoryOf(sp).key)
        assertEquals("kids", LiveCategories.categoryOf(kids).key)
        assertFalse(LiveCategories.categoryOf(sp2).key == "kids")
    }

    @Test fun urlGuard() {
        assertNull(UrlGuard.check("https://archive.org/download/x/y.mp4"))
        assertNull(UrlGuard.check("http://192.168.0.10:8080/lista.m3u"))
        assertNotNull(UrlGuard.check("file:///sdcard/a.mp4"))
        assertNotNull(UrlGuard.check("javascript:alert(1)"))
        assertNotNull(UrlGuard.check("content://com.x/y"))
        assertNotNull(UrlGuard.check("https://user:pass@example.com/a.m3u8"))
        assertNotNull(UrlGuard.check("http://localhost/a.m3u8"))
        assertNotNull(UrlGuard.check("http://127.0.0.1:8080/a.m3u8"))
        assertNotNull(UrlGuard.check("http://169.254.169.254/latest/meta-data"))
        assertNotNull(UrlGuard.check("http://[::1]/a.m3u8"))
        assertNotNull(UrlGuard.check("https://example.com/update.APK"))
        assertNotNull(UrlGuard.check("https://example.com/a b.m3u8"))
        assertTrue(UrlGuard.isExecutableResponse("application/vnd.android.package-archive; charset=x", "https://x.com/video"))
        assertFalse(UrlGuard.isExecutableResponse("video/mp4", "https://x.com/video.mp4"))
    }
}
