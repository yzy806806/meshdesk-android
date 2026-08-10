package dev.yzy806806.meshdeskandroid

/**
 * Manages the meshdesk daemon on Android (root, native, no chroot).
 *
 * Layout:
 *   /data/adb/meshdesk/meshdesk        — binary
 *   /data/adb/meshdesk/config.yaml     — config
 *   /data/adb/meshdesk/identity.pem    — identity key (generated)
 *   /data/adb/meshdesk/meshdesk.pid    — pid file
 *   /data/adb/meshdesk/meshdesk.log    — daemon log
 *
 * Daemon runs with a clean env (env -i PATH=...) per the chroot lessons:
 * Android PATH leaking into the daemon caused weird issues before.
 */
object MeshDeskDaemon {

    const val DIR = "/data/adb/meshdesk"
    const val BIN = "$DIR/meshdesk"
    const val CONFIG = "$DIR/config.yaml"
    const val IDENTITY = "$DIR/identity.pem"
    const val PID = "$DIR/meshdesk.pid"
    const val LOG = "$DIR/meshdesk.log"

    private const val TAG = "MeshDeskDaemon"

    /** True if the binary is installed. */
    fun isInstalled(): Boolean =
        RootShell.exec("ls -la $BIN 2>/dev/null").contains("meshdesk")

    /** Installed binary version (from `--version`). */
    fun version(): String =
        RootShell.exec("$BIN --version 2>&1 | head -1").trim()

    /** True if the daemon is running (pid file + kill -0). */
    fun isRunning(): Boolean {
        val alive = RootShell.exec(
            "cat $PID 2>/dev/null | xargs -r kill -0 2>/dev/null && echo yes"
        )
        return alive.contains("yes")
    }

    /** Starts the daemon with a clean env. Returns combined output. */
    fun start(): String {
        val cmd = buildString {
            append("mkdir -p $DIR && ")
            append("chmod 755 $BIN && ")
            // clean env: only essential PATH; no Android vars leaking in
            append("env -i PATH=/sbin:/system/bin:/system/xbin:/system/sbin ")
            append("nohup $BIN --config $CONFIG > $LOG 2>&1 & ")
            append("echo \$! > $PID && echo STARTED")
        }
        val out = RootShell.exec(cmd)
        // verify
        return if (isRunning()) "启动成功 (pid $(cat $PID 2>/dev/null))" else "启动失败: $out"
    }

    /** Stops the daemon. */
    fun stop(): String {
        val out = RootShell.exec(
            "kill \$(cat $PID 2>/dev/null) 2>/dev/null; rm -f $PID; echo STOPPED"
        )
        return out
    }

    /** Generates a fresh identity keypair if missing. */
    fun ensureIdentity(): String =
        RootShell.exec("$BIN -gen-key 2>&1 | head -3")

    /** Reads current config. */
    fun readConfig(): String = RootShell.exec("cat $CONFIG 2>/dev/null")

    /** Writes config (via temp file). */
    fun writeConfig(content: String): Boolean {
        val tmp = "$CONFIG.tmp"
        val out = RootShell.exec(
            "echo '${content.replace("'", "'\\''")}' > $tmp && chmod 600 $tmp && mv $tmp $CONFIG && echo OK"
        )
        return out.contains("OK")
    }

    /** Reads the tail of the daemon log. */
    fun logTail(lines: Int = 30): String =
        RootShell.exec("tail -$lines $LOG 2>/dev/null")

    /** Queries the dashboard API (via root curl if available, else localhost). */
    fun stats(): String {
        // meshdesk --web binds :52888; query locally
        return RootShell.exec(
            "curl -s --max-time 5 http://127.0.0.1:52888/api/stats 2>/dev/null || " +
                "toybox wget -qO- http://127.0.0.1:52888/api/stats 2>/dev/null || echo '{}'"
        )
    }
}
