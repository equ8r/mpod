package com.circpros.micahpod

import android.app.Application
import android.util.Log
import com.circpros.micahpod.data.ServiceLocator
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

class MicahPodApp : Application() {
    override fun onCreate() {
        super.onCreate()
        installCrashLogger()
        // Do not touch CastContext here. OptionsProvider is declared in the manifest;
        // Cast is initialized lazily from PlayerFragment / MicahPodMediaService so a
        // Cast/Play Services failure cannot take down cold start.
        try {
            ServiceLocator.init(this)
        } catch (t: Throwable) {
            Log.e(TAG, "ServiceLocator.init failed", t)
            writeCrashLog(t)
            throw t
        }
    }

    private fun installCrashLogger() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Log.e(TAG, "Uncaught exception on thread=${thread.name}", throwable)
            writeCrashLog(throwable)
            previous?.uncaughtException(thread, throwable)
        }
    }

    private fun writeCrashLog(throwable: Throwable) {
        try {
            val sw = StringWriter()
            throwable.printStackTrace(PrintWriter(sw))
            val stack = sw.toString()
            val text = buildString {
                appendLine(System.currentTimeMillis().toString())
                appendLine(stack)
            }
            File(filesDir, CRASH_LOG_NAME).writeText(text)
            // Also append a short marker into the playback log so "Submit history"
            // includes evidence even if last_crash.txt is overlooked.
            runCatching {
                com.circpros.micahpod.playback.PlaybackLogger.log(
                    context = this,
                    event = "crash",
                    reason = throwable.javaClass.simpleName,
                    extra = stack.take(1500)
                )
            }
        } catch (_: Exception) {
            // Best-effort only — never mask the original crash.
        }
    }

    companion object {
        private const val TAG = "MicahPodApp"
        const val CRASH_LOG_NAME = "last_crash.txt"
    }
}
