package dev.yzy806806.meshdeskandroid

import android.content.Context
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Manages meshdesk binary updates from GitHub Releases.
 *
 * Design (user-confirmed):
 * - only the binary is updated; config.yaml / identity.pem are preserved
 * - shows changelog (release notes)
 * - keeps a backup of the previous binary for rollback
 * - manual check only (no auto-prompt)
 */
object MeshDeskUpdater {

    private const val RELEASES_API =
        "https://api.github.com/repos/yzy806806/meshdesk/releases"
    private const val ASSET_NAME = "meshdesk-linux-arm64"
    private const val BACKUP_SUFFIX = ".bak"

    /** Data class for a GitHub release. */
    data class Release(
        val tag: String,
        val body: String,
        val publishedAt: String,
        val assetUrl: String?,
    )

    /**
     * Fetches the latest N releases (default 5). Returns empty on failure.
     * Uses org.json (Android built-in) — robust against field ordering.
     */
    fun fetchReleases(limit: Int = 5): List<Release> {
        return try {
            val conn = URL("$RELEASES_API?per_page=$limit").openConnection() as HttpURLConnection
            conn.connectTimeout = 10000
            conn.readTimeout = 10000
            conn.setRequestProperty("User-Agent", "MeshDeskAndroid")
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            conn.disconnect()

            val root = org.json.JSONArray(body)
            val releases = mutableListOf<Release>()
            for (i in 0 until root.length()) {
                val rel = root.getJSONObject(i)
                val tag = rel.optString("tag_name", "")
                val body2 = rel.optString("body", "")
                val pub = rel.optString("published_at", "")
                // find arm64 asset within THIS release's assets array
                var assetUrl: String? = null
                val assets = rel.optJSONArray("assets")
                if (assets != null) {
                    for (j in 0 until assets.length()) {
                        val a = assets.getJSONObject(j)
                        if (a.optString("name", "") == ASSET_NAME) {
                            assetUrl = a.optString("browser_download_url", "")
                            break
                        }
                    }
                }
                releases.add(Release(tag, body2, pub, assetUrl))
            }
            releases
        } catch (e: Exception) {
            android.util.Log.e("MeshDeskUpdater", "fetchReleases failed", e)
            emptyList()
        }
    }

    /** True if a backup exists (can roll back). */
    fun hasBackup(): Boolean =
        RootShell.exec("ls -la ${MeshDeskDaemon.BIN}$BACKUP_SUFFIX 2>/dev/null").contains("meshdesk")

    /**
     * Normalizes a version string to a comparable tag, e.g.
     * "meshdesk v1.5.11" / "v1.5.11" / "v1.5.11 (commit...)" → "v1.5.11".
     */
    fun normalizeVersion(raw: String): String {
        val m = Regex("v\\d+\\.\\d+\\.\\d+").find(raw)
        return m?.value ?: raw.trim()
    }

    /**
     * Updates the binary: stop daemon → backup current → download new →
     * replace → restart. Preserves config/identity. Returns result message.
     */
    fun update(context: Context, release: Release): String {
        val assetUrl = release.assetUrl ?: return "该版本无 arm64 二进制"
        val wasRunning = MeshDeskDaemon.isRunning()

        // 1. stop daemon if running
        if (wasRunning) MeshDeskDaemon.stop()

        // 2. backup current binary (if exists)
        RootShell.exec(
            "[ -f ${MeshDeskDaemon.BIN} ] && cp ${MeshDeskDaemon.BIN} ${MeshDeskDaemon.BIN}$BACKUP_SUFFIX && echo BACKED || echo NO_OLD"
        )

        // 3. download to cache
        val cacheFile = File(context.cacheDir, "meshdesk-arm64-new")
        val conn = URL(assetUrl).openConnection() as HttpURLConnection
        conn.connectTimeout = 15000
        conn.readTimeout = 180000
        conn.setRequestProperty("User-Agent", "MeshDeskAndroid")
        conn.inputStream.use { input ->
            cacheFile.outputStream().use { output -> input.copyTo(output) }
        }
        conn.disconnect()
        if (!cacheFile.exists() || cacheFile.length() < 5_000_000) {
            return "下载失败（${cacheFile.length()} bytes）"
        }

        // 4. verify it's a real binary before replacing
        RootShell.exec("chmod 755 '${cacheFile.absolutePath}'")
        val ver = RootShell.exec("'${cacheFile.absolutePath}' --version 2>&1 | head -1")
        if (!ver.contains("meshdesk")) {
            return "下载的二进制校验失败: $ver"
        }

        // 5. install
        val install = RootShell.exec(
            "cp '${cacheFile.absolutePath}' ${MeshDeskDaemon.BIN} && chmod 755 ${MeshDeskDaemon.BIN} && echo OK"
        )
        if (!install.contains("OK")) return "安装失败: $install"

        // 6. restart if it was running
        if (wasRunning) {
            val start = MeshDeskDaemon.start()
            return "已更新到 ${release.tag}（校验: $ver），$start"
        }
        return "已更新到 ${release.tag}（校验: $ver），请手动启动"
    }

    /** Rolls back to the backup binary. Returns result message. */
    fun rollback(): String {
        if (!hasBackup()) return "没有可回滚的备份"
        val wasRunning = MeshDeskDaemon.isRunning()
        if (wasRunning) MeshDeskDaemon.stop()
        val out = RootShell.exec(
            "cp ${MeshDeskDaemon.BIN}$BACKUP_SUFFIX ${MeshDeskDaemon.BIN} && chmod 755 ${MeshDeskDaemon.BIN} && echo OK"
        )
        if (!out.contains("OK")) return "回滚失败: $out"
        if (wasRunning) {
            return "已回滚，${MeshDeskDaemon.start()}"
        }
        return "已回滚到备份版本"
    }
}
