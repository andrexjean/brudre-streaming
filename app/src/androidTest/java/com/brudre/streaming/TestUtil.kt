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
}
