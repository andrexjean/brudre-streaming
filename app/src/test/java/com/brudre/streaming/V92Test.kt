package com.brudre.streaming

import com.brudre.streaming.data.Playable
import com.brudre.streaming.data.SourceOption
import com.brudre.streaming.data.SourcePreferences
import com.brudre.streaming.sources.AuthMethod
import com.brudre.streaming.sources.ClassifiedException
import com.brudre.streaming.sources.ErrorKind
import com.brudre.streaming.sources.FeedSource
import com.brudre.streaming.sources.IndexerRegistry
import com.brudre.streaming.sources.LocalServers
import com.brudre.streaming.sources.RestAdapter
import com.brudre.streaming.sources.RestDescriptor
import com.brudre.streaming.sources.SearchTemplate
import com.brudre.streaming.sources.SourceDetector
import com.brudre.streaming.sources.SourceRanker
import com.brudre.streaming.sources.SourceType
import com.brudre.streaming.sources.Torznab
import com.brudre.streaming.sources.TorznabAdapter
import com.brudre.streaming.sources.TorznabCaps
import com.brudre.streaming.sources.UrlGuard
import com.brudre.streaming.sources.UserSource
import com.brudre.streaming.sources.UserSourcesJson
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * v9.2 — Source Engine 4.0. Respostas Torznab em test/resources/torznab foram geradas pelo servidor de referência
 * do projeto (ci/torznab_server.py), o mesmo usado nos testes de integração do emulador.
 */
class V92Test {
    private fun res(name: String) = javaClass.classLoader!!.getResource("torznab/$name")!!.readText()

    @After fun clean() = LocalServers.sync(emptyMap())

    // ------------------------------------------------------------------ Torznab / Newznab

    @Test fun torznabCapabilities() {
        val caps = Torznab.parseCaps(res("caps.xml"), "https://indexador.exemplo.org/api")
        assertEquals(3, caps.limitMax)
        assertTrue("season" in caps.tv!!)
        assertTrue("tmdbid" in caps.movie!!)
        assertTrue("categoria adulta nunca aparece", caps.categories.none { it.first / 1000 == 6 })
        val back = TorznabCaps.fromConfig(caps.toConfig())
        assertEquals(caps, back)
    }

    @Test fun torznabProtocolErrorsAreClassified() {
        try {
            Torznab.parseCaps(res("error100.xml"), "x"); fail()
        } catch (e: ClassifiedException) {
            assertEquals(ErrorKind.HTTP, e.kind)
            assertTrue(e.message!!.contains("Credencial"))
        }
        try {
            Torznab.parseResults("<error code=\"201\" description=\"Incorrect parameter apikey=SEGREDO\"/>", "s"); fail()
        } catch (e: ClassifiedException) {
            assertEquals(ErrorKind.FORMAT, e.kind)
            assertFalse("a chave nunca aparece na mensagem", e.message!!.contains("SEGREDO"))
        }
    }

    @Test fun torznabPaginationFiltersAndEpisodes() {
        val caps = Torznab.parseCaps(res("caps.xml"), "https://indexador.exemplo.org/api")
        val a = TorznabAdapter(caps, "k")
        val pages = (1..3).map { a.parse(res("search-p$it.xml"), "search_tz") }
        assertEquals(listOf(true, true, false), pages.map { it.hasMore })
        val items = pages.flatMap { it.items }
        // aceitos: 3 episódios com .torrent + 1 só com infohash (vira magnet); descartados: NZB, adulto, sem semeadores, software
        assertEquals(4, items.size)
        assertTrue(items.all { it.title.name.startsWith("BruDre Teste Serie") })
        val e2 = items.first { it.episodes.single().number == 2 }.episodes.single()
        assertEquals(1, e2.season)
        assertEquals("S01E02", e2.sources.single().file)
        assertEquals(FeedSource.Type.TORRENT, e2.sources.single().type)
        val hash = items.flatMap { it.episodes }.flatMap { it.sources }.first { it.type == FeedSource.Type.MAGNET }
        assertTrue(hash.url.startsWith("magnet:?xt=urn:btih:bf16e86e7636315da5c443296bee3dd58c051039"))
        // páginas com offset/limit do caps
        assertTrue(a.url(SearchTemplate.Query("x"), 2).contains("offset=3&limit=3"))
    }

    @Test fun torznabMovieByIdAndUrls() {
        val caps = Torznab.parseCaps(res("caps.xml"), "https://indexador.exemplo.org/api")
        val movie = Torznab.parseResults(res("movie.xml"), "search_tz").items.single()
        assertEquals("movie:10331", movie.tmdbId)
        assertEquals("1968", movie.title.year)
        val a = TorznabAdapter(caps, "chave")
        val m = a.url(SearchTemplate.Query("Night of the Living Dead", "movie:10331", "1968", "movie"), 1)
        assertTrue(m.startsWith("https://indexador.exemplo.org/api?t=movie&"))
        assertTrue(m.contains("tmdbid=10331") && m.contains("cat=2000") && m.endsWith("apikey=chave"))
        val tv = a.url(SearchTemplate.Query("Serie", "tv:5", null, "tv", season = 2), 1)
        assertTrue(tv.contains("t=tvsearch") && tv.contains("season=2") && tv.contains("cat=5000"))
        // sem tv-search no indexador: cai para pesquisa de texto
        val plain = TorznabAdapter(caps.copy(tv = null, movie = null), null).url(SearchTemplate.Query("Serie", type = "tv"), 1)
        assertTrue(plain.contains("t=search") && !plain.contains("apikey"))
    }

    @Test fun torznabReleaseNames() {
        assertEquals(Torznab.Release("Night of the Living Dead", "1968", null, null, "1080p"), Torznab.release("Night.of.the.Living.Dead.1968.1080p.BluRay.x264"))
        assertEquals("2001 A Space Odyssey", Torznab.release("2001.A.Space.Odyssey.1968.720p").name)
        assertEquals("2017", Torznab.release("Blade.Runner.2049.2017.1080p").year)
        Torznab.release("Mr.Robot.S02E03.720p.HDTV").let { assertEquals("Mr Robot", it.name); assertEquals(2, it.season); assertEquals(3, it.episode) }
        assertEquals(1 to 5, Torznab.release("Dark.1x05.WEB").let { it.season to it.episode })
        assertEquals(3, Torznab.release("The Office US Season 3 Complete").season)
    }

    @Test fun torznabKeyLeavesTheAddress() {
        val (base, key) = Torznab.splitKey("https://h.org:9117/api/v2.0/indexers/all/results/torznab/api?apikey=abc123&t=caps&x=1")
        assertEquals("https://h.org:9117/api/v2.0/indexers/all/results/torznab/api?x=1", base)
        assertEquals("abc123", key)
        assertEquals(listOf("https://h.org/torznab/", "https://h.org/torznab/api"), Torznab.candidates("https://h.org/torznab/"))
        assertFalse("endereço com apikey não é salvo", UserSourcesJson.isSafe(SourceType.TORZNAB, "https://h.org/api?apikey=x"))
        assertEquals(SourceType.TORZNAB, SourceDetector.classify("https://h.org/api?t=caps", "application/xml", res("caps.xml").toByteArray()).type)
    }

    // ------------------------------------------------------------------ Servidores locais

    @Test fun localServersNeedPinnedHttps() {
        assertNotNull("rede privada bloqueada sem cadastro", UrlGuard.check("https://192.168.1.10:9696/1/api"))
        LocalServers.register("https://192.168.1.10:9696/1/api", "ab".repeat(32))
        assertNull(UrlGuard.check("https://192.168.1.10:9696/1/api?t=caps"))
        assertNotNull("outra porta continua bloqueada", UrlGuard.check("https://192.168.1.10:22/"))
        assertNotNull("loopback nunca", UrlGuard.check("https://127.0.0.1:9696/"))
        assertNull(UrlGuard.check("https://example.org/"))
        LocalServers.sync(emptyMap())
        assertNotNull(UrlGuard.check("https://192.168.1.10:9696/1/api"))
        assertEquals("AB:CD", LocalServers.pretty("abcd"))
    }

    @Test fun importNeverBringsCertificatePins() {
        val local = UserSource("x", "Jackett", "https://192.168.0.5:9117/api", SourceType.TORZNAB, localPin = "ab".repeat(32))
        val json = UserSourcesJson.export(listOf(local))
        assertFalse(json.contains("localPin\": \"ab"))
        val back = UserSourcesJson.import(JSONObject(json).apply {
            getJSONArray("sources").getJSONObject(0).put("localPin", "cd".repeat(32))
        }.toString()).single()
        assertNull(back.localPin)
        assertFalse("entra desativada até confirmar o certificado", back.enabled)
        assertTrue(back.pending)
    }

    // ------------------------------------------------------------------ Biblioteca e descritores

    @Test fun libraryHasThirtyPlusEntriesAndNoThirdPartyEndpoints() {
        val entries = IndexerRegistry.parse(File("src/main/assets/source_library.json").readText())
        assertTrue(entries.size >= 30)
        assertEquals(entries.size, entries.map { it.id }.distinct().size)
        val notAuthorized = entries.filter { it.integration == IndexerRegistry.Integration.NAO_INTEGRADO }
        assertTrue(notAuthorized.isNotEmpty())
        assertTrue("indexador não autorizado nunca tem botão de adicionar", notAuthorized.all { it.addUrl == null })
        assertTrue(entries.filter { it.addUrl != null }.all { it.addUrl!!.startsWith("https://") })
        assertTrue(entries.any { it.protocol.contains("Torznab", true) && it.integration == IndexerRegistry.Integration.INTEGRADO })
    }

    @Test fun graphQlBodyIsEscapedAndKeyGoesToHeader() {
        val d = RestDescriptor.parse(File("../samples/library/graphql-exemplo.json").readText())
        assertEquals("POST", d.method)
        assertTrue(d.needsKey)
        val a = RestAdapter(d, "tok")
        val body = a.body(SearchTemplate.Query("a\"b", page = 2), 2)!!
        val parsed = JSONObject(body)
        assertEquals("a\"b", parsed.getJSONObject("variables").getString("q"))
        assertEquals("Bearer tok", a.headers["Authorization"])
    }

    @Test fun authMethodsAndCategories() {
        assertEquals("X-Api-Key" to "k", AuthMethod.HEADER_KEY.header("k"))
        assertEquals("Authorization" to "Bearer k", AuthMethod.BEARER.header("k"))
        assertNull(AuthMethod.URL_KEY.header("k"))
        assertNull(AuthMethod.BEARER.header(""))
        val s = UserSource("x", "x", "https://a.org/f.json", SourceType.CATALOG_JSON, categories = setOf("series"))
        assertTrue(s.serves("series"))
        assertFalse(s.serves("filmes"))
        assertTrue(s.serves(null))
    }

    @Test fun durationToleranceForFallback() {
        val pa = com.brudre.streaming.player.PlayerActivity.Companion
        assertTrue(pa.durationsMatch(30_000, 20_000)) // curtos: até 60 s
        assertTrue(pa.durationsMatch(6_000_000, 6_250_000)) // 100 min: até 5 min (5%)
        assertFalse(pa.durationsMatch(6_000_000, 7_000_000))
        assertFalse(pa.durationsMatch(30_000, 120_000))
    }

    @Test fun rankingIsExplained() {
        val p = Playable("k", "f", 1, 1, "https://a.org/a.mp4", null, emptyList(), null, false)
        val opt = SourceOption("b", "B", listOf(p), "HTTP", "1080p", audio = listOf("pt"))
        val r = SourceRanker.order(
            null,
            listOf(SourceRanker.Candidate(opt, 0, false, latencyMs = 420, history = "✓ funcionou 2x")),
            false, SourcePreferences(audio = "pt", subtitle = "off", resolution = 1080, preferredSourceId = "b"),
        ).single().reasons
        assertTrue(r.any { it.contains("preferida") })
        assertTrue(r.any { it.contains("áudio português") })
        assertTrue(r.any { it.contains("1080p") })
        assertTrue(r.any { it.contains("420 ms") })
        assertTrue(r.any { it.contains("histórico") })
    }
}
