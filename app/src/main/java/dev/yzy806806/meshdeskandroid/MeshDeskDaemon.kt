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

    /**
     * Android adapter: ensures the policy-routing rule so mesh-subnet traffic
     * goes to the TUN instead of the default gateway (Android's per-network
     * ip rules outrank the main table — without this, TX is blackholed).
     * Idempotent: skips if the rule already exists.
     */
    fun ensurePolicyRule(): String {
        val cidr = RootShell.exec("grep '^  mesh_cidr:' $CONFIG 2>/dev/null | awk '{print \$2}'")
            .trim().ifEmpty { "10.100.0.0/24" }
        val cmd = buildString {
            append("ip rule show | grep -q '^500:' || ")
            append("ip rule add pref 500 from all to $cidr lookup main; ")
            append("ip route flush cache; ")
            append("ip rule show | grep 500")
        }
        val out = RootShell.exec(cmd)
        return if (out.contains("500")) "策略路由 OK ($cidr)" else "策略路由失败: $out"
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
        // verify + ensure policy rule (App-level Android adapter)
        val rule = ensurePolicyRule()
        return if (isRunning()) {
            "启动成功，$rule"
        } else {
            "启动失败: $out"
        }
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

    // ── Boot autostart (Magisk module, written by the app as root) ──────────

    private const val MODULE_DIR = "/data/adb/modules/meshdesk-autostart"

    /** True if the autostart Magisk module is installed. */
    fun isAutostartInstalled(): Boolean =
        RootShell.exec("ls $MODULE_DIR/module.prop 2>/dev/null").contains("module.prop")

    /**
     * Installs a Magisk module that starts the daemon on boot (root layer,
     * independent of the app being alive). Takes effect after reboot.
     */
    fun installAutostart(): String {
        val moduleProp = "id=meshdesk-autostart\n" +
            "name=MeshDesk autostart\n" +
            "version=v1.0\nversionCode=1\n" +
            "author=yzy806806\n" +
            "description=Start meshdesk daemon on boot + ensure policy route\n"
        val serviceSh = "#!/system/bin/sh\n" +
            "# start meshdesk daemon on boot + policy route\n" +
            "sleep 5\n" +
            "[ -f ${MeshDeskDaemon.BIN} ] || exit 0\n" +
            "env -i PATH=/sbin:/system/bin:/system/xbin:/system/sbin " +
            "nohup ${MeshDeskDaemon.BIN} --config ${MeshDeskDaemon.CONFIG} " +
            "> ${MeshDeskDaemon.LOG} 2>&1 &\n" +
            "sleep 2\n" +
            "ip rule add pref 500 from all to 10.100.0.0/24 lookup main 2>/dev/null\n" +
            "exit 0\n"

        val cmd = buildString {
            append("mkdir -p $MODULE_DIR && ")
            append("printf '%s' '${moduleProp.replace("'", "'\\''")}' > $MODULE_DIR/module.prop && ")
            append("printf '%s' '${serviceSh.replace("'", "'\\''")}' > $MODULE_DIR/service.sh && ")
            append("chmod 755 $MODULE_DIR/service.sh && echo INSTALLED")
        }
        val out = RootShell.exec(cmd)
        return if (out.contains("INSTALLED")) {
            "自启模块已安装，重启手机后生效"
        } else {
            "安装失败: $out"
        }
    }

    /** Removes the autostart module. */
    fun removeAutostart(): String {
        val out = RootShell.exec("rm -rf $MODULE_DIR && echo REMOVED")
        return if (out.contains("REMOVED")) "自启模块已移除" else "移除失败: $out"
    }
}
