package com.brudre.streaming.data

/**
 * Liga os nomes de provedores do TMDB/JustWatch aos apps de Android TV que os reproduzem.
 * Pacotes de TV primeiro, depois os de celular (alguns sticks instalam a versão móvel).
 */
object StreamingApps {

    data class App(val key: String, val label: String, val packages: List<String>)

    private val APPS = listOf(
        App("netflix", "Netflix", listOf("com.netflix.ninja", "com.netflix.mediaclient")),
        App("amazon", "Prime Video", listOf("com.amazon.amazonvideo.livingroom", "com.amazon.avod.thirdpartyclient")),
        App("disney", "Disney+", listOf("com.disney.disneyplus")),
        App("max", "HBO Max", listOf("com.wbd.stream", "com.hbo.hbonow", "com.hbo.android.app")),
        App("globoplay", "Globoplay", listOf("com.globo.globotv")),
        App("paramount", "Paramount+", listOf("com.cbs.ott", "com.cbs.app")),
        App("apple", "Apple TV", listOf("com.apple.atve.androidtv.appletv", "com.apple.atve.amazon.appletv")),
        App("crunchyroll", "Crunchyroll", listOf("com.crunchyroll.crunchyroid")),
        App("pluto", "Pluto TV", listOf("tv.pluto.android")),
        App("plex", "Plex", listOf("com.plexapp.android")),
        App("mubi", "MUBI", listOf("com.mubi")),
        App("youtube", "YouTube", listOf("com.google.android.youtube.tv", "com.google.android.youtube")),
        App("googletv", "Google TV", listOf("com.google.android.videos")),
        App("claro", "Claro tv+", listOf("com.clarobr.tvmais", "br.com.claro.now", "com.clarovideo.android")),
        App("looke", "Looke", listOf("br.com.looke", "com.looke.android")),
        App("mercadoplay", "Mercado Play", listOf("com.mercadolibre.mercadoplay", "com.mercadolibre")),
    ).associateBy { it.key }

    /** Nome normalizado do serviço. "Netflix Standard with Ads" → "netflix"; canais Amazon → "amazon". */
    fun canonical(providerName: String): String {
        val n = providerName.lowercase()
        return when {
            n.contains("amazon channel") || n.contains("amazon prime") || n == "amazon video" || n.startsWith("prime video") -> "amazon"
            n.contains("netflix") -> "netflix"
            n.contains("disney") -> "disney"
            n.contains("hbo") || n == "max" || n.startsWith("max ") -> "max"
            n.contains("globoplay") || n.contains("telecine") -> "globoplay"
            n.contains("paramount") -> "paramount"
            n.contains("apple") -> "apple"
            n.contains("crunchyroll") -> "crunchyroll"
            n.contains("pluto") -> "pluto"
            n.contains("plex") -> "plex"
            n.contains("mubi") -> "mubi"
            n.contains("youtube") -> "youtube"
            n.contains("google play") -> "googletv"
            n.contains("claro") -> "claro"
            n.contains("looke") -> "looke"
            n.contains("mercado") -> "mercadoplay"
            else -> n.replace(Regex("[^a-z0-9]+"), "")
        }
    }

    fun appFor(providerName: String): App? = APPS[canonical(providerName)]

    /** Serviços oferecidos em Configurações → Meus streamings. */
    val SELECTABLE: List<App> = listOf("netflix", "amazon", "disney", "max", "globoplay", "paramount", "apple", "crunchyroll", "pluto", "plex", "mubi", "claro", "looke", "mercadoplay")
        .mapNotNull { APPS[it] }
}
