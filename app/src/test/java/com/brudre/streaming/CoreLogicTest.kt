package com.brudre.streaming

import com.brudre.streaming.data.CuratedCatalog
import com.brudre.streaming.data.Kind
import com.brudre.streaming.data.Playable
import com.brudre.streaming.data.SourceMode
import com.brudre.streaming.data.SubtitleRef
import com.brudre.streaming.data.Title
import com.brudre.streaming.data.TorrentRef
import com.brudre.streaming.player.PlaybackPlan
import com.brudre.streaming.player.SourceAttempt
import com.brudre.streaming.torrent.CacheManager
import com.brudre.streaming.torrent.PieceMath
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class CoreLogicTest {

    @Test fun pieceMathForFileInsideTorrent() {
        // arquivo começa no byte 1000 de um torrent com peças de 256 bytes
        val m = PieceMath(fileOffset = 1000, fileSize = 2000, pieceLength = 256, numPieces = 12)
        assertEquals(3, m.firstPiece)          // 1000/256
        assertEquals(11, m.lastPiece)          // (2999)/256
        assertEquals(3, m.pieceAt(0))
        assertEquals(4, m.pieceAt(24))         // byte 1024
        assertEquals(24L, m.pieceEndInFile(3)) // fim da peça 3 = byte 1024 do torrent
        assertEquals(2000L, m.pieceEndInFile(11))
        assertEquals(3..6, m.window(0, 4))
        assertEquals(10..11, m.window(10, 8))
        assertEquals(4, m.piecesFor(1000))
    }

    @Test fun playbackOrderRespectsModeAndSource() {
        val p = Playable("main", "x", 1, 1, "https://d/x.mp4", TorrentRef("https://t/x.torrent", null, "x.mp4"), emptyList(), null, preferTorrent = false)
        assertTrue(PlaybackPlan.attempts(p, SourceMode.AUTO)[0] is SourceAttempt.Direct)
        assertTrue(PlaybackPlan.attempts(p.copy(preferTorrent = true), SourceMode.AUTO)[0] is SourceAttempt.Torrent)
        assertTrue(PlaybackPlan.attempts(p, SourceMode.TORRENT)[0] is SourceAttempt.Torrent)
        assertEquals(2, PlaybackPlan.attempts(p, SourceMode.DIRECT).size)
        val onlyTorrent = p.copy(directUrl = null)
        assertEquals(1, PlaybackPlan.attempts(onlyTorrent, SourceMode.DIRECT).size)
        assertTrue(PlaybackPlan.attempts(onlyTorrent.copy(torrent = null), SourceMode.AUTO).isEmpty())
    }

    @Test fun modelsRoundTripJson() {
        val t = Title("ia:x", Kind.SERIES, "Nome", "1950", "Sinopse", 30, listOf("Drama"), null, "https://p", null, "PD")
        assertEquals(t, Title.fromJson(JSONObject(t.toJson().toString())))
        val p = Playable("f:ep1", "Ep 1", 1, 1, null, TorrentRef(null, "magnet:?xt=urn:btih:abc", "ep1.mp4"),
            listOf(SubtitleRef("https://s.srt", "pt", "Português")), 1500, true)
        assertEquals(p, Playable.fromJson(JSONObject(p.toJson().toString())))
    }

    @Test fun curatedIndexIsValidAndLegalOnly() {
        val json = File("src/main/assets/authorized_sources.json").readText()
        val c = CuratedCatalog(json)
        assertTrue(c.entries.size >= 3)
        c.entries.forEach { e ->
            assertTrue("${e.title.id} sem fonte", e.playable != null || e.archiveId != null)
            assertNotNull("${e.title.id} sem licença", e.title.license)
            e.playable?.torrent?.let { tr ->
                assertTrue(tr.torrentUrl == null || tr.torrentUrl!!.startsWith("https://"))
                assertTrue(tr.magnet == null || tr.magnet!!.startsWith("magnet:?xt=urn:btih:"))
            }
        }
        assertTrue(c.featured().isNotEmpty())
        assertTrue(c.search("sintel").any { it.id == "cur:sintel" })
        assertFalse(c.search("zzzz").isNotEmpty())
    }

    @Test fun cacheTrimDeletesOldestFirstAndKeepsActive() {
        val dir = Files.createTempDirectory("brudre").toFile()
        val old = File(dir, "a/old.bin").apply { parentFile!!.mkdirs(); writeBytes(ByteArray(3000)); setLastModified(1000) }
        val mid = File(dir, "b/mid.bin").apply { parentFile!!.mkdirs(); writeBytes(ByteArray(3000)); setLastModified(2000) }
        val active = File(dir, "c/active.bin").apply { parentFile!!.mkdirs(); writeBytes(ByteArray(3000)); setLastModified(500) }
        val freed = CacheManager.trimTorrentDir(dir, keep = setOf(active), limitBytes = 6500)
        assertEquals(3000L, freed)
        assertFalse(old.exists()); assertTrue(mid.exists()); assertTrue(active.exists())
        assertEquals(3000L, CacheManager.clearDir(dir, keep = setOf(active)))
        assertTrue(active.exists())
        assertEquals("1,5 KB", CacheManager.formatBytes(1536))
        assertEquals("512 B", CacheManager.formatBytes(512))
        dir.deleteRecursively()
    }

    @Test fun magnetLinkParsesTrackersAndWebSeeds() {
        val m = com.brudre.streaming.torrent.MagnetLink.parse(
            "magnet:?xt=urn:btih:08ADA5A7A6183AAE1E09D831DF6748D566095A10&dn=Sintel&tr=udp%3A%2F%2Fexplodie.org%3A6969&tr=wss%3A%2F%2Ftracker.webtorrent.dev&ws=https%3A%2F%2Fwebtorrent.io%2Ftorrents%2F"
        )!!
        assertEquals("08ada5a7a6183aae1e09d831df6748d566095a10", m.infoHash)
        assertEquals(listOf("udp://explodie.org:6969", "wss://tracker.webtorrent.dev"), m.trackers)
        assertEquals(listOf("https://webtorrent.io/torrents/"), m.webSeeds)
        assertEquals(null, com.brudre.streaming.torrent.MagnetLink.parse("https://x"))
    }
}
