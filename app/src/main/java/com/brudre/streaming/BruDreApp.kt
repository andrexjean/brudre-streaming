package com.brudre.streaming

import android.app.Application
import android.content.ComponentCallbacks2
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.brudre.streaming.data.AppSettings
import com.brudre.streaming.data.CatalogRepository
import com.brudre.streaming.data.CuratedCatalog
import com.brudre.streaming.data.LibraryRepository
import com.brudre.streaming.data.db.BruDreDatabase
import com.brudre.streaming.torrent.CacheManager
import com.brudre.streaming.torrent.TorrentEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.Cache
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit

class BruDreApp : Application(), ImageLoaderFactory {

    lateinit var services: Services
        private set

    override fun onCreate() {
        super.onCreate()
        com.brudre.streaming.data.Homologation.init(this)
        services = Services(this)
    }

    override fun newImageLoader(): ImageLoader = services.imageLoader

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        com.brudre.streaming.data.Homologation.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            services.releaseMemory()
            Log.i("BruDre", "Memória baixa (nível $level): caches liberados")
        }
    }

    override fun onLowMemory() {
        super.onLowMemory()
        services.releaseMemory()
    }

    companion object {
        fun services(context: Context): Services = (context.applicationContext as BruDreApp).services
    }
}

/** Dependências do app (sem framework de injeção: mais leve e simples de manter). */
class Services(private val app: Context) {

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(60, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .cache(Cache(File(app.cacheDir, "http"), 8L * 1024 * 1024))
        // Respostas de endereços com chave/token (ex.: api_key do TMDB) não vão para o cache em disco,
        // que grava o endereço completo junto da resposta.
        .addInterceptor { chain ->
            val r = chain.request()
            if (r.url.queryParameterNames.any { com.brudre.streaming.sources.Secrets.isSensitive(it) }) chain.proceed(r.newBuilder().header("Cache-Control", "no-store").build())
            else chain.proceed(r)
        }
        .build()

    val settings = AppSettings(app)

    init { com.brudre.streaming.data.VerifiedLanguages.init(app) }
    val database: BruDreDatabase by lazy { BruDreDatabase.create(app) }
    val curated: CuratedCatalog by lazy { CuratedCatalog(app.assets.open("authorized_sources.json").bufferedReader().use { it.readText() }) }
    val catalog: CatalogRepository by lazy { CatalogRepository(http, database, curated, settings, ::isOnline, BuildConfig.TMDB_API_KEY) }
    val library: LibraryRepository by lazy { LibraryRepository(database) }

    /** Cliente para mídia e listas de terceiros: valida cada redirecionamento e bloqueia executáveis. */
    val guardedHttp: OkHttpClient by lazy { com.brudre.streaming.sources.GuardedHttp.client(http) }
    val sourceStatus = com.brudre.streaming.sources.StatusBoard()
    /** Fontes cadastradas pelo usuário (Gerenciar fontes), salvas no aparelho. */
    val sourceStore: com.brudre.streaming.sources.SourceStore by lazy { com.brudre.streaming.sources.SourceStore(app, settings) }
    val feeds: com.brudre.streaming.sources.FeedRepository by lazy {
        com.brudre.streaming.sources.FeedRepository(guardedHttp, settings, sourceStatus, File(app.cacheDir, "feeds"), sourceStore)
    }
    val validator: com.brudre.streaming.sources.StreamValidator by lazy { com.brudre.streaming.sources.StreamValidator(guardedHttp) }
    val inspector: com.brudre.streaming.sources.SourceInspector by lazy {
        com.brudre.streaming.sources.SourceInspector(guardedHttp, { feeds }, { live }, validator, { searchRepo })
    }
    val sources: com.brudre.streaming.sources.SourceManager by lazy {
        com.brudre.streaming.sources.SourceManager(catalog, settings, validator, sourceStatus, feeds, sourceStore, searchRepo, { torrentLibrary })
    }
    val live: com.brudre.streaming.live.LiveRepository by lazy {
        com.brudre.streaming.live.LiveRepository(app, guardedHttp, settings, sourceStatus, ::isOnline, sourceStore)
    }
    val torrent: TorrentEngine by lazy { TorrentEngine(app, guardedHttp, settings) }
    /** Torrents adicionados pelo usuário (magnet, .torrent importado ou compartilhado). */
    val torrentLibrary: com.brudre.streaming.torrent.TorrentLibrary by lazy { com.brudre.streaming.torrent.TorrentLibrary(app, torrent) }
    /** APIs de pesquisa autorizadas cadastradas pelo usuário. */
    /** Pedido do player para reabrir a escolha de fonte de um título (todas as fontes falharam). */
    @Volatile var chooseSourceFor: com.brudre.streaming.data.Title? = null
    val searchRepo: com.brudre.streaming.sources.SearchRepository by lazy { com.brudre.streaming.sources.SearchRepository(guardedHttp, sourceStore, credentials) }
    /** Credenciais das fontes, cifradas pelo Android Keystore. */
    val credentials: com.brudre.streaming.sources.CredentialStore by lazy { com.brudre.streaming.sources.CredentialStore(app) }

    init {
        // Credenciais só no instante da requisição, para o host:porta da fonte dona delas (9.2.43)
        com.brudre.streaming.sources.CredentialRefs.lookup = { host, port ->
            sourceStore.current.firstOrNull { s -> com.brudre.streaming.sources.LocalServers.keyOf(s.url) == "$host:$port" && credentials.has(s.id) }
                ?.let { credentials.get(it.id) }
        }
        com.brudre.streaming.sources.Secrets.known = { sourceStore.current.flatMap { com.brudre.streaming.sources.Secrets.values(credentials.get(it.id)) } }
        appScope.launch(Dispatchers.IO) { runCatching { migrateSecrets() }.onFailure { Log.w("BruDre", "Migração de segredos falhou: ${it.javaClass.simpleName}") } }
    }

    /** Registros antigos com segredos: fontes, histórico de torrents, homologação e caches (9.2.43). Idempotente. */
    private suspend fun migrateSecrets() {
        val moved = sourceStore.migrateSecrets(credentials)
        torrentLibrary.sanitizeStored()
        com.brudre.streaming.data.Homologation.sanitizeStored()
        val prefs = app.getSharedPreferences("brudre_migrations", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("secrets_v1", false)) {
            // Cache HTTP antigo pode guardar endereços com api_key; caches de catálogo com segredos são descartados.
            runCatching { http.cache?.evictAll() }
            val dropped = database.catalogCache().all().filter { com.brudre.streaming.sources.Secrets.containsSecret(it.json) || com.brudre.streaming.sources.Secrets.containsSecret(it.key) }
            dropped.forEach { database.catalogCache().delete(it.key) }
            listOf("feeds", "live").forEach { d -> File(app.cacheDir, d).listFiles()?.forEach { f -> if (f.isFile && f.length() < 8_000_000 && com.brudre.streaming.sources.Secrets.containsSecret(f.readText())) f.delete() } }
            prefs.edit().putBoolean("secrets_v1", true).apply()
            Log.i("BruDre", "Migração de segredos: $moved fonte(s) com credencial movida; ${dropped.size} cache(s) descartado(s)")
        }
    }
    val updater: com.brudre.streaming.update.UpdateManager by lazy { com.brudre.streaming.update.UpdateManager(app, http) }

    val imageCacheDir: File get() = File(app.cacheDir, "images")

    val imageLoader: ImageLoader by lazy {
        ImageLoader.Builder(app)
            .okHttpClient(http)
            .memoryCache { MemoryCache.Builder(app).maxSizePercent(0.12).build() }
            .diskCache { DiskCache.Builder().directory(imageCacheDir).maxSizeBytes(48L * 1024 * 1024).build() }
            .allowRgb565(true)
            // Sem crossfade: em listas longas a animação de cada capa pesa na GPU do Mi Stick.
            .crossfade(false)
            .respectCacheHeaders(false)
            .build()
    }

    fun isOnline(): Boolean {
        val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return true
        val caps = cm.getNetworkCapabilities(cm.activeNetwork ?: return false) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    fun releaseMemory() {
        imageLoader.memoryCache?.clear()
        catalog.trimMemory()
        live.trimMemory()
        searchRepo.clear()
    }

    @OptIn(coil.annotation.ExperimentalCoilApi::class)
    /** Limpa dados temporários: vídeo do torrent (exceto o que estiver tocando), imagens, catálogo e listas de canais. */
    suspend fun clearTemporaryData(): Long {
        val torrentBytes = CacheManager.clearDir(torrent.saveDir)
        val imageBytes = CacheManager.dirSize(imageCacheDir)
        imageLoader.diskCache?.clear()
        imageLoader.memoryCache?.clear()
        val httpBytes = CacheManager.dirSize(File(app.cacheDir, "http"))
        runCatching { http.cache?.evictAll() }
        catalog.clearCache()
        val liveBytes = live.clearCache() + CacheManager.dirSize(File(app.cacheDir, "feeds")).also { feeds.clearCache() }
        return torrentBytes + imageBytes + httpBytes + liveBytes
    }

    fun cacheUsage(): Long =
        CacheManager.dirSize(torrent.saveDir) + CacheManager.dirSize(imageCacheDir) + CacheManager.dirSize(File(app.cacheDir, "http")) +
            CacheManager.dirSize(File(app.cacheDir, "live"))
}
