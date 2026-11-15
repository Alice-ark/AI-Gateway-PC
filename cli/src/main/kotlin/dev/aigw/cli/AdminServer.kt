package dev.aigw.cli

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.aigw.core.gateway.GatewayEngine
import dev.aigw.core.provider.DeviceAuthPoll
import fi.iki.elonen.NanoHTTPD
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * PC 管理台：把 App 端的核心管理能力（账号 / 登录 / 额度 / 模型 / 用量 / 设置）
 * 以本地网页形式提供，供 PC 用户在没有 Android App 的情况下完成全部日常操作。
 *
 * 仅绑定 127.0.0.1，端口独立于 OpenAI 网关端口（默认 8791），不随局域网暴露开关开放。
 */
class AdminServer(
    private val engine: GatewayEngine,
    port: Int,
) : NanoHTTPD("127.0.0.1", port) {

    /** 回调型登录等待项：浏览器跳回本地回调后由 onAccountsChanged 唤醒。 */
    private class CallbackWait(val latch: CountDownLatch, val startedAt: Long)

    private val callbackWaits = ConcurrentHashMap<String, CallbackWait>()
    private val gson = com.google.gson.Gson()

    fun startServer() {
        start(SOCKET_READ_TIMEOUT, false)
        engine.onAccountsChanged = { callbackWaits.values.forEach { it.latch.countDown() } }
    }

    fun stopServer() {
        engine.onAccountsChanged = null
        stop()
    }

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri
        val method = session.method
        return try {
            when {
                uri == "/" || uri == "/index.html" -> staticRes("web/index.html", "text/html; charset=utf-8")
                uri.startsWith("/web/") -> staticRes(uri.removePrefix("/"), mimeOf(uri))
                // ---------- POST 全部前置（when 顺序匹配，GET 分支绝不放在 POST 前面） ----------
                method == Method.POST && uri == "/api/accounts/enabled" -> accountEnabled(body(session))
                method == Method.POST && uri == "/api/accounts/remove" -> accountRemove(body(session))
                method == Method.POST && uri == "/api/credits" -> refreshCredits(body(session))
                method == Method.POST && uri == "/api/credits/all" -> creditsAll(body(session))
                method == Method.POST && uri == "/api/login/start" -> loginStart(body(session))
                method == Method.POST && uri == "/api/login/paste" -> loginPaste(body(session))
                method == Method.POST && uri == "/api/login/import" -> loginImport(body(session))
                method == Method.POST && uri == "/api/sms/send" -> smsSend(body(session))
                method == Method.POST && uri == "/api/sms/complete" -> smsComplete(body(session))
                method == Method.POST && uri == "/api/checkin" -> checkin(body(session))
                method == Method.POST && uri == "/api/transfer/import" -> transferImport(body(session))
                method == Method.POST && uri == "/api/provider/settings" -> providerSettingsUpdate(body(session))
                method == Method.POST && uri == "/api/settings" -> updateSettings(body(session))
                method == Method.POST && uri == "/api/service" -> serviceToggle(body(session))
                method == Method.POST && uri == "/api/autostart" -> autostartToggle(body(session))
                method == Method.POST && uri == "/api/exit" -> {
                    newFixedLengthResponse(Response.Status.OK, "application/json", """{"ok":true,"note":"exit soon"}""")
                        .also { Thread { Thread.sleep(300); stopServer(); engine.stop(); kotlin.system.exitProcess(0) }.start() }
                }
                // ---------- GET ----------
                uri == "/api/overview" -> overview()
                uri == "/api/providers" -> ok(providersJson())
                uri == "/api/accounts" -> ok(gson.toJson(mapOf("accounts" to engine.pool.statuses())))
                uri == "/api/models" -> modelsJson()
                uri == "/api/usage" -> usageJson()
                uri == "/api/records" -> recordsJson(session.parameters)
                uri == "/api/transfer/export" -> transferExport()
                uri == "/api/settings" -> ok(settingsJson())
                uri == "/api/service" -> serviceStatus()
                uri == "/api/autostart" -> autostartStatus()
                uri == "/api/login/poll" -> loginPoll(session.parameters)
                uri == "/api/login/wait" -> loginWait(session.parameters)
                uri.startsWith("/api/provider/") && uri.length > "/api/provider/".length -> providerDetail(uri.removePrefix("/api/provider/"))
                else -> newFixedLengthResponse(Response.Status.NOT_FOUND, "application/json", """{"error":"not found"}""")
            }
        } catch (e: Exception) {
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "application/json", """{"error":${jsonStr(e.message ?: e.toString())}}""")
        }
    }

    // ---------- 端点实现 ----------

    private fun overview(): Response {
        val s = engine.settings()
        val statuses = engine.pool.statuses()
        val usage = engine.callLogStore.totalStats()
        val obj = JsonObject().apply {
            addProperty("port", s.port)
            addProperty("apiKey", s.apiKey.mask())
            addProperty("allowNoKey", s.allowNoKey)
            addProperty("exposeLan", s.exposeLan)
            addProperty("modelsCount", engine.models().size)
            add("accounts", gson.toJsonTree(statuses))
            add("providers", gson.toJsonTree(engine.providers()))
            add("usage", gson.toJsonTree(usage))
        }
        return ok(obj.toString())
    }

    private fun providersJson(): String = gson.toJson(mapOf("providers" to engine.providers()))

    private fun modelsJson(): Response {
        val arr = com.google.gson.JsonArray()
        for (m in engine.models()) {
            arr.add(JsonObject().apply {
                addProperty("fullId", m.fullId)
                addProperty("providerId", m.providerId)
                addProperty("providerName", m.providerName)
                addProperty("modelId", m.model.id)
                addProperty("name", m.model.name)
                addProperty("contextWindow", m.model.contextWindow)
            })
        }
        return ok("""{"models":$arr}""")
    }

    private fun usageJson(): Response {
        val recent = engine.callLogStore.list().take(30)
        return ok(gson.toJson(mapOf("totals" to engine.callLogStore.totalStats(), "recent" to recent)))
    }

    private fun settingsJson(): String {
        val s = engine.settings()
        return gson.toJson(
            mapOf(
                "port" to s.port, "apiKey" to s.apiKey.mask(), "allowNoKey" to s.allowNoKey,
                "exposeLan" to s.exposeLan, "defaultProvider" to s.defaultProvider, "onlyUsable" to s.onlyUsableModels,
            ),
        )
    }

    private fun accountEnabled(body: JsonObject): Response {
        val providerId = body.str("providerId") ?: return bad("缺 providerId")
        val uid = body.str("uid") ?: return bad("缺 uid")
        val enabled = body.get("enabled")?.asBoolean ?: return bad("缺 enabled")
        engine.pool.setEnabled(providerId, uid, enabled)
        return ok("""{"ok":true}""")
    }

    private fun accountRemove(body: JsonObject): Response {
        val providerId = body.str("providerId") ?: return bad("缺 providerId")
        val uid = body.str("uid") ?: return bad("缺 uid")
        val removed = engine.pool.remove(providerId, uid)
        return ok("""{"ok":$removed}""")
    }

    private fun refreshCredits(body: JsonObject): Response {
        val providerId = body.str("providerId") ?: return bad("缺 providerId")
        val uid = body.str("uid") ?: return bad("缺 uid")
        val result = engine.refreshCredits(providerId, uid)
        val ok = result.error.isEmpty()
        val message = if (ok) "余额 ${result.balance}${if (result.unit.isNotEmpty()) " ${result.unit}" else ""}" else result.error
        return ok(gson.toJson(mapOf("ok" to ok, "message" to message)))
    }

    /** 登录入口：按供应商的 AuthKind 分流为 poll（设备码）/ callback（浏览器跳回）/ sms（短信）。 */
    private fun loginStart(body: JsonObject): Response {
        val providerId = body.str("providerId") ?: return bad("缺 providerId")
        val region = body.str("region") ?: ""
        val info = engine.providers().firstOrNull { it.id == providerId } ?: return bad("未知供应商: $providerId")
        return when (info.authKind.name) {
            "SMS_CODE" -> ok("""{"mode":"sms"}""")
            else -> {
                val ticket = engine.beginBrowserLogin(providerId, region)
                if (ticket.pollState.isNotEmpty()) {
                    ok(
                        gson.toJson(
                            mapOf(
                                "mode" to "poll", "loginUrl" to ticket.loginUrl,
                                "pollState" to ticket.pollState, "region" to ticket.region,
                            ),
                        ),
                    )
                } else {
                    callbackWaits[providerId] = CallbackWait(CountDownLatch(1), System.currentTimeMillis())
                    ok(gson.toJson(mapOf("mode" to "callback", "loginUrl" to ticket.loginUrl)))
                }
            }
        }
    }

    private fun loginPoll(params: Map<String, List<String>>): Response {
        val providerId = params["provider"]?.firstOrNull() ?: return bad("缺 provider")
        val state = params["state"]?.firstOrNull() ?: return bad("缺 state")
        val region = params["region"]?.firstOrNull() ?: ""
        return when (val poll = engine.pollDeviceAuth(providerId, state, region)) {
            is DeviceAuthPoll.Pending -> ok("""{"status":"pending"}""")
            is DeviceAuthPoll.Success -> ok(
                gson.toJson(mapOf("status" to "success", "uid" to poll.account.uid, "nickname" to poll.account.nickname)),
            )
            is DeviceAuthPoll.Failed -> ok(gson.toJson(mapOf("status" to "failed", "error" to poll.error)))
        }
    }

    private fun loginWait(params: Map<String, List<String>>): Response {
        val providerId = params["provider"]?.firstOrNull() ?: return bad("缺 provider")
        val wait = callbackWaits[providerId] ?: return ok("""{"status":"idle"}""")
        val signaled = wait.latch.await(2, TimeUnit.SECONDS)
        return when {
            signaled -> {
                callbackWaits.remove(providerId)
                runCatching { engine.stopCallbackServer() }
                ok("""{"status":"success"}""")
            }
            System.currentTimeMillis() - wait.startedAt > 5 * 60 * 1000 -> {
                callbackWaits.remove(providerId)
                runCatching { engine.stopCallbackServer() }
                ok("""{"status":"timeout"}""")
            }
            else -> ok("""{"status":"pending"}""")
        }
    }

    /** 兜底：App 端 WebView 拦截的回调链接，PC 上可由用户粘贴进来。 */
    private fun loginPaste(body: JsonObject): Response {
        val providerId = body.str("providerId") ?: return bad("缺 providerId")
        val url = body.str("url") ?: return bad("缺 url")
        val outcome = engine.completeWebLogin(providerId, url)
        return outcomeJson(outcome)
    }

    private fun smsSend(body: JsonObject): Response {
        val providerId = body.str("providerId") ?: return bad("缺 providerId")
        val phone = body.str("phone") ?: return bad("缺 phone")
        val msgid = engine.sendSmsCode(providerId, phone)
        return ok(gson.toJson(mapOf("msgid" to msgid)))
    }

    private fun smsComplete(body: JsonObject): Response {
        val providerId = body.str("providerId") ?: return bad("缺 providerId")
        val phone = body.str("phone") ?: return bad("缺 phone")
        val code = body.str("code") ?: return bad("缺 code")
        val msgid = body.str("msgid") ?: return bad("缺 msgid")
        val outcome = engine.completeSmsLogin(providerId, phone, code, msgid)
        return outcomeJson(outcome)
    }

    private fun updateSettings(body: JsonObject): Response {
        val s = engine.settings()
        val updated = s.copy(
            port = body.get("port")?.takeIf { it.isJsonPrimitive }?.asInt ?: s.port,
            apiKey = body.str("apiKey")?.takeIf { !it.contains('*') } ?: s.apiKey,
            allowNoKey = body.get("allowNoKey")?.takeIf { it.isJsonPrimitive }?.asBoolean ?: s.allowNoKey,
            exposeLan = body.get("exposeLan")?.takeIf { it.isJsonPrimitive }?.asBoolean ?: s.exposeLan,
            onlyUsableModels = body.get("onlyUsable")?.takeIf { it.isJsonPrimitive }?.asBoolean ?: s.onlyUsableModels,
        )
        engine.updateSettings(updated)
        return ok("""{"ok":true,"note":"端口改动需重启 aigw serve 生效"}""")
    }

    private fun serviceStatus(): Response = ok(gson.toJson(engine.status()))

    private fun serviceToggle(body: JsonObject): Response {
        val running = body.get("running")?.takeIf { it.isJsonPrimitive }?.asBoolean ?: return bad("缺 running")
        val result = JsonObject()
        try {
            if (running && !engine.status().running) engine.start()
            if (!running && engine.status().running) engine.stop()
            result.addProperty("ok", true)
        } catch (e: Exception) {
            result.addProperty("ok", false)
            result.addProperty("error", e.message ?: e.toString())
        }
        result.add("status", gson.toJsonTree(engine.status()))
        return ok(result.toString())
    }

    private fun checkin(body: JsonObject): Response {
        val providerId = body.str("providerId") ?: return bad("缺 providerId")
        val uid = body.str("uid")
        val targets = uid?.let { listOf(it) } ?: engine.accounts(providerId).map { it.uid }
        val arr = com.google.gson.JsonArray()
        for (t in targets) {
            val r = engine.performAction(providerId, t, "checkin")
            arr.add(JsonObject().apply {
                addProperty("uid", t)
                addProperty("ok", r.ok)
                addProperty("message", r.message)
            })
        }
        return ok("""{"results":$arr}""")
    }

    private fun creditsAll(body: JsonObject): Response {
        val results = engine.refreshAllCredits(body.str("providerId"))
        val okN = results.count { it.error.isEmpty() }
        return ok("""{"total":${results.size},"okCount":$okN,"failed":${results.size - okN}}""")
    }

    private fun transferImport(body: JsonObject): Response {
        val json = body.str("json") ?: return bad("缺 json")
        val overwrite = body.get("overwrite")?.takeIf { it.isJsonPrimitive }?.asBoolean ?: false
        val r = engine.importAccounts(json, overwrite)
        return ok(gson.toJson(mapOf("added" to r.added, "updated" to r.updated, "skipped" to r.skipped, "unknown" to r.unknownProviders)))
    }

    private fun transferExport(): Response = ok(gson.toJson(mapOf("json" to engine.exportAccounts())))

    private fun recordsJson(params: Map<String, List<String>>): Response {
        val limit = params["limit"]?.firstOrNull()?.toIntOrNull() ?: 200
        val records = engine.callLogStore.list().takeLast(limit).reversed()
        return ok(gson.toJson(mapOf("records" to records, "totals" to engine.callLogStore.totalStats())))
    }

    /** 供应商详情：info + 设置（启停/默认模型/region option） + 该供应商的账号状态。 */
    private fun providerDetail(id: String): Response {
        val info = engine.providers().firstOrNull { it.id == id } ?: return bad("未知供应商: $id")
        val settings = engine.providerSettings(id)
        return ok(
            gson.toJson(
                mapOf(
                    "info" to info,
                    "settings" to mapOf(
                        "enabled" to settings.enabled,
                        "defaultModel" to settings.defaultModel,
                        "region" to settings.option("region", ""),
                    ),
                    "accounts" to engine.pool.statuses(id),
                ),
            ),
        )
    }

    private fun providerSettingsUpdate(body: JsonObject): Response {
        val providerId = body.str("providerId") ?: return bad("缺 providerId")
        val cur = engine.providerSettings(providerId)
        val updated = cur.copy(
            enabled = body.get("enabled")?.takeIf { it.isJsonPrimitive }?.asBoolean ?: cur.enabled,
            defaultModel = body.str("defaultModel") ?: cur.defaultModel,
            options = body.str("region")?.let { cur.options + ("region" to it) } ?: cur.options,
        )
        engine.updateProviderSettings(providerId, updated)
        return ok("""{"ok":true}""")
    }

    /** 粘贴导入：回调链接（http 开头）或凭证 JSON，与 App「粘贴凭证 JSON 导入」同一入口。 */
    private fun loginImport(body: JsonObject): Response {
        val providerId = body.str("providerId") ?: return bad("缺 providerId")
        val payload = body.str("payload") ?: return bad("缺 payload")
        return outcomeJson(engine.importAccountJson(providerId, payload))
    }

    /** 安装根目录：jar 形态 = .../aigw（lib 的上一级）；开发形态退化为 classes 目录，仅供调试。 */
    private val installDir: java.nio.file.Path by lazy {
        val p = java.nio.file.Paths.get(AdminServer::class.java.protectionDomain.codeSource.location.toURI()).toAbsolutePath()
        if (p.fileName.toString().endsWith(".jar")) p.parent.parent else p.parent
    }

    /** 启动文件夹里的自启动快捷方式（纯 .lnk 文件：看得见、删得掉，不含任何脚本）。 */
    private val startupLnk: java.nio.file.Path by lazy {
        java.nio.file.Paths.get(
            System.getenv("APPDATA"), "Microsoft", "Windows",
            "Start Menu", "Programs", "Startup", "AI-Gateway-PC.lnk",
        )
    }

    /** 历史遗留的 VBS 自启动项（切换时顺手清理，VBS 易被杀软启发式误报）。 */
    private val legacyStartupVbs: java.nio.file.Path by lazy {
        startupLnk.resolveSibling("AI-Gateway-PC-autostart.vbs")
    }

    /** 桌面快捷方式：作为自启动项创建失败时的兜底复制源。 */
    private fun desktopLnk(): java.nio.file.Path? {
        val home = System.getProperty("user.home")
        return listOf(
            java.nio.file.Paths.get(home, "Desktop", "AI Gateway PC.lnk"),
            java.nio.file.Paths.get(home, "OneDrive", "Desktop", "AI Gateway PC.lnk"),
        ).firstOrNull { java.nio.file.Files.exists(it) }
    }

    private fun autostartEnabled(): Boolean = java.nio.file.Files.exists(startupLnk)

    private fun autostartStatus(): Response =
        ok(gson.toJson(mapOf("enabled" to autostartEnabled(), "installDir" to installDir.toString())))

    private fun runCmd(vararg cmd: String): Boolean = try {
        ProcessBuilder(*cmd).redirectErrorStream(true).start().waitFor() == 0
    } catch (e: Exception) { false }

    /** 生成「静默」自启动项：参数不带 --open，开机只后台起网关，不弹管理台页面。 */
    private fun createSilentStartupLnk(): Boolean {
        val pwsh = java.nio.file.Paths.get(
            System.getenv("WINDIR") ?: "C://Windows", "System32", "WindowsPowerShell", "v1.0", "powershell.exe",
        ).toString()
        val javaw = java.nio.file.Paths.get(System.getProperty("java.home"), "bin", "javaw.exe")
            .toAbsolutePath().toString().replace("'", "''")
        val target = startupLnk.toString().replace("'", "''")
        val wd = installDir.toString().replace("'", "''")
        val icon = installDir.resolve("aigw-icon.ico").toString().replace("'", "''")
        val d = "${'$'}"
        val ps = """
            ${d}ws=New-Object -ComObject WScript.Shell
            ${d}s=${d}ws.CreateShortcut('$target')
            ${d}s.TargetPath='$javaw'
            ${d}s.Arguments='-cp lib\* dev.aigw.cli.MainKt serve --port 8790'
            ${d}s.WorkingDirectory='$wd'
            ${d}s.IconLocation='$icon,0'
            ${d}s.Save()
        """.trimIndent().lines().joinToString("; ")
        return runCmd(pwsh, "-NoProfile", "-NonInteractive", "-WindowStyle", "Hidden", "-Command", ps)
    }

    private fun autostartToggle(body: JsonObject): Response {
        val enabled = body.get("enabled")?.takeIf { it.isJsonPrimitive }?.asBoolean ?: return bad("缺 enabled")
        java.nio.file.Files.deleteIfExists(legacyStartupVbs)
        if (enabled) {
            java.nio.file.Files.createDirectories(startupLnk.parent)
            // 优先生成静默 lnk（开机不弹页面）；PowerShell 不可用时退回复制桌面 lnk
            var silent = createSilentStartupLnk()
            if (!java.nio.file.Files.exists(startupLnk)) {
                val src = desktopLnk()
                    ?: return bad("无法创建自启动快捷方式：请先重建桌面快捷方式「AI Gateway PC.lnk」")
                java.nio.file.Files.copy(src, startupLnk, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                silent = false
            }
            return ok(gson.toJson(mapOf("enabled" to true, "silent" to silent)))
        }
        java.nio.file.Files.deleteIfExists(startupLnk)
        return ok(gson.toJson(mapOf("enabled" to false, "silent" to true)))
    }

    // ---------- 工具 ----------

    private fun outcomeJson(outcome: dev.aigw.core.gateway.LoginOutcome): Response =
        if (outcome.ok) ok(gson.toJson(mapOf("status" to "success", "uid" to outcome.uid, "nickname" to outcome.nickname)))
        else ok(gson.toJson(mapOf("status" to "failed", "error" to outcome.error)))

    private fun body(session: IHTTPSession): JsonObject {
        val len = session.headers["content-length"]?.toIntOrNull() ?: 0
        val buf = ByteArray(len.coerceAtMost(2 * 1024 * 1024))
        session.inputStream.readFully(buf, 0, buf.size)
        val text = String(buf, Charsets.UTF_8).trim()
        return if (text.isEmpty()) JsonObject() else JsonParser.parseString(text).asJsonObject
    }

    private fun staticRes(path: String, mime: String): Response {
        val stream: InputStream = javaClass.classLoader.getResourceAsStream(path)
            ?: return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "resource not found: $path")
        val bytes = stream.readBytes()
        return newChunkedResponse(Response.Status.OK, mime, ByteArrayInputStream(bytes))
    }

    private fun mimeOf(uri: String): String = when (uri.substringAfterLast('.', "")) {
        "html" -> "text/html; charset=utf-8"
        "css" -> "text/css; charset=utf-8"
        "js" -> "application/javascript; charset=utf-8"
        "svg" -> "image/svg+xml"
        else -> "application/octet-stream"
    }

    private fun ok(json: String): Response = newFixedLengthResponse(Response.Status.OK, "application/json", json)
    private fun bad(msg: String): Response = newFixedLengthResponse(Response.Status.BAD_REQUEST, "application/json", """{"error":${jsonStr(msg)}}""")
    private fun jsonStr(s: String): String = com.google.gson.JsonPrimitive(s).toString()

    private fun JsonObject.str(key: String): String? =
        (get(key) as? com.google.gson.JsonPrimitive)?.takeIf { it.isString }?.asString

    private fun String.mask(): String = if (length <= 6) "•••" else take(4) + "•••••" + takeLast(2)
}

/** readFully 来自 NanoHTTPD 对 InputStream 的扩展？——自行实现一个稳妥版本。 */
private fun InputStream.readFully(buffer: ByteArray, offset: Int, length: Int) {
    var read = 0
    while (read < length) {
        val n = read(buffer, offset + read, length - read)
        if (n < 0) break
        read += n
    }
}
