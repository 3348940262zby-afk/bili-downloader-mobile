package com.bilidownloader.mobile.bridge

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.widget.Toast
import com.bilidownloader.mobile.MainActivity
import com.bilidownloader.mobile.engine.BiliParser
import com.bilidownloader.mobile.engine.MediaParserDispatcher
import com.bilidownloader.mobile.service.DownloadService
import com.bilidownloader.mobile.service.DownloadTask
import com.bilidownloader.mobile.util.GalleryHelper
import com.bilidownloader.mobile.util.parseAsJsonObject
import com.bilidownloader.mobile.util.str
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.util.UUID

class WebAppBridge(
    private val context: Context,
    private val webView: WebView,
    private val serviceProvider: () -> DownloadService?
) {
    private val scope = CoroutineScope(Dispatchers.Main)
    private val gson = Gson()
    private val prefs = context.getSharedPreferences("bili_downloader_prefs", Context.MODE_PRIVATE)
    private val mainHandler = Handler(Looper.getMainLooper())

    private fun runOnUiThread(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            action()
        } else {
            mainHandler.post(action)
        }
    }

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
            runOnUiThread {
                webView.evaluateJavascript("javascript:window.onParseResult && window.onParseResult('$escaped')", null)
            }
        }
    }

    @JavascriptInterface
    fun startDownload(taskJson: String) {
        runOnUiThread {
            try {
                val obj = taskJson.parseAsJsonObject()
                if (obj == null) {
                    Toast.makeText(context, "任务参数格式错误", Toast.LENGTH_SHORT).show()
                    return@runOnUiThread
                }

                val id = UUID.randomUUID().toString().take(8)
                val title = obj.str("title", "媒体下载") ?: "媒体下载"
                val videoUrl = obj.str("videoUrl") ?: ""
                val audioUrl = obj.str("audioUrl")
                val referer = obj.str("referer", "https://www.bilibili.com/") ?: "https://www.bilibili.com/"
                val userAgent = obj.str("userAgent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36") 
                    ?: "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"

                if (videoUrl.isEmpty()) {
                    Toast.makeText(context, "下载链接为空", Toast.LENGTH_SHORT).show()
                    return@runOnUiThread
                }

                val isAudio = runCatching { obj.get("isAudio")?.asBoolean }.getOrDefault(false) == true || obj.str("isAudio") == "true"

                val task = DownloadTask(
                    id = id,
                    title = title,
                    videoUrl = videoUrl,
                    audioUrl = audioUrl,
                    referer = referer,
                    userAgent = userAgent,
                    isAudio = isAudio
                )

                val service = serviceProvider()
                if (service != null) {
                    service.addTask(task)
                    Toast.makeText(context, "已加入下载队列: $title", Toast.LENGTH_SHORT).show()
                    val safeTitle = escapeForJs(title)
                    webView.evaluateJavascript("javascript:window.onTaskAdded && window.onTaskAdded('$id', '$safeTitle')", null)
                } else {
                    Toast.makeText(context, "下载服务未就绪，请稍后重试", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                e.printStackTrace()
                Toast.makeText(context, "创建下载任务失败: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    @JavascriptInterface
    fun getClipboardText(): String {
        var result = ""
        val latch = java.util.concurrent.CountDownLatch(1)
        runOnUiThread {
            try {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                val clip = clipboard?.primaryClip
                if (clip != null && clip.itemCount > 0) {
                    val text = clip.getItemAt(0).text
                    result = text?.toString() ?: ""
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                latch.countDown()
            }
        }
        try {
            latch.await(500, java.util.concurrent.TimeUnit.MILLISECONDS)
        } catch (_: Exception) {}
        return result
    }

    @JavascriptInterface
    fun generateQrCode() {
        scope.launch {
            val res = withContext(Dispatchers.IO) { BiliParser.generateQrCode() }
            val jsonStr = gson.toJson(res)
            val escaped = escapeForJs(jsonStr)
            runOnUiThread {
                webView.evaluateJavascript("javascript:window.onQrCodeResult && window.onQrCodeResult('$escaped')", null)
            }
        }
    }

    @JavascriptInterface
    fun pollQrCode(key: String) {
        scope.launch {
            val res = withContext(Dispatchers.IO) { BiliParser.pollQrCode(key) }
            val cookies = res.str("cookies")
            if (!cookies.isNullOrEmpty()) {
                prefs.edit().putString("bili_cookies", cookies).apply()
            }
            val jsonStr = gson.toJson(res)
            val escaped = escapeForJs(jsonStr)
            runOnUiThread {
                webView.evaluateJavascript("javascript:window.onQrPollResult && window.onQrPollResult('$escaped')", null)
            }
        }
    }

    @JavascriptInterface
    fun openWebLogin() {
        runOnUiThread {
            (context as? MainActivity)?.openBilibiliWebLogin()
        }
    }

    @JavascriptInterface
    fun saveQrImage(imageUrl: String) {
        scope.launch(Dispatchers.IO) {
            try {
                val bytes: ByteArray? = if (imageUrl.startsWith("data:image/")) {
                    val base64 = imageUrl.substringAfter(",")
                    Base64.decode(base64, Base64.DEFAULT)
                } else {
                    val req = Request.Builder()
                        .url(imageUrl)
                        .header("User-Agent", BiliParser.USER_AGENT)
                        .build()
                    BiliParser.client.newCall(req).execute().use { resp ->
                        resp.body?.bytes()
                    }
                }

                if (bytes != null && bytes.isNotEmpty()) {
                    val filename = "bili_login_qr_${System.currentTimeMillis()}.png"
                    val uri = GalleryHelper.saveImageToGallery(context, bytes, filename)
                    runOnUiThread {
                        if (uri != null) {
                            Toast.makeText(context, "二维码已保存至系统相册，可前往B站扫码识别", Toast.LENGTH_LONG).show()
                        } else {
                            Toast.makeText(context, "保存二维码失败", Toast.LENGTH_SHORT).show()
                        }
                    }
                } else {
                    runOnUiThread {
                        Toast.makeText(context, "获取二维码图片数据失败", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                runOnUiThread {
                    Toast.makeText(context, "保存二维码失败: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    @JavascriptInterface
    fun openBilibiliApp() {
        runOnUiThread {
            try {
                val scanIntent = Intent(Intent.ACTION_VIEW, Uri.parse("bilibili://qr/scan")).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(scanIntent)
            } catch (_: Exception) {
                try {
                    val appIntent = context.packageManager.getLaunchIntentForPackage("tv.danmaku.bili")?.apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    if (appIntent != null) {
                        context.startActivity(appIntent)
                    } else {
                        Toast.makeText(context, "未检测到哔哩哔哩APP，建议使用内置网页登录", Toast.LENGTH_LONG).show()
                    }
                } catch (e2: Exception) {
                    Toast.makeText(context, "无法启动哔哩哔哩: ${e2.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    @JavascriptInterface
    fun getStoredCookies(): String {
        return prefs.getString("bili_cookies", "") ?: ""
    }

    @JavascriptInterface
    fun clearCookies() {
        runOnUiThread {
            prefs.edit().remove("bili_cookies").apply()
            Toast.makeText(context, "已清除登录凭据", Toast.LENGTH_SHORT).show()
        }
    }

    @JavascriptInterface
    fun showToast(msg: String) {
        runOnUiThread {
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
        }
    }
}
