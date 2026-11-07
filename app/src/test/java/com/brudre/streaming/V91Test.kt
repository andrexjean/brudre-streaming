package com.brudre.streaming

import com.brudre.streaming.data.Kind
import com.brudre.streaming.data.Playable
import com.brudre.streaming.data.SourceOption
import com.brudre.streaming.data.SourcePreferences
import com.brudre.streaming.data.Title
import com.brudre.streaming.data.VerifiedLanguages
import com.brudre.streaming.sources.BruDreTemplateAdapter
import com.brudre.streaming.sources.ClassifiedException
import com.brudre.streaming.sources.ErrorClassifier
import com.brudre.streaming.sources.ErrorKind
import com.brudre.streaming.sources.FeedItem
import com.brudre.streaming.sources.FeedParser
import com.brudre.streaming.sources.FeedSource
import com.brudre.streaming.sources.OpenSearch
import com.brudre.streaming.sources.OpenSearchAdapter
import com.brudre.streaming.sources.RestAdapter
import com.brudre.streaming.sources.RestDescriptor
import com.brudre.streaming.sources.SearchTemplate
import com.brudre.streaming.sources.SourceDetector
import com.brudre.streaming.sources.SourceRanker
import com.brudre.streaming.sources.SourceType
import com.brudre.streaming.sources.TitleMatcher
import com.brudre.streaming.sources.UserSource
import com.brudre.streaming.sources.UserSourcesJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/** v9.1: adaptadores (Atom, OpenSearch, REST), paginação, associação filme/série, preferências e duplicadas. */
class V91Test {
    private fun sample(path: String) = listOf(File("../samples/$path"), File("samples/$path")).first { it.exists() }.readText()

    // ------------------------------------------------------------ adaptadores (item 6)

    @Test fun atomFeedWithEnclosureAndNextPage() {
        val atom = sample("opensearch/resultados.atom")
        assertEquals(SourceType.RSS, SourceDetector.classify("https://x.org/a.atom", "application/atom+xml", atom.toByteArray()).type)
        val feed = FeedParser.parse(atom, "a")
        assertEquals(1, feed.items.size)
        assertEquals("2008", feed.items[0].title.year)
        assertEquals(FeedSource.Type.HTTP, feed.items[0].sources.single().type)
        val paged = FeedParser.parse("""<feed xmlns="http://www.w3.org/2005/Atom"><title>t</title><link rel="next" href="https://x.org/p2.atom"/>
            <entry><id>1</id><title>X (2001)</title><link rel="enclosure" href="https://x.org/x.mp4"/></entry></feed>""", "b")
        assertEquals("https://x.org/p2.atom", paged.next)
    }

    @Test fun openSearchDescription() {
        val xml = sample("opensearch/descricao.xml")
        assertEquals(SourceType.OPENSEARCH, SourceDetector.classify("https://x.org/os.xml", "application/opensearchdescription+xml", xml.toByteArray()).type)
        val os = OpenSearch.parseDescription(xml)
        assertEquals("application/atom+xml", os.responseType)
        assertTrue(os.template.contains("{query}") && os.template.contains("{page}"))
        val adapter = OpenSearchAdapter(os)
        assertTrue(adapter.capabilities.pagination && adapter.capabilities.textSearch)
        assertTrue(adapter.url(SearchTemplate.Query("big buck bunny"), 2)!!.endsWith("q=big%20buck%20bunny&p=2"))
        val page = adapter.parse(sample("opensearch/resultados.atom"), "search_os")
        assertEquals(1, page.items.size)
        assertFalse("totalResults=1: sem mais páginas", page.hasMore)
        // só HTML: recusado (não é indexador)
        try { OpenSearch.parseDescription("""<OpenSearchDescription><Url type="text/html" template="https://x.org/?q={searchTerms}"/></OpenSearchDescription>"""); fail() } catch (e: ClassifiedException) { assertEquals(ErrorKind.FORMAT, e.kind) }
        assertEquals("https://x.org/s?q={query}&start={offset}&n={limit}&l=pt-BR&x=", OpenSearch.convert("https://x.org/s?q={searchTerms}&start={startIndex?}&n={count?}&l={language}&x={geo:box?}"))
    }

    @Test fun restDescriptorMapsMoviesAndEpisodes() {
        val text = sample("rest/descritor.json")
        assertEquals(SourceType.REST, SourceDetector.classify("https://x.org/d.json", "application/json", text.toByteArray()).type)
        val d = RestDescriptor.parse(text)
        val caps = d.capabilities
        assertTrue(caps.textSearch && caps.idLookup && caps.episodes && caps.pagination)
        assertEquals(30, caps.ratePerMinute)
        val a = RestAdapter(d)
        assertTrue("busca por TMDB usa o lookup", a.url(SearchTemplate.Query("x", tmdbId = "movie:10378"), 1)!!.contains("lookup.json?tmdb=movie%3A10378"))
        assertTrue(a.url(SearchTemplate.Query("x"), 2)!!.contains("page=2"))
        val page = a.parse(sample("rest/busca.json"), "search_rest")
        assertEquals("item sem vídeo descartado", 2, page.items.size)
        assertFalse(page.hasMore)
        val movie = page.items.first { it.tmdbId == "movie:10378" }
        assertEquals("en", movie.title.language)
        val ep = page.items.first { it.episodes.isNotEmpty() }
        assertEquals("BruDre Teste Série", ep.title.name)
        assertEquals(Kind.SERIES, ep.title.kind)
        assertEquals(1 to 2, ep.episodes.single().season to ep.episodes.single().number)
        // lookup devolve um item só (objeto)
        assertEquals(1, a.parse(sample("rest/lookup.json"), "search_rest").items.size)
    }

    @Test fun restDescriptorSecurity() {
        fun bad(json: String, kind: ErrorKind) { try { RestDescriptor.parse(json); fail(json) } catch (e: ClassifiedException) { assertEquals(json, kind, e.kind) } }
        val ok = """"search": {"url": "https://x.org/?q={query}"}, "fields": {"title": "t"}, "media": [{"type": "http", "url": "{u}"}]"""
        bad("""{"format": "brudre-adapter", "search": {"url": "http://x.org/?q={query}"}, "fields": {"title": "t"}, "media": [{"type": "http", "url": "{u}"}]}""", ErrorKind.BLOCKED)
        bad("""{"format": "brudre-adapter", $ok, "headers": {"Cookie": "a"}}""", ErrorKind.BLOCKED)
        bad("""{"format": "outro", $ok}""", ErrorKind.FORMAT)
        bad("""{"format": "brudre-adapter", "search": {"url": "https://x.org/?q={query}"}, "fields": {"title": "t"}}""", ErrorKind.CATALOG)
        assertTrue(RestDescriptor.parse("""{"format": "brudre-adapter", $ok, "headers": {"X-Api-Key": "k"}}""").capabilities.auth)
        // URL de mídia insegura no item: descartada pelo parser comum
        val d = RestDescriptor.parse("""{"format": "brudre-adapter", $ok}""")
        assertEquals(0, RestAdapter(d).parse("""{"items": [{"t": "A", "u": "http://x.org/a.mp4"}, {"t": "B", "u": "https://127.0.0.1/b.mp4"}]}""", "s").items.size)
    }

    // ------------------------------------------------------------ paginação (item 7)

    @Test fun paginationMarkersAndFlags() {
        val q = SearchTemplate.Query("o auto", page = 3)
        assertEquals("https://x.org/?q=o%20auto&o=100&n=50&p=3", SearchTemplate.expand("https://x.org/?q={query}&o={offset}&n={limit}&p={page}", q))
        val a = BruDreTemplateAdapter("https://x.org/s/{slug}-p{page}.json")
        assertTrue(a.capabilities.pagination)
        assertTrue(a.parse(sample("search/paged/big-buck-bunny-p1.json"), "s").hasMore)
        assertFalse(a.parse(sample("search/paged/big-buck-bunny-p2.json"), "s").hasMore)
        val noPages = BruDreTemplateAdapter("https://x.org/s/{slug}.json")
        assertNull("fonte sem paginação não pede página 2", noPages.url(q, 2))
        val p1 = FeedParser.parse(sample("feeds/paginado-1.json"), "pg")
        assertTrue(p1.next!!.endsWith("paginado-2.json"))
        assertNull(FeedParser.parse(sample("feeds/paginado-2.json"), "pg").next)
    }

    // ------------------------------------------------------------ associação (itens 9 e 10)

    private fun item(name: String, year: String?, tmdb: String? = null, episodes: Boolean = false) = FeedItem(
        Title("feed:x:" + name.hashCode(), if (episodes) Kind.SERIES else Kind.MOVIE, name, year),
        if (episodes) emptyList() else listOf(FeedSource(FeedSource.Type.HTTP, "https://x.org/a.mp4")),
        if (episodes) listOf(com.brudre.streaming.sources.FeedEpisode(1, 1, "e1", listOf(FeedSource(FeedSource.Type.HTTP, "https://x.org/e.mp4")))) else emptyList(),
        tmdbId = tmdb,
    )

    @Test fun movieAssociationAvoidsLookalikes() {
        val alien = TitleMatcher.names("Alien")
        assertTrue(TitleMatcher.matchesItem(item("Alien", "1979"), alien, "1979", null, false))
        assertFalse("Aliens ≠ Alien", TitleMatcher.matchesItem(item("Aliens", "1986"), alien, "1979", null, false))
        assertFalse("Duna 1984 ≠ Duna 2021", TitleMatcher.matchesItem(item("Dune", "1984"), TitleMatcher.names("Dune"), "2021", null, false))
        assertFalse("TMDB diferente decide", TitleMatcher.matchesItem(item("Dune", "2021", "movie:1"), TitleMatcher.names("Dune"), "2021", "movie:438631", false))
        assertTrue("TMDB igual decide", TitleMatcher.matchesItem(item("Duna (dublado)", null, "movie:438631"), TitleMatcher.names("Dune"), "2021", "movie:438631", false))
        assertFalse("filme não casa com série homônima", TitleMatcher.matchesItem(item("Fargo", "1996", episodes = true), TitleMatcher.names("Fargo"), "1996", null, false))
        // sem ano na fonte: casa pelo nome, mas sem confiança (só escolha manual)
        val noYear = item("Alien", null)
        assertTrue(TitleMatcher.matchesItem(noYear, alien, "1979", null, false))
        assertFalse(TitleMatcher.confident(noYear, "1979", null))
        assertTrue(TitleMatcher.confident(item("Alien", "1979"), "1979", null))
    }

    @Test fun seriesAndEpisodesAssociation() {
        val names = TitleMatcher.names("BruDre Teste Série")
        assertTrue(TitleMatcher.matchesItem(item("BruDre Teste Série", "2026", episodes = true), names, "2026", null, true))
        assertFalse("série não casa com filme homônimo", TitleMatcher.matchesItem(item("BruDre Teste Série", "2026"), names, "2026", null, true))
        fun ep(src: String, s: Int, n: Int) = Playable("$src$s$n", "e", s, n, "https://$src.org/$s$n.mp4", null, emptyList(), null, false)
        val a = SourceOption("a", "a", listOf(ep("a", 1, 1), ep("a", 1, 2), ep("a", 2, 1)), "HTTP")
        val b = SourceOption("b", "b", listOf(ep("b", 2, 1), ep("b", 1, 2)), "HTTP")
        val m = SourceRanker.merge(listOf(a, b))
        assertEquals(emptyList<String>(), m[0].mirrors) // T1E1 só existe em A
        assertEquals(listOf("https://b.org/12.mp4"), m[1].mirrors)
        assertEquals(listOf("https://b.org/21.mp4"), m[2].mirrors)
    }

    @Test fun unconfirmedSourcesNeverJoinAutomatically() {
        val ok = SourceOption("a", "A", listOf(Playable("k", "f", 1, 1, "https://a.org/a.mp4", null, emptyList(), null, false)), "HTTP")
        val unsure = ok.copy(sourceId = "b", playables = listOf(ok.playables[0].copy(directUrl = "https://b.org/b.mp4")), confident = false)
        assertEquals(emptyList<String>(), SourceRanker.merge(listOf(ok, unsure)).single().mirrors)
        assertTrue("só fonte sem confirmação: nada automático", SourceRanker.merge(listOf(unsure)).isEmpty())
        assertEquals("https://b.org/b.mp4", SourceRanker.merge(listOf(unsure), manual = true).single().directUrl)
        val order = SourceRanker.order(null, listOf(SourceRanker.Candidate(unsure, 0, false), SourceRanker.Candidate(ok, 5, false)), false)
        assertEquals(listOf("a", "b"), order.map { it.sourceId })
    }

    // ------------------------------------------------------------ preferências

    @Test fun preferencesRankKnownOverUnknownNeverInvent() {
        fun o(id: String, q: String?, audio: List<String>) = SourceRanker.Candidate(SourceOption(id, id, emptyList(), "HTTP", q, audio = audio), 0, false)
        val prefs = SourcePreferences(audio = "pt", subtitle = "off", resolution = 1080)
        val r = SourceRanker.order(null, listOf(o("en720", "720p", listOf("en")), o("unk", null, emptyList()), o("pt1080", "1080p", listOf("pt")), o("pt720", "720p", listOf("pt"))), false, prefs)
        assertEquals(listOf("pt1080", "pt720", "unk", "en720"), r.map { it.sourceId })
        val pref = SourceRanker.order(null, listOf(o("a", null, emptyList()), o("b", null, emptyList())), false, prefs.copy(preferredSourceId = "b"))
        assertEquals("b", pref.first().sourceId)
        assertEquals(1, SourceRanker.languageScore(emptyList(), "pt"))
        assertEquals(0, SourceRanker.languageScore(listOf("pt-br"), "pt"))
        assertEquals(1, SourceRanker.resolutionScore(null, 1080))
        assertEquals(0, SourceRanker.resolutionScore("4K", 2160))
        assertEquals("áudio: desconhecido • legenda: desconhecida", SourceOption("x", "x", emptyList(), "HTTP").languageInfo)
    }

    @Test fun languageCodesFromTracks() {
        assertEquals("pt", VerifiedLanguages.normalize("por"))
        assertEquals("pt", VerifiedLanguages.normalize("pt-BR"))
        assertEquals("en", VerifiedLanguages.normalize("eng"))
        assertNull("und = desconhecido", VerifiedLanguages.normalize("und"))
        assertNull(VerifiedLanguages.normalize(null))
    }

    // ------------------------------------------------------------ duplicadas (item 16)

    @Test fun duplicateSourcesAreDetected() {
        val a = UserSource.normalizedUrl("https://Exemplo.org:443/catalogo.json/?b=2&a=1#x")
        val b = UserSource.normalizedUrl("https://exemplo.org/catalogo.json?a=1&b=2")
        assertEquals(a, b)
        assertEquals(UserSource.normalizedUrl("magnet:?xt=urn:btih:08ADA5A7A6183AAE1E09D831DF6748D566095A10&dn=a"),
            UserSource.normalizedUrl("magnet:?dn=b&xt=urn:btih:08ada5a7a6183aae1e09d831df6748d566095a10"))
        val text = """{"format": "brudre-fontes", "version": 1, "sources": [
            {"name": "A", "url": "https://exemplo.org/c.json", "type": "CATALOG_JSON"},
            {"name": "B", "url": "https://EXEMPLO.org/c.json/", "type": "CATALOG_JSON"}]}"""
        assertEquals("importação ignora a duplicada", 1, UserSourcesJson.import(text).size)
    }

    @Test fun capabilitiesAndConfigRoundTrip() {
        val u = UserSource("search_x", "OS", "https://x.org/os.xml", SourceType.OPENSEARCH, config = OpenSearch("n", "https://x.org/?q={query}&p={page}", "application/atom+xml").toConfig(), validatedAt = 5)
        val back = UserSource.fromJson(u.toJson())!!
        assertEquals(u.config, back.config)
        assertEquals(5L, back.validatedAt)
        assertTrue(back.capabilities.pagination)
        assertTrue(back.capabilities.summary.contains("paginação ✓"))
    }

    @Test fun cleartextRedirectIsBlocked() {
        val e = java.net.UnknownServiceException("CLEARTEXT communication to example.com not permitted by network security policy")
        assertEquals(ErrorKind.BLOCKED, ErrorClassifier.classify(e).kind)
    }
}
