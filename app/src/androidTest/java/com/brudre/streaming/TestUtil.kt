package com.brudre.streaming

import androidx.test.platform.app.InstrumentationRegistry

object TestUtil {
    val app: BruDreApp get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as BruDreApp
    val services: Services get() = app.services

    fun waitFor(timeoutMs: Long, what: String, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (runCatching(condition).getOrDefault(false)) return
            Thread.sleep(250)
        }
        throw AssertionError("Tempo esgotado esperando: $what")
    }

    fun shell(cmd: String): String {
        val pfd = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(cmd)
        return android.os.ParcelFileDescriptor.AutoCloseInputStream(pfd).bufferedReader().use { it.readText() }
    }

    fun log(msg: String) = android.util.Log.i("BruDreTest", msg)

    /** Captura a tela do emulador (validação visual). Feita pelo shell para sobreviver à desinstalação do app de teste. */
    fun shot(name: String) {
        runCatching {
            shell("mkdir -p /sdcard/brudre_shots")
            shell("screencap -p /sdcard/brudre_shots/$name.png")
            log("screenshot: $name")
        }.onFailure { log("screenshot falhou: $name ${it.message}") }
    }
}
