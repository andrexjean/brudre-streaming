package com.brudre.streaming.data

/**
 * Apps oficiais de streaming GRATUITO (com anúncios, sem assinatura) disponíveis no Brasil.
 * Serviços pagos nunca entram aqui: o BruDre não oferece assinatura, aluguel nem compra.
 */
object FreeApps {
    data class App(val key: String, val label: String, val packages: List<String>, val website: String? = null)

    val APPS: Map<String, App> = listOf(
        App("pluto", "Pluto TV", listOf("tv.pluto.android")),
        App("plex", "Plex", listOf("com.plexapp.android")),
        App("youtube", "YouTube", listOf("com.google.android.youtube.tv", "com.google.android.youtube")),
        App("mercadoplay", "Mercado Play", listOf("com.mercadolibre.mercadoplay", "com.mercadolibre")),
        App("vix", "ViX", listOf("com.univision.prendetv")),
        App("tubi", "Tubi", listOf("com.tubitv", "com.tubitv.ott")),
        App("libreflix", "Libreflix", emptyList(), "libreflix.org"),
        App("rakuten", "Rakuten TV", listOf("tv.wuaki", "tv.wuaki.apptv")),
    ).associateBy { it.key }

    /** Ordem de preferência quando o título está em vários serviços grátis. */
    private val ORDER = listOf("pluto", "youtube", "mercadoplay", "plex", "vix", "tubi", "rakuten", "libreflix")

    /** Marcas pagas (inclusive planos pagos "com anúncios"): sempre descartadas. */
    private val PAID = Regex(
        "(?i)(netflix|amazon|prime video|disney|hbo|\\bmax\\b|globoplay|telecine|paramount|apple|claro|star\\+|crunchyroll|mubi|looke|" +
            "google play|microsoft|vivo play|sky|oi play|directv|starz|lionsgate|belas artes|imovision|filmbox|univer|darkflix|" +
            "filmicca|guidedoc|curiosity|magellan|history play|discovery\\+|espn|nba|ufc|dazn|premiere|mgm|amc\\+|britbox|acorn|" +
            "shudder|hallmark|philo|fubo|sling|peacock|hulu)",
    )

    fun isPaidBrand(name: String): Boolean = PAID.containsMatchIn(name)

    fun canonical(name: String): String {
        val n = name.lowercase()
        return when {
            n.contains("pluto") -> "pluto"
            n.contains("plex") -> "plex"
            n.contains("youtube") -> "youtube"
            n.contains("mercado") -> "mercadoplay"
            n == "vix" || n.startsWith("vix ") || n.contains("vix gratis") || n.contains("vix free") -> "vix"
            n.contains("tubi") -> "tubi"
            n.contains("libreflix") -> "libreflix"
            n.contains("rakuten") -> "rakuten"
            else -> n.replace(Regex("[^a-z0-9]+"), "")
        }
    }

    fun appFor(name: String): App? = APPS[canonical(name)]

    fun isKnownFree(name: String): Boolean = !isPaidBrand(name) && appFor(name) != null

    fun rank(name: String): Int = ORDER.indexOf(canonical(name)).let { if (it < 0) ORDER.size else it }

    fun label(name: String): String = appFor(name)?.label ?: name
}
