# 无感代理 · AutoProxyLSP

基于 **Root** 的「前台应用感知」代理自动启停工具：打开被监控的应用时自动拉起 **CMFA**（Clash Meta for Android）代理，应用全部退到后台且流量空闲后自动关闭。**无需 LSPosed / Xposed**，不受 HyperOS / MIUI 的进程冻结与自启动拦截影响。

- 应用包名：`com.autoproxy.lsp`
- 当前版本：**v1.4.2**（versionCode 9）
- 界面：Jetpack Compose + MIUIX 组件

---

## 特性

- **100% Root 原生守护进程**：以 `setsid` 独立 root 进程运行（PPID=1），2 秒轮询前台应用，免疫 HyperOS 应用冻结/查杀。
- **自动启停代理**：被监控应用进入前台 → 启动 CMFA；全部退到后台 → 进入宽限期 → 流量持续空闲 → 关闭 CMFA。
- **宽限期 + 流量空闲判定**：退到后台后不立即关闭，宽限期内若流量高于阈值则延长保留，避免频繁启停。
- **仅关闭本应用启动的代理**（可选）：开启后，你手动打开的代理不会被自动关闭。
- **开机自启**：安装守护脚本到 `/data/adb/service.d/`，每次开机自动拉起。
- **MIUIX 现代界面**：可折叠 / 可搜索的应用列表、实时状态卡片、可清空的运行日志、展开收起动画。
- **通知反射**：Java 前台服务仅用于把守护状态映射为系统通知，不参与控制逻辑。

## 工作原理

```
被监控应用进入前台
        │  (root 守护进程每 2s 轮询 dumpsys 前台)
        ▼
   启动 CMFA 代理 ──► 应用退到后台 ──► 宽限期(grace)
                                          │
                          流量 > 阈值 ──► 延长保留
                          持续空闲(idle) ──► 关闭 CMFA 代理
```

- 守护脚本：`app/src/main/res/raw/autoproxy_daemon.sh`，安装后位于 `/data/adb/service.d/autoproxy_lsp.sh`。
- 通过 CMFA 的外部控制 Intent 启停：
  - 启动 `com.github.metacubex.clash.meta.action.START_CLASH`
  - 停止 `com.github.metacubex.clash.meta.action.STOP_CLASH`

## 环境要求

| 项目 | 要求 |
| --- | --- |
| Root | 已 Root（推荐 **KernelSU**；实测 HyperOS / MIUI，机型 2509FPN0BC） |
| 系统 | Android 8.1+（minSdk 27，targetSdk 34） |
| 配套代理 | **CMFA**（Clash Meta for Android，包名 `com.github.metacubex.clash.meta`；实测 v2.11.37 arm64） |
| 框架 | 无需 LSPosed / Xposed |

CMFA 下载：<https://github.com/MetaCubeX/ClashMetaForAndroid/releases>

## 安装与使用

1. 安装本应用 APK 与 CMFA，并在 CMFA 中导入你的订阅/配置。
2. 打开「无感代理」→ **工具** → **授予 CMFA VPN 免确认权限**（需 Root）。
3. **守护进程** → **安装开机自启并启动**（需 Root）。守护进程立即启动，并在每次开机后自动拉起；「移除开机自启」可撤销。
4. 在 **监控的应用** 中勾选需要代理的应用（默认 **Gemini**、**ChatGPT**）。列表支持折叠与按名称/包名搜索。
5. 打开被监控应用 → 自动启动代理；全部退到后台并空闲 → 自动关闭。
6. **MIUI / HyperOS 额外设置**：允许 CMFA「后台弹出界面」与「自启动」，否则 Root 启动代理可能失败。

> 默认监控应用：Gemini (`com.google.android.apps.bard`)、ChatGPT (`com.openai.chatgpt`)。

## 参数说明

| 参数 | 默认值 | 说明 |
| --- | --- | --- |
| 宽限期 `grace_sec` | 60 秒（可调 5–3600） | 应用退到后台后保留代理的最短时间 |
| 空闲阈值 `idle_kbps` | 20 KB/s（可调 1–10240） | 宽限期内流量低于此值才判定为空闲 |
| 仅关闭本应用启动的代理 `only_ours` | 关 | 开启后不关闭你手动启动的代理 |
| 轮询间隔 | 2 秒 | 守护进程前台检测周期 |
| 空闲确认 | 30 秒 | 持续空闲达到此时长后关闭代理 |

## 从源码构建

工具链：**JDK 17** · **Gradle 9.3.1**（wrapper）· **AGP 9.1.0** · **Kotlin 2.3.20** · **Compose Multiplatform 1.10.2** · **MIUIX 0.8.7**

```bash
# 调试包
./gradlew assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

> `gradle.properties` 中的 `android.builtInKotlin=false` 与 `android.newDsl=false` 是 AGP 9 + KGP 2.3 组合所必需的兼容开关；`android.overridePathCheck=true` 用于允许非 ASCII 工程路径。

## 项目结构

```
app/src/main/java/com/autoproxy/lsp/
├── SettingsActivity.kt        # MIUIX Compose 设置界面
├── CmfaControl.kt             # Root 启停 CMFA / 安装守护脚本 / 读写配置
├── WatchdogService.kt         # 前台服务：把守护状态映射为通知
├── RunLog.kt                  # 运行日志（最近 30 条，可清空）
└── Consts.kt                  # 常量与默认值
app/src/main/res/raw/
└── autoproxy_daemon.sh        # Root 守护脚本（前台轮询 + 启停/宽限/空闲）
```

### 关键路径（Root）

| 用途 | 路径 |
| --- | --- |
| 守护脚本 | `/data/adb/service.d/autoproxy_lsp.sh` |
| 守护状态 | `/data/local/tmp/autoproxy_lsp.state` |
| PID 文件 | `/data/local/tmp/autoproxy_lsp.pid` |
| 配置 | `/data/data/com.autoproxy.lsp/files/autoproxy.conf` |
| 运行日志 | `/data/data/com.autoproxy.lsp/files/run.log` |

## 注意

- 本应用需要 Root 权限来安装守护脚本与启停 CMFA；请自行评估 Root 与代理使用风险。
- 守护进程为原生 root 进程，卸载应用前请先在界面「移除开机自启」以清理脚本。