package com.brudre.streaming

import com.brudre.streaming.data.Kind
import com.brudre.streaming.data.TmdbParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TmdbTest {

    private val trending = """
        {"page":1,"results":[
          {"id":693134,"media_type":"movie","title":"Duna: Parte Dois","overview":"Paul Atreides se une aos Fremen.","poster_path":"/p.jpg","backdrop_path":"/b.jpg","release_date":"2024-02-27","genre_ids":[878,12],"adult":false},
          {"id":1396,"media_type":"tv","name":"Breaking Bad","overview":"","poster_path":"/bb.jpg","backdrop_path":null,"first_air_date":"2008-01-20","genre_ids":[18,80]},
          {"id":10,"media_type":"person","name":"Fulano"},
          {"id":11,"media_type":"movie","title":"Sem imagem","poster_path":null,"backdrop_path":null},
          {"id":12,"media_type":"tv","name":"Bluey","poster_path":"/bl.jpg","first_air_date":"2018-10-01","genre_ids":[16,10762]}
        ],"total_pages":1}
    """.trimIndent()

    @Test fun parsesTrendingMixedMedia() {
        val list = TmdbParser.parseList(trending, null)
        assertEquals(listOf("tmdb:movie:693134", "tmdb:tv:1396", "tmdb:tv:12"), list.map { it.id })
        val dune = list[0]
        assertEquals("Duna: Parte Dois", dune.name)
        assertEquals("2024", dune.year)
        assertEquals(Kind.MOVIE, dune.kind)
        assertEquals(listOf("Ficção científica", "Aventura"), dune.genres)
        assertEquals("https://image.tmdb.org/t/p/w780/b.jpg", dune.bannerUrl)
        assertEquals("https://image.tmdb.org/t/p/w342/bb.jpg", list[1].posterUrl)
        assertEquals(Kind.SERIES, list[1].kind)
        assertNull(list[1].synopsis)
        assertEquals(Kind.KIDS, list[2].kind)
    }

    private val details = """
        {"id":693134,"title":"Duna: Parte Dois","overview":"Sinopse.","release_date":"2024-02-27","runtime":166,
         "genres":[{"id":878,"name":"Ficção científica"},{"id":12,"name":"Aventura"}],"backdrop_path":"/b.jpg","poster_path":"/p.jpg",
         "release_dates":{"results":[{"iso_3166_1":"US","release_dates":[{"certification":"PG-13"}]},{"iso_3166_1":"BR","release_dates":[{"certification":""},{"certification":"14"}]}]},
         "original_title":"Dune: Part Two","original_language":"en",
         "translations":{"translations":[{"iso_3166_1":"GB","iso_639_1":"en","data":{"title":"Dune Two (UK)"}},{"iso_3166_1":"US","iso_639_1":"en","data":{"title":"Dune: Part Two"}},{"iso_3166_1":"BR","iso_639_1":"pt","data":{"title":"Duna: Parte Dois"}}]}}
    """.trimIndent()

    @Test fun parsesDetailsRatingAndMatchingTitles() {
        val d = TmdbParser.parseDetails(details, "movie", null)
        val t = d.title
        assertEquals(166, t.durationMin)
        assertEquals("14", t.rating)
        assertEquals(listOf("Ficção científica", "Aventura"), t.genres)
        assertEquals("Dune: Part Two", d.originalTitle)
        assertEquals("Dune: Part Two", d.englishTitle) // prefere a tradução dos EUA
        assertEquals("en", d.originalLanguage)
        assertEquals("Metadados: TMDB", t.license)
    }

    @Test fun tvRatingAndSeasons() {
        val json = """{"id":1,"name":"Série","overview":"X","first_air_date":"2020-01-01","episode_run_time":[42],"number_of_seasons":3,
            "genres":[{"id":18,"name":"Drama"}],"content_ratings":{"results":[{"iso_3166_1":"BR","rating":"A16"}]}}"""
        val d = TmdbParser.parseDetails(json, "tv", null)
        val t = d.title
        assertEquals("16", t.rating)
        assertEquals(42, t.durationMin)
        assertTrue(t.synopsis!!.contains("3 temporadas"))
        assertNull(d.englishTitle)
    }

    @Test fun onlyFreeProvidersAreKept() {
        val wp = org.json.JSONObject("""{"results":{"BR":{
          "flatrate":[{"provider_id":8,"provider_name":"Netflix","logo_path":"/n.jpg","display_priority":1}],
          "ads":[{"provider_id":1796,"provider_name":"Netflix basic with Ads","logo_path":"/na.jpg","display_priority":2},
                 {"provider_id":538,"provider_name":"Plex","logo_path":"/p.jpg","display_priority":40}],
          "free":[{"provider_id":300,"provider_name":"Pluto TV","logo_path":"/pl.jpg","display_priority":30},
                  {"provider_id":301,"provider_name":"Pluto TV","logo_path":"/pl.jpg","display_priority":31}],
          "rent":[{"provider_id":2,"provider_name":"Apple TV","logo_path":"/a.jpg","display_priority":5}],
          "buy":[{"provider_id":3,"provider_name":"Google Play Movies","logo_path":"/g.jpg","display_priority":6}]}}}""")
        val free = TmdbParser.parseFreeProviders(wp)
        assertEquals(listOf("Pluto TV", "Plex"), free.map { it.name })
        assertEquals(listOf(false, true), free.map { it.withAds })
        val details = TmdbParser.parseDetails("""{"id":1,"title":"Filme","release_date":"2019-01-01","watch/providers":${wp}}""", "movie", null)
        assertEquals(2, details.freeProviders.size)
        val catalog = TmdbParser.parseFreeCatalog("""{"results":[{"provider_id":300,"provider_name":"Pluto TV"},{"provider_id":8,"provider_name":"Netflix"},{"provider_id":538,"provider_name":"Plex"},{"provider_id":999,"provider_name":"Serviço Qualquer"}]}""")
        assertEquals(listOf(300, 538), catalog)
    }

    @Test fun ids() {
        assertEquals("movie" to 5, TmdbParser.parseId("tmdb:movie:5"))
        assertEquals("tv" to 7, TmdbParser.parseId("tmdb:tv:7"))
        assertNull(TmdbParser.parseId("ia:x"))
        assertNull(TmdbParser.parseId("tmdb:person:1"))
    }

    @Test(expected = IllegalStateException::class)
    fun invalidKeyIsReported() {
        TmdbParser.parseList("""{"status_code":7,"status_message":"Invalid API key","success":false}""", "movie")
    }
}

class ParentalTest {
    @org.junit.Test fun explicitContentAlwaysBlocked() {
        val json = """{"results":[
          {"id":1,"media_type":"movie","title":"Filme Normal","overview":"Uma família viaja.","poster_path":"/a.jpg"},
          {"id":2,"media_type":"movie","title":"Qualquer","overview":"Um filme pornô de 1999.","poster_path":"/b.jpg"},
          {"id":3,"media_type":"movie","title":"Erotic Tales","overview":"x","poster_path":"/c.jpg"},
          {"id":4,"media_type":"movie","title":"Outro","adult":true,"poster_path":"/d.jpg"}]}"""
        org.junit.Assert.assertEquals(listOf("tmdb:movie:1"), com.brudre.streaming.data.TmdbParser.parseList(json, null).map { it.id })
    }

    @org.junit.Test(expected = com.brudre.streaming.data.BlockedContentException::class)
    fun explicitDetailsAreRefused() {
        com.brudre.streaming.data.TmdbParser.parseDetails("""{"id":9,"title":"X","adult":true}""", "movie", null)
    }

    @org.junit.Test fun ratingGateAndPin() {
        val P = com.brudre.streaming.data.Parental
        org.junit.Assert.assertTrue(P.needsPin("18", "16"))
        org.junit.Assert.assertFalse(P.needsPin("16", "16"))
        org.junit.Assert.assertFalse(P.needsPin("L", "L"))
        org.junit.Assert.assertTrue(P.needsPin("12", "L"))
        org.junit.Assert.assertFalse(P.needsPin(null, "L"))
        val h = P.hash("1234")
        org.junit.Assert.assertTrue(P.check("1234", h))
        org.junit.Assert.assertFalse(P.check("4321", h))
        org.junit.Assert.assertFalse(P.check("1234", ""))
    }
}
