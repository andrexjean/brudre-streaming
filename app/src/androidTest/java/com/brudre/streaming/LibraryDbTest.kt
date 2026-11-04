package com.brudre.streaming

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.brudre.streaming.data.Kind
import com.brudre.streaming.data.LibraryRepository
import com.brudre.streaming.data.Title
import com.brudre.streaming.data.db.BruDreDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Testes 5 (Minha Lista), 6 (Histórico) e 11 (Retomada) na camada de persistência. */
@RunWith(AndroidJUnit4::class)
class LibraryDbTest {
    private lateinit var db: BruDreDatabase
    private lateinit var lib: LibraryRepository
    private val title = Title("cur:sintel", Kind.MOVIE, "Sintel", "2010", posterUrl = "https://p")

    @Before fun setUp() {
        db = BruDreDatabase.inMemory(InstrumentationRegistry.getInstrumentation().targetContext)
        lib = LibraryRepository(db)
    }

    @After fun tearDown() = db.close()

    @Test fun myListAddRemovePersists() = runBlocking {
        assertTrue(lib.toggleFavorite(title))
        assertEquals(listOf("cur:sintel"), lib.favorites.first().map { it.titleId })
        assertTrue(lib.isFavorite("cur:sintel").first())
        assertFalse(lib.toggleFavorite(title))
        assertTrue(lib.favorites.first().isEmpty())
    }

    @Test fun historyAndContinueWatching() = runBlocking {
        lib.savePosition("ia:a", "main", "A", "A", Kind.MOVIE, null, 120_000, 600_000)
        Thread.sleep(5)
        lib.savePosition("ia:b", "f:e1", "B", "Ep 1", Kind.SERIES, null, 5_000, 600_000) // < 15 s: só histórico
        Thread.sleep(5)
        lib.savePosition("ia:c", "main", "C", "C", Kind.MOVIE, null, 599_000, 600_000) // concluído
        val cont = lib.continueWatching.first().map { it.titleId }
        assertEquals(listOf("ia:a"), cont)
        val hist = lib.history.first().map { it.titleId }
        assertEquals(listOf("ia:c", "ia:b", "ia:a"), hist)
        // último episódio por título
        Thread.sleep(5)
        lib.savePosition("ia:b", "f:e2", "B", "Ep 2", Kind.SERIES, null, 90_000, 600_000)
        assertEquals("f:e2", lib.latestFor("ia:b")!!.episodeKey)
        assertEquals("ia:b", lib.continueWatching.first().first().titleId)
        lib.clearHistory()
        assertTrue(lib.history.first().isEmpty())
    }

    @Test fun resumePositionRules() = runBlocking {
        lib.savePosition("ia:a", "main", "A", "A", Kind.MOVIE, null, 125_000, 600_000)
        assertEquals(125_000L, lib.resumePosition("ia:a", "main"))
        lib.savePosition("ia:a", "main", "A", "A", Kind.MOVIE, null, 590_000, 600_000)
        assertEquals(0L, lib.resumePosition("ia:a", "main"))
        assertEquals(0L, lib.resumePosition("ia:none", "main"))
    }
}
