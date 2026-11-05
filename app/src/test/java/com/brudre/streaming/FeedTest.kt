package com.brudre.streaming

import com.brudre.streaming.data.Kind
import com.brudre.streaming.data.SourceMode
import com.brudre.streaming.player.PlaybackPlan
import com.brudre.streaming.player.SourceAttempt
import com.brudre.streaming.sources.FeedParser
import com.brudre.streaming.sources.FeedSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Catálogos externos (JSON/RSS), validação de fontes e links abertos à mão. */
class FeedTest {

    private fun sample(name: String): String =
        listOf(File("../samples/feeds/$name"), File("samples/feeds/$name"), File("/home/claude/brudre/samples/feeds/$name")).first { it.exists() }.readText()

    @Test fun parsesSampleJsonFeedWithAllSourceTypes() {
        val feed = FeedParser.parse(sample("blender-open-movies.json"), "abc")
        assertEquals(5, feed.items.size)
        val types = feed.items.flatMap { it.sources.map { s -> s.type } }.toSet()
        assertEquals(setOf(FeedSource.Type.MAGNET, FeedSource.Type.TORRENT, FeedSource.Type.HTTP, FeedSource.Type.HLS), types)
        val magnetOnly = feed.items.first { it.title.name.startsWith("Sintel (magnet)") }
        assertEquals("feed:abc:sintel-magnet", magnetOnly.title.id)
        val p = magnetOnly.playables().single()
        assertNull(p.directUrl)
        assertNotNull(p.torrent!!.magnet)
        assertEquals("Sintel.mp4", p.torrent!!.filePathHint)
        assertEquals(Kind.KIDS, feed.items.first { it.title.name.startsWith("Big Buck Bunny (.torrent)") }.title.kind)
    }

    @Test fun bestSourceIsChosenAutomatically() {
        val item = FeedParser.parse(sample("blender-open-movies.json"), "abc").items.first { it.title.name.startsWith("Sintel (HTTP") }
        val p = item.playables().single()
        val attempts = PlaybackPlan.attempts(p, SourceMode.AUTO)
        assertTrue(attempts[0] is SourceAttempt.Direct)
        assertTrue(attempts.last() is SourceAttempt.Torrent)
        assertEquals(1, p.subtitles.size)
        // magnet + .torrent do mesmo título viram uma fonte BitTorrent só
        val tos = FeedParser.parse(sample("blender-open-movies.json"), "abc").items.first { it.title.name == "Tears of Steel" }.playables().single()
        assertNotNull(tos.torrent!!.magnet)
        assertNotNull(tos.torrent!!.torrentUrl)
    }

    @Test fun parsesRssWithTorrentEnclosures() {
        val feed = FeedParser.parse(sample("blender-open-movies.rss"), "rss")
        assertEquals("Filmes abertos da Blender (RSS de exemplo)", feed.name)
        assertEquals(2, feed.items.size)
        assertEquals("2010", feed.items[0].title.year)
        assertEquals(FeedSource.Type.TORRENT, feed.items[0].sources.single().type)
        assertFalse(feed.items[0].title.synopsis!!.contains("<b>"))
    }

    @Test fun unsafeSourcesAreRejected() {
        assertNull(FeedParser.source("http", "http://example.com/a.mp4"))          // sem TLS
        assertNull(FeedParser.source("http", "https://example.com/setup.apk"))     // executável
        assertNull(FeedParser.source(null, "file:///sdcard/a.mp4"))
        assertNull(FeedParser.source(null, "https://127.0.0.1/a.mp4"))
        assertNull(FeedParser.source(null, "magnet:?dn=sem-hash"))
        assertNull(FeedParser.source(null, "magnet:?xt=urn:btih:xyz"))
        assertNotNull(FeedParser.source(null, "magnet:?xt=urn:btih:08ada5a7a6183aae1e09d831df6748d566095a10&dn=Sintel"))
        assertNotNull(FeedParser.source(null, "https://example.com/live/index.m3u8"))
        val json = """{"items":[{"title":"Filme pornô","sources":[{"type":"http","url":"https://x.example.com/a.mp4"}]},
            {"title":"Sem fonte","sources":[{"type":"http","url":"http://x.example.com/a.mp4"}]},
            {"title":"Bom","sources":[{"type":"http","url":"https://x.example.com/a.mp4"}]}]}"""
        val f = FeedParser.parse(json, "t")
        assertEquals(listOf("Bom"), f.items.map { it.title.name })
        assertEquals(2, f.rejected)
    }

    @Test fun seriesEpisodesBecomePlayables() {
        val json = """{"items":[{"title":"Série Aberta","episodes":[
            {"season":1,"number":1,"title":"Piloto","sources":[{"type":"hls","url":"https://x.example.com/e1.m3u8"}]},
            {"season":1,"number":2,"title":"Ep 2","sources":[{"type":"magnet","url":"magnet:?xt=urn:btih:dd8255ecdc7ca55fb0bbf81323d87062db1f6d1c"}]}]}]}"""
        val item = FeedParser.parse(json, "s").items.single()
        assertEquals(Kind.SERIES, item.title.kind)
        assertEquals(listOf(1, 2), item.playables().map { it.number })
        val back = FeedParser.itemsFromJson(FeedParser.itemsToJson(listOf(item))).single()
        assertEquals(item, back)
    }

    @Test fun manualLinks() {
        val (t, p) = FeedParser.manual("magnet:?xt=urn:btih:08ada5a7a6183aae1e09d831df6748d566095a10&dn=Sintel")!!
        assertEquals("Sintel", t.name)
        assertNotNull(p.torrent)
        assertNotNull(FeedParser.manual("https://webtorrent.io/torrents/sintel.torrent")!!.second.torrent)
        assertEquals("https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8", FeedParser.manual("https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8")!!.second.directUrl)
        assertNotNull(FeedParser.manual("https://example.com/stream?id=9")) // vídeo https sem extensão
        assertNull(FeedParser.manual("http://example.com/a.mp4"))
        assertNull(FeedParser.manual("javascript:alert(1)"))
        assertNull(FeedParser.manual("https://example.com/virus.exe"))
    }
}
