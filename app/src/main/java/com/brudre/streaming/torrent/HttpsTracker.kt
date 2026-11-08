package com.brudre.streaming.torrent

import android.util.Log
import com.brudre.streaming.data.CatalogRepository
import com.brudre.streaming.sources.ErrorClassifier
import com.brudre.streaming.sources.Secrets
import com.brudre.streaming.sources.UrlGuard
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import org.libtorrent4j.TorrentHandle
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Anúncio a trackers HTTPS (BEP 3/23) pelo OkHttp protegido (9.2.43).
 *
 * Motivo comprovado no CI (v9.2.42/9.2.44): o HTTPS interno do libtorrent não valida certificados no Android (já era o
 * caso dos web seeds, por isso existe o [WebSeedFetcher]); um torrent só com tracker HTTPS (Ubuntu) não achava ninguém
 * pelo tracker. Aqui o anúncio usa a validação de certificados do Android (nenhuma proteção desligada) e os
 * participantes recebidos são entregues ao motor. A contagem é exata: o diagnóstico sabe quantos vieram por HTTPS.
 */
object TrackerAnnounce {
    data class Reply(val interval: Int, val peers: List<Pair<String, Int>>, val failure: String?, val complete: Int?, val incomplete: Int?)

    fun peerId(): ByteArray {
        val chars = "abcdefghijklmnopqrstuvwxyz0123456789"
        return ("-BD0920-" + (1..12).map { chars.random() }.joinToString("")).toByteArray(Charsets.US_ASCII)
    }

    /** Bytes crus em %XX (o info_hash não é texto). */
    fun encodeBytes(b: ByteArray): String = buildString {
        for (x in b) {
            val c = x.toInt() and 0xff
            if ((c in 'a'.code..'z'.code) || (c in 'A'.code..'Z'.code) || (c in '0'.code..'9'.code) || c == '-'.code || c == '_'.code || c == '.'.code || c == '~'.code) append(c.toChar())
            else append('%').append("%02X".format(c))
        }
    }

    fun hexToBytes(hex: String): ByteArray = ByteArray(hex.length / 2) { i -> hex.substring(i * 2, i * 2 + 2).toInt(16).toByte() }

    fun url(base: String, infoHash: ByteArray, peerId: ByteArray, port: Int, left: Long, event: String?): String {
        val sep = if (base.contains('?')) "&" else "?"
        return base + sep + "info_hash=" + encodeBytes(infoHash) + "&peer_id=" + encodeBytes(peerId) + "&port=$port&uploaded=0&downloaded=0" +
            "&left=$left&compact=1&numwant=50" + (event?.let { "&event=$it" } ?: "")
    }

    private fun str(v: Any?) = (v as? ByteArray)?.let { String(it, Charsets.UTF_8) }

    /** Resposta bencode: peers compactos (6 bytes IPv4 / 18 bytes IPv6) ou lista de dicionários. */
    fun parse(body: ByteArray): Reply {
        @Suppress("UNCHECKED_CAST")
        val m = Bencode.Reader(body).read() as? Map<String, Any> ?: throw IllegalArgumentException("resposta do tracker não é um dicionário")
        str(m["failure reason"])?.let { return Reply(0, emptyList(), Secrets.redactText(it.take(200)), null, null) }
        val peers = ArrayList<Pair<String, Int>>()
        when (val p = m["peers"]) {
            is ByteArray -> for (i in 0 until p.size / 6) {
                val o = i * 6
                val ip = (0..3).joinToString(".") { (p[o + it].toInt() and 0xff).toString() }
                peers += ip to (((p[o + 4].toInt() and 0xff) shl 8) or (p[o + 5].toInt() and 0xff))
            }
            is List<*> -> p.forEach { d -> (d as? Map<*, *>)?.let { e -> val ip = str(e["ip"]); val port = (e["port"] as? Long)?.toInt(); if (ip != null && port != null) peers += ip to port } }
        }
        (m["peers6"] as? ByteArray)?.let { p ->
            for (i in 0 until p.size / 18) {
                val o = i * 18
                val ip = java.net.InetAddress.getByAddress(p.copyOfRange(o, o + 16)).hostAddress ?: continue
                peers += ip to (((p[o + 16].toInt() and 0xff) shl 8) or (p[o + 17].toInt() and 0xff))
            }
        }
        val interval = ((m["min interval"] ?: m["interval"]) as? Long)?.toInt() ?: 1800
        return Reply(interval, peers.filter { it.second in 1..65535 }, null, (m["complete"] as? Long)?.toInt(), (m["incomplete"] as? Long)?.toInt())
    }

    /** Entrega um participante ao motor (API do libtorrent4j por reflexão: nomes estáveis, falha vira "não conectado"). */
    fun connect(h: TorrentHandle, ip: String, port: Int): Boolean {
        runCatching {
            val epCls = Class.forName("org.libtorrent4j.TcpEndpoint")
            val ep = epCls.getConstructor(String::class.java, Int::class.javaPrimitiveType).newInstance(ip, port)
            h.javaClass.getMethod("connectPeer", epCls).invoke(h, ep)
            return true
        }
        return runCatching {
            val ecCls = Class.forName("org.libtorrent4j.swig.error_code")
            val addrCls = Class.forName("org.libtorrent4j.swig.address")
            val addr = addrCls.getMethod("from_string", String::class.java, ecCls).invoke(null, ip, ecCls.getConstructor().newInstance())
            val tcpCls = Class.forName("org.libtorrent4j.swig.tcp_endpoint")
            val ep = tcpCls.getConstructor(addrCls, Int::class.javaPrimitiveType).newInstance(addr, port)
            val sw = h.javaClass.getMethod("swig").invoke(h)
            sw.javaClass.getMethod("connect_peer", tcpCls).invoke(sw, ep)
            true
        }.getOrDefault(false)
    }
}

class HttpsTrackerBridge(
    http: OkHttpClient,
    private val handle: TorrentHandle,
    infoHashHex: String,
    trackers: List<String>,
    private val left: Long,
) {
    private val client = http.newBuilder().callTimeout(20, TimeUnit.SECONDS).build()
    private val infoHash = TrackerAnnounce.hexToBytes(infoHashHex)
    private val peerId = TrackerAnnounce.peerId()
    val trackers = trackers.filter { it.startsWith("https://", true) && UrlGuard.isAllowed(it) }.distinct().take(8)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    val peersReceived = AtomicInteger()
    val peersConnected = AtomicInteger()
    @Volatile var swarm: Int = 0
        private set
    /** "https://tracker/announce: 50" (sem parâmetros: passkeys nunca aparecem). */
    val replies = CopyOnWriteArrayList<String>()
    val errors = CopyOnWriteArrayList<String>()

    private fun label(u: String) = u.substringBefore('?')

    fun start() {
        if (trackers.isEmpty() || job != null) return
        job = scope.launch {
            trackers.forEach { t ->
                launch {
                    var event: String? = "started"
                    var failures = 0
                    while (isActive) {
                        val wait = try {
                            val reply = announce(t, event)
                            event = null
                            failures = 0
                            if (reply.failure != null) {
                                if (errors.size < 10) errors += "${label(t)}: recusado (${reply.failure})"
                                600
                            } else {
                                peersReceived.addAndGet(reply.peers.size)
                                swarm = maxOf(swarm, (reply.complete ?: 0) + (reply.incomplete ?: 0))
                                reply.peers.forEach { (ip, port) -> if (TrackerAnnounce.connect(handle, ip, port)) peersConnected.incrementAndGet() }
                                if (replies.size < 10) replies += "${label(t)}: ${reply.peers.size}"
                                reply.interval.coerceIn(120, 1800)
                            }
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            val f = ErrorClassifier.classify(e)
                            if (errors.size < 10) errors += "${label(t)}: ${f.kind.title} — ${f.technical.take(160)}"
                            Log.w(TAG, "Tracker HTTPS ${label(t)}: ${f.kind} ${f.technical.take(200)}")
                            (60 shl failures++.coerceAtMost(3)).coerceAtMost(600)
                        }
                        delay(wait * 1000L)
                    }
                }
            }
        }
    }

    private fun announce(tracker: String, event: String?): TrackerAnnounce.Reply {
        val url = TrackerAnnounce.url(tracker, infoHash, peerId, 6881, left, event)
        UrlGuard.check(url)?.let { throw com.brudre.streaming.sources.BlockedUrlException(it) }
        val req = Request.Builder().url(url).header("User-Agent", CatalogRepository.USER_AGENT).build()
        client.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw com.brudre.streaming.sources.HttpStatusException(r.code)
            val body = r.body?.source()?.let { s -> s.request(MAX_BYTES); s.buffer.readByteArray(minOf(s.buffer.size, MAX_BYTES)) } ?: ByteArray(0)
            return TrackerAnnounce.parse(body)
        }
    }

    fun stop() {
        scope.cancel()
    }

    companion object {
        private const val TAG = "BruDreTorrent"
        private const val MAX_BYTES = 512L * 1024
    }
}
