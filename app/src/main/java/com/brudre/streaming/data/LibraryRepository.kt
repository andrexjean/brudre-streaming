package com.brudre.streaming.data

import com.brudre.streaming.data.db.BruDreDatabase
import com.brudre.streaming.data.db.FavoriteEntity
import com.brudre.streaming.data.db.ProgressEntity
import kotlinx.coroutines.flow.Flow

/** Minha Lista, Continuar Assistindo e Histórico — persistência local (Room). */
class LibraryRepository(private val db: BruDreDatabase) {

    val favorites: Flow<List<FavoriteEntity>> = db.favorites().observeAll()
    val continueWatching: Flow<List<ProgressEntity>> = db.progress().observeContinueWatching()
    val history: Flow<List<ProgressEntity>> = db.progress().observeHistory()

    fun isFavorite(titleId: String): Flow<Boolean> = db.favorites().observeIsFavorite(titleId)
    fun progressFor(titleId: String): Flow<List<ProgressEntity>> = db.progress().observeForTitle(titleId)

    suspend fun toggleFavorite(title: Title): Boolean {
        val dao = db.favorites()
        return if (dao.isFavorite(title.id)) {
            dao.delete(title.id); false
        } else {
            dao.upsert(FavoriteEntity(title.id, title.name, title.kind.name, title.posterUrl, title.year, System.currentTimeMillis()))
            true
        }
    }

    suspend fun addFavorite(title: Title) =
        db.favorites().upsert(FavoriteEntity(title.id, title.name, title.kind.name, title.posterUrl, title.year, System.currentTimeMillis()))

    suspend fun removeFavorite(titleId: String) = db.favorites().delete(titleId)

    suspend fun savePosition(
        titleId: String, episodeKey: String, titleName: String, episodeLabel: String,
        kind: Kind, posterUrl: String?, positionMs: Long, durationMs: Long,
    ) {
        if (positionMs < 0) return
        db.progress().upsert(
            ProgressEntity(titleId, episodeKey, titleName, episodeLabel, kind.name, posterUrl,
                positionMs, maxOf(durationMs, 0L), System.currentTimeMillis())
        )
    }

    suspend fun position(titleId: String, episodeKey: String): ProgressEntity? = db.progress().get(titleId, episodeKey)

    suspend fun latestFor(titleId: String): ProgressEntity? = db.progress().latestForTitle(titleId)

    /** Posição para retomar, ou 0 se o episódio foi concluído/não iniciado. */
    suspend fun resumePosition(titleId: String, episodeKey: String): Long {
        val p = position(titleId, episodeKey) ?: return 0L
        val done = p.durationMs > 0 && p.positionMs >= p.durationMs * 0.95
        return if (done || p.positionMs < 15_000) 0L else p.positionMs
    }

    suspend fun clearHistory() = db.progress().clear()
}
