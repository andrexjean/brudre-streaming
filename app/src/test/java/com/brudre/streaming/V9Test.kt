package com.brudre.streaming

import com.brudre.streaming.data.Kind
import com.brudre.streaming.data.Playable
import com.brudre.streaming.data.SourceOption
import com.brudre.streaming.data.Title
import com.brudre.streaming.data.TorrentRef
import com.brudre.streaming.player.PlaybackPlan
import com.brudre.streaming.player.SourceAttempt
import com.brudre.streaming.data.SourceMode
import com.brudre.streaming.sources.BlockedUrlException
import com.brudre.streaming.sources.ClassifiedException
import com.brudre.streaming.sources.ErrorClassifier
import com.brudre.streaming.sources.ErrorKind
import com.brudre.streaming.sources.FeedParser
import com.brudre.streaming.sources.HttpStatusException
import com.brudre.streaming.sources.SearchRepository
import com.brudre.streaming.sources.SearchTemplate
import com.brudre.streaming.sources.SourceDetector
import com.brudre.streaming.sources.SourceRanker
import com.brudre.streaming.sources.SourceStats
import com.brudre.streaming.sources.SourceTest
import com.brudre.streaming.sources.SourceType
import com.brudre.streaming.sources.UserSource
import com.brudre.streaming.sources.UserSourcesJson
import com.brudre.streaming.torrent.Bencode
import com.brudre.streaming.torrent.EpisodeId
import com.brudre.streaming.torrent.TorrentContent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException

/** v9.0: classificação de erros, torrent multiarquivo, episódios, legendas, pesquisa e agregação de fontes. */
class V9Test {

    // ------------------------------------------------------------ erros (itens 10, 11, 12)

    @Test fun dnsFailureIsClassified() {
        val f = ErrorClassifier.classify(com.brudre.streaming.data.CatalogException("falha", UnknownHostException("Unable to resolve host \"1337x.to\": No address associated with hostname")))
        assertEquals(ErrorKind.DNS, f.kind)
        assertTrue(f.message.contains("1337x.to"))
        assertTrue("erro original preservado", f.technical.contains("UnknownHostException"))
    }

    @Test fun timeoutUnreachableTlsHttpStorageBlocked() {
        assertEquals(ErrorKind.TIMEOUT, ErrorClassifier.classify(SocketTimeoutException("timeout")).kind)
        assertEquals(ErrorKind.UNREACHABLE, ErrorClassifier.classify(ConnectException("Failed to connect")).kind)
        assertEquals(ErrorKind.TLS, ErrorClassifier.classify(SSLHandshakeException("cert expired")).kind)
        val http = ErrorClassifier.classify(HttpStatusException(404))
        assertEquals(ErrorKind.HTTP, http.kind)
        assertTrue(http.message.contains("404"))
        assertEquals(ErrorKind.HTTP, ErrorClassifier.classify(HttpStatusException(503)).kind)
        assertEquals(ErrorKind.STORAGE, ErrorClassifier.classify(java.io.IOException("write failed: ENOSPC (No space left on device)")).kind)
        assertEquals(ErrorKind.BLOCKED, ErrorClassifier.classify(BlockedUrlException("Destino local bloqueado")).kind)
        assertEquals(ErrorKind.NO_PEERS, ErrorClassifier.classify(ClassifiedException(ErrorKind.NO_PEERS, "sem peers")).kind)
        assertEquals(ErrorKind.CATALOG, ErrorClassifier.classify(org.json.JSONException("bad")).kind)
    }

    @Test fun messagesArePortuguese() {
        ErrorKind.entries.forEach { k -> assertFalse(k.title.isBlank()); assertFalse(k.hint.contains("Unable") || k.hint.contains("failed")) }
    }

    // ------------------------------------------------------------ magnet (item 1)

    @Test fun magnetValidation() {
        assertTrue(FeedParser.isValidMagnet("magnet:?xt=urn:btih:08ada5a7a6183aae1e09d831df6748d566095a10&dn=Sintel"))
        assertFalse(FeedParser.isValidMagnet("magnet:?xt=urn:btih:123"))
        assertFalse(FeedParser.isValidMagnet("magnet:?dn=sem-hash"))
        assertEquals("08ada5a7a6183aae1e09d831df6748d566095a10", TorrentContent.magnetHash("magnet:?xt=urn:btih:08ADA5A7A6183AAE1E09D831DF6748D566095A10"))
        // base32 → hex
        assertEquals(40, TorrentContent.magnetHash("magnet:?xt=urn:btih:BCW6QJ5WHDBPHVSDI6XEAOUBCY5GK5KX")?.length)
        assertEquals(SourceType.MAGNET, SourceDetector.classify("magnet:?xt=urn:btih:08ada5a7a6183aae1e09d831df6748d566095a10", null, ByteArray(0)).type)
        assertNull(SourceDetector.classify("magnet:?xt=urn:btih:zz", null, ByteArray(0)).type)
    }

    // ------------------------------------------------------------ .torrent (itens 2, 4, 5)

    private fun testTorrent(): ByteArray {
        val f = listOf(File("../samples/test-torrent/brudre-teste-serie.torrent"), File("samples/test-torrent/brudre-teste-serie.torrent")).first { it.exists() }
        return f.readBytes()
    }

    @Test fun parsesMultiFileTorrent() {
        val meta = TorrentContent.parse(testTorrent())
        assertEquals("bf16e86e7636315da5c443296bee3dd58c051039", meta.infoHash)
        assertEquals("BruDre.Teste.Serie", meta.name)
        assertEquals(5, meta.files.size)
        assertTrue(meta.webSeeds.single().startsWith("https://raw.githubusercontent.com/"))
        val v = meta.videos
        assertEquals(3, v.size)
        assertEquals(listOf(EpisodeId(1, 1), EpisodeId(1, 2), EpisodeId(2, 1)), v.map { it.episode })
        // Legenda do torrent associada ao episódio certo
        assertEquals(1, v[0].subtitles.size)
        assertEquals("pt", TorrentContent.subtitleLanguage(v[0].subtitles.first().name))
        assertTrue(v[1].subtitles.isEmpty())
        // índices iguais aos do libtorrent (ordem do "files")
        assertTrue(v.all { meta.files[it.file.index] == it.file })
    }

    @Test fun rejectsInvalidTorrent() {
        listOf("<html>".toByteArray(), ByteArray(0), "d4:infoi1e".toByteArray(), "d8:announce3:abce".toByteArray()).forEach { b ->
            try { TorrentContent.parse(b); fail("deveria recusar") } catch (e: ClassifiedException) { assertEquals(ErrorKind.FORMAT, e.kind) }
        }
    }

    @Test fun pathTraversalIsNeutralized() {
        val info = mapOf("name" to "x", "piece length" to 16384L, "pieces" to ByteArray(20),
            "files" to listOf(mapOf("length" to 10L, "path" to listOf("..", "..", "etc", "passwd")), mapOf("length" to 10L, "path" to listOf("a\\..\\b.mp4"))))
        val meta = TorrentContent.parse(Bencode.encode(mapOf("info" to info)))
        assertEquals("x/etc/passwd", meta.files[0].path)
        assertFalse(meta.files[1].path.contains(".."))
    }

    @Test fun episodePatterns() {
        assertEquals(EpisodeId(1, 2), TorrentContent.episodeOf("Serie/Show.S01E02.1080p.mkv"))
        assertEquals(EpisodeId(3, 10), TorrentContent.episodeOf("Show 3x10.mp4"))
        assertEquals(EpisodeId(2, 5), TorrentContent.episodeOf("Serie/Temporada 2/Episodio 05.mp4"))
        assertEquals(EpisodeId(4, 7), TorrentContent.episodeOf("Season 04/E07 - Title.mp4"))
        assertNull(TorrentContent.episodeOf("Filme.2010.1080p.mp4"))
    }

    @Test fun samplesAreHiddenAndLargestIsNotAssumed() {
        val info = mapOf("name" to "Filme", "piece length" to 16384L, "pieces" to ByteArray(20), "files" to listOf(
            mapOf("length" to 5_000_000L, "path" to listOf("Sample", "filme-sample.mkv")),
            mapOf("length" to 900_000_000L, "path" to listOf("Filme.2010.mkv")),
            mapOf("length" to 800_000_000L, "path" to listOf("Extras", "Bastidores.mkv")),
        ))
        val v = TorrentContent.parse(Bencode.encode(mapOf("info" to info))).videos
        // amostra fora; dois vídeos restantes → o app pergunta (não escolhe o maior sozinho)
        assertEquals(2, v.size)
        assertFalse(v.any { it.file.isSample })
    }

    // ------------------------------------------------------------ catálogo JSON (itens 6, 8)

    private val catalog = """
        {"name": "Teste", "items": [
          {"id": "a", "title": "Big Buck Bunny (1080p)", "year": "2008", "tmdb": "movie:10378", "language": "en", "audio": ["en", "pt"],
           "sources": [{"type": "http", "url": "https://example.org/bbb.mp4"}]},
          {"id": "b", "title": "Sintel", "year": "2010", "tmdb": 45745, "kind": "MOVIE", "language": "en",
           "subtitles": [{"lang": "pt", "url": "https://example.org/sintel.pt.srt"}],
           "sources": [{"type": "magnet", "url": "magnet:?xt=urn:btih:08ada5a7a6183aae1e09d831df6748d566095a10"}]},
          {"id": "c", "title": "Curta", "year": "2015", "language": "ja", "sources": [{"type": "http", "url": "https://example.org/c.mp4"}]}
        ]}
    """.trimIndent()

    @Test fun catalogTmdbLanguageAndIndex() {
        val feed = FeedParser.parse(catalog, "t")
        assertEquals(3, feed.items.size)
        val (bbb, sintel, curta) = feed.items
        assertEquals("movie:10378", bbb.tmdbId)
        assertEquals("movie:45745", sintel.tmdbId)
        assertTrue(bbb.title.ptAudio)
        assertEquals("PT-BR", bbb.title.languageBadge)
        assertTrue(sintel.title.ptSubtitles)
        assertEquals("LEG PT", sintel.title.languageBadge)
        assertEquals("ORIGINAL JA", curta.title.languageBadge)
        assertTrue(curta.title.languageLine!!.contains("japonês"))
        // índice leve: nome limpo + ano + tmdb + posição
        val idx = FeedParser.indexOf(feed.items).lines().map { FeedParser.parseIndexLine(it)!! }
        assertEquals("big buck bunny", idx[0].name)
        assertEquals("movie:10378", idx[0].tmdbId)
        assertEquals(2, idx[2].position)
        assertEquals("1080p", FeedParser.qualityOf(bbb.title.name))
        // round-trip do cache em disco mantém o TMDB
        assertEquals("movie:45745", FeedParser.itemsFromJson(FeedParser.itemsToJson(feed.items))[1].tmdbId)
    }

    @Test fun titleJsonKeepsLanguage() {
        val t = Title("x", Kind.MOVIE, "Filme", language = "en", ptSubtitles = true)
        val back = Title.fromJson(t.toJson())
        assertEquals("en", back.language)
        assertTrue(back.ptSubtitles)
        assertFalse(back.ptAudio)
    }

    // ------------------------------------------------------------ pesquisa (item 7)

    @Test fun searchTemplateExpansionAndSafety() {
        val t = "https://api.exemplo.org/busca?q={query}&ano={year}&tmdb={tmdb}"
        assertTrue(SearchTemplate.isTemplate(t))
        assertEquals("https://api.exemplo.org/busca?q=o%20auto%20da%20compadecida&ano=2000&tmdb=movie%3A40096",
            SearchTemplate.expand(t, SearchTemplate.Query("o auto da compadecida", "movie:40096", "2000")))
        assertEquals("https://x.org/s/auto-da-compadecida.json", SearchTemplate.expand("https://x.org/s/{slug}.json", SearchTemplate.Query("O Auto da Compadecida!")))
        assertNull(SearchTemplate.check(t))
        assertNotNull(SearchTemplate.check("http://api.exemplo.org/?q={query}"))
        assertNotNull(SearchTemplate.check("https://{query}.exemplo.org/"))
        assertNotNull(SearchTemplate.check("https://127.0.0.1/?q={query}"))
        assertNotNull(SearchTemplate.check("https://api.exemplo.org/sem-marcador"))
        assertEquals(SourceType.SEARCH, SourceDetector.classify(t, null, ByteArray(0)).type)
        assertTrue(UserSourcesJson.isSafe(SourceType.SEARCH, t))
    }

    @Test fun searchResponseMustBeBruDreJson() {
        assertEquals(3, SearchRepository.parse(catalog, "search_x").items.size)
        assertEquals(0, SearchRepository.parse("""{"items": []}""", "search_x").items.size)
        try { SearchRepository.parse("<html><body>site</body></html>", "s"); fail() } catch (e: ClassifiedException) { assertEquals(ErrorKind.FORMAT, e.kind) }
        try { SearchRepository.parse("""{"results": []}""", "s"); fail() } catch (e: ClassifiedException) { assertEquals(ErrorKind.CATALOG, e.kind) }
    }

    // ------------------------------------------------------------ agregação e prioridade (item 9)

    private fun opt(id: String, url: String?, magnet: String? = null, native: Boolean = false) = SourceOption(
        id, id, SourceRanker.tag(listOf(Playable("k", "f", 1, 1, url, magnet?.let { TorrentRef(null, it, null) }, emptyList(), null, false)), id), "HTTP", native = native,
    )

    @Test fun orderRespectsPriorityHistoryAndNativePosition() {
        val native = opt("archive", "https://n.org/a.mp4", native = true)
        val a = SourceRanker.Candidate(opt("feed_a", "https://a.org/a.mp4"), 0, false)
        val b = SourceRanker.Candidate(opt("feed_b", "https://b.org/b.mp4"), 1, false)
        val bad = SourceRanker.Candidate(opt("feed_bad", "https://c.org/c.mp4"), -1, true)
        assertEquals(listOf("archive", "feed_a", "feed_b", "feed_bad"), SourceRanker.order(native, listOf(b, bad, a), userFirst = false).map { it.sourceId })
        assertEquals(listOf("feed_a", "feed_b", "feed_bad", "archive"), SourceRanker.order(native, listOf(b, bad, a), userFirst = true).map { it.sourceId })
    }

    @Test fun mergeKeepsAllSourcesAsFallbackInOrder() {
        val m1 = "magnet:?xt=urn:btih:08ada5a7a6183aae1e09d831df6748d566095a10"
        val m2 = "magnet:?xt=urn:btih:dd8255ecdc7ca55fb0bbf81323d87062db1f6d1c"
        val merged = SourceRanker.merge(listOf(opt("feed_a", "https://a.org/a.mp4", m1), opt("feed_b", "https://b.org/b.mp4", m2), opt("feed_c", null, m1)))
        val p = merged.single()
        assertEquals("https://a.org/a.mp4", p.directUrl)
        assertEquals(listOf("https://b.org/b.mp4"), p.mirrors)
        assertEquals(m1, p.torrent?.magnet)
        assertEquals(listOf(m2), p.altTorrents.map { it.magnet })
        assertEquals("feed_b", p.origins["https://b.org/b.mp4"])
        val attempts = PlaybackPlan.attempts(p, SourceMode.AUTO)
        assertEquals(listOf("https://a.org/a.mp4", "https://b.org/b.mp4"), attempts.filterIsInstance<SourceAttempt.Direct>().map { it.url })
        assertEquals(listOf("feed_a", "feed_b", "feed_a", "feed_b"), attempts.map { it.sourceId })
        assertEquals(2, attempts.filterIsInstance<SourceAttempt.Torrent>().size)
    }

    @Test fun mergeMatchesEpisodes() {
        fun ep(id: String, s: Int, n: Int) = Playable("$id$s$n", "e", s, n, "https://$id.org/$s$n.mp4", null, emptyList(), null, false)
        val a = SourceOption("a", "a", listOf(ep("a", 1, 1), ep("a", 1, 2)), "HTTP")
        val b = SourceOption("b", "b", listOf(ep("b", 1, 2), ep("b", 1, 1)), "HTTP")
        val m = SourceRanker.merge(listOf(a, b))
        assertEquals(listOf("https://b.org/11.mp4"), m[0].mirrors)
        assertEquals(listOf("https://b.org/12.mp4"), m[1].mirrors)
    }

    @Test fun statsDemoteRepeatedFailures() {
        var s = SourceStats()
        repeat(3) { s = s.record(false, "DNS") }
        assertTrue(s.unreliable)
        s = s.record(true)
        assertFalse(s.unreliable)
        assertEquals(1, s.ok); assertEquals(3, s.fail)
    }

    // ------------------------------------------------------------ fonte offline (item 13)

    @Test fun pendingSourceRoundTripAndGuess() {
        val u = UserSource("feed_x", "X", "https://fonte.org/catalogo.json", SourceType.CATALOG_JSON, enabled = false, pending = true,
            lastTest = SourceTest.failure(ErrorClassifier.classify(UnknownHostException("fonte.org"))), stats = SourceStats(1, 2, 2, 5, "x"))
        val back = UserSource.fromJson(u.toJson())!!
        assertTrue(back.pending); assertFalse(back.enabled)
        assertEquals(ErrorKind.DNS, back.lastTest?.errorKind)
        assertTrue(back.lastTest!!.detail!!.contains("UnknownHostException"))
        assertEquals(2, back.stats.fail)
        assertEquals(SourceType.CATALOG_JSON, SourceDetector.guessByAddress("https://fonte.org/catalogo.json"))
        assertEquals(SourceType.TORRENT, SourceDetector.guessByAddress("https://fonte.org/x.torrent?a=1"))
        assertEquals(SourceType.HLS, SourceDetector.guessByAddress("https://fonte.org/live.m3u8"))
        assertEquals(SourceType.SEARCH, SourceDetector.guessByAddress("https://fonte.org/b?q={query}"))
        assertNull(SourceDetector.guessByAddress("https://1337x.to/"))
        // HTML nunca é catálogo
        assertNull(SourceDetector.classify("https://site.org/", "text/html", "<!DOCTYPE html><html>".toByteArray()).type)
    }

    @Test fun torrentRefJsonKeepsFileIndex() {
        val r = TorrentRef(null, "magnet:?xt=urn:btih:08ada5a7a6183aae1e09d831df6748d566095a10", "a/b.mp4", 3, "/data/x.torrent")
        assertEquals(r, TorrentRef.fromJson(r.toJson()))
        val p = Playable("k", "l", 1, 1, null, r, emptyList(), null, true, altTorrents = listOf(r.copy(fileIndex = 1)), origins = mapOf("a" to "b"))
        assertEquals(p, Playable.fromJson(p.toJson()))
    }
}
