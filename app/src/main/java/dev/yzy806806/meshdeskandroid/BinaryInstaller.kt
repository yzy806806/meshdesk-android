package dev.yzy806806.meshdeskandroid

import android.content.Context
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Downloads the meshdesk arm64 binary from GitHub Releases and installs it
 * to /data/adb/meshdesk/. Uses the latest release by default.
 */
object BinaryInstaller {

    private const val RELEASE_API =
        "https://api.github.com/repos/yzy806806/meshdesk/releases/latest"
    private const val ASSET_NAME = "meshdesk-linux-arm64"

    /**
     * Returns the latest release tag + asset download URL, or null on failure.
     */
    fun latestReleaseInfo(): Pair<String, String>? {
        return try {
            val conn = URL(RELEASE_API).openConnection() as HttpURLConnection
            conn.connectTimeout = 10000
            conn.readTimeout = 10000
            conn.setRequestProperty("User-Agent", "MeshDeskAndroid")
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            conn.disconnect()
            val tag = Regex("\"tag_name\":\\s*\"([^\"]+)\"").find(body)?.groupValues?.get(1)
                ?: return null
            // find the arm64 asset browser_download_url
            val urlRe = Regex(
                "\"name\":\\s*\"$ASSET_NAME\",[^}]*?\"browser_download_url\":\\s*\"([^\"]+)\""
            ).find(body)?.groupValues?.get(1)
                ?: return null
            tag to urlRe
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Downloads the binary to app cache, then installs via root to
     * /data/adb/meshdesk/meshdesk. Returns a user-facing result.
     */
    fun install(context: Context): String {
        val info = latestReleaseInfo() ?: return "获取最新版本失败（网络或 API 问题）"
        val (tag, url) = info

        val cacheFile = File(context.cacheDir, "meshdesk-arm64")
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15000
        conn.readTimeout = 120000
        conn.setRequestProperty("User-Agent", "MeshDeskAndroid")
        conn.inputStream.use { input ->
            cacheFile.outputStream().use { output -> input.copyTo(output) }
        }
        conn.disconnect()

        if (!cacheFile.exists() || cacheFile.length() < 5_000_000) {
            return "下载失败或文件不完整（${cacheFile.length()} bytes）"
        }

        // install via root
        val out = RootShell.exec(
            "mkdir -p ${MeshDeskDaemon.DIR} && " +
                "cp '${cacheFile.absolutePath}' ${MeshDeskDaemon.BIN} && " +
                "chmod 755 ${MeshDeskDaemon.BIN} && echo INSTALLED"
        )
        return if (out.contains("INSTALLED")) {
            "已安装 $tag（${cacheFile.length() / 1024 / 1024}MB）"
        } else {
            "安装失败: $out"
        }
    }
}
