package com.brudre.streaming

import com.brudre.streaming.data.ArchiveParser
import com.brudre.streaming.data.Kind
import com.brudre.streaming.data.Quality
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ArchiveParserTest {

    private val search = """
        {"responseHeader":{"status":0},"response":{"numFound":2,"start":0,"docs":[
          {"identifier":"his_girl_friday","title":"His Girl Friday","year":1940,
           "description":["<p>A newspaper editor &amp; his ex-wife.</p>"],
           "subject":["Comedy; Romance","Cary Grant"],"runtime":"1:32:00","collection":["feature_films","moviesandfilms"]},
          {"identifier":"popeye_cartoon","title":"Popeye","date":"1936-05-01T00:00:00Z",
           "subject":"cartoon","collection":"classic_cartoons"}
        ]}}
    """.trimIndent()

    @Test fun parsesSearchDocsWithMixedTypes() {
        val list = ArchiveParser.parseSearch(search, Kind.MOVIE)
        assertEquals(2, list.size)
        val a = list[0]
        assertEquals("ia:his_girl_friday", a.id)
        assertEquals("1940", a.year)
        assertEquals(Kind.MOVIE, a.kind)
        assertEquals("A newspaper editor & his ex-wife.", a.synopsis)
        assertEquals(92, a.durationMin)
        assertEquals(listOf("Comedy", "Romance", "Cary Grant"), a.genres)
        assertEquals("https://archive.org/services/img/his_girl_friday", a.posterUrl)
        val b = list[1]
        assertEquals(Kind.KIDS, b.kind)
        assertEquals("1936", b.year)
    }

    @Test(expected = IllegalStateException::class)
    fun searchErrorIsReported() {
        ArchiveParser.parseSearch("""{"error":"bad query"}""", Kind.MOVIE)
    }

    private val movieMeta = """
        {"metadata":{"identifier":"film1","title":"Film One","licenseurl":"http://creativecommons.org/publicdomain/mark/1.0/"},
         "files":[
          {"name":"Film One.avi","source":"original","format":"DivX","size":"900000000"},
          {"name":"Film One.mp4","source":"derivative","format":"h.264","size":"400000000","length":"5400.5","height":"480"},
          {"name":"Film One_512kb.mp4","source":"derivative","format":"512Kb MPEG4","size":"200000000","length":"5400.5"},
          {"name":"Film One.ogv","source":"derivative","format":"Ogg Video"},
          {"name":"Film One.pt.srt","source":"original","format":"SubRip"},
          {"name":"film1_archive.torrent","source":"metadata","format":"Archive BitTorrent"}
         ]}
    """.trimIndent()

    @Test fun picksBestQualityH264AndTorrent() {
        val p = ArchiveParser.buildPlayables("film1", movieMeta, Quality.BEST)
        assertEquals(1, p.size)
        val m = p[0]
        assertEquals("main", m.key)
        assertEquals("https://archive.org/download/film1/Film%20One.mp4", m.directUrl)
        assertNotNull(m.torrent)
        assertEquals("https://archive.org/download/film1/film1_archive.torrent", m.torrent!!.torrentUrl)
        assertEquals("Film One.mp4", m.torrent!!.filePathHint)
        assertEquals(5400, m.durationSec)
        assertEquals(1, m.subtitles.size)
        assertEquals("pt", m.subtitles[0].language)
    }

    @Test fun lightQualityPrefers512kb() {
        val p = ArchiveParser.buildPlayables("film1", movieMeta, Quality.LIGHT)
        assertEquals("https://archive.org/download/film1/Film%20One_512kb.mp4", p[0].directUrl)
    }

    @Test fun enrichTitleUsesMetadata() {
        val base = ArchiveParser.parseSearch(search, Kind.MOVIE)[0]
        val t = ArchiveParser.enrichTitle(base, movieMeta)
        assertEquals("Film One", t.name)
        assertTrue(t.license!!.contains("Domínio público"))
    }

    @Test fun restrictedItemHasNoPlayables() {
        val json = """{"metadata":{"access-restricted-item":"true"},"files":[{"name":"a.mp4","format":"h.264"}]}"""
        assertTrue(ArchiveParser.buildPlayables("x", json, Quality.BEST).isEmpty())
    }

    @Test fun seriesEpisodesAreGroupedAndOrdered() {
        val json = """
            {"metadata":{},"files":[
             {"name":"Show S01E10.mp4","format":"h.264"},
             {"name":"Show S01E02.mp4","format":"h.264"},
             {"name":"Show S01E02_512kb.mp4","format":"512Kb MPEG4"},
             {"name":"Show S02E01.mp4","format":"h.264"},
             {"name":"Show S01E01.ia.mp4","format":"h.264 IA"}
            ]}
        """.trimIndent()
        val p = ArchiveParser.buildPlayables("show", json, Quality.BEST)
        assertEquals(4, p.size)
        assertEquals(listOf(1 to 1, 1 to 2, 1 to 10, 2 to 1), p.map { it.season to it.number })
        assertTrue(p.all { it.key.startsWith("f:") })
        assertEquals("https://archive.org/download/show/Show%20S01E02.mp4", p[1].directUrl)
    }

    @Test fun numberedPartsWithoutPatternUseNaturalOrder() {
        val json = """{"metadata":{},"files":[{"name":"part10.mp4","format":"h.264"},{"name":"part2.mp4","format":"h.264"},{"name":"part1.mp4","format":"h.264"}]}"""
        val p = ArchiveParser.buildPlayables("x", json, Quality.BEST)
        assertEquals(listOf("part1", "part2", "part10"), p.map { it.label })
    }

    @Test fun rejectsNonMp4AndOversizedVideos() {
        val json = """{"metadata":{},"files":[{"name":"a.mkv","format":"Matroska"},{"name":"b.mp4","format":"h.264","height":"2160"},{"name":"trailer.mp4","format":"h.264"}]}"""
        assertTrue(ArchiveParser.buildPlayables("x", json, Quality.BEST).isEmpty())
    }

    @Test fun helpers() {
        assertEquals(5400, ArchiveParser.parseDuration("1:30:00"))
        assertEquals(125, ArchiveParser.parseDuration("2:05"))
        assertEquals(90, ArchiveParser.parseDuration("90"))
        assertNull(ArchiveParser.parseDuration("abc"))
        assertEquals(3 to 7, ArchiveParser.seasonEpisode("serie.3x07.mp4"))
        assertNull(ArchiveParser.seasonEpisode("movie_1080.mp4"))
        assertEquals("Show S01E02", ArchiveParser.groupKey("Show S01E02_512kb.mp4"))
        assertEquals("en", ArchiveParser.languageOf("movie.eng.srt"))
        assertEquals("a b c", ArchiveParser.cleanText("a<br/>b &nbsp; c"))
        assertTrue(ArchiveParser.naturalCompare("ep2", "ep10") < 0)
    }
}
