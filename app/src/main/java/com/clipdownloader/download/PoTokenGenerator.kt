package com.clipdownloader.download

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.JavascriptInterface
import android.webkit.WebView
import com.clipdownloader.util.LogFile
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * YouTube PO token（Proof of Origin）生成器（2026-08-23 引入）。
 *
 * 现状：全流程已跑通（visitorData → 挑战 → 快照 → GenerateIT → 铸造），但 PO token
 * 分平台——Web BotGuard 签发的 token 只对 WEB 系客户端有效，对 visionos 无效
 * （yt-dlp wiki 明确，NewPipeExtractor 的 visionos 请求也不带 token，实测重试仍被拒）。
 * visionos 通道已改为请求保真方案（见 YouTubeInnerTubeParser），本生成器保留，
 * 供将来 WEB/mweb 通道（需配合网页签名解密）使用。
 *
 * 协议（对齐 LuanRT/BgUtils 逆向实现，与网页播放器行为一致）：
 * 1. POST jnn-pa.googleapis.com/$rpc/google.internal.waa.v1.Waa/Create，
 *    body [requestKey] → 挑战数组（rawData[1] 为字符串时是 base64url 混淆体：
 *    解码后每字节 +97 即明文 JSON）：
 *    [messageId, [interpreterJs], [interpreterUrl], interpreterHash,
 *     program, globalName, _, clientExperimentsStateBlob]
 * 2. WebView 里 eval(interpreterJs) 得到 window[globalName] 的 BotGuard VM；
 *    vm.a(program, …) → asyncSnapshotFunction；调用 snapshot 时传入空的
 *    webPoSignalOutput 数组，BotGuard 会把 token minter 塞进去
 * 3. POST www.youtube.com/api/jnn/v1/GenerateIT，body [requestKey, snapshot响应]
 *    → [integrityToken, ttlSecs, mintRefreshThreshold, fallback]
 * 4. 页面里 getMinter(integrityToken 字节) → mintCallback(visitorData 字节)
 *    → PO token 字节 → websafe base64
 *
 * 网络步骤全部走 OkHttp（不受 WebView CORS 限制）；WebView 只承担纯 JS 计算
 * （BotGuard 有浏览器环境检测，Android WebView 是真浏览器环境，PipePipe 已验证可行）。
 * interpreterJs 以 base64 内嵌进页面，避免任何 HTML/JS 转义问题。
 */
object PoTokenGenerator {

    private const val TAG = "PoToken"

    /** 网页播放器内置的 BotGuard request key（公开常量，全端一致） */
    private const val REQUEST_KEY = "O43z0dpjhgX20SCx4KAo"
    private const val WAA_KEY = "AIzaSyDyT5W0Jh49F30Pqqtyfdf7pDLFKLJoAnw"
    private const val CREATE_URL =
        "https://jnn-pa.googleapis.com/\$rpc/google.internal.waa.v1.Waa/Create"
    private const val GENERATE_IT_URL = "https://www.youtube.com/api/jnn/v1/GenerateIT"

    private const val DESKTOP_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    /** PO token 有效期上限（integrity token 的 ttl 再长也只信 6 小时） */
    private const val MAX_TTL_MS = 6 * 3600_000L

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private class TokenCache(val visitorData: String, val poToken: String, val expiresAt: Long)

    @Volatile private var cached: TokenCache? = null
    private val mutex = kotlinx.coroutines.sync.Mutex()

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("po_token", Context.MODE_PRIVATE)

    /** 取（必要时生成）visitor 绑定的 player PO token；失败抛异常由调用方回落 */
    suspend fun getPlayerToken(ctx: Context): Pair<String, String> {
        cachedValid()?.let { return it.visitorData to it.poToken }
        mutex.lock()
        try {
            cachedValid()?.let { return it.visitorData to it.poToken }
            val fresh = generate(ctx)
            cached = fresh
            prefs(ctx).edit()
                .putString("visitor", fresh.visitorData)
                .putString("po_token", fresh.poToken)
                .putLong("expires_at", fresh.expiresAt)
                .apply()
            LogFile.i(TAG, "PO token 已生成并缓存，有效期至 ${java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.US).format(java.util.Date(fresh.expiresAt))}")
            return fresh.visitorData to fresh.poToken
        } finally {
            mutex.unlock()
        }
    }

    /** 请求带 token 仍被拒时清缓存，强制下次重新生成 */
    fun invalidate(ctx: Context) {
        cached = null
        prefs(ctx).edit().remove("po_token").remove("expires_at").apply()
        LogFile.d(TAG, "PO token 缓存已失效")
    }

    private fun cachedValid(): TokenCache? {
        cached?.let { if (it.expiresAt > System.currentTimeMillis()) return it }
        return null
    }

    // ==================== 生成流程 ====================

    private class Challenge(val interpreterJs: String, val program: String, val globalName: String)
    private class Integrity(val tokenB64: String, val ttlSecs: Long)

    private suspend fun generate(ctx: Context): TokenCache {
        val visitor = getVisitorData()
        LogFile.d(TAG, "visitorData 获取成功（${visitor.length} 字符）")
        val challenge = waaCreate()
        var integrity: Integrity? = null
        val poToken = webviewFlow(ctx, challenge, visitor) { snapshotResponse ->
            val it = generateIt(snapshotResponse)
            LogFile.d(TAG, "GenerateIT 成功（ttl=${it.ttlSecs}s）")
            integrity = it
            it.tokenB64
        }
        val ttl = integrity?.ttlSecs ?: 0L
        val ttlMs = (if (ttl > 0) kotlin.math.min(ttl * 1000L, MAX_TTL_MS) else MAX_TTL_MS) - 300_000L
        return TokenCache(visitor, poToken, System.currentTimeMillis() + kotlin.math.max(ttlMs, 300_000L))
    }

    /** visitorData：youtube.com 首页 ytcfg 里的会话标识（与 token 一起缓存，过期整体重新生成） */
    private fun getVisitorData(): String {
        val req = Request.Builder()
            .url("https://www.youtube.com/")
            .header("User-Agent", DESKTOP_UA)
            .header("Accept-Language", "en-US,en;q=0.9")
            .build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw RuntimeException("获取 visitorData HTTP ${resp.code}")
            val html = resp.body?.string().orEmpty()
            val m = Regex("\"visitorData\":\"([^\"]+)\"").find(html)
                ?: throw RuntimeException("页面未包含 visitorData")
            return m.groupValues[1]
        }
    }

    /** Waa/Create → BotGuard 挑战（interpreterJs 优先，缺失时回落 interpreterUrl 下载） */
    private fun waaCreate(): Challenge {
        val respBody = waaPost(CREATE_URL, """["$REQUEST_KEY"]""")
        val raw = JSONArray(respBody)
        val challengeArr = if (raw.length() > 1 && raw.opt(1) is String) {
            JSONArray(descramble(raw.getString(1)))
        } else raw.getJSONArray(0)

        val interpreterJs = firstStringIn(challengeArr.optJSONArray(1))
        val interpreterUrl = firstStringIn(challengeArr.optJSONArray(2))
        val program = challengeArr.optString(4)
        val globalName = challengeArr.optString(5)
        if (program.isBlank() || globalName.isBlank()) throw RuntimeException("挑战缺少 program/globalName")

        val js = when {
            interpreterJs.isNotBlank() -> interpreterJs
            interpreterUrl.isNotBlank() -> {
                val req = Request.Builder().url(interpreterUrl).header("User-Agent", DESKTOP_UA).build()
                http.newCall(req).execute().use { r ->
                    if (!r.isSuccessful) throw RuntimeException("下载 interpreter HTTP ${r.code}")
                    r.body?.string().orEmpty()
                }
            }
            else -> throw RuntimeException("挑战缺少 interpreter 脚本")
        }
        LogFile.d(TAG, "挑战就绪：interpreter ${js.length} 字符，globalName=$globalName")
        return Challenge(js, program, globalName)
    }

    /** Waa/GenerateIT → integrity token。
     *  midStep 在 webviewFlow 的 Main 上下文里被调，必须切 IO，否则 OkHttp 同步
     *  execute() 落在主线程直接 NetworkOnMainThreadException。 */
    private suspend fun generateIt(botguardResponse: String): Integrity = withContext(Dispatchers.IO) {
        val respBody = waaPost(GENERATE_IT_URL, """["$REQUEST_KEY",${JSONObject.quote(botguardResponse)}]""")
        val arr = JSONArray(respBody)
        val token = arr.optString(0)
        if (token.isBlank()) throw RuntimeException("GenerateIT 未返回 integrity token")
        Integrity(token, arr.optLong(1, 0L))
    }

    private fun waaPost(url: String, jsonBody: String): String {
        val req = Request.Builder()
            .url(url)
            .header("Content-Type", "application/json+protobuf")
            .header("x-goog-api-key", WAA_KEY)
            .header("x-user-agent", "grpc-web-javascript/0.1")
            .header("User-Agent", DESKTOP_UA)
            .post(jsonBody.toRequestBody("application/json+protobuf".toMediaType()))
            .build()
        http.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw RuntimeException("WAA HTTP ${resp.code}: ${text.take(120)}")
            return text
        }
    }

    /** 混淆挑战解码：base64url → 字节 +97 → UTF-8 JSON 文本 */
    private fun descramble(s: String): String {
        val b64 = s.replace("-", "+").replace("_", "/").replace(".", "=")
        val bytes = android.util.Base64.decode(b64, android.util.Base64.DEFAULT)
        val out = ByteArray(bytes.size) { (bytes[it] + 97).toByte() }
        return String(out, Charsets.UTF_8)
    }

    private fun firstStringIn(arr: JSONArray?): String {
        if (arr == null) return ""
        for (i in 0 until arr.length()) {
            val v = arr.opt(i)
            if (v is String && v.isNotBlank()) return v
        }
        return ""
    }

    // ==================== WebView（BotGuard VM + minter） ====================

    /** JS → Kotlin 桥（回调发生在 WebView 内部线程，只做线程安全的 complete） */
    private class Bridge(
        val snapshot: CompletableDeferred<String>,
        val token: CompletableDeferred<String>
    ) {
        @JavascriptInterface
        fun onSnapshot(response: String) {
            snapshot.complete(response)
        }

        @JavascriptInterface
        fun onToken(tokenB64: String) {
            token.complete(tokenB64)
        }

        @JavascriptInterface
        fun onError(error: String) {
            val e = RuntimeException("BotGuard JS: $error")
            snapshot.completeExceptionally(e)
            token.completeExceptionally(e)
        }
    }

    /**
     * 完整 WebView 流程：snapshot →（midStep 做 GenerateIT）→ mint。
     * 页面只做纯 JS 计算，无任何网络依赖。每一步都有日志，失败可精确定位。
     */
    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun webviewFlow(
        ctx: Context,
        challenge: Challenge,
        contentBinding: String,
        midStep: suspend (snapshotResponse: String) -> String
    ): String = withContext(Dispatchers.Main) {
        withTimeout(25_000L) {
            val snapshotDef = CompletableDeferred<String>()
            val tokenDef = CompletableDeferred<String>()
            val bridge = Bridge(snapshotDef, tokenDef)

            val html = buildHtml(challenge, contentBinding)
            val webView = try {
                WebView(ctx.applicationContext).also { w ->
                    w.settings.javaScriptEnabled = true
                    w.settings.domStorageEnabled = false
                    w.addJavascriptInterface(bridge, "AndroidBridge")
                    // BotGuard JS 的报错只进浏览器 console，捕获进来便于定位
                    w.webChromeClient = object : android.webkit.WebChromeClient() {
                        override fun onConsoleMessage(msg: android.webkit.ConsoleMessage): Boolean {
                            LogFile.d(TAG, "JS console: ${msg.message().take(300)}")
                            return true
                        }
                    }
                }
            } catch (e: Exception) {
                throw RuntimeException(
                    "WebView 创建失败（${e.javaClass.simpleName}: ${e.message}）——设备 WebView 组件不可用？", e)
            }
            try {
                webView.loadDataWithBaseURL("https://www.youtube.com/", html, "text/html", "utf-8", null)
                LogFile.d(TAG, "WebView 页面已加载（${html.length} 字符），等待 BotGuard 快照…")

                // 1) BotGuard snapshot
                val snapshotResponse = try {
                    snapshotDef.await()
                } catch (e: Exception) {
                    throw RuntimeException("BotGuard 快照失败（${e.javaClass.simpleName}: ${e.message}）", e)
                }
                LogFile.d(TAG, "BotGuard 快照完成（${snapshotResponse.length} 字符）")

                // 2) GenerateIT（OkHttp，协程切 IO）
                val integrityB64 = try {
                    midStep(snapshotResponse)
                } catch (e: Exception) {
                    throw RuntimeException("GenerateIT 失败（${e.javaClass.simpleName}: ${e.message}）", e)
                }

                // 3) 用 minter 铸 PO token
                webView.evaluateJavascript("bgMint(${JSONObject.quote(integrityB64)})", null)
                val token = try {
                    tokenDef.await()
                } catch (e: Exception) {
                    throw RuntimeException("PO token 铸造失败（${e.javaClass.simpleName}: ${e.message}）", e)
                }
                LogFile.d(TAG, "PO token 铸造完成（${token.length} 字符）")
                token
            } finally {
                try {
                    webView.stopLoading()
                    webView.destroy()
                } catch (_: Exception) {
                }
            }
        }
    }

    /** 组装内嵌页面：interpreter 脚本 base64 内嵌，避免任何转义问题 */
    private fun buildHtml(challenge: Challenge, contentBinding: String): String {
        val interpreterB64 = android.util.Base64.encodeToString(
            challenge.interpreterJs.toByteArray(Charsets.UTF_8),
            android.util.Base64.NO_WRAP
        )
        val cfg = JSONObject()
            .put("program", challenge.program)
            .put("globalName", challenge.globalName)
            .put("contentBinding", contentBinding)
        return """
        <!DOCTYPE html><html><head><meta charset="utf-8"></head><body>
        <script>
        var CFG = $cfg;
        var minterOutput = null;

        function b64ToU8(b64) {
          b64 = b64.replace(/-/g, '+').replace(/_/g, '/').replace(/\./g, '=');
          var bin = atob(b64);
          var u8 = new Uint8Array(bin.length);
          for (var i = 0; i < bin.length; i++) u8[i] = bin.charCodeAt(i);
          return u8;
        }
        function u8ToB64(u8, websafe) {
          var s = '';
          for (var i = 0; i < u8.length; i++) s += String.fromCharCode(u8[i]);
          var b = btoa(s);
          if (websafe) b = b.replace(/\+/g, '-').replace(/\//g, '_');
          return b;
        }

        function bgStart() {
          try {
            var src = new TextDecoder('utf-8').decode(b64ToU8("$interpreterB64"));
            (0, eval)(src);
            var vm = window[CFG.globalName];
            if (!vm || !vm.a) { AndroidBridge.onError('VM unavailable: ' + CFG.globalName); return; }
            var noop = function() {};
            var p = vm.a(
              CFG.program,
              function(asyncSnapshotFunction, shutdownFunction, passEventFunction, checkCameraFunction) {
                var webPoSignalOutput = [];
                minterOutput = webPoSignalOutput;
                asyncSnapshotFunction(function(response) {
                  try {
                    AndroidBridge.onSnapshot(typeof response === 'string' ? response : JSON.stringify(response));
                  } catch (e) { AndroidBridge.onError('snapshot bridge: ' + e); }
                }, [undefined, undefined, webPoSignalOutput, undefined]);
              },
              true,
              undefined,
              function(latency, f1, f2) {},
              [[], []],
              undefined,
              false,
              [noop, noop, noop, noop, noop]
            );
            if (p && p.catch) p.catch(function(e) { AndroidBridge.onError('vm.a: ' + e); });
          } catch (e) { AndroidBridge.onError('start: ' + e); }
        }

        function bgMint(integrityTokenB64) {
          try {
            if (!minterOutput || !minterOutput[0]) { AndroidBridge.onError('no minter'); return; }
            // getMinter 可能同步返回 mintCallback（当前 interpreter 即如此）而非 Promise，
            // 须用 Promise.resolve 归一，对齐 BgUtils WebPoMinter 的 await 语义
            Promise.resolve(minterOutput[0](b64ToU8(integrityTokenB64))).then(function(mintCallback) {
              if (typeof mintCallback !== 'function') throw new Error('minter init failed');
              return Promise.resolve(mintCallback(new TextEncoder().encode(CFG.contentBinding)));
            }).then(function(bytes) {
              if (!bytes) throw new Error('empty token');
              AndroidBridge.onToken(u8ToB64(bytes, true));
            }).catch(function(e) { AndroidBridge.onError('mint: ' + e); });
          } catch (e) { AndroidBridge.onError('mint: ' + e); }
        }

        bgStart();
        </script>
        </body></html>
        """.trimIndent()
    }
}
