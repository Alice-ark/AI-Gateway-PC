package dev.aigw.cli

import dev.aigw.core.gateway.GatewayEngine
import dev.aigw.core.gateway.GatewaySettings
import dev.aigw.core.gateway.LoginOutcome
import dev.aigw.core.provider.DeviceAuthPoll
import dev.aigw.core.store.FileKeyValueStore
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 命令行入口。用途是在本机（容器内）直接起网关做端到端联调，
 * 以及不打开 App 也能导入账号 / 签到 / 看用量。
 *
 * 用法：
 *   aigw serve [--port 8790] [--data DIR]
 *   aigw providers
 *   aigw accounts [--provider ID]
 *   aigw credits [--provider ID]
 *   aigw checkin --provider ID [--uid UID]
 *   aigw login --provider ID <回调链接或凭证 JSON>
 *   aigw sms --provider ID --phone P [--msgid M --code C]
 *   aigw models
 *   aigw usage
 *   aigw logs
 */
fun main(args: Array<String>) {
    val command = args.firstOrNull() ?: "help"
    val rest = args.drop(1)
    val dataDir = File(option(rest, "--data") ?: defaultDataDir())
    if (!dataDir.exists()) dataDir.mkdirs()

    val engine = GatewayEngine(FileKeyValueStore(dataDir), onLog = { println("[log] $it") })
    // uid 口径变更后的自愈（如小浣熊早期把 JWT 的 iss 当 uid）：一次性命令同步跑，拿到的就是修好的账号
    engine.migrateAccounts()

    when (command) {
        "serve" -> serve(engine, rest)
        "providers" -> providers(engine)
        "accounts" -> accounts(engine, rest)
        "credits" -> credits(engine, rest)
        "checkin" -> checkin(engine, rest)
        "login" -> login(engine, rest)
        "browser-login" -> browserLogin(engine, rest)
        "sms" -> sms(engine, rest)
        "transfer" -> transfer(engine, rest)
        "models" -> models(engine)
        "usage" -> usage(engine)
        "logs" -> logs(engine)
        "help", "--help", "-h" -> printHelp()
        else -> {
            System.err.println("未知命令：$command")
            printHelp()
            kotlin.system.exitProcess(2)
        }
    }
}

private fun serve(engine: GatewayEngine, rest: List<String>) {
    option(rest, "--port")?.toIntOrNull()?.let { port ->
        engine.updateSettings(engine.settings().copy(port = port))
    }
    val adminPort = option(rest, "--admin-port")?.toIntOrNull() ?: 8791
    engine.start()
    val settings: GatewaySettings = engine.settings()
    println("网关已启动：http://127.0.0.1:${settings.port}/v1")
    println("供应商数：${engine.providers().size}，账号数：${engine.pool.size()}，无 Key 调用：${settings.allowNoKey}")
    val admin = AdminServer(engine, adminPort)
    admin.startServer()
    println("管理界面：http://127.0.0.1:$adminPort/（仅本机）")
    if ("--open" in rest) {
        runCatching { java.awt.Desktop.getDesktop().browse(java.net.URI("http://127.0.0.1:$adminPort/")) }
    }

    // 系统托盘：javaw 无窗口启动时，托盘图标是进程唯一的"存在感"（运行状态肉眼可见）。
    // 失败可忽略（如 Linux 无桌面），网关照常运行。
    var trayIconRef: java.awt.TrayIcon? = null
    if (!java.awt.GraphicsEnvironment.isHeadless() && java.awt.SystemTray.isSupported() && "--no-tray" !in rest) {
        runCatching {
            // Class.getResourceAsStream 才接受 "/icon.png" 前导斜杠；ClassLoader.getResourceAsStream 不接受（会返回 null）
            val img = javax.imageio.ImageIO.read(AdminServer::class.java.getResourceAsStream("/icon.png"))
                ?.getScaledInstance(32, 32, java.awt.Image.SCALE_SMOOTH)
            if (img != null) {
                val tray = java.awt.SystemTray.getSystemTray()
                val popup = java.awt.PopupMenu()
                val trayIcon = java.awt.TrayIcon(img, "AI Gateway PC · 网关 :${settings.port}", popup)
                trayIcon.isImageAutoSize = true
                val openUi = Runnable {
                    runCatching { java.awt.Desktop.getDesktop().browse(java.net.URI("http://127.0.0.1:$adminPort/")) }
                }
                val miOpen = java.awt.MenuItem("打开管理台")
                miOpen.addActionListener { openUi.run() }
                val miToggle = java.awt.MenuItem("停止服务")
                miToggle.addActionListener {
                    val running = engine.status().running
                    runCatching { if (running) engine.stop() else engine.start() }
                    miToggle.label = if (running) "启动服务" else "停止服务"
                    trayIcon.toolTip = "AI Gateway PC · 网关 ${if (running) "已停止" else ":${engine.settings().port}"}"
                }
                val miExit = java.awt.MenuItem("退出")
                miExit.addActionListener {
                    runCatching { tray.remove(trayIcon) }
                    admin.stopServer()
                    engine.stop()
                    kotlin.system.exitProcess(0)
                }
                popup.add(miOpen)
                popup.add(miToggle)
                popup.addSeparator()
                popup.add(miExit)
                trayIcon.addActionListener { openUi.run() } // 双击托盘图标 = 打开管理台
                tray.add(trayIcon)
                trayIconRef = trayIcon
                println("托盘图标已加载（右键：打开管理台 / 启停服务 / 退出；双击图标 = 打开管理台）")
            }
        }.onFailure { println("托盘初始化失败（忽略，网关照常运行）: ${it.message}") }
    }

    println("按 Ctrl+C 退出。")
    val latch = CountDownLatch(1)
    Runtime.getRuntime().addShutdownHook(Thread {
        trayIconRef?.let { t -> runCatching { java.awt.SystemTray.getSystemTray().remove(t) } }
        admin.stopServer()
        engine.stop()
        latch.countDown()
    })
    latch.await()
}

private fun providers(engine: GatewayEngine) {
    for (info in engine.providers()) {
        val caps = buildString {
            if (info.capabilities.isNotEmpty()) append(" [${info.capabilities.joinToString(",")}]")
        }
        println(
            "${info.id.padEnd(18)} ${info.displayName.padEnd(14)} 登录=${info.authKind} " +
                "账号=${info.accountCount} 可用=${info.usableCount}" +
                (if (info.enabled) "" else " [已停用]") + caps,
        )
    }
}

private fun accounts(engine: GatewayEngine, rest: List<String>) {
    val providerId = option(rest, "--provider")
    val statuses = engine.accounts(providerId)
    if (statuses.isEmpty()) {
        println("账号池为空。用 `login --provider <id> <凭证>` 导入账号。")
        return
    }
    for (status in statuses) {
        val flags = buildString {
            if (status.disabled) append(" [凭证失效]")
            else if (!status.enabled) append(" [已停用]")
        }
        val credits = if (status.creditsKnown) status.credits.toString() else "—"
        println(
            "${status.providerId}/${status.nickname.ifEmpty { status.uid }}  " +
                "额度=$credits  uid=${status.uid}$flags",
        )
    }
    val summary = engine.pool.summary(providerId)
    println(
        "合计 ${summary.total} 个账号，可用 ${summary.usable}，" +
            "停用 ${summary.disabled + summary.disabledByUser}，已知额度合计 ${summary.totalCredits}",
    )
}

private fun credits(engine: GatewayEngine, rest: List<String>) {
    val providerId = option(rest, "--provider")
    for (result in engine.refreshAllCredits(providerId)) {
        if (result.error.isEmpty()) {
            println("${result.providerId}/${result.uid}  额度=${result.balance}")
        } else {
            println("${result.providerId}/${result.uid}  刷新失败：${result.error}")
        }
    }
}

private fun checkin(engine: GatewayEngine, rest: List<String>) {
    val providerId = option(rest, "--provider") ?: "trae"
    val uid = option(rest, "--uid")
    val targets = if (uid != null) listOf(uid) else engine.accounts(providerId).map { it.uid }
    for (target in targets) {
        val result = engine.performAction(providerId, target, "checkin")
        println("$providerId/$target  ${if (result.ok) result.message else "失败：${result.message}"}")
    }
}

private fun browserLogin(engine: GatewayEngine, rest: List<String>) {
    val providerId = option(rest, "--provider") ?: "trae"
    val ticket = engine.beginBrowserLogin(providerId)
    println("请在浏览器打开：${ticket.loginUrl}")
    if (ticket.pollState.isNotEmpty()) {
        // 轮询型登录（设备授权，如 WorkBuddy/CodeBuddy）：CLI 侧自行轮询直到授权完成
        println("轮询型登录（state=${ticket.pollState}），等待你在浏览器完成授权（最长 5 分钟）…")
        val deadline = System.currentTimeMillis() + 5 * 60 * 1000
        while (System.currentTimeMillis() < deadline) {
            when (val poll = engine.pollDeviceAuth(providerId, ticket.pollState, ticket.region)) {
                is DeviceAuthPoll.Pending -> Thread.sleep(3_000)
                is DeviceAuthPoll.Success -> {
                    println("登录成功：${poll.account.nickname.ifEmpty { poll.account.uid }}（${poll.account.uid}），账号已加入账号池")
                    return
                }
                is DeviceAuthPoll.Failed -> {
                    System.err.println("登录失败：${poll.error}")
                    kotlin.system.exitProcess(1)
                }
            }
        }
        System.err.println("等待授权超时（5 分钟），请重试")
        kotlin.system.exitProcess(1)
    }
    println("已监听本地回调，等待浏览器跳回（最长 5 分钟）…")
    val latch = CountDownLatch(1)
    engine.onAccountsChanged = { latch.countDown() }
    val done = latch.await(5, TimeUnit.MINUTES)
    engine.stopCallbackServer()
    if (done) println("登录成功，账号已加入账号池") else System.err.println("等待回调超时")
}

private fun login(engine: GatewayEngine, rest: List<String>) {
    val providerId = option(rest, "--provider") ?: "trae"
    val payload = positional(rest).firstOrNull() ?: error("请给回调链接或凭证")
    // 回调链接（网页登录）以 http 开头；其余按凭证导入（Trae 的 JSON、自定义供应商的裸 Key）
    val outcome = if (payload.startsWith("http", ignoreCase = true)) {
        engine.completeWebLogin(providerId, payload)
    } else {
        engine.importAccountJson(providerId, payload)
    }
    report(outcome)
}

private fun sms(engine: GatewayEngine, rest: List<String>) {
    val providerId = option(rest, "--provider") ?: "loomy"
    val phone = option(rest, "--phone") ?: error("请给 --phone")
    val code = option(rest, "--code")
    if (code == null) {
        val msgid = engine.sendSmsCode(providerId, phone)
        println("验证码已发送，msgid=$msgid（再用 --code 与 --msgid 完成登录）")
        return
    }
    val msgid = option(rest, "--msgid") ?: error("请给 --msgid")
    report(engine.completeSmsLogin(providerId, phone, code, msgid))
}

/** 账号信封导入导出：与 App 端「账号导入导出」共用同一信封格式（AccountTransferEnvelope）。
 *
 * 用法：
 *   aigw transfer export [--file FILE]        导出全部账号（含明文凭证）
 *   aigw transfer import (--file FILE | <json>) [--overwrite]  导入信封（手机端导出的内容可直接导入）
 */
private fun transfer(engine: GatewayEngine, rest: List<String>) {
    val action = positional(rest).firstOrNull() ?: "help"
    val overwrite = "--overwrite" in rest
    when (action) {
        "export" -> {
            val target = option(rest, "--file") ?: "aigw-accounts.json"
            val json = engine.exportAccounts()
            java.io.File(target).writeText(json)
            println("已导出 ${engine.pool.accounts().size} 个账号（含明文凭证，勿外传）→ $target")
        }
        "import" -> {
            val file = option(rest, "--file")
            val json = when {
                file != null -> java.io.File(file).readText()
                else -> positional(rest).drop(1).firstOrNull()
                    ?: error("请给 --file FILE 或直接粘贴信封 JSON")
            }
            val preview = engine.parseAccountTransfer(json)
            println(
                "信封含 ${preview.size} 个账号：" + preview.joinToString("、") {
                    "${it.providerId}/${it.nickname.ifEmpty { it.uid.take(8) }}"
                },
            )
            val result = engine.importAccounts(json, overwrite)
            println(
                "导入完成：新增 ${result.added.size}、覆盖 ${result.updated.size}、" +
                    "跳过 ${result.skipped.size}（已存在，加 --overwrite 可覆盖）、" +
                    "未知供应商 ${result.unknownProviders.size}",
            )
            for (u in result.unknownProviders) println("  未落池（本机没有该供应商）: $u")
        }
        else -> println("用法: aigw transfer export [--file FILE] | aigw transfer import (--file FILE | <json>) [--overwrite]")
    }
}

private fun report(outcome: LoginOutcome) {
    if (outcome.ok) {
        println("登录成功：${outcome.nickname.ifEmpty { outcome.uid }}（${outcome.uid}）")
    } else {
        System.err.println("登录失败：${outcome.error}")
        kotlin.system.exitProcess(1)
    }
}

private fun models(engine: GatewayEngine) {
    for (model in engine.models()) {
        println("${model.fullId}\t${model.model.name}")
    }
}

private fun usage(engine: GatewayEngine) {
    val stats = engine.callLogStore.totalStats()
    println("累计请求 ${stats.requests}，成功 ${stats.success}，失败 ${stats.failed}，中断 ${stats.aborted}，tokens ${stats.totalTokens}")
    if (stats.cachedTokens > 0 && stats.promptTokens > 0) {
        val hitPercent = String.format(java.util.Locale.US, "%.0f", stats.cachedTokens * 100.0 / stats.promptTokens)
        println("提示词命中 ${stats.cachedTokens} / 输入 ${stats.promptTokens}（$hitPercent%）")
    }
    for (record in engine.callLogStore.list().take(20)) {
        println(
            "${java.util.Date(record.startedAtMillis)}  ${record.providerId}/${record.model}  " +
                "${record.status}  ${record.durationMillis}ms  ${record.accountNickname}",
        )
    }
}

private fun logs(engine: GatewayEngine) {
    for (line in engine.requestLog.lines().take(60)) println(line.render())
}

private fun option(args: List<String>, name: String): String? {
    val index = args.indexOf(name)
    if (index < 0) return null
    return args.getOrNull(index + 1)
}

/**
 * 取出位置参数，跳过 `--选项 值`。
 * 否则 `tasks --data DIR` 会把 `--data` 当成账号名。
 */
private fun positional(args: List<String>): List<String> {
    val result = ArrayList<String>()
    var i = 0
    while (i < args.size) {
        val current = args[i]
        if (current.startsWith("--")) {
            i += if (i + 1 < args.size && !args[i + 1].startsWith("--")) 2 else 1
        } else {
            result.add(current)
            i += 1
        }
    }
    return result
}

private fun defaultDataDir(): String =
    System.getenv("AIGW_DATA") ?: (System.getProperty("user.home") + "/.aigw")

private fun printHelp() {
    println(
        """
        用法：aigw <命令> [选项]

          serve                   启动网关（--port 8790，--data DIR）
          providers               列出全部供应商
          accounts                列出账号与状态（--provider ID）
          credits                 刷新账号额度（--provider ID）
          checkin                 签到（--provider ID，--uid UID）
          login                   导入账号：回调链接或凭证 JSON（--provider ID）
          browser-login           用系统浏览器登录（--provider ID，打印登录链接并等待回调）
          sms                     短信登录：--provider ID --phone P [--msgid M --code C]
          transfer                账号信封导入导出（与 App 端互通）：export [--file FILE] / import (--file FILE | <json>) [--overwrite]
          models                  列出全部供应商的模型（id 带 provider 前缀）
          usage                   调用统计
          logs                    请求日志

        账号与设置存放于 --data 指定目录（默认 ~/.aigw）。
        """.trimIndent(),
    )
}
