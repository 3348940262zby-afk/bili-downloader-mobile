package com.bilidownloader.mobile.bridge

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.widget.Toast
import com.bilidownloader.mobile.engine.BiliParser
import com.bilidownloader.mobile.engine.MediaParserDispatcher
import com.bilidownloader.mobile.service.DownloadService
import com.bilidownloader.mobile.service.DownloadTask
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

class WebAppBridge(
    private val context: Context,
    private val webView: WebView,
    private val serviceProvider: () -> DownloadService?
) {
    private val scope = CoroutineScope(Dispatchers.Main)
    private val gson = Gson()
    private val prefs = context.getSharedPreferences("bili_downloader_prefs", Context.MODE_PRIVATE)

    private fun escapeForJs(json: String): String {
        return json.replace("\\", "\\\\")
            .replace("'", "\\'")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\u2028", "\\u2028")
            .replace("\u2029", "\\u2029")
    }

    @JavascriptInterface
    fun parseUrl(input: String) {
        scope.launch {
            val cookies = prefs.getString("bili_cookies", "") ?: ""
            val result = withContext(Dispatchers.IO) {
                MediaParserDispatcher.parse(input, cookies)
            }
            val jsonStr = gson.toJson(result)
            val escaped = escapeForJs(jsonStr)
            webView.evaluateJavascript("javascript:window.onParseResult('$escaped')", null)
        }
    }

    @JavascriptInterface
    fun startDownload(taskJson: String) {
        try {
            val obj = gson.fromJson(taskJson, JsonObject::class.java)
            val id = UUID.randomUUID().toString().take(8)
            val title = obj.get("title").asString
            val videoUrl = obj.get("videoUrl").asString
            val audioUrl = if (obj.has("audioUrl") && !obj.get("audioUrl").isJsonNull) obj.get("audioUrl").asString else null
            val referer = if (obj.has("referer")) obj.get("referer").asString else "https://www.bilibili.com/"

            val task = DownloadTask(
                id = id,
                title = title,
                videoUrl = videoUrl,
                audioUrl = audioUrl,
                referer = referer
            )

            val service = serviceProvider()
            if (service != null) {
                service.addTask(task)
                Toast.makeText(context, "已加入下载队列: $title", Toast.LENGTH_SHORT).show()
                val safeTitle = escapeForJs(title)
                webView.evaluateJavascript("javascript:window.onTaskAdded('$id', '$safeTitle')", null)
            } else {
                Toast.makeText(context, "下载服务未就绪，请稍后重试", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(context, "创建下载任务失败: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    @JavascriptInterface
    fun getClipboardText(): String {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        val clip = clipboard?.primaryClip
        if (clip != null && clip.itemCount > 0) {
            val text = clip.getItemAt(0).text
            return text?.toString() ?: ""
        }
        return ""
    }

    @JavascriptInterface
    fun generateQrCode() {
        scope.launch {
            val res = BiliParser.generateQrCode()
            val jsonStr = gson.toJson(res)
            val escaped = escapeForJs(jsonStr)
            webView.evaluateJavascript("javascript:window.onQrCodeResult('$escaped')", null)
        }
    }

    @JavascriptInterface
    fun pollQrCode(key: String) {
        scope.launch {
            val res = BiliParser.pollQrCode(key)
            if (res.has("cookies")) {
                val cookies = res.get("cookies").asString
                prefs.edit().putString("bili_cookies", cookies).apply()
            }
            val jsonStr = gson.toJson(res)
            val escaped = escapeForJs(jsonStr)
            webView.evaluateJavascript("javascript:window.onQrPollResult('$escaped')", null)
        }
    }

    @JavascriptInterface
    fun getStoredCookies(): String {
        return prefs.getString("bili_cookies", "") ?: ""
    }

    @JavascriptInterface
    fun clearCookies() {
        prefs.edit().remove("bili_cookies").apply()
        Toast.makeText(context, "已清除登录凭据", Toast.LENGTH_SHORT).show()
    }

    @JavascriptInterface
    fun showToast(msg: String) {
        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
    }
}
