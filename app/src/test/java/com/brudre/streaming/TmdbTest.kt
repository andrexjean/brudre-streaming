package com.brudre.streaming

import com.brudre.streaming.data.Kind
import com.brudre.streaming.data.Offer
import com.brudre.streaming.data.StreamingApps
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
         "watch/providers":{"results":{"BR":{"link":"https://www.themoviedb.org/movie/693134/watch?locale=BR",
           "flatrate":[{"provider_id":1899,"provider_name":"HBO Max","logo_path":"/max.jpg","display_priority":3},
                       {"provider_id":1825,"provider_name":"HBO Max Amazon Channel","logo_path":"/mc.jpg","display_priority":40}],
           "rent":[{"provider_id":2,"provider_name":"Apple TV","logo_path":"/a.jpg","display_priority":5},{"provider_id":10,"provider_name":"Amazon Video","logo_path":"/av.jpg","display_priority":7}],
           "buy":[{"provider_id":2,"provider_name":"Apple TV","logo_path":"/a.jpg","display_priority":5}]}}}}
    """.trimIndent()

    @Test fun parsesDetailsProvidersAndRating() {
        val (t, providers) = TmdbParser.parseDetails(details, "movie", null)
        assertEquals(166, t.durationMin)
        assertEquals("14", t.rating)
        assertEquals(listOf("Ficção científica", "Aventura"), t.genres)
        // HBO Max por assinatura; canal Amazon e Amazon Video viram "amazon" (aparece uma vez, na melhor oferta)
        assertEquals(listOf("HBO Max" to Offer.SUBSCRIPTION, "HBO Max Amazon Channel" to Offer.SUBSCRIPTION, "Apple TV" to Offer.RENT),
            providers.map { it.name to it.offer })
        assertEquals("https://image.tmdb.org/t/p/w92/max.jpg", providers[0].logoUrl)
    }

    @Test fun tvRatingAndSeasons() {
        val json = """{"id":1,"name":"Série","overview":"X","first_air_date":"2020-01-01","episode_run_time":[42],"number_of_seasons":3,
            "genres":[{"id":18,"name":"Drama"}],"content_ratings":{"results":[{"iso_3166_1":"BR","rating":"A16"}]}}"""
        val (t, p) = TmdbParser.parseDetails(json, "tv", null)
        assertEquals("16", t.rating)
        assertEquals(42, t.durationMin)
        assertTrue(t.synopsis!!.contains("3 temporadas"))
        assertTrue(p.isEmpty())
    }

    @Test fun idsAndApps() {
        assertEquals("movie" to 5, TmdbParser.parseId("tmdb:movie:5"))
        assertNull(TmdbParser.parseId("ia:x"))
        assertEquals("netflix", StreamingApps.canonical("Netflix Standard with Ads"))
        assertEquals("amazon", StreamingApps.canonical("Paramount Plus Amazon Channel"))
        assertEquals("amazon", StreamingApps.canonical("Amazon Prime Video"))
        assertEquals("max", StreamingApps.canonical("HBO Max"))
        assertEquals("max", StreamingApps.canonical("Max"))
        assertEquals("globoplay", StreamingApps.canonical("Globoplay"))
        assertEquals("com.netflix.ninja", StreamingApps.appFor("Netflix")!!.packages.first())
        assertNull(StreamingApps.appFor("Serviço Desconhecido"))
    }

    @Test(expected = IllegalStateException::class)
    fun invalidKeyIsReported() {
        TmdbParser.parseList("""{"status_code":7,"status_message":"Invalid API key","success":false}""", "movie")
    }
}
