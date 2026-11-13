package com.autoproxy.lsp

object Consts {
    const val MODULE_PKG = "com.autoproxy.lsp"
    const val WATCHDOG_CLASS = "com.autoproxy.lsp.WatchdogService"

    const val CMFA_PKG = "com.github.metacubex.clash.meta"
    const val CMFA_ACTIVITY = "com.github.kr328.clash.ExternalControlActivity"
    const val CMFA_ACTION_START = "com.github.metacubex.clash.meta.action.START_CLASH"
    const val CMFA_ACTION_STOP = "com.github.metacubex.clash.meta.action.STOP_CLASH"

    const val PREFS_NAME = "config"
    const val KEY_ENABLED = "enabled"
    const val KEY_TARGETS = "targets"
    const val KEY_GRACE_SEC = "grace_sec"
    const val KEY_IDLE_KBPS = "idle_kbps"
    const val KEY_ONLY_OURS = "only_ours"

    val DEFAULT_TARGETS = setOf("com.google.android.apps.bard", "com.openai.chatgpt")
    const val DEFAULT_GRACE_SEC = 60
    const val DEFAULT_IDLE_KBPS = 20

    const val TICK_MS = 2_000L
    const val IDLE_CONFIRM_SEC = 30
}