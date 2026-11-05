package com.brudre.streaming.ui

import android.app.SearchManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import com.brudre.streaming.data.FreeApps
import com.brudre.streaming.data.FreeProvider
import com.brudre.streaming.data.Title

/**
 * Abre o título no app oficial GRATUITO (Pluto TV, YouTube, Plex…).
 * Instalado: tenta a busca do app pelo nome do filme; senão, abre o app.
 * Não instalado: abre a página do app (grátis) na Play Store.
 */
object FreeAppLauncher {

    fun installedPackage(context: Context, providerName: String): String? {
        val app = FreeApps.appFor(providerName) ?: return null
        val pm = context.packageManager
        return app.packages.firstOrNull { pkg -> pm.getLeanbackLaunchIntentForPackage(pkg) != null || pm.getLaunchIntentForPackage(pkg) != null }
    }

    /** Mensagem para o usuário. */
    fun open(context: Context, provider: FreeProvider, title: Title): String {
        if (FreeApps.isPaidBrand(provider.name)) return "Serviço pago: o BruDre não abre serviços por assinatura"
        val app = FreeApps.appFor(provider.name)
        val label = app?.label ?: provider.name
        if (app?.packages?.isEmpty() == true && app.website != null) {
            return "$label não tem app para TV: assista grátis em ${app.website} (procure por \"${title.name}\")"
        }
        val pm = context.packageManager
        val pkg = installedPackage(context, provider.name)
        if (pkg != null) {
            val search = Intent(Intent.ACTION_SEARCH).setPackage(pkg)
                .putExtra(SearchManager.QUERY, title.name).putExtra("query", title.name)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                context.startActivity(search)
                return "Abrindo $label (grátis): procurando \"${title.name}\""
            } catch (e: ActivityNotFoundException) {
                Log.i(TAG, "$pkg não aceita busca externa")
            } catch (e: SecurityException) {
                Log.i(TAG, "$pkg recusou a busca externa")
            }
            val launch = (pm.getLeanbackLaunchIntentForPackage(pkg) ?: pm.getLaunchIntentForPackage(pkg))?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (launch != null) {
                return try {
                    context.startActivity(launch)
                    "Abrindo $label (grátis). Procure por \"${title.name}\""
                } catch (e: Exception) {
                    "Não foi possível abrir $label"
                }
            }
        }
        val market = app?.packages?.firstOrNull()?.let { Uri.parse("market://details?id=$it") }
            ?: Uri.parse("market://search?q=${Uri.encode(label)}&c=apps")
        return try {
            context.startActivity(Intent(Intent.ACTION_VIEW, market).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            "$label é grátis, mas não está instalado. Abrindo a Play Store para instalar; depois procure por \"${title.name}\""
        } catch (e: ActivityNotFoundException) {
            "Instale o app grátis $label pela Play Store da TV e procure por \"${title.name}\""
        }
    }

    private const val TAG = "BruDreFreeApps"
}
