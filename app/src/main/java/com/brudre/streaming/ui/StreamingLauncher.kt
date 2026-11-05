package com.brudre.streaming.ui

import android.app.SearchManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import com.brudre.streaming.data.StreamingApps
import com.brudre.streaming.data.Title
import com.brudre.streaming.data.WatchProvider

/**
 * Abre o título no app oficial do streaming instalado no Mi Stick.
 * Tenta a busca do app com o nome do título; se não houver, abre o app.
 * Se o app não estiver instalado, abre a página dele na Play Store.
 */
object StreamingLauncher {

    fun installedPackage(context: Context, providerName: String): String? {
        val app = StreamingApps.appFor(providerName) ?: return null
        val pm = context.packageManager
        return app.packages.firstOrNull { pkg ->
            pm.getLeanbackLaunchIntentForPackage(pkg) != null || pm.getLaunchIntentForPackage(pkg) != null
        }
    }

    /** Retorna a mensagem a mostrar para o usuário. */
    fun open(context: Context, provider: WatchProvider, title: Title): String {
        val app = StreamingApps.appFor(provider.name)
        val pm = context.packageManager
        val pkg = installedPackage(context, provider.name)
        if (pkg != null) {
            val search = Intent(Intent.ACTION_SEARCH)
                .setPackage(pkg)
                .putExtra(SearchManager.QUERY, title.name)
                .putExtra("query", title.name)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                context.startActivity(search)
                return "Abrindo ${app?.label ?: provider.name}: procurando \"${title.name}\""
            } catch (e: ActivityNotFoundException) {
                Log.i(TAG, "$pkg não aceita busca externa; abrindo o app")
            } catch (e: SecurityException) {
                Log.i(TAG, "$pkg recusou a busca externa; abrindo o app")
            }
            val launch = (pm.getLeanbackLaunchIntentForPackage(pkg) ?: pm.getLaunchIntentForPackage(pkg))
                ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (launch != null) {
                return try {
                    context.startActivity(launch)
                    "Abrindo ${app?.label ?: provider.name}. Procure por \"${title.name}\""
                } catch (e: Exception) {
                    "Não foi possível abrir ${provider.name}"
                }
            }
        }
        // Não instalado: Play Store
        val market = if (app != null) Uri.parse("market://details?id=${app.packages.first()}")
        else Uri.parse("market://search?q=${Uri.encode(provider.name)}&c=apps")
        return try {
            context.startActivity(Intent(Intent.ACTION_VIEW, market).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            "${app?.label ?: provider.name} não está instalado. Abrindo a Play Store para instalar"
        } catch (e: ActivityNotFoundException) {
            "Instale o app ${app?.label ?: provider.name} pela Play Store da TV"
        }
    }

    private const val TAG = "BruDreLauncher"
}
