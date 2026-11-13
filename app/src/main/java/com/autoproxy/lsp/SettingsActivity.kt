package com.autoproxy.lsp

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Checkbox
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.darkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme

class SettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        RunLog.init(this)
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 100)
        }
        setContent {
            val colors = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()
            MiuixTheme(colors = colors) {
                SettingsScreen()
            }
        }
    }
}

private data class AppEntry(val pkg: String, val label: String)

private fun loadApps(context: Context): List<AppEntry> {
    val pm = context.packageManager
    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    return pm.queryIntentActivities(intent, 0)
        .map { AppEntry(it.activityInfo.packageName, it.loadLabel(pm).toString()) }
        .filter { it.pkg != Consts.MODULE_PKG && it.pkg != Consts.CMFA_PKG }
        .distinctBy { it.pkg }
        .sortedBy { it.label.lowercase() }
}

@Composable
private fun SettingsScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { context.getSharedPreferences(Consts.PREFS_NAME, Context.MODE_PRIVATE) }

    var enabled by remember { mutableStateOf(prefs.getBoolean(Consts.KEY_ENABLED, true)) }
    var onlyOurs by remember { mutableStateOf(prefs.getBoolean(Consts.KEY_ONLY_OURS, true)) }
    var targets by remember {
        mutableStateOf(prefs.getStringSet(Consts.KEY_TARGETS, Consts.DEFAULT_TARGETS) ?: Consts.DEFAULT_TARGETS)
    }
    var graceText by remember { mutableStateOf(prefs.getInt(Consts.KEY_GRACE_SEC, Consts.DEFAULT_GRACE_SEC).toString()) }
    var idleText by remember { mutableStateOf(prefs.getInt(Consts.KEY_IDLE_KBPS, Consts.DEFAULT_IDLE_KBPS).toString()) }
    var apps by remember { mutableStateOf<List<AppEntry>>(emptyList()) }
    var vpnRunning by remember { mutableStateOf(false) }
    var daemonRunning by remember { mutableStateOf(false) }
    var bootInstalled by remember { mutableStateOf(false) }
    var daemonState by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var logText by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var appsExpanded by remember { mutableStateOf(false) }
    var appQuery by remember { mutableStateOf("") }
    var logExpanded by remember { mutableStateOf(false) }

    fun persist() {
        prefs.edit()
            .putBoolean(Consts.KEY_ENABLED, enabled)
            .putBoolean(Consts.KEY_ONLY_OURS, onlyOurs)
            .putStringSet(Consts.KEY_TARGETS, targets)
            .putInt(Consts.KEY_GRACE_SEC, graceText.toIntOrNull()?.coerceIn(5, 3600) ?: Consts.DEFAULT_GRACE_SEC)
            .putInt(Consts.KEY_IDLE_KBPS, idleText.toIntOrNull()?.coerceIn(1, 10240) ?: Consts.DEFAULT_IDLE_KBPS)
            .apply()
        CmfaControl.writeDaemonConf(context)
    }

    fun toast(msg: String) = Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()

    suspend fun pollStatus() {
        val state = withContext(Dispatchers.IO) { CmfaControl.readDaemonState() }
        val boot = withContext(Dispatchers.IO) { CmfaControl.bootScriptInstalled() }
        val daemon = withContext(Dispatchers.IO) { CmfaControl.keepaliveDaemonRunning() }
        daemonState = state
        bootInstalled = boot
        daemonRunning = daemon
        vpnRunning = CmfaControl.vpnActive(context)
    }

    fun refreshOnce() {
        scope.launch {
            pollStatus()
            logText = RunLog.read(context)
        }
    }

    fun toggleApp(pkg: String) {
        targets = if (pkg in targets) targets - pkg else targets + pkg
        persist()
    }

    fun installBoot() {
        if (busy) return
        busy = true
        scope.launch {
            val ok = withContext(Dispatchers.IO) { CmfaControl.installBootScript(context) }
            if (ok) withContext(Dispatchers.IO) { CmfaControl.startWatchdog() }
            busy = false
            toast(if (ok) "已安装开机自启，保活守护已启动（即时生效）" else "安装失败，请确认 Root 权限")
            refreshOnce()
        }
    }

    fun removeBoot() {
        if (busy) return
        busy = true
        scope.launch {
            val ok = withContext(Dispatchers.IO) { CmfaControl.removeBootScript() }
            busy = false
            toast(if (ok) "已移除开机自启并停止守护" else "移除失败，请确认 Root 权限")
            refreshOnce()
        }
    }

    fun grantVpn() {
        if (busy) return
        busy = true
        scope.launch {
            val ok = withContext(Dispatchers.IO) { CmfaControl.grantVpnPermission() }
            busy = false
            toast(if (ok) "已授予 CMFA VPN 权限" else "授权失败，请确认 Root 权限")
        }
    }

    fun testStart() {
        if (busy) return
        busy = true
        scope.launch {
            val ok = if (CmfaControl.startDirect(context)) true
            else withContext(Dispatchers.IO) { CmfaControl.startViaRoot() }
            busy = false
            toast(if (ok) "已发送启动代理指令" else "启动失败")
            refreshOnce()
        }
    }

    fun testStop() {
        if (busy) return
        busy = true
        scope.launch {
            val ok = withContext(Dispatchers.IO) { CmfaControl.stopViaRoot() }
            busy = false
            toast(if (ok) "已发送停止代理指令" else "停止失败")
            refreshOnce()
        }
    }

    fun labelFor(pkg: String): String =
        apps.firstOrNull { it.pkg == pkg }?.label ?: when (pkg) {
            "com.google.android.apps.bard" -> "Gemini"
            "com.openai.chatgpt" -> "ChatGPT"
            else -> pkg
        }

    LaunchedEffect(Unit) {
        apps = withContext(Dispatchers.IO) { loadApps(context) }
        logText = RunLog.read(context)
        while (isActive) {
            pollStatus()
            delay(3000)
        }
    }

    Scaffold(topBar = { TopAppBar(title = "无感代理", largeTitle = "无感代理") }) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(top = 4.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                StatusCard(vpnRunning, daemonRunning, bootInstalled, daemonState, ::labelFor)
            }
            item {
                Section("总开关") {
                    SwitchRow("启用自动代理", "关闭后守护进程不再自动启停代理", enabled) {
                        enabled = it
                        persist()
                    }
                }
            }
            item {
                Section("监控的应用") {
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable { appsExpanded = !appsExpanded }
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("选择监控应用")
                            Text(
                                if (targets.isEmpty()) "未勾选任何应用"
                                else "已勾选 ${targets.size} 个：" + targets.joinToString("、") { labelFor(it) },
                                fontSize = 12.sp,
                                maxLines = 1,
                                color = MiuixTheme.colorScheme.onSurfaceContainerVariant,
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Text(if (appsExpanded) "收起" else "展开", color = MiuixTheme.colorScheme.primary)
                    }
                    AnimatedVisibility(
                        visible = appsExpanded,
                        enter = expandVertically(tween(220, easing = FastOutSlowInEasing)) + fadeIn(tween(220)),
                        exit = shrinkVertically(tween(180, easing = FastOutSlowInEasing)) + fadeOut(tween(180)),
                    ) {
                        Column {
                            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                            TextField(
                                value = appQuery,
                                onValueChange = { appQuery = it },
                                label = "搜索应用（名称或包名）",
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                            )
                            if (apps.isEmpty()) {
                                Text(
                                    "正在加载应用列表…",
                                    modifier = Modifier.padding(16.dp),
                                    color = MiuixTheme.colorScheme.onSurfaceContainerVariant,
                                )
                            } else {
                                val q = appQuery.trim()
                                val filtered = if (q.isEmpty()) apps else apps.filter {
                                    it.label.contains(q, ignoreCase = true) || it.pkg.contains(q, ignoreCase = true)
                                }
                                if (filtered.isEmpty()) {
                                    Text(
                                        "无匹配应用",
                                        modifier = Modifier.padding(16.dp),
                                        color = MiuixTheme.colorScheme.onSurfaceContainerVariant,
                                    )
                                } else {
                                    LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
                                        items(filtered, key = { it.pkg }) { app ->
                                            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                                            AppRow(app, app.pkg in targets) { toggleApp(app.pkg) }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            item {
                Section("参数") {
                    TextField(
                        value = graceText,
                        onValueChange = { graceText = it; persist() },
                        label = "宽限期（秒，5-3600）",
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                    )
                    TextField(
                        value = idleText,
                        onValueChange = { idleText = it; persist() },
                        label = "空闲阈值（KB/s，1-10240）",
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                    )
                    SwitchRow("仅关闭本模块启动的代理", "手动开启的代理空闲后不自动关闭", onlyOurs) {
                        onlyOurs = it
                        persist()
                    }
                }
            }
            item {
                Section("守护进程") {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(
                            "基于 Root 守护进程，开机自启、免系统冻结。安装后立即生效，无需 LSPosed。",
                            fontSize = 13.sp,
                            color = MiuixTheme.colorScheme.onSurfaceContainerVariant,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Button(onClick = { installBoot() }, enabled = !busy, modifier = Modifier.weight(1f)) {
                                Text("安装 / 启动")
                            }
                            Button(onClick = { removeBoot() }, enabled = !busy, modifier = Modifier.weight(1f)) {
                                Text("移除 / 停止")
                            }
                        }
                    }
                }
            }
            item {
                Section("工具") {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Button(onClick = { grantVpn() }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                            Text("授予 CMFA VPN 权限")
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Button(onClick = { testStart() }, enabled = !busy, modifier = Modifier.weight(1f)) {
                                Text("测试启动")
                            }
                            Button(onClick = { testStop() }, enabled = !busy, modifier = Modifier.weight(1f)) {
                                Text("测试停止")
                            }
                        }
                    }
                }
            }
            item {
                Section("运行日志") {
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable { logExpanded = !logExpanded }
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("查看运行日志")
                            Text(
                                "最近 30 条，可清空",
                                fontSize = 12.sp,
                                color = MiuixTheme.colorScheme.onSurfaceContainerVariant,
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Text(if (logExpanded) "收起" else "展开", color = MiuixTheme.colorScheme.primary)
                    }
                    AnimatedVisibility(
                        visible = logExpanded,
                        enter = expandVertically(tween(220, easing = FastOutSlowInEasing)) + fadeIn(tween(220)),
                        exit = shrinkVertically(tween(180, easing = FastOutSlowInEasing)) + fadeOut(tween(180)),
                    ) {
                        Column {
                            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                            Column(
                                modifier = Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Button(onClick = { logText = RunLog.read(context) }, modifier = Modifier.weight(1f)) {
                                        Text("刷新")
                                    }
                                    Button(
                                        onClick = {
                                            RunLog.clear(context)
                                            logText = RunLog.read(context)
                                            toast("日志已清空")
                                        },
                                        modifier = Modifier.weight(1f),
                                    ) {
                                        Text("清空")
                                    }
                                }
                                Text(
                                    logText.ifEmpty { "（暂无日志）" },
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp,
                                    color = MiuixTheme.colorScheme.onSurfaceContainerVariant,
                                    modifier = Modifier.fillMaxWidth().heightIn(max = 300.dp)
                                        .verticalScroll(rememberScrollState()),
                                )
                            }
                        }
                    }
                }
            }
            item {
                Section("使用说明") {
                    Text(
                        stringResource(R.string.help_text),
                        modifier = Modifier.padding(16.dp),
                        fontSize = 13.sp,
                        color = MiuixTheme.colorScheme.onSurfaceContainerVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        SmallTitle(text = title, insideMargin = PaddingValues(start = 20.dp, top = 8.dp, bottom = 6.dp))
        Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp), content = content)
    }
}

@Composable
private fun StatusCard(
    vpnRunning: Boolean,
    daemonRunning: Boolean,
    bootInstalled: Boolean,
    state: Map<String, String>,
    labelFor: (String) -> String,
) {
    val green = Color(0xFF34C759)
    val variant = MiuixTheme.colorScheme.onSurfaceContainerVariant
    val primary = MiuixTheme.colorScheme.primary
    Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            StatusRow("代理（VPN）", if (vpnRunning) "运行中" else "未运行", if (vpnRunning) green else variant)
            StatusRow("守护进程", if (daemonRunning) "运行中" else "未运行", if (daemonRunning) green else variant)
            StatusRow("开机自启", if (bootInstalled) "已安装" else "未安装", if (bootInstalled) green else variant)
            val fg = state["fg"]?.trim().orEmpty()
            if (fg.isNotEmpty()) {
                StatusRow("前台应用", fg.split(" ").filter { it.isNotEmpty() }.joinToString("、") { labelFor(it) }, primary)
            }
            if (state["ingrace"] == "1") {
                StatusRow("宽限期剩余", "${state["remain"] ?: "0"} 秒", primary)
                StatusRow("后台速率", "${state["rate"] ?: "0"} KB/s", variant)
            }
        }
    }
}

@Composable
private fun StatusRow(label: String, value: String, valueColor: Color) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = MiuixTheme.colorScheme.onSurfaceContainerVariant)
        Text(value, color = valueColor, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun SwitchRow(title: String, subtitle: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title)
            Text(subtitle, fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceContainerVariant)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun AppRow(app: AppEntry, checked: Boolean, onToggle: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(state = ToggleableState(checked), onClick = null)
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(app.label, maxLines = 1)
            Text(app.pkg, fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceContainerVariant, maxLines = 1)
        }
    }
}