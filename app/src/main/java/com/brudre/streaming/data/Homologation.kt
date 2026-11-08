package com.brudre.streaming.data

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Debug
import android.os.Process
import android.os.StatFs
import android.os.SystemClock
import android.util.Log
import com.brudre.streaming.sources.Secrets
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Rotina de homologação do aparelho (ex.: Xiaomi Mi TV Stick, 1 GB).
 *
 * Mede no PRÓPRIO aparelho, durante o uso normal: tempo de abertura do app, tempo até o vídeo começar, travamentos
 * (rebuffering), falhas e encerramentos inesperados, memória (PSS do app e do sistema), avisos de pouca memória,
 * armazenamento e a sessão de reprodução mais longa sem falha. O D-pad é conferido pelo usuário (lista de itens).
 *
 * Nada é enviado para fora: o relatório é gerado em texto, salvo na pasta do app e pode ser compartilhado.
 * O relatório só vale como homologação quando gerado no aparelho físico — o emulador NÃO homologa o Mi Stick.
 */
object Homologation {
    private const val TAG = "BruDreHomolog"
    private const val PREFS = "brudre_homolog"
    private const val MAX_ROWS = 30

    val DPAD_ITEMS = linkedMapOf(
        "catalogo" to "Navegar pelo catálogo (cima/baixo/esquerda/direita) sem perder o foco",
        "detalhes" to "Abrir detalhes de um título com OK",
        "fontes" to "Escolher fonte e episódio só com o controle",
        "player" to "Pausar, avançar e voltar no player",
        "voltar" to "Botão Voltar fecha diálogos e telas na ordem certa",
        "teclado" to "Digitar na pesquisa e no cadastro de fontes",
        "config" to "Mudar uma configuração e sair",
    )

    private lateinit var app: Context
    private val prefs get() = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    @Volatile private var coldStartRecorded = false

    fun init(context: Context) {
        app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching {
                // commit(): o processo vai terminar logo em seguida
                prefs.edit().putInt("crashes", prefs.getInt("crashes", 0) + 1)
                    .putString("lastCrash", "${now()} ${e.javaClass.simpleName}: ${com.brudre.streaming.sources.Secrets.redactText(e.message?.take(200))}").commit()
            }
            previous?.uncaughtException(t, e)
        }
    }

    private fun now() = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale("pt", "BR")).format(Date())

    private fun push(key: String, value: JSONObject) = synchronized(this) {
        val a = runCatching { JSONArray(prefs.getString(key, "[]")) }.getOrDefault(JSONArray())
        a.put(value.put("at", System.currentTimeMillis()))
        val trimmed = JSONArray().apply { for (i in maxOf(0, a.length() - MAX_ROWS) until a.length()) put(a.get(i)) }
        prefs.edit().putString(key, trimmed.toString()).apply()
    }

    private fun rows(key: String): List<JSONObject> = runCatching { JSONArray(prefs.getString(key, "[]")) }.getOrDefault(JSONArray())
        .let { a -> (0 until a.length()).map { a.getJSONObject(it) } }

    /** Primeira tela desenhada: tempo desde o início do processo (abertura a frio). */
    fun onFirstFrame() {
        if (!::app.isInitialized || coldStartRecorded) return
        coldStartRecorded = true
        val ms = SystemClock.uptimeMillis() - Process.getStartUptimeMillis()
        if (ms in 1..120_000) { push("starts", JSONObject().put("ms", ms)); Log.i(TAG, "Abertura a frio: $ms ms") }
    }

    fun onTrimMemory(level: Int) {
        if (!::app.isInitialized) return
        prefs.edit().putInt("trim", prefs.getInt("trim", 0) + 1).putInt("trimMax", maxOf(level, prefs.getInt("trimMax", 0))).apply()
    }

    /** Uma sessão de reprodução (gravada ao fechar o player). */
    data class Playback(
        val kind: String, val timeToVideoMs: Long?, val rebuffers: Int, val rebufferMs: Long, val watchedMs: Long,
        val failed: Boolean, val switches: Int, val pssKb: Int,
        /** Motivo da falha mostrado ao usuário (sem endereços nem segredos). */
        val error: String? = null,
    )

    fun recordPlayback(p: Playback) {
        if (!::app.isInitialized) return
        push("plays", JSONObject().put("kind", p.kind).put("ttv", p.timeToVideoMs ?: -1).put("rb", p.rebuffers).put("rbMs", p.rebufferMs)
            .put("watched", p.watchedMs).put("failed", p.failed).put("switches", p.switches).put("pss", p.pssKb)
            .put("err", Secrets.redactText(p.error?.replace(Regex("https?://\\S+"), "[endereço]")?.take(160)) ?: JSONObject.NULL))
        Log.i(TAG, "Reprodução ${p.kind}: até o vídeo=${p.timeToVideoMs} ms, travamentos=${p.rebuffers} (${p.rebufferMs} ms), assistido=${p.watchedMs / 1000} s, falhou=${p.failed}, PSS=${p.pssKb / 1024} MB")
    }

    fun pssKb(): Int = runCatching { (Debug.getPss()).toInt() }.getOrDefault(0)

    fun setDpad(item: String, ok: Boolean?) {
        prefs.edit().apply { if (ok == null) remove("dpad_$item") else putBoolean("dpad_$item", ok) }.apply()
    }

    fun dpad(item: String): Boolean? = if (prefs.contains("dpad_$item")) prefs.getBoolean("dpad_$item", false) else null

    fun reset() = prefs.edit().clear().apply()

    /** Registros antigos: mensagem de falha gravada sem segredos (9.2.43). */
    fun sanitizeStored() {
        if (!::app.isInitialized) return
        prefs.getString("lastCrash", null)?.let { c -> if (com.brudre.streaming.sources.Secrets.containsSecret(c)) prefs.edit().putString("lastCrash", com.brudre.streaming.sources.Secrets.redactText(c)).apply() }
    }

    private fun dirSize(f: File): Long = if (!f.exists()) 0 else if (f.isFile) f.length() else f.listFiles()?.sumOf { dirSize(it) } ?: 0

    private fun mb(bytes: Long) = "%.0f MB".format(Locale.US, bytes / 1048576.0)

    private fun stats(values: List<Long>): String = if (values.isEmpty()) "sem medição" else {
        val s = values.sorted()
        "mediana ${s[s.size / 2]} ms, pior ${s.last()} ms (${s.size} medições)"
    }

    /** Relatório em texto (o usuário completa os campos de observação e compartilha). */
    fun report(): String {
        val am = app.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val stat = StatFs(app.filesDir.path)
        val plays = rows("plays")
        val ok = plays.filter { !it.optBoolean("failed") }
        val ttv = plays.mapNotNull { it.optLong("ttv").takeIf { v -> v > 0 } }
        val ttvTorrent = plays.filter { it.optString("kind") == "BitTorrent" }.mapNotNull { it.optLong("ttv").takeIf { v -> v > 0 } }
        val pss = plays.map { it.optInt("pss") }.filter { it > 0 }
        val longest = ok.maxOfOrNull { it.optLong("watched") } ?: 0
        val dpad = DPAD_ITEMS.map { (k, label) -> "  [${when (dpad(k)) { true -> "OK"; false -> "FALHA"; null -> "  " }}] $label" }
        val isEmulator = Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("sdk", true) || Build.HARDWARE.contains("ranchu")
        val version = runCatching { app.packageManager.getPackageInfo(app.packageName, 0).let { "${it.versionName} (${if (Build.VERSION.SDK_INT >= 28) it.longVersionCode else @Suppress("DEPRECATION") it.versionCode.toLong()})" } }.getOrDefault("?")
        val exits = if (Build.VERSION.SDK_INT >= 30) runCatching {
            am.getHistoricalProcessExitReasons(app.packageName, 0, 20).groupingBy {
                when (it.reason) {
                    android.app.ApplicationExitInfo.REASON_CRASH, android.app.ApplicationExitInfo.REASON_CRASH_NATIVE -> "crash"
                    android.app.ApplicationExitInfo.REASON_ANR -> "travamento (ANR)"
                    android.app.ApplicationExitInfo.REASON_LOW_MEMORY -> "falta de memória"
                    else -> null
                }
            }.eachCount().filterKeys { it != null }.entries.joinToString(", ") { "${it.key}: ${it.value}" }.ifEmpty { "nenhum" }
        }.getOrDefault("indisponível") else "indisponível nesta versão do Android (registro do sistema só a partir do Android 11)"
        val errors = plays.mapNotNull { it.optString("err").takeIf { e -> e.isNotBlank() && e != "null" } }.groupingBy { it }.eachCount()
        val sessionMin = plays.sumOf { it.optLong("watched") } / 60_000
        return Secrets.redactText(buildString {
            appendLine("RELATÓRIO DE HOMOLOGAÇÃO — BruDre Streaming")
            appendLine("Gerado em: ${now()}")
            appendLine("Situação: NÃO HOMOLOGADO até o usuário conferir e marcar o resultado abaixo (o app nunca aprova sozinho).")
            appendLine("BruDre: versão $version")
            appendLine("Aparelho: ${Build.MANUFACTURER} ${Build.MODEL} (Android ${Build.VERSION.RELEASE}, API ${Build.VERSION.SDK_INT})" + if (isEmulator) " — EMULADOR: não vale como homologação" else "")
            appendLine("RAM total: ${mb(mi.totalMem)} • livre agora: ${mb(mi.availMem)} • limite de memória baixa: ${mb(mi.threshold)} • aparelho de pouca RAM: ${if (am.isLowRamDevice) "sim" else "não"}")
            appendLine()
            appendLine("1. Tempo de abertura do app (a frio): ${stats(rows("starts").map { it.optLong("ms") })}")
            appendLine("2. Tempo até o vídeo começar: todos ${stats(ttv)}; BitTorrent ${stats(ttvTorrent)}")
            appendLine("3. Memória do app durante a reprodução (PSS): " + if (pss.isEmpty()) "sem medição" else "média ${mb(pss.average().toLong() * 1024)}, pico ${mb(pss.max().toLong() * 1024)}")
            appendLine("   Avisos de pouca memória do sistema: ${prefs.getInt("trim", 0)} (nível máximo ${prefs.getInt("trimMax", 0)})")
            appendLine("4. Encerramentos inesperados (crash): ${prefs.getInt("crashes", 0)}" + (prefs.getString("lastCrash", null)?.let { " — último: ${Secrets.redactText(it)}" } ?: ""))
            appendLine("   Registro do sistema (últimas saídas do app): $exits")
            appendLine("5. Travamentos durante a reprodução (rebuffering): ${plays.sumOf { it.optInt("rb") }} em ${plays.size} sessões, ${plays.sumOf { it.optLong("rbMs") } / 1000} s no total")
            appendLine("   Sessões que falharam: ${plays.count { it.optBoolean("failed") }} • trocas de fonte: ${plays.sumOf { it.optInt("switches") }}")
            appendLine("   Erros de reprodução: " + if (errors.isEmpty()) "nenhum" else errors.entries.joinToString("; ") { "${it.key} (${it.value}x)" })
            appendLine("6. Reprodução prolongada: sessão mais longa sem falha = ${longest / 60_000} min (meta sugerida: ≥ 60 min) • total assistido: $sessionMin min em ${plays.size} sessões")
            appendLine("7. Armazenamento: livre ${mb(stat.availableBytes)} de ${mb(stat.totalBytes)} • cache do app ${mb(dirSize(app.cacheDir))} • torrents ${mb(dirSize(File(app.cacheDir, "torrent-stream")))}")
            appendLine("8. Controle remoto (D-pad) — conferido pelo usuário:")
            dpad.forEach { appendLine(it) }
            appendLine()
            appendLine("Observações do usuário: ______________________________________________")
            appendLine("Resultado: [ ] Homologado  [ ] Homologado com ressalvas  [ ] Reprovado")
            appendLine()
            appendLine("Últimas reproduções (tipo; até o vídeo; travamentos; assistido; falhou):")
            plays.takeLast(10).forEach { appendLine("  ${it.optString("kind")}; ${it.optLong("ttv")} ms; ${it.optInt("rb")}; ${it.optLong("watched") / 1000} s; ${if (it.optBoolean("failed")) "sim" else "não"}") }
        })!!
    }

    /** Salva o relatório na pasta do app (acessível por gerenciadores de arquivos) e devolve o arquivo. */
    fun export(): File {
        val f = File(app.getExternalFilesDir(null) ?: app.filesDir, "brudre-homologacao.txt")
        f.writeText(report())
        return f
    }
}
