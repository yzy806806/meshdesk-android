package dev.yzy806806.meshdeskandroid

import android.util.Log
import java.util.concurrent.TimeUnit

/**
 * Root shell helper: runs commands via Magisk su (stdin-fed, most reliable).
 * All daemon management goes through here.
 */
object RootShell {

    private const val TAG = "RootShell"

    // su lives at different paths on different ROMs
    private val suPaths = listOf("/system/bin/su", "/product/bin/su", "/system/xbin/su", "/sbin/su", "su")

    private fun suBinary(): String? =
        suPaths.firstOrNull { path ->
            try {
                val p = Runtime.getRuntime().exec(arrayOf(path))
                p.outputStream.bufferedWriter().use { it.write("id\n"); it.flush() }
                val out = p.inputStream.bufferedReader().use { it.readText() }
                p.waitFor()
                out.contains("uid=0")
            } catch (_: Exception) { false }
        }

    fun isAvailable(): Boolean = suBinary() != null

    /**
     * Executes a command as root, feeding via stdin. Returns trimmed output.
     */
    fun exec(command: String, timeoutSec: Long = 30): String {
        val su = suBinary() ?: return ""
        return try {
            val process = ProcessBuilder(su)
                .redirectErrorStream(true)
                .start()
            process.outputStream.bufferedWriter().use { it.write("$command\n"); it.flush() }
            val output = process.inputStream.bufferedReader().use { it.readText() }
            process.waitFor(timeoutSec, TimeUnit.SECONDS)
            output.trim()
        } catch (e: Exception) {
            Log.w(TAG, "exec failed: ${e.message}")
            ""
        }
    }
}
