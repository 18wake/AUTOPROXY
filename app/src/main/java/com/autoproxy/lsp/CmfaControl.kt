package com.autoproxy.lsp

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import java.io.File
import java.util.concurrent.TimeUnit

object CmfaControl {

    private const val BOOT_SCRIPT_PATH = "/data/adb/service.d/autoproxy_lsp.sh"
    private const val DAEMON_PIDFILE = "/data/local/tmp/autoproxy_lsp.pid"
    private const val STATE_PATH = "/data/local/tmp/autoproxy_lsp.state"
    private const val CONF_NAME = "autoproxy.conf"

    fun vpnActive(context: Context): Boolean = try {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
    } catch (e: Exception) {
        false
    }

    private fun controlIntent(action: String): Intent =
        Intent(action)
            .setClassName(Consts.CMFA_PKG, Consts.CMFA_ACTIVITY)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun startDirect(context: Context): Boolean = try {
        context.startActivity(controlIntent(Consts.CMFA_ACTION_START))
        true
    } catch (e: Exception) {
        Log.w("AutoProxyLSP", "startDirect failed", e)
        false
    }

    fun startViaRoot(): Boolean =
        shell("am start -n ${Consts.CMFA_PKG}/${Consts.CMFA_ACTIVITY} -a ${Consts.CMFA_ACTION_START}")

    fun stopViaRoot(): Boolean =
        shell("am start -n ${Consts.CMFA_PKG}/${Consts.CMFA_ACTIVITY} -a ${Consts.CMFA_ACTION_STOP}")

    fun startWatchdog(): Boolean =
        shell("am start-foreground-service -n ${Consts.MODULE_PKG}/${Consts.WATCHDOG_CLASS}")

    fun grantVpnPermission(): Boolean =
        shell("appops set ${Consts.CMFA_PKG} ACTIVATE_VPN allow")

    fun writeDaemonConf(context: Context) {
        try {
            val prefs = context.getSharedPreferences(Consts.PREFS_NAME, Context.MODE_PRIVATE)
            val enabled = if (prefs.getBoolean(Consts.KEY_ENABLED, true)) "1" else "0"
            val targets = (prefs.getStringSet(Consts.KEY_TARGETS, Consts.DEFAULT_TARGETS)
                ?: Consts.DEFAULT_TARGETS).joinToString(" ")
            val grace = prefs.getInt(Consts.KEY_GRACE_SEC, Consts.DEFAULT_GRACE_SEC)
            val idle = prefs.getInt(Consts.KEY_IDLE_KBPS, Consts.DEFAULT_IDLE_KBPS)
            val onlyOurs = if (prefs.getBoolean(Consts.KEY_ONLY_OURS, true)) "1" else "0"
            File(context.filesDir, CONF_NAME).writeText(
                "enabled=$enabled\ntargets=$targets\ngrace=$grace\nidle=$idle\nonlyours=$onlyOurs\n"
            )
        } catch (e: Exception) {
            Log.w("AutoProxyLSP", "writeDaemonConf failed", e)
        }
    }

    fun readDaemonState(): Map<String, String> {
        val out = shellOutput("cat $STATE_PATH") ?: return emptyMap()
        val map = mutableMapOf<String, String>()
        for (line in out.lineSequence()) {
            val idx = line.indexOf('=')
            if (idx > 0) map[line.substring(0, idx).trim()] = line.substring(idx + 1).trim()
        }
        return map
    }

    fun installBootScript(context: Context): Boolean {
        return try {
            val script = context.resources.openRawResource(R.raw.autoproxy_daemon)
                .bufferedReader().use { it.readText() }
            val f = File(context.filesDir, "autoproxy_lsp.sh")
            f.writeText(script)
            shell("cp ${f.absolutePath} $BOOT_SCRIPT_PATH && chmod 755 $BOOT_SCRIPT_PATH")
            writeDaemonConf(context)
            val installed = bootScriptInstalled()
            if (installed) startKeepaliveDaemon()
            installed
        } catch (e: Exception) {
            Log.w("AutoProxyLSP", "installBootScript failed", e)
            false
        }
    }

    fun startKeepaliveDaemon(): Boolean =
        shell("setsid sh $BOOT_SCRIPT_PATH >/dev/null 2>&1 </dev/null &")

    fun keepaliveDaemonRunning(): Boolean =
        shell("test -f $DAEMON_PIDFILE && kill -0 \$(cat $DAEMON_PIDFILE) 2>/dev/null")

    fun removeBootScript(): Boolean {
        shell("if [ -f $DAEMON_PIDFILE ]; then kill \$(cat $DAEMON_PIDFILE) 2>/dev/null; rm -f $DAEMON_PIDFILE; fi")
        shell("rm -f $STATE_PATH /data/local/tmp/autoproxy_ours")
        return shell("rm -f $BOOT_SCRIPT_PATH")
    }

    fun bootScriptInstalled(): Boolean = shell("test -f $BOOT_SCRIPT_PATH")

    fun shell(cmd: String): Boolean = try {
        val process = Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
        val finished = process.waitFor(15, TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            false
        } else {
            process.exitValue() == 0
        }
    } catch (e: Exception) {
        false
    }

    fun shellOutput(cmd: String): String? = try {
        val process = ProcessBuilder("su", "-c", cmd).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        val finished = process.waitFor(15, TimeUnit.SECONDS)
        when {
            !finished -> {
                process.destroyForcibly()
                null
            }
            process.exitValue() == 0 -> output
            else -> null
        }
    } catch (e: Exception) {
        null
    }
}