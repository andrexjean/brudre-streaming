package com.brudre.streaming

import com.brudre.streaming.data.Kind
import com.brudre.streaming.data.Playable
import com.brudre.streaming.data.SourceMode
import com.brudre.streaming.data.Title
import com.brudre.streaming.data.TmdbSource
import com.brudre.streaming.data.TorrentRef
import com.brudre.streaming.player.PlaybackPlan
import com.brudre.streaming.player.SourceAttempt
import com.brudre.streaming.sources.TitleMatcher
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SourcesTest {

    private fun ia(name: String, year: String?) = Title("ia:$name", Kind.MOVIE, name, year = year)

    @Test fun normalizesTitles() {
        assertEquals("metropolis", TitleMatcher.normalize("Metropolis (1927)"))
        assertEquals("general", TitleMatcher.normalize("The General"))
        assertEquals("nosferatu eine symphonie des grauens", TitleMatcher.normalize("Nosferatu, eine Symphonie des Grauens"))
        assertEquals("tempos modernos", TitleMatcher.normalize("Tempos Modernos"))
        assertEquals("cabinet of dr caligari", TitleMatcher.normalize("The Cabinet of Dr. Caligari [restaurado]"))
        assertEquals("safety last", TitleMatcher.normalize("Safety Last!"))
    }

    @Test fun picksTheSameWorkByTitleAndYear() {
        val names = TitleMatcher.names("The General", "The General")
        val candidates = listOf(
            ia("The General Died at Dawn", "1936"),
            ia("General Spanky", "1936"),
            ia("The General (1926)", "1926"),
        )
        assertEquals("ia:The General (1926)", TitleMatcher.best(candidates, names, "1926")?.id)
    }

    @Test fun refusesDifferentWorksWithSameName() {
        val names = TitleMatcher.names("Metropolis")
        // Mesmo nome, outro ano (anime de 2001): não é a mesma obra.
        assertNull(TitleMatcher.best(listOf(ia("Metropolis", "2001")), names, "1927"))
        assertEquals("ia:Metropolis 1927 Restored", TitleMatcher.best(listOf(ia("Metropolis 1927 Restored", "1927")), names, "1927")?.id)
        // Sem ano no acervo: só aceita nome idêntico.
        assertEquals("ia:Metropolis", TitleMatcher.best(listOf(ia("Metropolis full movie", null), ia("Metropolis", null)), names, "1927")?.id)
    }

    @Test fun archiveQueryEscapesQuotes() {
        val q = TitleMatcher.archiveQuery(listOf("he said \"hi\"", "general"))
        assertEquals("title:(\"he said hi\") OR title:(\"general\")", q)
    }

    private val both = Playable("m", "Filme", 1, 1, "https://archive.org/download/x/x.mp4",
        TorrentRef("https://archive.org/download/x/x_archive.torrent", null, "x.mp4"), emptyList(), 100, preferTorrent = true,
        mirrors = listOf("https://mirror.example.org/x.mp4"))

    @Test fun directHttpComesBeforeTorrentEvenWhenTorrentPreferredByTheSource() {
        val a = PlaybackPlan.attempts(both, SourceMode.AUTO)
        assertEquals(listOf("direct", "direct", "torrent"), a.map { if (it is SourceAttempt.Direct) "direct" else "torrent" })
        assertEquals("https://archive.org/download/x/x.mp4", (a[0] as SourceAttempt.Direct).url)
        assertEquals("https://mirror.example.org/x.mp4", (a[1] as SourceAttempt.Direct).url)
    }

    @Test fun torrentEngineNeverStartsWhenDisabledOrDirectOnly() {
        assertTrue(PlaybackPlan.attempts(both, SourceMode.AUTO, allowTorrent = false).all { it is SourceAttempt.Direct })
        assertTrue(PlaybackPlan.attempts(both, SourceMode.DIRECT).all { it is SourceAttempt.Direct })
        assertTrue(PlaybackPlan.attempts(both, SourceMode.TORRENT).first() is SourceAttempt.Torrent)
    }

    @Test fun unsafeUrlsNeverReachThePlayer() {
        val bad = both.copy(directUrl = "file:///sdcard/x.mp4", mirrors = listOf("https://x.example.com/setup.apk"), torrent = null)
        assertTrue(PlaybackPlan.attempts(bad, SourceMode.AUTO).isEmpty())
    }

    @Test fun livePlayableRoundTripKeepsHeadersAndMirrors() {
        val live = Playable("live", "Ao vivo", 1, 1, "https://x.example.com/a.m3u8", null, emptyList(), null, false,
            mirrors = listOf("https://y.example.com/a.m3u8"), live = true, headers = mapOf("Referer" to "https://x.example.com/"))
        val back = Playable.fromJson(JSONObject(live.toJson().toString()))
        assertEquals(live, back)
        assertTrue(PlaybackPlan.attempts(back, SourceMode.TORRENT).all { it is SourceAttempt.Direct })
        assertEquals("streaming HLS", PlaybackPlan.attempts(back, SourceMode.AUTO).first().label)
    }

    @Test fun tmdbRowsOnlyCoverPublicDomainEra() {
        assertTrue(TmdbSource.ROWS.keys.all { it.startsWith("tmdb_pd_") })
        assertTrue(TmdbSource.PUBLIC_DOMAIN_YEAR <= 1930)
    }

    /** Requisito 18: nenhum serviço pago, aluguel ou compra no código do app. */
    @Test fun noPaidServiceRedirectsInSource() {
        val root = listOf(File("src/main"), File("app/src/main"), File("/home/claude/brudre/app/src/main")).first { it.exists() }
        val forbidden = Regex("(?i)(com\\.netflix|amazonvideo|disneyplus|com\\.hbo|globo\\.globotv|watch/providers|flatrate|StreamingLauncher|StreamingApps|Offer\\.RENT|Offer\\.BUY|JustWatch|with_watch_monetization)")
        val hits = root.walkTopDown().filter { it.isFile && (it.extension == "kt" || it.extension == "xml") }
            .flatMap { f -> f.readLines().mapIndexedNotNull { i, l -> if (forbidden.containsMatchIn(l)) "${f.name}:${i + 1}: ${l.trim()}" else null } }
            .toList()
        assertTrue("referências a serviços pagos: $hits", hits.isEmpty())
        assertFalse(File(root, "java/com/brudre/streaming/data/StreamingApps.kt").exists())
    }
}
