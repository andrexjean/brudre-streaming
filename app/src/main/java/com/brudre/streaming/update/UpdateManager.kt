package com.brudre.streaming.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import com.brudre.streaming.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.IOException

/** Release publicada no GitHub. */
data class ReleaseInfo(val tag: String, val build: Int, val apkUrl: String, val sizeBytes: Long)

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val release: ReleaseInfo) : UpdateState
    data class Downloading(val release: ReleaseInfo, val percent: Int) : UpdateState
    data class ReadyToInstall(val release: ReleaseInfo, val file: File) : UpdateState
    data class NeedsPermission(val release: ReleaseInfo, val file: File) : UpdateState
    data class Error(val message: String) : UpdateState
}

/**
 * Atualização dentro do app: consulta a última release do GitHub, baixa o APK,
 * confere se é o mesmo app e versão mais nova, e abre o instalador do Android.
 */
class UpdateManager(private val context: Context, private val http: OkHttpClient) {

    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    private val prefs = context.getSharedPreferences("brudre_update", Context.MODE_PRIVATE)
    private val dir: File get() = File(context.cacheDir, "updates").apply { mkdirs() }

    val currentBuild: Int get() = BuildConfig.BUILD_NUMBER
    val currentVersion: String get() = BuildConfig.VERSION_NAME

    /** Verifica no máximo a cada 6 h, a menos que [force]. */
    suspend fun check(force: Boolean = false): UpdateState {
        val now = System.currentTimeMillis()
        if (!force && now - prefs.getLong("lastCheck", 0) < CHECK_INTERVAL_MS) {
            val cached = prefs.getString("lastRelease", null)?.let { runCatching { parseRelease(it) }.getOrNull() }
            if (cached != null && cached.build > currentBuild) return UpdateState.Available(cached).also { _state.value = it }
            return _state.value
        }
        _state.value = UpdateState.Checking
        val result = try {
            val json = withContext(Dispatchers.IO) { get("https://api.github.com/repos/${BuildConfig.UPDATE_REPO}/releases/latest") }
            val release = parseRelease(json)
            prefs.edit().putLong("lastCheck", now).putString("lastRelease", json).apply()
            if (release.build > currentBuild) UpdateState.Available(release) else UpdateState.UpToDate
        } catch (e: Exception) {
            Log.w(TAG, "Falha ao verificar atualização", e)
            UpdateState.Error("Não foi possível verificar atualizações")
        }
        _state.value = result
        return result
    }

    /** Baixa e valida o APK; depois abre o instalador. */
    suspend fun downloadAndInstall(release: ReleaseInfo) {
        val file = File(dir, "BruDre-Streaming-${release.build}.apk")
        try {
            dir.listFiles()?.filter { it != file }?.forEach { it.delete() }
            if (!(file.exists() && file.length() == release.sizeBytes)) {
                _state.value = UpdateState.Downloading(release, 0)
                withContext(Dispatchers.IO) { download(release, file) }
            }
            val problem = validate(file, release)
            if (problem != null) {
                file.delete()
                _state.value = UpdateState.Error(problem)
                return
            }
            install(release, file)
        } catch (e: Exception) {
            Log.w(TAG, "Falha na atualização", e)
            file.delete()
            _state.value = UpdateState.Error("Falha ao baixar a atualização. Verifique a internet e tente de novo.")
        }
    }

    /** Abre o instalador; se o Android ainda não permitir, pede a permissão primeiro. */
    fun install(release: ReleaseInfo, file: File) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()) {
            _state.value = UpdateState.NeedsPermission(release, file)
            return
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", file)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        _state.value = UpdateState.ReadyToInstall(release, file)
    }

    /** Tela do Android para liberar "instalar apps desconhecidos" para o BruDre. */
    fun openPermissionSettings() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }.onFailure {
            _state.value = UpdateState.Error("Abra Configurações > Apps > BruDre e permita instalar apps desconhecidos")
        }
    }

    private fun download(release: ReleaseInfo, target: File) {
        val tmp = File(target.parentFile, target.name + ".part")
        val req = Request.Builder().url(release.apkUrl).header("User-Agent", "BruDreStreaming-Updater").build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
            val body = resp.body ?: throw IOException("Resposta vazia")
            val total = body.contentLength().takeIf { it > 0 } ?: release.sizeBytes
            body.byteStream().use { input ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    var lastPct = -1
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        val pct = if (total > 0) ((done * 100) / total).toInt().coerceIn(0, 100) else 0
                        if (pct != lastPct) { lastPct = pct; _state.value = UpdateState.Downloading(release, pct) }
                    }
                }
            }
        }
        if (!tmp.renameTo(target)) throw IOException("Falha ao salvar o arquivo")
    }

    /** Confere pacote, versão e assinatura antes de instalar. Retorna o problema ou null. */
    fun validate(file: File, release: ReleaseInfo): String? {
        if (release.sizeBytes > 0 && file.length() != release.sizeBytes) return "Download incompleto. Tente de novo."
        val pm = context.packageManager
        @Suppress("DEPRECATION")
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val info: PackageInfo = pm.getPackageArchiveInfo(file.absolutePath, flags) ?: return "Arquivo de atualização inválido"
        if (info.packageName != context.packageName) return "A atualização não é do BruDre"
        @Suppress("DEPRECATION")
        val newCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode else info.versionCode.toLong()
        if (newCode <= BuildConfig.VERSION_CODE) return "Você já tem esta versão"
        val mine = signatures(pm.getPackageInfo(context.packageName, flags))
        val theirs = signatures(info)
        if (mine.isNotEmpty() && theirs.isNotEmpty() && mine != theirs) return "Assinatura da atualização não confere"
        return null
    }

    @Suppress("DEPRECATION")
    private fun signatures(info: PackageInfo): Set<String> {
        val sigs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.signingInfo?.apkContentsSigners else info.signatures
        return sigs?.map { it.toCharsString() }?.toSet() ?: emptySet()
    }

    private fun get(url: String): String {
        val req = Request.Builder().url(url)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "BruDreStreaming-Updater")
            .build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
            return resp.body?.string() ?: throw IOException("Resposta vazia")
        }
    }

    companion object {
        private const val TAG = "BruDreUpdate"
        private const val CHECK_INTERVAL_MS = 6 * 60 * 60 * 1000L
        const val APK_NAME = "BruDre-Streaming.apk"

        /** "v7.0.14" → 14 */
        fun buildFromTag(tag: String): Int = Regex("(\\d+)$").find(tag.trim())?.value?.toIntOrNull() ?: 0

        fun parseRelease(json: String): ReleaseInfo {
            val o = JSONObject(json)
            val tag = o.getString("tag_name")
            val assets = o.optJSONArray("assets") ?: throw IOException("Release sem arquivos")
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                if (a.optString("name") == APK_NAME) {
                    return ReleaseInfo(tag, buildFromTag(tag), a.getString("browser_download_url"), a.optLong("size", 0))
                }
            }
            throw IOException("Release sem APK")
        }
    }
}
